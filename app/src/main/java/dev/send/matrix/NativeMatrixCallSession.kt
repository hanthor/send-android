package dev.send.matrix

import android.content.Context
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.matrix.ElementCallMatrixTransport
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.ElementCallStack
import io.element.android.call.matrix.ElementCallSdkTransport
import io.element.android.call.matrix.SdkElementCallRoomContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.matrix.rustcomponents.sdk.Client

/** Native MatrixRTC controller kept for the lifetime of its authenticated Matrix session. */
class NativeMatrixCallSession(
    context: Context,
    client: Client,
    private val sessionScope: CoroutineScope,
) : AutoCloseable {
    private val transport: ElementCallMatrixTransport = ElementCallSdkTransport(client, sessionScope)
    private val stack = ElementCallStack.Builder(context, transport)
        .roomContext(SdkElementCallRoomContext(client))
        .options(ElementCallOptions())
        .build(sessionScope)

    val controller: ElementCallController = stack.controller

    fun startAudioCall(roomId: String) {
        controller.startCall(io.element.android.call.api.ElementCallData(RoomId(roomId), isAudioCall = true))
    }

    override fun close() {
        stack.close()
        sessionScope.cancel()
    }
}
