package dev.send.matrix

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 conversation actions on fixtures: long-press actions, swipe-to-reply, edit/delete rendering, typing dots,
 * read receipts, link previews, the media viewer, the emoji picker, search and locked voice recording. Each visual
 * state is saved with [auditShot] for the Google Messages comparison.
 */
@RunWith(AndroidJUnit4::class)
class ConversationActionsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = System.currentTimeMillis()

    private fun message(
        id: String,
        text: String,
        own: Boolean,
        minutesAgo: Int,
        edited: Boolean = false,
        deleted: Boolean = false,
        replyTo: ReplyQuote? = null,
        readBy: Map<String, Long> = emptyMap(),
        attachment: MatrixAttachment? = null,
    ) = MatrixMessage(
        id = id,
        sender = if (own) "@me:example.org" else "@alice:example.org",
        text = text,
        own = own,
        timestampMillis = now - minutesAgo * 60_000L,
        reactions = emptyList(),
        attachment = attachment,
        senderName = if (own) null else "Alice",
        replyTo = replyTo,
        edited = edited,
        deleted = deleted,
        editable = own && attachment == null,
        readBy = readBy,
    )

    private val chat = listOf(
        message("\$a1", "Are we still on for dinner tonight?", own = false, minutesAgo = 9),
        message("\$m1", "Yes! Dinner at 7 works for me", own = true, minutesAgo = 8),
        message("\$a2", "Great, I'll book the table", own = false, minutesAgo = 7),
        message("\$m2", "See you there", own = true, minutesAgo = 6),
    )

    private class Callbacks {
        var sent: MatrixMessage? = null
        var sendCount = 0
        var edited: Pair<String, String>? = null
        var deleted: String? = null
        var forwarded: String? = null
        var reacted: Pair<String, String>? = null
        var loadUntil: String? = null
        var voice: VoiceRecording? = null
        var older = 0
    }

    private fun show(
        messages: List<MatrixMessage>,
        isDirect: Boolean = true,
        draft: MutableState<String> = mutableStateOf(""),
        typingUsers: List<String> = emptyList(),
        loadMedia: MediaLoader = { _, _ -> error("No media in this fixture.") },
        loadLinkPreview: (suspend (String) -> LinkPreview?)? = null,
        voiceCapture: VoiceCapture? = null,
    ): Callbacks {
        val callbacks = Callbacks()
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = if (isDirect) "Alice" else "Weekend plans",
                    isDirect = isDirect,
                    draft = draft.value,
                    onDraft = { draft.value = it },
                    messages = messages,
                    onBack = {},
                    onSend = { replyTo ->
                        callbacks.sent = replyTo
                        callbacks.sendCount += 1
                        draft.value = ""
                    },
                    onOlder = { callbacks.older += 1 },
                    onReact = { id, emoji -> callbacks.reacted = id to emoji },
                    onCall = {},
                    onSendAttachment = {},
                    onSendVoice = { callbacks.voice = it },
                    loadMedia = loadMedia,
                    uploading = false,
                    error = "",
                    decryptionActionLabel = null,
                    onDecryptionAction = {},
                    onEdit = { message, text -> callbacks.edited = message.id to text },
                    onDelete = { callbacks.deleted = it.id },
                    onForward = { callbacks.forwarded = it.single().id },
                    onLoadUntil = { callbacks.loadUntil = it },
                    typingUsers = typingUsers,
                    loadLinkPreview = loadLinkPreview,
                    voiceCapture = voiceCapture,
                )
            }
        }
        return callbacks
    }

    private fun longPress(text: String) {
        composeRule.onNodeWithText(text).performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    @Test
    fun longPressShowsMessagesActionsAndEachActionWorks() {
        val draft = mutableStateOf("")
        val callbacks = show(chat, draft = draft)

        longPress("Yes! Dinner at 7 works for me")
        listOf("Reply", "Forward", "Copy", "Edit", "Delete", "Info").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeRule.onNodeWithContentDescription("More reactions").assertIsDisplayed()
        composeRule.onNodeWithText("👍").assertIsDisplayed()
        composeRule.auditShot("message-actions")

        // Quick reaction from the row on top.
        composeRule.onNodeWithText("❤️").performClick()
        composeRule.runOnIdle { assertEquals("\$m1" to "❤️", callbacks.reacted) }
        composeRule.onAllNodesWithText("Copy").assertCountEquals(0)

        // Back dismisses the overlay without acting.
        longPress("Yes! Dinner at 7 works for me")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Copy").assertCountEquals(0)
        composeRule.onNodeWithText("Yes! Dinner at 7 works for me").assertIsDisplayed()

        // Copy puts the text on the clipboard.
        longPress("Yes! Dinner at 7 works for me")
        composeRule.onNodeWithText("Copy").performClick()
        composeRule.runOnIdle {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            assertEquals("Yes! Dinner at 7 works for me", clip?.getItemAt(0)?.text?.toString())
        }

        // Info: Messages-style details.
        longPress("Yes! Dinner at 7 works for me")
        composeRule.onNodeWithText("Info").performClick()
        composeRule.onNodeWithText("Message details").assertIsDisplayed()
        composeRule.onNodeWithText("Text message").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()

        // Forward hands the message to the picker.
        longPress("Great, I'll book the table")
        composeRule.onAllNodesWithText("Edit").assertCountEquals(0)
        composeRule.onAllNodesWithText("Delete").assertCountEquals(0)
        composeRule.onNodeWithText("Forward").performClick()
        composeRule.runOnIdle { assertEquals("\$a2", callbacks.forwarded) }

        // Delete asks first, then deletes for everyone.
        longPress("See you there")
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete message?").assertIsDisplayed()
        composeRule.onNodeWithText("This message will be deleted for everyone in this conversation.").assertIsDisplayed()
        composeRule.auditShot("delete-confirm")
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertEquals("\$m2", callbacks.deleted) }

        // Edit: the composer takes the original text; sending saves the edit and restores the old draft.
        draft.value = "unsent draft"
        longPress("Yes! Dinner at 7 works for me")
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithText("Editing message").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("Yes! Dinner at 7 works for me", draft.value) }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Yes! Dinner at 7:30 works for me")
        composeRule.auditShot("edit-composer")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.runOnIdle {
            assertEquals("\$m1" to "Yes! Dinner at 7:30 works for me", callbacks.edited)
            assertEquals("unsent draft", draft.value)
            assertEquals(0, callbacks.sendCount)
        }
    }

    @Test
    fun swipingRightRepliesWithAPreviewAboveTheComposer() {
        val draft = mutableStateOf("")
        val callbacks = show(chat, draft = draft)

        composeRule.onNodeWithText("Great, I'll book the table").performTouchInput {
            swipe(start = Offset(centerX, centerY), end = Offset(centerX + 420f, centerY), durationMillis = 250)
        }
        composeRule.onNodeWithText("Replying to Alice").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel reply").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("Thanks!")
        composeRule.auditShot("reply-preview")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.runOnIdle {
            assertEquals("\$a2", callbacks.sent?.id)
            assertEquals(1, callbacks.sendCount)
        }
        composeRule.onAllNodesWithText("Replying to Alice").assertCountEquals(0)

        // The Reply action does the same (here on an own message, nearest the composer), and the strip can be cancelled.
        longPress("See you there")
        composeRule.onNodeWithText("Reply").performClick()
        composeRule.onNodeWithText("Replying to yourself").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel reply").performClick()
        composeRule.onAllNodesWithText("Replying to yourself").assertCountEquals(0)
    }

    @Test
    fun repliesEditsDeletesAndReceiptsRender() {
        val messages = listOf(
            message("\$a1", "Are we still on for dinner tonight?", own = false, minutesAgo = 9),
            message("\$gone", "", own = false, minutesAgo = 8, deleted = true),
            message(
                "\$m1",
                "Yes, 7:30",
                own = true,
                minutesAgo = 7,
                edited = true,
                replyTo = ReplyQuote("\$a1", sender = "Alice", own = false, text = "Are we still on for dinner tonight?"),
            ),
            message(
                "\$m2",
                "And bring the photos",
                own = true,
                minutesAgo = 6,
                replyTo = ReplyQuote("\$older", sender = "Alice", own = false, text = "Pictures from the trip"),
                readBy = mapOf("@alice:example.org" to now),
            ),
        )
        val callbacks = show(messages)

        composeRule.onNodeWithText("Message deleted").assertIsDisplayed()
        composeRule.onNode(hasText("Edited", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText(" · Read", substring = true)).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Reply to Alice", substring = true).onFirst().assertExists()
        composeRule.auditShot("edited-deleted")

        // A quote whose original isn't loaded asks for older history.
        composeRule.onNodeWithText("Pictures from the trip").performClick()
        composeRule.runOnIdle { assertEquals("\$older", callbacks.loadUntil) }
    }

    @Test
    fun groupReceiptsCountReaders() {
        val messages = listOf(
            message("\$a1", "Who's coming?", own = false, minutesAgo = 5),
            message("\$m1", "Me", own = true, minutesAgo = 4, readBy = mapOf("@alice:example.org" to now, "@bob:example.org" to now)),
        )
        show(messages, isDirect = false)
        composeRule.onNode(hasText("Seen by 2", substring = true)).assertIsDisplayed()
    }

    @Test
    fun typingShowsMessagesThreeDots() {
        show(chat, typingUsers = listOf("Alice"))
        composeRule.onNodeWithContentDescription("Alice is typing").assertIsDisplayed()
        composeRule.auditShot("typing")
    }

    @Test
    fun linksAreTappableAndGetAPreviewCard() {
        val image = fixtureImage("link-preview.png")
        val messages = listOf(
            message("\$a1", "Have you seen this? https://matrix.org/blog", own = false, minutesAgo = 3),
        )
        val requested = mutableListOf<String>()
        show(messages, loadLinkPreview = { url ->
            requested += url
            LinkPreview(url = url, title = "Matrix.org blog", description = "News from the Matrix ecosystem.", siteName = "Matrix.org", image = image)
        })
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Matrix.org blog").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("News from the Matrix ecosystem.").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf("https://matrix.org/blog"), requested) }
        composeRule.auditShot("link-preview")
    }

    @Test
    fun linkWithoutPreviewShowsNoCard() {
        show(listOf(message("\$a1", "Try https://example.org", own = false, minutesAgo = 3)), loadLinkPreview = { null })
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("Link preview", substring = true).assertCountEquals(0)
    }

    @Test
    fun tappingAPhotoOpensTheInAppViewer() {
        val image = fixtureImage("viewer-photo.png")
        val messages = listOf(
            message(
                "\$photo",
                "",
                own = false,
                minutesAgo = 2,
                attachment = MatrixAttachment(
                    kind = AttachmentKind.Image,
                    filename = "sunset.png",
                    mimetype = "image/png",
                    sizeBytes = image.length(),
                    durationMillis = null,
                    width = 1200,
                    height = 800,
                    sourceJson = "{\"url\":\"mxc://example.org/sunset\"}",
                    thumbnailSourceJson = null,
                ),
            ),
        )
        show(messages, loadMedia = { _, _ -> image })
        composeRule.onNodeWithContentDescription("Photo").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(MEDIA_VIEWER_TAG).assertExists()
        composeRule.onNodeWithContentDescription("Save").assertIsDisplayed()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithContentDescription("Photo").fetchSemanticsNodes().size >= 2 }
        composeRule.auditShot("media-viewer")
        // The viewer's back arrow and the conversation's share the label; system back closes the viewer.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(MEDIA_VIEWER_TAG).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun emojiButtonOpensTheFullPickerWithSearch() {
        val draft = mutableStateOf("")
        show(chat, draft = draft)
        composeRule.onNodeWithContentDescription("Emoji").performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("Keyboard").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Flags").assertIsDisplayed()
        composeRule.auditShot("emoji-picker")
        composeRule.onAllNodesWithText("😀").onFirst().performClick()
        composeRule.runOnIdle { assertEquals("😀", draft.value) }
        composeRule.onNodeWithText("Search emoji").performTextInput("thumbs")
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("👍").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithText("👍").onFirst().performClick()
        composeRule.runOnIdle { assertEquals("😀👍", draft.value) }
    }

    @Test
    fun moreReactionsOpensThePickerForTheMessage() {
        val callbacks = show(chat)
        longPress("Great, I'll book the table")
        composeRule.onNodeWithContentDescription("More reactions").performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithText("😀").onFirst().performClick()
        composeRule.runOnIdle { assertEquals("\$a2" to "😀", callbacks.reacted) }
    }

    @Test
    fun searchHighlightsAndStepsThroughMatches() {
        val callbacks = show(chat)
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("Search messages")).performTextInput("dinner")
        composeRule.onNodeWithText("1 of 2").assertIsDisplayed()
        composeRule.auditShot("conversation-search")
        composeRule.onNodeWithContentDescription("Older match").performClick()
        composeRule.onNodeWithText("2 of 2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Newer match").performClick()
        composeRule.onNodeWithText("1 of 2").assertIsDisplayed()
        // Past the oldest loaded match, older history is requested.
        composeRule.onNodeWithContentDescription("Older match").performClick()
        composeRule.onNodeWithContentDescription("Older match").performClick()
        composeRule.runOnIdle { assertEquals(1, callbacks.older) }
        composeRule.onNodeWithContentDescription("Close search").performClick()
        composeRule.onNodeWithContentDescription("More options").assertIsDisplayed()
    }

    @Test
    fun slidingUpLocksTheVoiceRecording() {
        val capture = FakeVoiceCapture(File(context.cacheDir, "fixture-voice.ogg").apply { writeBytes(ByteArray(64)) })
        val callbacks = show(chat, voiceCapture = capture)
        // From the button's centre: half its height to its top edge, then the lock distance and some margin.
        val lift = with(composeRule.density) { (VOICE_LOCK_DISTANCE + 56.dp).toPx() }
        // The recording timer ticks with delay(); with a manual clock the test can still reach idle.
        composeRule.mainClock.autoAdvance = false
        fun lock() = composeRule.onNodeWithContentDescription("Voice message").performTouchInput {
            down(center)
            repeat(10) { step -> moveTo(Offset(centerX, centerY - lift * (step + 1) / 10f)) }
            up()
        }
        lock()
        composeRule.mainClock.advanceTimeBy(1_000)
        // Messages' recorder: composer stays, the panel and its Restart / Stop / Attach row sit below it.
        composeRule.onNodeWithContentDescription("Stop recording").assertIsDisplayed()
        composeRule.onNodeWithText("Restart").assertIsDisplayed()
        composeRule.onNodeWithText("Attach").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Audio recording waveform").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide voice recorder").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Send voice message").assertCountEquals(0)
        composeRule.runOnIdle { assertTrue(capture.recording) }
        composeRule.auditShot("voice-locked")
        // Restart throws the take away and records again.
        composeRule.onNodeWithText("Restart").performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.runOnIdle {
            assertTrue(capture.recording)
            assertEquals(2, capture.starts)
        }
        composeRule.onNodeWithContentDescription("Stop recording").performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("Play recording").assertIsDisplayed()
        // Attach closes the panel and leaves the clip in the composer for the send button.
        composeRule.onNodeWithText("Attach").performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onAllNodesWithText("Restart").assertCountEquals(0)
        composeRule.onNodeWithText("Voice message · ", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete recording").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send voice message").performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.runOnIdle { assertNotNull(callbacks.voice) }

        // The round button closes the recorder again, discarding the take.
        callbacks.voice = null
        lock()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("Hide voice recorder").performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onAllNodesWithText("Restart").assertCountEquals(0)
        composeRule.runOnIdle {
            assertFalse(capture.recording)
            assertNull(callbacks.voice)
        }
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun galleryButtonOpensThePhotoPicker() {
        show(chat)
        composeRule.onNodeWithContentDescription("Gallery").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // The system photo picker runs in its own process.
        device.waitForIdle()
        Thread.sleep(3_000)
        composeRule.auditShot("gallery")
        device.pressBack()
    }

    private fun fixtureImage(name: String): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 1200f, 800f, 0xFF1A73E8.toInt(), 0xFFF9AB00.toInt(), Shader.TileMode.CLAMP)
        })
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return file
    }

    /** Stands in for the microphone (the CI emulator has no audio input); the clip is a placeholder file. */
    private class FakeVoiceCapture(private val clip: File) : VoiceCapture {
        var recording = false
        var starts = 0
        private var startedAt = 0L
        override val elapsedMillis: Long get() = if (recording) System.currentTimeMillis() - startedAt else 0L
        override fun start(): Boolean {
            recording = true
            starts++
            startedAt = System.currentTimeMillis() - 4_000L
            return true
        }
        override fun sampleAmplitude(): Float = 0.5f
        override fun stop(): VoiceRecording? {
            if (!recording) return null
            recording = false
            return VoiceRecording(clip, elapsedMillis.coerceAtLeast(4_000L), List(40) { (it % 7) / 7f })
        }
        override fun cancel() {
            recording = false
        }
    }
}
