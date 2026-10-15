package dev.send.matrix.notifications

import android.content.Context
import android.util.Log
import dev.send.matrix.MatrixRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.NotificationItem

/**
 * Single entry point for every notification source — the SDK's sync notification handler (app running or
 * kept alive in the background), UnifiedPush messages and the periodic background sync — so each event is
 * decided on once: calls go to [IncomingCalls], messages through the filters below to [MessageNotifier].
 */
internal object NotificationRouter {
    private const val PREFS = "send_notifications"
    private const val KEY_SINCE = "notify_since"
    private const val KEY_NOTIFIED = "notified_events"
    private const val NOTIFIED_LIMIT = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifiedLock = Any()
    private var notified: LinkedHashSet<String>? = null

    /** Conversation the activity shows (UI state), whether or not the activity is in the foreground. */
    @Volatile var openRoomId: String? = null
    /** Set from MainActivity's onResume/onPause, so it is current even while Compose isn't drawing. */
    @Volatile var activityResumed = false

    /** The conversation the user is looking at: its messages show there, so they don't notify. */
    val visibleRoomId: String? get() = openRoomId.takeIf { activityResumed }

    /** Called on the SDK's sync thread: copies the event out, then decides off that thread. */
    fun onSyncNotification(context: Context, item: NotificationItem, roomId: String) {
        val parsed = parseNotification(item, roomId) ?: return
        val appContext = context.applicationContext
        scope.launch { route(appContext, parsed) }
    }

    /**
     * Push or background fetch of one event: restores the session if the process was cold, decrypts the event
     * with the SDK notification client and routes it. Returns false when there was no session to use.
     */
    suspend fun fetchAndRoute(context: Context, roomId: String, eventId: String): Boolean {
        val repository = MatrixRepository.get(context)
        if (!repository.restore()) return false
        if (isNotified(context, eventId)) return true
        val item = repository.fetchNotification(roomId, eventId) ?: return true
        parseNotification(item, roomId)?.let { route(context, it) }
        return true
    }

    private suspend fun route(context: Context, parsed: ParsedNotification) {
        val repository = MatrixRepository.get(context)
        try {
            when (parsed) {
                is ParsedNotification.Encrypted -> {
                    // Sync had the event before its room key: the notification client waits for the key.
                    val resolved = try {
                        repository.fetchNotification(parsed.roomId, parsed.eventId)?.let { parseNotification(it, parsed.roomId) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not decrypt ${parsed.eventId} for its notification", error)
                        parsed.undecrypted
                    }
                    // Null: redacted, gone, or filtered out by push rules once decrypted.
                    if (resolved is ParsedNotification.Encrypted) {
                        notifyMessage(context, repository, resolved.undecrypted)
                    } else if (resolved != null) {
                        route(context, resolved)
                    }
                }
                is ParsedNotification.Message -> notifyMessage(context, repository, parsed)
                is ParsedNotification.IncomingCall ->
                    if (parsed.callerId != repository.ownUserId() && claim(context, parsed.eventId)) {
                        IncomingCalls.onIncomingCall(context, parsed)
                    }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Notification for ${parsed.eventId} failed", error)
        }
    }

    private suspend fun notifyMessage(context: Context, repository: MatrixRepository, message: ParsedNotification.Message) {
        val ownUserId = repository.ownUserId()
        val skip = message.senderId == ownUserId ||
            // Send only opens end-to-end encrypted conversations.
            message.roomEncrypted == false ||
            message.roomId == visibleRoomId ||
            // A new device doesn't replay the history it syncs.
            message.timestampMillis < since(context) ||
            isNotified(context, message.eventId) ||
            repository.isRoomMuted(message.roomId) ||
            // Read on this or another device already.
            repository.hasReadSince(message.roomId, message.timestampMillis)
        if (skip || !claim(context, message.eventId)) return
        MessageNotifier.showMessage(context, message, ownUserId)
    }

    fun onSignedIn(context: Context) {
        synchronized(notifiedLock) {
            prefs(context).edit().putLong(KEY_SINCE, System.currentTimeMillis()).remove(KEY_NOTIFIED).commit()
            notified = LinkedHashSet()
        }
    }

    fun onSignedOut(context: Context) {
        synchronized(notifiedLock) {
            prefs(context).edit().remove(KEY_SINCE).remove(KEY_NOTIFIED).commit()
            notified = null
        }
        openRoomId = null
    }

    private fun since(context: Context): Long {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_SINCE)) prefs.edit().putLong(KEY_SINCE, System.currentTimeMillis()).apply()
        return prefs.getLong(KEY_SINCE, System.currentTimeMillis())
    }

    private fun isNotified(context: Context, eventId: String): Boolean = synchronized(notifiedLock) {
        eventId in loadNotified(context)
    }

    /** Records [eventId] as handled; false if another source (sync vs push) already did. */
    private fun claim(context: Context, eventId: String): Boolean = synchronized(notifiedLock) {
        val ids = loadNotified(context)
        if (!ids.add(eventId)) return false
        while (ids.size > NOTIFIED_LIMIT) ids.remove(ids.first())
        prefs(context).edit().putString(KEY_NOTIFIED, ids.joinToString("\n")).apply()
        true
    }

    private fun loadNotified(context: Context): LinkedHashSet<String> = notified ?: LinkedHashSet(
        prefs(context).getString(KEY_NOTIFIED, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
    ).also { notified = it }

    internal fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
