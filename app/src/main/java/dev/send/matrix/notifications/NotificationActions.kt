package dev.send.matrix.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.send.matrix.MatrixRepository
import kotlinx.coroutines.CancellationException

/**
 * Receives the notification's Reply and Mark as read actions and an incoming call's Decline. The work runs as
 * an expedited WorkManager job, so it completes even when no activity exists and the process was started
 * just for it.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(MessageNotifier.EXTRA_ROOM_ID) ?: return
        val input = when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessageNotifier.REMOTE_INPUT_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                workDataOf(KEY_ACTION to ACTION_REPLY, KEY_ROOM_ID to roomId, KEY_TEXT to text)
            }
            ACTION_MARK_READ -> workDataOf(
                KEY_ACTION to ACTION_MARK_READ,
                KEY_ROOM_ID to roomId,
                KEY_EVENT_ID to intent.getStringExtra(MessageNotifier.EXTRA_EVENT_ID),
            )
            ACTION_DECLINE_CALL -> {
                val eventId = intent.getStringExtra(MessageNotifier.EXTRA_EVENT_ID) ?: return
                // Ringing stops at once; the decline event follows from the worker.
                IncomingCalls.decline(context, roomId, eventId)
                return
            }
            else -> return
        }
        enqueue(context, input)
    }

    companion object {
        const val ACTION_REPLY = "dev.send.matrix.action.NOTIFICATION_REPLY"
        const val ACTION_MARK_READ = "dev.send.matrix.action.NOTIFICATION_MARK_READ"
        const val ACTION_DECLINE_CALL = "dev.send.matrix.action.DECLINE_CALL"
        internal const val KEY_ACTION = "action"
        internal const val KEY_ROOM_ID = "roomId"
        internal const val KEY_EVENT_ID = "eventId"
        internal const val KEY_TEXT = "text"

        internal fun declineIntent(context: Context, roomId: String, eventId: String): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_DECLINE_CALL)
                .putExtra(MessageNotifier.EXTRA_ROOM_ID, roomId)
                .putExtra(MessageNotifier.EXTRA_EVENT_ID, eventId)

        /** Sends `m.rtc.decline` for the ring [eventId] in [roomId] (`Room.declineCall`), restoring the session if needed. */
        internal fun enqueueDecline(context: Context, roomId: String, eventId: String) = enqueue(
            context,
            workDataOf(KEY_ACTION to ACTION_DECLINE_CALL, KEY_ROOM_ID to roomId, KEY_EVENT_ID to eventId),
        )

        private fun enqueue(context: Context, input: androidx.work.Data) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<NotificationActionWorker>()
                    .setInputData(input)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }
    }
}

/** Sends the inline reply (encrypted rooms only), the read receipt or a call decline, restoring the session when cold. */
class NotificationActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val roomId = inputData.getString(NotificationActionReceiver.KEY_ROOM_ID) ?: return Result.failure()
        val repository = MatrixRepository.get(applicationContext)
        return try {
            when (inputData.getString(NotificationActionReceiver.KEY_ACTION)) {
                NotificationActionReceiver.ACTION_REPLY -> {
                    val text = inputData.getString(NotificationActionReceiver.KEY_TEXT) ?: return Result.failure()
                    repository.sendNotificationReply(roomId, text)
                    MessageNotifier.showSentReply(applicationContext, roomId, text)
                }
                NotificationActionReceiver.ACTION_MARK_READ -> {
                    repository.markRoomRead(roomId, inputData.getString(NotificationActionReceiver.KEY_EVENT_ID))
                    MessageNotifier.cancel(applicationContext, roomId)
                }
                NotificationActionReceiver.ACTION_DECLINE_CALL -> {
                    val eventId = inputData.getString(NotificationActionReceiver.KEY_EVENT_ID) ?: return Result.failure()
                    repository.declineCall(roomId, eventId)
                }
                else -> return Result.failure()
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Notification action in $roomId failed", error)
            when (val action = inputData.getString(NotificationActionReceiver.KEY_ACTION)) {
                NotificationActionReceiver.ACTION_REPLY, NotificationActionReceiver.ACTION_MARK_READ -> MessageNotifier.showActionFailed(
                    applicationContext,
                    roomId,
                    if (action == NotificationActionReceiver.ACTION_REPLY) "Reply not sent" else "Couldn't mark as read",
                )
            }
            Result.failure()
        }
    }
}
