package dev.send.matrix.notifications

import android.content.Context
import android.util.Log

/**
 * Phase 3 hook (ROADMAP.md "Calling"): every MatrixRTC `m.rtc.notification` event that reaches a notification
 * source — sync handler, UnifiedPush, background sync — lands here, already decrypted, de-duplicated and with
 * the caller resolved. Phase 3 turns RING events into a full-screen `CallStyle` notification (Core-Telecom,
 * accept through Element Call, decline with `Room.declineCall`, and stop when [ParsedNotification.IncomingCall.expiresAtMillis]
 * passes). Until then nothing user-visible happens: Send shows no call UI it cannot answer.
 */
internal object IncomingCalls {
    fun onIncomingCall(context: Context, call: ParsedNotification.IncomingCall) {
        Log.i(
            TAG,
            "Incoming ${if (call.ring) "ring" else "call notice"} in ${call.roomId} (${call.eventId}) from ${call.callerId}; " +
                "call notifications arrive in phase 3",
        )
    }
}
