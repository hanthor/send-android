package dev.send.matrix

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.send.matrix.notifications.NotificationRouter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TypingNotificationsListener

/**
 * Phase 4 acceptance against the local Synapse: Alice drives the app's conversation actions and Bob, an independent
 * SDK client, checks what arrived — the reply relation, the edit, the redaction, a forward into a second room, typing
 * in both directions, and Bob's read receipt turning Alice's "Sent" into "Read".
 */
@RunWith(AndroidJUnit4::class)
class ZZConversationActionsIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = MatrixRepository.get(context)

    /** One message as Bob's client sees it. */
    private data class BobEvent(
        val roomId: String,
        val eventId: String,
        val body: String?,
        val inReplyTo: String?,
        val edited: Boolean,
        val redacted: Boolean,
        /** The `mxc://` URL of an `m.sticker`. */
        val stickerUrl: String? = null,
    )

    private var bobClient: Client? = null
    private var bobSync: SyncService? = null
    private val bobRooms = ConcurrentHashMap<String, Room>()
    private val bobTimelines = ConcurrentHashMap<String, Timeline>()
    private val bobHandles = mutableListOf<TaskHandle>()
    private val bobEvents = ConcurrentHashMap<String, BobEvent>()
    /** Users Bob's client saw typing in the DM. */
    private val bobSawTyping = CopyOnWriteArraySet<String>()

    @After
    fun closeBob() {
        bobHandles.forEach { it.close() }
        bobTimelines.values.forEach { it.close() }
        bobRooms.values.forEach { it.close() }
        bobSync?.close()
        bobClient?.close()
    }

    @Test
    fun replyEditDeleteForwardTypingAndReadReachBob() {
        composeRule.signInAsAliceIfNeeded()
        waitFor("Alice's session", 60_000, { "account=${repository.account.value}" }) { repository.account.value != null }
        val stamp = System.currentTimeMillis()
        val dm = runBlocking { repository.createEncryptedDirectRoom(BOB) }
        // The CI homeserver has URL previews off: no card data, rather than anything made up.
        assertNull(runBlocking { repository.linkPreview("https://example.org/send-ci-$stamp") })
        val second = runBlocking { repository.createEncryptedGroup("$FORWARD_ROOM $stamp", listOf(BOB)) }
        startBob(dm, second)
        waitFor("both rooms in Alice's list", 60_000, ::roomsState) {
            repository.joinedRooms.value.count { (it.id == dm || it.id == second) && it.encrypted } == 2
        }

        // Open the DM the way a notification tap does.
        composeRule.runOnUiThread { composeRule.activity.pendingConversation.value = dm }
        waitForUi("the DM on screen", 60_000, ::conversationState) {
            NotificationRouter.visibleRoomId == dm && composeRule.onAllNodesWithContentDescription("More options").fetchSemanticsNodes().isNotEmpty()
        }
        val original = "Original from Bob $stamp"
        val originalId = bobSends(dm, original)
        waitForUi("Bob's message in the DM", 60_000, ::conversationState) { hasNodeWithText(original) }

        // 1. Swipe right to reply: Bob gets the in-reply-to relation; Alice's bubble quotes the original.
        composeRule.onNodeWithText(original).performTouchInput {
            swipe(start = Offset(centerX, centerY), end = Offset(centerX + 420f, centerY), durationMillis = 250)
        }
        waitForUi("the reply preview", 10_000, ::conversationState) { hasNodeWithText("Replying to", substring = true) }
        val reply = "Reply from Alice $stamp"
        composeRule.onNode(hasSetTextAction()).performTextInput(reply)
        composeRule.onNodeWithContentDescription("Send").performClick()
        waitFor("Bob to receive the reply", 60_000, ::bobState) { bobEvent(dm, reply)?.inReplyTo == originalId }
        val replyId = checkNotNull(bobEvent(dm, reply)).eventId
        waitForUi("the quote in Alice's reply", 30_000, ::conversationState) {
            composeRule.onAllNodes(hasContentDescription("Reply to", substring = true)).fetchSemanticsNodes().isNotEmpty() &&
                repository.messages.value.any { it.id == replyId && it.replyTo?.eventId == originalId }
        }

        // 2. Edit from the long-press actions: Bob sees the replacement; Alice's bubble says "Edited".
        selectAndTap(reply, "Edit")
        waitForUi("edit mode", 10_000, ::conversationState) { hasNodeWithText("Editing message") }
        val edited = "Edited by Alice $stamp"
        composeRule.onNode(hasSetTextAction()).performTextReplacement(edited)
        composeRule.onNodeWithContentDescription("Send").performClick()
        waitFor("Bob to see the edit", 60_000, ::bobState) { bobEvents[replyId]?.let { it.edited && it.body == edited } == true }
        waitForUi("Edited under Alice's bubble", 30_000, ::conversationState) {
            hasNodeWithText(edited) && composeRule.onAllNodes(hasText("Edited", substring = true) and !hasText(edited)).fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Delete for everyone: Bob sees the redaction; Alice sees "Message deleted".
        selectAndTap(edited, "Delete")
        composeRule.onNodeWithText("Delete").performClick()
        waitFor("Bob to see the redaction", 60_000, ::bobState) { bobEvents[replyId]?.redacted == true }
        waitForUi("the deleted bubble", 30_000, ::conversationState) { hasNodeWithText("Message deleted") }
        composeRule.auditShot("live-deleted")

        // 4. Typing both ways: Bob's notice shows Messages' dots; Alice's draft reaches Bob's typing listener.
        val bobTyping = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    runBlocking(Dispatchers.IO) { checkNotNull(bobRooms[dm]).typingNotice(true) }
                    Thread.sleep(1_000)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        try {
            waitForUi("Bob's typing dots", 60_000, { "typing=${repository.typingUsers.value} ${conversationState()}" }) {
                composeRule.onAllNodes(hasContentDescription("is typing", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.auditShot("live-typing")
        } finally {
            bobTyping.interrupt()
            runBlocking(Dispatchers.IO) { checkNotNull(bobRooms[dm]).typingNotice(false) }
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("Typing check")
        waitFor("Bob to see Alice typing", 60_000, { "bobSawTyping=$bobSawTyping" }) { ALICE in bobSawTyping }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("")

        // 5. Read receipt: once Bob reads Alice's newest message, its status says "Read".
        val readMe = "Please read $stamp"
        composeRule.onNode(hasSetTextAction()).performTextInput(readMe)
        composeRule.onNodeWithContentDescription("Send").performClick()
        waitFor("Bob to receive the message to read", 60_000, ::bobState) { bobEvent(dm, readMe) != null }
        waitForUi("Sent under Alice's message", 30_000, ::conversationState) {
            composeRule.onAllNodes(hasText(" · Sent", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking(Dispatchers.IO) {
            checkNotNull(bobRooms[dm]).sendSingleReceipt(ReceiptType.READ, ReceiptThread.Unthreaded, checkNotNull(bobEvent(dm, readMe)).eventId)
        }
        waitForUi("Read under Alice's message", 60_000, ::conversationState) {
            composeRule.onAllNodes(hasText(" · Read", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.auditShot("live-read")

        // 6. Forward Bob's original into the second room: it arrives there for Bob, and that conversation opens.
        selectAndTap(original, "Forward")
        waitForUi("the forward picker", 10_000, ::conversationState) { hasNodeWithText("Forward to") }
        composeRule.onNodeWithText("$FORWARD_ROOM $stamp").performClick()
        waitFor("Bob to receive the forwarded copy", 60_000, ::bobState) { bobEvent(second, original) != null }
        assertEquals(null, bobEvent(second, original)?.inReplyTo)
        waitForUi("the second room open with the copy", 60_000, ::conversationState) {
            NotificationRouter.visibleRoomId == second && hasNodeWithText(original)
        }
        assertTrue(bobEvent(dm, original) != null)

        // 7. Schedule send: holding Send opens the sheet; a message scheduled a few seconds out shows under the
        // conversation with its time, goes out encrypted from the open app when due, and then leaves the list.
        val scheduledText = "Scheduled from Alice $stamp"
        composeRule.onNode(hasSetTextAction()).performTextInput(scheduledText)
        composeRule.onNodeWithContentDescription("Send").performTouchInput { longClick() }
        waitForUi("the Schedule send sheet", 10_000, ::conversationState) { hasNodeWithText("Schedule send") && hasNodeWithText("Pick date and time") }
        composeRule.auditShot("live-schedule-send")
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        waitForUi("the sheet to close", 10_000, ::conversationState) { !hasNodeWithText("Pick date and time") }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("")
        val store = ScheduledMessages.get(composeRule.activity)
        val pending = store.schedule(second, scheduledText, System.currentTimeMillis() + 4_000)
        waitForUi("the scheduled message under the conversation", 10_000, ::conversationState) {
            hasNodeWithText("Scheduled for Today, ", substring = true) && hasNodeWithText(scheduledText)
        }
        composeRule.auditShot("live-scheduled-message")
        waitFor("Bob to receive the scheduled message", 90_000, ::bobState) { bobEvent(second, scheduledText) != null }
        waitForUi("the schedule to clear once sent", 30_000, { "scheduled=${store.messages.value}" }) {
            store.messages.value.none { it.id == pending.id } && !hasNodeWithText("Scheduled for Today, ", substring = true)
        }

        // 8. Stickers: a picture added to Alice's own image pack (uploaded, recorded in im.ponies.user_emotes) sends
        // as an encrypted m.sticker that references the pack's picture; Bob's client decrypts a sticker with that
        // URL and body, and Alice's conversation shows it as a sticker, not a text bubble.
        val stickerFile = java.io.File(composeRule.activity.cacheDir, "ci-sticker-$stamp.png")
        val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF1A73E8.toInt()) }
        stickerFile.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        val added = runBlocking { repository.addToUserPack(android.net.Uri.fromFile(stickerFile)) }
        assertTrue(added.url, added.url.startsWith("mxc://"))
        assertEquals("ci-sticker-$stamp", added.body)
        val packs = runBlocking { repository.imagePacks(second) }
        assertTrue("packs=${packs.map { it.id to it.images.map { image -> image.shortcode } }}", packs.any { pack ->
            pack.id == MatrixRepository.USER_PACK_ID && pack.images.any { it.url == added.url && it.sticker }
        })
        composeRule.onNodeWithContentDescription("Emoji").performClick()
        waitForUi("the Stickers tab", 15_000, ::conversationState) { hasNodeWithText("Stickers") }
        composeRule.onNodeWithText("Stickers").performClick()
        waitForUi("the added sticker in the picker", 30_000, ::conversationState) {
            composeRule.onAllNodesWithContentDescription("Send sticker: ${added.body}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.auditShot("live-sticker-picker")
        composeRule.onNodeWithContentDescription("Send sticker: ${added.body}").performClick()
        waitFor("Bob to receive the sticker", 60_000, ::bobState) { bobEvents.values.any { it.roomId == second && it.stickerUrl == added.url } }
        assertEquals(added.body, bobEvents.values.first { it.roomId == second && it.stickerUrl == added.url }.body)
        waitForUi("the sticker in Alice's conversation", 60_000, ::conversationState) {
            composeRule.onAllNodesWithContentDescription("Sticker: ${added.body}").fetchSemanticsNodes().isNotEmpty() &&
                repository.messages.value.any { it.attachment?.kind == AttachmentKind.Sticker && it.own }
        }
        composeRule.auditShot("live-sticker")
        runBlocking { repository.removeFromUserPack(added.shortcode) }
        assertTrue(runBlocking { repository.imagePacks(second) }.none { pack -> pack.images.any { it.url == added.url } })
    }

    private fun hasNodeWithText(text: String, substring: Boolean = false): Boolean =
        composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /** Long-presses the bubble showing [text] and taps [action] in the popup action menu. */
    private fun selectAndTap(text: String, action: String) {
        composeRule.onNodeWithText(text).performTouchInput { longClick() }
        waitForUi("the $action action for \"$text\"", 10_000, ::conversationState) {
            composeRule.onAllNodesWithText(action).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(action).performClick()
    }

    private fun bobEvent(roomId: String, body: String): BobEvent? = bobEvents.values.firstOrNull { it.roomId == roomId && it.body == body }

    private fun bobState(): String = "bob=${bobEvents.values.map { "${it.body}:${it.inReplyTo}:${it.edited}:${it.redacted}" }}"

    private fun roomsState(): String = "rooms=${repository.joinedRooms.value.map { "${it.id}:${it.name}:${it.encrypted}" }}"

    private fun conversationState(): String =
        "visible=${NotificationRouter.visibleRoomId} messages=${repository.messages.value.map { "${it.sender}:${it.text}:${it.status}:${it.readBy.keys}:deleted=${it.deleted}:edited=${it.edited}:editable=${it.editable}" }}"

    /** Compose's waitUntil (keeps the app's UI composing), reporting app state on timeout. */
    private fun waitForUi(what: String, timeoutMillis: Long, state: () -> String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $what; ${state()}", timeout)
        }
    }

    private fun waitFor(what: String, timeoutMillis: Long, state: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what; ${state()}")
            Thread.sleep(250)
        }
    }

    private fun startBob(vararg roomIds: String) {
        val root = context.filesDir.resolve("matrix-conversation-bob")
        root.deleteRecursively()
        root.mkdirs()
        val client = runBlocking(Dispatchers.IO) {
            ClientBuilder()
                .homeserverUrl(SYNAPSE_URL)
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
                .sqliteStore(SqliteStoreBuilder(root.resolve("store.db").absolutePath, "").key(ByteArray(32)))
                .sessionPaths(root.resolve("cache").absolutePath, root.resolve("crypto").absolutePath)
                .build()
        }
        bobClient = client
        runBlocking(Dispatchers.IO) {
            client.login(BOB, DEMO_PASSWORD, "Send conversation test", null)
            val sync = client.syncService().finish()
            bobSync = sync
            sync.start()
            for (roomId in roomIds) {
                val room = client.joinWhenSynced(roomId)
                bobRooms[roomId] = room
                val timeline = room.timeline()
                bobTimelines[roomId] = timeline
                bobHandles += timeline.addListener(object : TimelineListener {
                    override fun onUpdate(diffs: List<TimelineDiff>) {
                        for (diff in diffs) for (item in timelineItems(diff)) record(roomId, item)
                    }
                })
            }
            bobHandles += checkNotNull(bobRooms[roomIds.first()]).subscribeToTypingNotifications(object : TypingNotificationsListener {
                override fun call(typingUserIds: List<String>) {
                    bobSawTyping.addAll(typingUserIds)
                }
            })
        }
    }

    private fun record(roomId: String, item: TimelineItem) {
        val event = item.asEvent() ?: return
        val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return
        val content = event.content as? TimelineItemContent.MsgLike ?: return
        val recorded = when (val kind = content.content.kind) {
            is MsgLikeKind.Message -> BobEvent(
                roomId = roomId,
                eventId = eventId,
                body = (kind.content.msgType as? MessageType.Text)?.content?.body ?: kind.content.body,
                inReplyTo = content.content.inReplyTo?.eventId(),
                edited = kind.content.isEdited,
                redacted = false,
            )
            is MsgLikeKind.Redacted -> BobEvent(roomId, eventId, body = bobEvents[eventId]?.body, inReplyTo = null, edited = false, redacted = true)
            is MsgLikeKind.Sticker -> BobEvent(roomId, eventId, body = kind.body, inReplyTo = null, edited = false, redacted = false, stickerUrl = kind.source.url())
            else -> return
        }
        bobEvents[eventId] = recorded
    }

    /** Bob sends [body] to [roomId] and returns its event ID once the homeserver has it. */
    private fun bobSends(roomId: String, body: String): String {
        val timeline = checkNotNull(bobTimelines[roomId])
        runBlocking(Dispatchers.IO) {
            timeline.send(checkNotNull(timeline.createMessageContent(MessageType.Text(TextMessageContent(body, null)))))
        }
        waitFor("Bob's message to be sent", 60_000, ::bobState) { bobEvent(roomId, body) != null }
        return checkNotNull(bobEvent(roomId, body)).eventId
    }

    private fun timelineItems(diff: TimelineDiff): List<TimelineItem> = when (diff) {
        is TimelineDiff.Reset -> diff.values
        is TimelineDiff.Append -> diff.values
        is TimelineDiff.PushBack -> listOf(diff.value)
        is TimelineDiff.PushFront -> listOf(diff.value)
        is TimelineDiff.Insert -> listOf(diff.value)
        is TimelineDiff.Set -> listOf(diff.value)
        else -> emptyList()
    }

    private companion object {
        const val FORWARD_ROOM = "Forward check"
    }
}
