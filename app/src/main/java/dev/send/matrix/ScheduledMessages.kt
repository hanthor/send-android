package dev.send.matrix

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.send.matrix.notifications.MessageNotifier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** A message written now to be sent later (Messages' "Schedule send"); [failed] once every attempt at its time failed. */
data class ScheduledMessage(
    val id: String,
    val roomId: String,
    val text: String,
    val sendAtMillis: Long,
    val createdAtMillis: Long,
    val failed: Boolean = false,
)

/**
 * Messages' scheduled send, kept on this device as Messages keeps it: the text and its time live here, and the
 * message is sent through the normal encrypted path when the time comes — by the open app if it is running
 * (checked every few seconds, so an on-screen schedule goes out on the minute) and otherwise by a WorkManager
 * job that restores the session, so a phone that was off sends it once it is back. Matrix's delayed events
 * (MSC4140) can't carry encrypted content from this client, so nothing is handed to the server early.
 */
class ScheduledMessages private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _messages = MutableStateFlow(load())
    /** Every scheduled message on this device, soonest first. */
    val messages: StateFlow<List<ScheduledMessage>> = _messages.asStateFlow()
    private val sending = Mutex()
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var foregroundTicker: Job? = null

    /** Schedules [text] for [roomId] at [sendAtMillis]; it shows in the conversation until it is sent. */
    fun schedule(roomId: String, text: String, sendAtMillis: Long): ScheduledMessage {
        val message = ScheduledMessage(
            id = java.util.UUID.randomUUID().toString(),
            roomId = roomId,
            text = text.trim(),
            sendAtMillis = sendAtMillis,
            createdAtMillis = System.currentTimeMillis(),
        )
        save(_messages.value + message)
        enqueue(message, sendAtMillis - System.currentTimeMillis())
        return message
    }

    /** Changes a scheduled message's text and time (Messages' "Update message"). */
    fun update(id: String, text: String, sendAtMillis: Long) {
        val current = _messages.value.firstOrNull { it.id == id } ?: return
        val updated = current.copy(text = text.trim(), sendAtMillis = sendAtMillis, failed = false)
        save(_messages.value.map { if (it.id == id) updated else it })
        enqueue(updated, sendAtMillis - System.currentTimeMillis())
    }

    /** Deletes a scheduled message without sending it. */
    fun cancel(id: String) {
        save(_messages.value.filterNot { it.id == id })
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    /** Drops every scheduled message and its job (sign-out). */
    fun clear() {
        val manager = WorkManager.getInstance(context)
        _messages.value.forEach { manager.cancelUniqueWork(workName(it.id)) }
        save(emptyList())
    }

    /** Sends a scheduled message right away (Messages' "Send now"). */
    fun sendNow(id: String) {
        val current = _messages.value.firstOrNull { it.id == id } ?: return
        val now = current.copy(sendAtMillis = System.currentTimeMillis(), failed = false)
        save(_messages.value.map { if (it.id == id) now else it })
        scope.launch { sendDue() }
        enqueue(now, 0L)
    }

    /** Scheduled messages of one conversation, soonest first. */
    fun forRoom(roomId: String): List<ScheduledMessage> = _messages.value.filter { it.roomId == roomId }

    /**
     * While the app is on screen, due messages go out from here within [FOREGROUND_TICK_MS]; WorkManager's job
     * stays enqueued as the fallback and finds nothing left to do.
     */
    fun onForeground(active: Boolean) {
        foregroundTicker?.cancel()
        foregroundTicker = if (active) scope.launch {
            while (true) {
                if (_messages.value.any { !it.failed && it.sendAtMillis <= System.currentTimeMillis() }) sendDue()
                delay(FOREGROUND_TICK_MS)
            }
        } else null
    }

    /**
     * Sends every message whose time has come, one at a time; each is removed once the homeserver has it.
     * Returns false when one failed (the caller may retry); a message that keeps failing is marked
     * [ScheduledMessage.failed] after [MAX_ATTEMPTS] tries and shown as "Not sent".
     */
    suspend fun sendDue(attempt: Int = 1): Boolean = sending.withLock {
        var allSent = true
        val due = _messages.value.filter { !it.failed && it.sendAtMillis <= System.currentTimeMillis() && inFlight.add(it.id) }
        for (message in due) {
            try {
                MatrixRepository.get(context).sendNotificationReply(message.roomId, message.text)
                save(_messages.value.filterNot { it.id == message.id })
                WorkManager.getInstance(context).cancelUniqueWork(workName(message.id))
            } catch (cancelled: CancellationException) {
                inFlight.remove(message.id)
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Scheduled message ${message.id} for ${message.roomId} not sent (attempt $attempt)", error)
                allSent = false
                if (attempt >= MAX_ATTEMPTS) {
                    save(_messages.value.map { if (it.id == message.id) it.copy(failed = true) else it })
                    MessageNotifier.showActionFailed(context, message.roomId, "Scheduled message not sent")
                }
            } finally {
                inFlight.remove(message.id)
            }
        }
        allSent
    }

    private fun enqueue(message: ScheduledMessage, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<ScheduledSendWorker>()
            .setInputData(workDataOf(KEY_ID to message.id))
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(message.id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun save(messages: List<ScheduledMessage>) {
        val sorted = messages.sortedBy { it.sendAtMillis }
        _messages.value = sorted
        val array = JSONArray()
        sorted.forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("roomId", message.roomId)
                    .put("text", message.text)
                    .put("sendAt", message.sendAtMillis)
                    .put("createdAt", message.createdAtMillis)
                    .put("failed", message.failed),
            )
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    private fun load(): List<ScheduledMessage> = runCatching {
        val array = JSONArray(prefs.getString(KEY_MESSAGES, null) ?: return emptyList())
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ScheduledMessage(
                id = item.getString("id"),
                roomId = item.getString("roomId"),
                text = item.getString("text"),
                sendAtMillis = item.getLong("sendAt"),
                createdAtMillis = item.optLong("createdAt", 0L),
                failed = item.optBoolean("failed", false),
            )
        }.sortedBy { it.sendAtMillis }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "ScheduledMessages"
        private const val PREFS = "scheduled_messages"
        private const val KEY_MESSAGES = "messages"
        internal const val KEY_ID = "scheduled_message_id"
        private const val WORK_TAG = "scheduled-send"
        internal const val MAX_ATTEMPTS = 3
        /** How often the open app checks for a due message. */
        internal const val FOREGROUND_TICK_MS = 5_000L
        @Volatile private var instance: ScheduledMessages? = null

        fun get(context: Context): ScheduledMessages = instance ?: synchronized(this) {
            instance ?: ScheduledMessages(context.applicationContext).also { instance = it }
        }

        private fun workName(id: String) = "$WORK_TAG-$id"
    }
}

/** WorkManager's side of a scheduled message: sends what is due (restoring the session when cold), retrying on failure. */
class ScheduledSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = ScheduledMessages.get(applicationContext)
        val id = inputData.getString(ScheduledMessages.KEY_ID)
        // Already sent by the open app (or deleted): nothing to do.
        if (id != null && store.messages.value.none { it.id == id && !it.failed }) return Result.success()
        val attempt = runAttemptCount + 1
        return if (store.sendDue(attempt)) Result.success() else if (attempt >= ScheduledMessages.MAX_ATTEMPTS) Result.failure() else Result.retry()
    }
}
