package dev.send.matrix

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private const val MINUTE = 60_000L

/** [count] encrypted conversations, newest first: "Maya" then "Contact 2" … "Contact [count]". */
internal fun motionRooms(count: Int, now: Long = System.currentTimeMillis()): List<MatrixRoom> = List(count) { index ->
    val name = if (index == 0) "Maya" else "Contact ${index + 1}"
    MatrixRoom(
        id = "!room$index:example.org",
        name = name,
        encrypted = true,
        direct = true,
        lastMessage = "Message $index",
        lastTimestampMillis = now - index * 7 * MINUTE,
        unreadCount = if (index % 5 == 0) 1 else 0,
        dmUserId = "@contact$index:example.org",
    )
}

/** An alternating chat of [count] messages that all predate the test (history, so none animates in). */
internal fun motionChat(count: Int, now: Long = System.currentTimeMillis()): List<MatrixMessage> = List(count) { index ->
    val own = index % 3 == 1
    MatrixMessage(
        id = "\$history$index",
        sender = if (own) "@me:example.org" else "@maya:example.org",
        text = if (own) "Sure, see you at $index" else "Message number $index from Maya",
        own = own,
        timestampMillis = now - (count - index) * MINUTE,
        reactions = emptyList(),
        senderName = if (own) null else "Maya",
    )
}

/**
 * The app's top-level navigation on fixtures: the conversation list and a conversation in [SendScreenHost], as
 * `SendApp` wires them (open from a row, back to the list), so the real transitions, shared elements and
 * predictive back run. [onTransitionScope] receives the host's shared transition scope.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun MotionFixture(
    rooms: List<MatrixRoom>,
    messages: List<MatrixMessage>,
    onTransitionScope: (SharedTransitionScope?) -> Unit = {},
) {
    var open by remember { mutableStateOf<MatrixRoom?>(null) }
    // Like SendApp, the leaving conversation keeps its room through the close transition.
    val shown = remember { mutableStateOf<MatrixRoom?>(null) }.apply { if (open != null) value = open }.value
    SendScreenHost(if (open != null) 3 else 2, Modifier.fillMaxSize()) { target ->
        onTransitionScope(LocalSendSharedTransitionScope.current)
        if (target == 3 && shown != null) {
            ConversationScreen(
                title = shown.name,
                isDirect = shown.direct,
                draft = "",
                onDraft = {},
                messages = messages,
                onBack = { open = null },
                onSend = {},
                onOlder = {},
                onReact = { _, _ -> },
                onCall = {},
                onSendAttachment = {},
                onSendVoice = {},
                loadMedia = { _, _ -> error("No media in this fixture.") },
                uploading = false,
                error = "",
                decryptionActionLabel = null,
                onDecryptionAction = {},
                sharedKey = shown.id,
            )
        } else {
            HomeScreen(
                rooms = rooms,
                onOpen = { open = it },
                error = "",
                notice = "",
                creatingDirectRoom = false,
                onCreateDirectRoom = {},
                onCreateGroup = { _, _ -> },
                onAccount = {},
                onVerifyDevice = null,
            )
        }
    }
}
