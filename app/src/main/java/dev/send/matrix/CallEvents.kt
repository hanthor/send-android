package dev.send.matrix

import org.json.JSONException
import org.json.JSONObject

/** MSC4196 `m.call.intent` for video, as the SDK reports it. */
internal const val CALL_INTENT_VIDEO = "video"

/**
 * A MatrixRTC call notification (`m.rtc.notification`, the ring or "call started" event) as a timeline row,
 * with what happened to that call worked out from the room's call memberships.
 */
data class CallEvent(
    val video: Boolean,
    /** This account placed the call (sent the notification). */
    val outgoing: Boolean,
    /** The call is still running in the room. */
    val ongoing: Boolean = false,
    /** This account is in the running call (on any device). */
    val joined: Boolean = false,
    /** Somebody declined the ring (`m.rtc.decline`). */
    val declined: Boolean = false,
    /** Outgoing: somebody else joined. Incoming: this account joined. */
    val answered: Boolean = false,
    /** From the answer until the last member left, when the room's history shows both. */
    val durationMillis: Long? = null,
) {
    val missed: Boolean get() = !outgoing && !ongoing && !declined && !answered

    val label: String
        get() {
            val kind = if (video) "Video call" else "Voice call"
            return when {
                ongoing -> "$kind in progress"
                declined -> "Call declined"
                answered && durationMillis != null -> "$kind · ${callDuration(durationMillis)}"
                answered -> kind
                outgoing -> "$kind · No answer"
                else -> if (video) "Missed video call" else "Missed voice call"
            }
        }
}

/** `2:31`, or `1:02:31` past an hour. */
internal fun callDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** One `org.matrix.msc3401.call.member` state change from the timeline: a device joining (content) or leaving (`{}`). */
internal data class CallMemberChange(
    val timestampMillis: Long,
    val stateKey: String,
    val userId: String,
    val joined: Boolean,
)

internal val CALL_MEMBER_EVENT_TYPES = setOf("org.matrix.msc3401.call.member", "m.call.member")

/** Reads a call-member state event's JSON; null when it isn't one. */
internal fun callMemberChange(eventJson: String): CallMemberChange? = try {
    val event = JSONObject(eventJson)
    if (event.optString("type") !in CALL_MEMBER_EVENT_TYPES) {
        null
    } else {
        val content = event.optJSONObject("content")
        // Element Call's per-device state is `{}` once the device left; the older per-user form lists memberships.
        val joined = content != null && content.length() > 0 && (content.optJSONArray("memberships")?.length() ?: 1) > 0
        CallMemberChange(
            timestampMillis = event.optLong("origin_server_ts"),
            stateKey = event.optString("state_key"),
            userId = event.optString("sender"),
            joined = joined,
        )
    }
} catch (_: JSONException) {
    null
}

/**
 * Whether the call a notification at [ringMillis] from [callerId] was answered, and for how long it ran: the
 * memberships before the ring give the state it started from, and the changes until [nextRingMillis] (the
 * room's next call) are replayed in order. Answered means somebody other than the caller joined for an
 * outgoing call, and [ownUserId] joined for an incoming one; the call ends when its last device leaves.
 */
internal fun callOutcome(
    ringMillis: Long,
    nextRingMillis: Long?,
    callerId: String,
    ownUserId: String?,
    changes: List<CallMemberChange>,
): Pair<Boolean, Long?> {
    val outgoing = callerId == ownUserId
    val active = LinkedHashMap<String, String>()
    fun apply(change: CallMemberChange) {
        if (change.joined) active[change.stateKey] = change.userId else active.remove(change.stateKey)
    }
    fun answering() = active.values.any { if (outgoing) it != callerId else it == ownUserId }
    changes.filter { it.timestampMillis < ringMillis }.forEach(::apply)
    var answeredAt = if (answering()) ringMillis else null
    for (change in changes) {
        if (change.timestampMillis < ringMillis) continue
        if (nextRingMillis != null && change.timestampMillis >= nextRingMillis) break
        apply(change)
        if (answeredAt == null && answering()) answeredAt = change.timestampMillis
        if (answeredAt != null && active.isEmpty()) return true to change.timestampMillis - answeredAt
    }
    return (answeredAt != null) to null
}
