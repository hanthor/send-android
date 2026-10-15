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
 * Receives the notification's Reply and Mark as read actions. The work runs as an expedited WorkManager job,
 * so it completes even when no activity exists and the process was started just for it.
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
            else -> return
        }
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<NotificationActionWorker>()
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    companion object {
        const val ACTION_REPLY = "dev.send.matrix.action.NOTIFICATION_REPLY"
        const val ACTION_MARK_READ = "dev.send.matrix.action.NOTIFICATION_MARK_READ"
        internal const val KEY_ACTION = "action"
        internal const val KEY_ROOM_ID = "roomId"
        internal const val KEY_EVENT_ID = "eventId"
        internal const val KEY_TEXT = "text"
    }
}

/** Sends the inline reply (encrypted rooms only) or the read receipt, restoring the session when cold. */
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
                else -> return Result.failure()
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Notification action in $roomId failed", error)
            val action = inputData.getString(NotificationActionReceiver.KEY_ACTION)
            MessageNotifier.showActionFailed(
                applicationContext,
                roomId,
                if (action == NotificationActionReceiver.ACTION_REPLY) "Reply not sent" else "Couldn't mark as read",
            )
            Result.failure()
        }
    }
}
