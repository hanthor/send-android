package dev.send.matrix.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import dev.send.matrix.IncomingCallActivity
import dev.send.matrix.MainActivity
import dev.send.matrix.MatrixRepository
import dev.send.matrix.R
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.CallDeclineListener
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.TaskHandle

/**
 * Incoming MatrixRTC rings. Every `m.rtc.notification` that reaches a notification source — sync handler,
 * UnifiedPush, background sync — lands in [onIncomingCall], already decrypted, de-duplicated and with the
 * caller resolved. A RING becomes a full-screen `CallStyle` notification on the "Calls" channel (ringtone,
 * vibration, [IncomingCallActivity] over the lock screen) until it is answered or declined here, declined or
 * answered on another of the user's devices, the caller leaves, or the ring expires.
 *
 * Answer opens [MainActivity], which joins the room's MatrixRTC session through Element Call; Decline sends
 * `m.rtc.decline` with `Room.declineCall`. The ring is a notification only: Element Call owns call audio
 * (its own audio-device controller and microphone foreground service), so Android's Core-Telecom isn't used.
 */
internal object IncomingCalls {
    const val CHANNEL_CALLS = "calls"
    const val NOTIFICATION_ID = 2
    const val ACTION_ANSWER = "dev.send.matrix.action.ANSWER_CALL"
    const val EXTRA_VIDEO = "dev.send.matrix.extra.VIDEO"
    private const val EXTRA_CALLER_ID = "dev.send.matrix.extra.CALLER_ID"
    private const val EXTRA_CALLER_NAME = "dev.send.matrix.extra.CALLER_NAME"
    private const val EXTRA_ROOM_NAME = "dev.send.matrix.extra.ROOM_NAME"
    private const val EXTRA_EXPIRES_AT = "dev.send.matrix.extra.EXPIRES_AT"
    private const val ACCENT = 0xFF415F91.toInt()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchers = ConcurrentHashMap<String, Job>()
    private val _ringing = MutableStateFlow<Map<String, ParsedNotification.IncomingCall>>(emptyMap())
    /** Rings shown right now, by room. */
    val ringing: StateFlow<Map<String, ParsedNotification.IncomingCall>> = _ringing.asStateFlow()
    private val _ended = MutableStateFlow<Set<String>>(emptySet())
    /** Ring events that stopped (answered, declined, expired, joined elsewhere) during this process. */
    val ended: StateFlow<Set<String>> = _ended.asStateFlow()

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_CALLS) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming voice and video calls"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1_000, 1_000)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    /** Whether Android lets Send take over the screen for a ring (special app access on Android 14+). */
    fun canRingFullScreen(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun onIncomingCall(context: Context, call: ParsedNotification.IncomingCall) {
        val remaining = call.expiresAtMillis - System.currentTimeMillis()
        val repository = MatrixRepository.get(context)
        when {
            // A NOTIFICATION (group call started) doesn't ring; the conversation list and the conversation show the call.
            !call.ring -> Log.i(TAG, "Call started in ${call.roomId} by ${call.callerId}")
            remaining <= 0 -> Log.i(TAG, "Ring ${call.eventId} in ${call.roomId} already expired")
            repository.activeCallRoomId() == call.roomId -> Log.i(TAG, "Already in the call ringing in ${call.roomId}")
            else -> {
                _ringing.update { it + (call.roomId to call) }
                show(context, call, remaining)
                watch(context, call, remaining)
            }
        }
    }

    /** Ends the ring for [roomId], if any: notification, full-screen screen and watcher. */
    fun stop(context: Context, roomId: String) {
        val call = _ringing.value[roomId]
        _ringing.update { it - roomId }
        if (call != null) _ended.update { it + call.eventId }
        watchers.remove(roomId)?.cancel()
        NotificationManagerCompat.from(context).cancel(roomId, NOTIFICATION_ID)
    }

    /** Decline from the notification or the incoming-call screen: stops ringing now, then sends `m.rtc.decline`. */
    fun decline(context: Context, roomId: String, eventId: String) {
        stop(context, roomId)
        NotificationActionReceiver.enqueueDecline(context, roomId, eventId)
    }

    /** Opens Send and joins the call (the notification's Answer and the incoming-call screen). */
    fun answerIntent(context: Context, call: ParsedNotification.IncomingCall): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_ANSWER)
            .setData(callUri(call))
            .putExtra(MessageNotifier.EXTRA_ROOM_ID, call.roomId)
            .putExtra(MessageNotifier.EXTRA_EVENT_ID, call.eventId)
            .putExtra(EXTRA_VIDEO, call.video)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun putCall(intent: Intent, call: ParsedNotification.IncomingCall): Intent = intent
        .setData(callUri(call))
        .putExtra(MessageNotifier.EXTRA_ROOM_ID, call.roomId)
        .putExtra(MessageNotifier.EXTRA_EVENT_ID, call.eventId)
        .putExtra(EXTRA_CALLER_ID, call.callerId)
        .putExtra(EXTRA_CALLER_NAME, call.callerName)
        .putExtra(EXTRA_ROOM_NAME, call.roomName)
        .putExtra(EXTRA_VIDEO, call.video)
        .putExtra(EXTRA_EXPIRES_AT, call.expiresAtMillis)

    fun callFrom(intent: Intent): ParsedNotification.IncomingCall? {
        val roomId = intent.getStringExtra(MessageNotifier.EXTRA_ROOM_ID) ?: return null
        val eventId = intent.getStringExtra(MessageNotifier.EXTRA_EVENT_ID) ?: return null
        val callerId = intent.getStringExtra(EXTRA_CALLER_ID) ?: return null
        return ParsedNotification.IncomingCall(
            roomId = roomId,
            eventId = eventId,
            callerId = callerId,
            callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: callerId,
            roomName = intent.getStringExtra(EXTRA_ROOM_NAME).orEmpty(),
            ring = true,
            video = intent.getBooleanExtra(EXTRA_VIDEO, false),
            expiresAtMillis = intent.getLongExtra(EXTRA_EXPIRES_AT, 0L),
        )
    }

    private fun show(context: Context, call: ParsedNotification.IncomingCall, remaining: Long) {
        ensureChannel(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val caller = Person.Builder()
            .setKey(call.callerId)
            .setName(call.callerName)
            .setIcon(IconCompat.createWithBitmap(MessageNotifier.avatarBitmap(call.callerName, adaptive = false)))
            .setImportant(true)
            .build()
        val answer = PendingIntent.getActivity(context, 0, answerIntent(context, call), flags)
        val decline = PendingIntent.getBroadcast(
            context,
            0,
            NotificationActionReceiver.declineIntent(context, call.roomId, call.eventId).setData(callUri(call)),
            flags,
        )
        // Without the full-screen permission (Android 14+ special access) Android shows this as a heads-up instead.
        val fullScreen = PendingIntent.getActivity(
            context,
            0,
            putCall(Intent(context, IncomingCallActivity::class.java), call).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
        )
        val kind = if (call.video) "Incoming video call" else "Incoming voice call"
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setColor(ACCENT)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer).setIsVideo(call.video))
            .setContentTitle(call.callerName)
            .setContentText(kind)
            .setSubText(call.roomName.takeIf { it.isNotBlank() && it != call.callerName })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(remaining)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        // Rings until answered, declined or timed out rather than playing the ringtone once.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        NotificationManagerCompat.from(context).notify(call.roomId, NOTIFICATION_ID, notification)
    }

    /**
     * Stops the ring when it no longer applies: declined on another of the user's devices (`m.rtc.decline` from
     * the user, `subscribeToCallDeclineEvents`), joined on any device (the user in `activeRoomCallParticipants`),
     * the caller gone from the call, or the ring's lifetime over.
     */
    private fun watch(context: Context, call: ParsedNotification.IncomingCall, remaining: Long) {
        val appContext = context.applicationContext
        watchers.remove(call.roomId)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val repository = MatrixRepository.get(appContext)
            val ownUserId = repository.ownUserId()
            val done = CompletableDeferred<String>()
            val handles = mutableListOf<TaskHandle>()
            val room = repository.sdkRoom(call.roomId)
            var callerSeen = false
            fun check(info: RoomInfo) {
                val participants = info.activeRoomCallParticipants
                if (ownUserId != null && ownUserId in participants) done.complete("joined on a device")
                if (call.callerId in participants) callerSeen = true
                if (callerSeen && !info.hasRoomCall) done.complete("the caller left")
            }
            try {
                if (room != null) {
                    handles += room.subscribeToCallDeclineEvents(call.eventId, object : CallDeclineListener {
                        override fun call(userId: String) {
                            if (userId == ownUserId) done.complete("declined on a device")
                        }
                    })
                    handles += room.subscribeToRoomInfoUpdates(object : RoomInfoListener {
                        override fun call(roomInfo: RoomInfo) = check(roomInfo)
                    })
                    check(room.roomInfo())
                }
                val reason = withTimeoutOrNull(remaining) { done.await() } ?: "expired"
                Log.i(TAG, "Ring ${call.eventId} in ${call.roomId} stopped: $reason")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Watching ring ${call.eventId} failed", error)
                withTimeoutOrNull(remaining) { done.await() }
            } finally {
                handles.forEach { it.close() }
                room?.close()
            }
            if (watchers[call.roomId] === coroutineContext[Job]) stop(appContext, call.roomId)
        }
        watchers[call.roomId] = job
        job.start()
    }

    private fun callUri(call: ParsedNotification.IncomingCall): Uri =
        Uri.Builder().scheme("dev.send.matrix").authority("call").appendPath(call.roomId).appendPath(call.eventId).build()
}
