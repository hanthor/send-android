package dev.send.matrix

import android.content.Context
import android.util.Log
import dev.send.matrix.notifications.IncomingCalls
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallLifecycleListener
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.matrix.ElementCallMatrixTransport
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.ElementCallStack
import io.element.android.call.matrix.ElementCallSdkTransport
import io.element.android.call.matrix.SdkElementCallRoomContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.CallDeclineListener
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.RtcTransport
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener

/** A call this device placed in a 1:1 conversation while it rings the other person: Send shows "Calling…". */
data class OutgoingRing(val roomId: String, val name: String, val video: Boolean)

/** A ring answered outside the call UI (notification, incoming-call screen), waiting for the session to join it. */
data class PendingCall(val roomId: String, val video: Boolean)

/**
 * Native MatrixRTC calls (Element Call) for the lifetime of one authenticated Matrix session: the controller
 * the call UI draws, outgoing and incoming call start, and what Send tracks around a call — the ring this
 * device sent and the other side declining it, and the incoming ring that stops once a call is joined here.
 */
class NativeMatrixCallSession(
    context: Context,
    private val client: Client,
    private val sessionScope: CoroutineScope,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val ownUserId = client.userId()
    private val lifecycle = Lifecycle()
    private val stack = ElementCallStack.Builder(context, SdkDiscoveryTransport(ElementCallSdkTransport(client, sessionScope), client))
        .roomContext(SdkElementCallRoomContext(client))
        .lifecycleListener(lifecycle)
        .options(ElementCallOptions())
        .build(sessionScope)

    val controller: ElementCallController = stack.controller

    private val _outgoingRing = MutableStateFlow<OutgoingRing?>(null)
    val outgoingRing: StateFlow<OutgoingRing?> = _outgoingRing.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off messages about how a call ended ("Call declined"). */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** Room whose ringing call this device is answering: joining it rings nobody, so nothing is watched. */
    @Volatile private var answeringRoomId: String? = null
    @Volatile private var ringWatch: Job? = null

    val activeRoomId: String? get() = controller.state.value?.callData?.roomId?.value

    /** Starts a call in [roomId]; in a 1:1 conversation Element Call rings the other person (MSC4075 RING). */
    fun startCall(roomId: String, video: Boolean) = start(roomId, video, answering = false)

    /** Joins the MatrixRTC session ringing in [roomId]; a call running in another room is hung up first. */
    fun answer(roomId: String, video: Boolean) = start(roomId, video, answering = true)

    fun hangUp() = controller.hangUp()

    private fun start(roomId: String, video: Boolean, answering: Boolean) {
        sessionScope.launch {
            val running = controller.state.value
            if (running != null) {
                if (running.callData.roomId.value == roomId) {
                    controller.setMaximized(true)
                    return@launch
                }
                // Element Call runs one call at a time.
                controller.hangUp()
                withTimeoutOrNull(HANG_UP_TIMEOUT_MS) { controller.state.first { it == null } }
            }
            answeringRoomId = if (answering) roomId else null
            controller.startCall(ElementCallData(RoomId(roomId), isAudioCall = !video))
        }
    }

    override fun close() {
        stack.close()
        sessionScope.cancel()
    }

    /**
     * Watches the ring Element Call sends when this device starts a call in a 1:1 conversation: finds the
     * notification event in the room, then ends the call with "Call declined" if the other person declines
     * it (`CallDeclineListener`), and stops showing "Calling…" once they join or the ring expires.
     */
    private fun watchOutgoingRing(roomId: String, video: Boolean, startedAt: Long) {
        ringWatch?.cancel()
        ringWatch = sessionScope.launch {
            try {
                withContext(Dispatchers.IO) { followRing(roomId, video, startedAt) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not follow the ring in $roomId", error)
            } finally {
                _outgoingRing.value = null
            }
        }
    }

    private suspend fun followRing(roomId: String, video: Boolean, startedAt: Long) {
        val room = client.getRoom(roomId) ?: return
        room.use {
            if (!room.isDirect()) return
            val handles = mutableListOf<TaskHandle>()
            val ringEventId = CompletableDeferred<String>()
            val outcome = CompletableDeferred<RingOutcome>()
            fun checkAnswered(info: RoomInfo) {
                if (info.activeRoomCallParticipants.any { it != ownUserId }) outcome.complete(RingOutcome.Answered)
            }
            val timeline = room.timeline()
            try {
                _outgoingRing.value = OutgoingRing(roomId, room.displayName().orEmpty().ifBlank { roomId }, video)
                handles += room.subscribeToRoomInfoUpdates(object : RoomInfoListener {
                    override fun call(roomInfo: RoomInfo) = checkAnswered(roomInfo)
                })
                checkAnswered(room.roomInfo())
                handles += timeline.addListener(object : TimelineListener {
                    override fun onUpdate(diffs: List<TimelineDiff>) {
                        for (item in diffs.flatMap(::timelineDiffItems)) {
                            val event = item.asEvent() ?: continue
                            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: continue
                            if (event.isOwn && event.content is TimelineItemContent.RtcNotification &&
                                event.timestamp.toLong() >= startedAt - CLOCK_SKEW_MS
                            ) {
                                ringEventId.complete(eventId)
                            }
                        }
                    }
                })
                val eventId = withTimeoutOrNull(RING_SEND_TIMEOUT_MS) { ringEventId.await() } ?: return
                handles += room.subscribeToCallDeclineEvents(eventId, object : CallDeclineListener {
                    override fun call(userId: String) {
                        if (userId != ownUserId) outcome.complete(RingOutcome.Declined)
                    }
                })
                when (withTimeoutOrNull(RING_LIFETIME_MS) { outcome.await() }) {
                    RingOutcome.Declined -> withContext(Dispatchers.Main) {
                        if (activeRoomId == roomId) controller.hangUp()
                        _notices.tryEmit("Call declined")
                    }
                    RingOutcome.Answered, null -> Unit
                }
            } finally {
                handles.forEach { it.close() }
                timeline.close()
            }
        }
    }

    private enum class RingOutcome { Answered, Declined }

    private inner class Lifecycle : ElementCallLifecycleListener {
        override val isAppInForeground: Flow<Boolean> = flowOf(true)

        override fun onCallStarted(callData: ElementCallData) {
            val roomId = callData.roomId.value
            // Joining here answers whatever rings for this room.
            IncomingCalls.stop(appContext, roomId)
            if (answeringRoomId != roomId) watchOutgoingRing(roomId, video = !callData.isAudioCall, System.currentTimeMillis())
        }

        override fun onCallJoined(callData: ElementCallData) {
            IncomingCalls.stop(appContext, callData.roomId.value)
        }

        override fun onCallEnded(callData: ElementCallData?) {
            ringWatch?.cancel()
            ringWatch = null
            answeringRoomId = null
            _outgoingRing.value = null
        }
    }

    private companion object {
        const val TAG = "SendCalls"
        const val HANG_UP_TIMEOUT_MS = 10_000L
        /** The ring goes out once the membership is published: joining, then the notification. */
        const val RING_SEND_TIMEOUT_MS = 60_000L
        /** MSC4075's default ring lifetime, which Element Call sends, plus sync delay. */
        const val RING_LIFETIME_MS = 45_000L
        const val CLOCK_SKEW_MS = 10_000L
    }
}

/**
 * Element Call's Matrix port with MatrixRTC transport discovery (MSC4143) answered by the Rust SDK. Element
 * Call `0.1.0-rc.7` reads the discovery endpoint through `getUrl`, which is unauthenticated, but the endpoint
 * requires an access token — so on a homeserver that advertises transports only there (as Synapse does) the
 * join fails with "the homeserver advertises no livekit transport". `Client.discoverRtcTransports` asks the
 * endpoint with the session's token and falls back to the well-known `rtc_foci` itself.
 */
private class SdkDiscoveryTransport(
    private val delegate: ElementCallMatrixTransport,
    private val client: Client,
) : ElementCallMatrixTransport by delegate {
    override suspend fun getUrl(url: String): Result<String> {
        if (!url.endsWith(RTC_TRANSPORTS_PATH)) return delegate.getUrl(url)
        return try {
            // Null: neither the endpoint nor the well-known had transports; Element Call then tries its own lookup.
            client.discoverRtcTransports()?.let { Result.success(rtcTransportsJson(it)) }
                ?: Result.failure(IllegalStateException("The homeserver advertises no MatrixRTC transports"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}

private const val RTC_TRANSPORTS_PATH = "/_matrix/client/unstable/org.matrix.msc4143/rtc/transports"

/** The SDK's transports as the MSC4143 discovery response (`{"rtc_transports": [...]}`). */
internal fun rtcTransportsJson(transports: List<RtcTransport>): String {
    val entries = JSONArray()
    for (transport in transports) {
        when (transport) {
            is RtcTransport.LiveKit -> entries.put(JSONObject().put("type", "livekit").put("livekit_service_url", transport.serviceUrl))
            is RtcTransport.Custom -> {
                val entry = runCatching { JSONObject(transport.data) }.getOrNull() ?: JSONObject()
                if (!entry.has("type")) entry.put("type", transport.transportType)
                entries.put(entry)
            }
        }
    }
    return JSONObject().put("rtc_transports", entries).toString()
}

/** The items a timeline diff adds or replaces. */
internal fun timelineDiffItems(diff: TimelineDiff): List<TimelineItem> = when (diff) {
    is TimelineDiff.Reset -> diff.values
    is TimelineDiff.Append -> diff.values
    is TimelineDiff.PushBack -> listOf(diff.value)
    is TimelineDiff.PushFront -> listOf(diff.value)
    is TimelineDiff.Insert -> listOf(diff.value)
    is TimelineDiff.Set -> listOf(diff.value)
    else -> emptyList()
}
