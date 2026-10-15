package dev.send.matrix.notifications

import android.util.Log
import dev.send.matrix.messageSummary
import org.json.JSONException
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.RtcCallIntent
import org.matrix.rustcomponents.sdk.RtcNotificationType
import org.matrix.rustcomponents.sdk.TimelineEventContent

/** What a notification-worthy event turned out to be, copied out of the SDK's [NotificationItem]. */
internal sealed interface ParsedNotification {
    val roomId: String
    val eventId: String

    data class Message(
        override val roomId: String,
        override val eventId: String,
        val senderId: String,
        val senderName: String,
        val roomName: String,
        /** Not a 1:1 conversation: the notification shows the room name as its conversation title. */
        val isGroup: Boolean,
        /** Null when the SDK doesn't know yet; Send never posts for rooms known to be unencrypted. */
        val roomEncrypted: Boolean?,
        val body: String,
        val timestampMillis: Long,
    ) : ParsedNotification

    /** Sync delivered the event before its room key; [undecrypted] is what to show if decryption still fails. */
    data class Encrypted(val undecrypted: Message) : ParsedNotification {
        override val roomId get() = undecrypted.roomId
        override val eventId get() = undecrypted.eventId
    }

    /** An `m.rtc.notification` (MatrixRTC ring or call-started notice); see [IncomingCalls]. */
    data class IncomingCall(
        override val roomId: String,
        override val eventId: String,
        val callerId: String,
        val callerName: String,
        val roomName: String,
        /** RING asks the device to ring; NOTIFICATION only announces a (group) call. */
        val ring: Boolean,
        val video: Boolean,
        /** Sender's timestamp plus the event's lifetime; a ring past this is stale. */
        val expiresAtMillis: Long,
    ) : ParsedNotification
}

internal const val UNDECRYPTED_BODY = "Encrypted message"

/**
 * Copies the routing data out of [item] and releases its event. Invites and other event kinds return null:
 * Send notifies for messages, and hands calls to [IncomingCalls].
 */
internal fun parseNotification(item: NotificationItem, roomId: String): ParsedNotification? {
    val timelineEvent = (item.event as? NotificationEvent.Timeline)?.event ?: return null
    return try {
        val eventId = timelineEvent.eventId()
        val senderId = timelineEvent.senderId()
        val timestamp = timelineEvent.timestamp().toLong()
        val senderName = item.senderInfo.displayName?.takeIf { it.isNotBlank() } ?: senderId
        val roomName = item.roomInfo.displayName.takeUnless { it.isNullOrBlank() } ?: senderName
        fun message(body: String) = ParsedNotification.Message(
            roomId = roomId,
            eventId = eventId,
            senderId = senderId,
            senderName = senderName,
            roomName = roomName,
            isGroup = !item.roomInfo.isDm,
            roomEncrypted = item.roomInfo.isEncrypted,
            body = body,
            timestampMillis = timestamp,
        )
        when (val content = (timelineEvent.content() as? TimelineEventContent.MessageLike)?.content) {
            is MessageLikeEventContent.RoomMessage -> message(messageSummary(content.messageType))
            is MessageLikeEventContent.Sticker -> message("Sticker")
            is MessageLikeEventContent.RoomEncrypted -> ParsedNotification.Encrypted(message(UNDECRYPTED_BODY))
            is MessageLikeEventContent.RtcNotification -> ParsedNotification.IncomingCall(
                roomId = roomId,
                eventId = eventId,
                callerId = senderId,
                callerName = senderName,
                roomName = roomName,
                ring = content.notificationType == RtcNotificationType.RING,
                video = content.callIntent == RtcCallIntent.VIDEO,
                expiresAtMillis = content.expirationTs.toLong(),
            )
            else -> null
        }
    } catch (error: Exception) {
        Log.w(TAG, "Notification event in $roomId could not be read", error)
        null
    } finally {
        timelineEvent.close()
    }
}

/** The event a Matrix push gateway forwarded (`event_id_only` pusher format). */
internal data class PushedEvent(val roomId: String, val eventId: String)

/**
 * Reads a push payload as delivered through a UnifiedPush Matrix gateway: the homeserver's
 * `{"notification": {...}}` body, or the notification object itself. Pushes without an event (badge-only
 * count updates) return null.
 */
internal fun parsePushPayload(content: ByteArray): PushedEvent? = try {
    val root = JSONObject(String(content, Charsets.UTF_8))
    val notification = root.optJSONObject("notification") ?: root
    val roomId = notification.optString("room_id")
    val eventId = notification.optString("event_id")
    if (roomId.startsWith("!") && eventId.startsWith("$")) PushedEvent(roomId, eventId) else null
} catch (_: JSONException) {
    null
}

internal const val TAG = "SendNotifications"
