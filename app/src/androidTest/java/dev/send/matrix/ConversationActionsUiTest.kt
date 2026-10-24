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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
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
        var scheduledAt: Pair<String, Long>? = null
        var scheduledAction: Pair<ScheduledMessage, ScheduledAction>? = null
    }

    private fun show(
        messages: List<MatrixMessage>,
        isDirect: Boolean = true,
        draft: MutableState<String> = mutableStateOf(""),
        typingUsers: List<String> = emptyList(),
        loadMedia: MediaLoader = { _, _ -> error("No media in this fixture.") },
        loadLinkPreview: (suspend (String) -> LinkPreview?)? = null,
        voiceCapture: VoiceCapture? = null,
        /** Scheduled messages shown under the timeline; null leaves scheduling out of the fixture. */
        scheduled: MutableState<List<ScheduledMessage>>? = null,
        /** When set, the screen follows this state instead of [messages] (tests that add messages). */
        liveMessages: MutableState<List<MatrixMessage>>? = null,
        hasOlder: Boolean = false,
        stickers: StickerActions? = null,
    ): Callbacks {
        val callbacks = Callbacks()
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = if (isDirect) "Alice" else "Weekend plans",
                    isDirect = isDirect,
                    draft = draft.value,
                    onDraft = { draft.value = it },
                    messages = liveMessages?.value ?: messages,
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
                    scheduled = scheduled?.value.orEmpty(),
                    onSchedule = if (scheduled != null) ({ text, at, _ -> callbacks.scheduledAt = text to at }) else null,
                    onScheduledAction = { pending, action -> callbacks.scheduledAction = pending to action },
                    hasOlder = hasOlder,
                    stickers = stickers,
                )
            }
        }
        return callbacks
    }

    @Test
    fun holdingSendSchedulesTheDraftAndScheduledMessagesOfferSendNowUpdateAndDelete() {
        val draft = mutableStateOf("Happy birthday!")
        val scheduled = mutableStateOf(emptyList<ScheduledMessage>())
        val callbacks = show(chat, draft = draft, scheduled = scheduled)
        val tomorrowEight = scheduleOptions().first { it.label == "Tomorrow, 8:00 AM" }.sendAtMillis

        // Hold Send: Messages' Schedule send sheet with quick times and a date/time picker.
        composeRule.onNodeWithContentDescription("Send").performTouchInput { longClick() }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Schedule send").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Pick date and time").assertIsDisplayed()
        composeRule.auditShot("schedule-send")
        composeRule.onNodeWithText("Tomorrow, 8:00 AM").performClick()
        composeRule.waitUntil(5_000) { callbacks.scheduledAt != null }
        assertEquals("Happy birthday!" to tomorrowEight, callbacks.scheduledAt)
        composeRule.runOnIdle { assertEquals("", draft.value) }

        // The scheduled message sits under the conversation with its time; tapping offers Send now / Update / Delete.
        val pending = ScheduledMessage("s1", "!room:example.org", "Happy birthday!", tomorrowEight, now)
        // State written from the test thread is applied on the UI thread, so the next frame sees it.
        composeRule.runOnIdle { scheduled.value = listOf(pending) }
        val status = composeRule.onNodeWithText("Scheduled for Tomorrow, 8:00 AM")
        try {
            status.assertIsDisplayed()
        } catch (failure: AssertionError) {
            val node = status.fetchSemanticsNode()
            throw AssertionError(
                "${failure.message}\nbounds=${node.boundsInRoot} window=${node.boundsInWindow} size=${node.size} placed=${node.layoutInfo.isPlaced}\n" +
                    composeRule.onRoot().printToString(maxDepth = 8).lineSequence().filter { "Scheduled" in it || "Node #" in it }.take(60).joinToString("\n"),
                failure,
            )
        }
        composeRule.auditShot("scheduled-message")
        composeRule.onNodeWithText("Happy birthday!").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Send now").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Send now").performClick()
        composeRule.waitUntil(5_000) { callbacks.scheduledAction != null }
        assertEquals(pending to ScheduledAction.SendNow, callbacks.scheduledAction)

        // Update message: the text comes back to the composer and Send (now a clock) re-schedules it.
        callbacks.scheduledAction = null
        composeRule.onNodeWithText("Happy birthday!").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Update message").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Update message").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Updating scheduled message").fetchSemanticsNodes().isNotEmpty() }
        composeRule.runOnIdle { assertEquals("Happy birthday!", draft.value) }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Happy birthday, Alice!")
        composeRule.onNodeWithContentDescription("Schedule").performClick()
        composeRule.waitUntil(5_000) { callbacks.scheduledAction != null }
        assertEquals(pending to ScheduledAction.Update("Happy birthday, Alice!", tomorrowEight), callbacks.scheduledAction)
        composeRule.runOnIdle { assertEquals("", draft.value) }

        // Delete.
        callbacks.scheduledAction = null
        composeRule.onNodeWithText("Happy birthday!").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Delete").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitUntil(5_000) { callbacks.scheduledAction != null }
        assertEquals(pending to ScheduledAction.Delete, callbacks.scheduledAction)

        // A scheduled photo shows as a tile above its caption (Messages' layout) and reads as one attachment.
        val photo = fixtureImage("cake.png")
        val withPhoto = ScheduledMessage(
            "s2", "!room:example.org", "Look at this cake", tomorrowEight, now,
            attachments = listOf(ScheduledAttachment(photo.absolutePath, photo.name, "image/png")),
        )
        composeRule.runOnIdle { scheduled.value = listOf(withPhoto) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription("Preview of cake.png").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("Scheduled message: 1 attachment, Look at this cake. Scheduled for Tomorrow, 8:00 AM").assertExists()
        composeRule.auditShot("scheduled-photo")
    }

    @Test
    fun scrollingUpLoadsOlderHistoryAndTheJumpButtonReturnsToTheNewest() {
        // A long conversation: the newest messages fill the screen, the oldest are far above.
        val history = (60 downTo 1).map { n ->
            message("\$h$n", if (n % 2 == 0) "Older message $n" else "Reply $n", own = n % 2 == 1, minutesAgo = n + 20)
        }
        val live = mutableStateOf(history)
        val callbacks = show(history, liveMessages = live, hasOlder = true)
        // At the newest message nothing is requested and there is no jump button (as in Messages).
        composeRule.runOnIdle { assertEquals(0, callbacks.older) }
        composeRule.onAllNodesWithContentDescription("Jump to newest message").assertCountEquals(0)

        // Scrolling up to the oldest loaded message asks for the next page once and shows a spinner above it.
        composeRule.onNodeWithTag(TIMELINE_TAG).performScrollToIndex(history.lastIndex)
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, callbacks.older) }
        // The list was scrolled to its very end before the spinner grew the oldest row upward past the top edge;
        // a nudge brings it into view, as a user's next finger movement would.
        composeRule.onNodeWithContentDescription("Loading older messages").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Jump to newest message").assertIsDisplayed()
        composeRule.auditShot("conversation-loading-older")

        // A message arriving meanwhile counts on the button; the page arriving replaces the spinner.
        // Fixture state is written on the UI thread so the next frame sees it.
        composeRule.runOnIdle { live.value = live.value + message("\$new1", "Are you there?", own = false, minutesAgo = 0) }
        composeRule.onNodeWithContentDescription("1 new message, jump to newest").assertIsDisplayed()
        composeRule.runOnIdle {
            live.value = (70 downTo 61).map { n -> message("\$h$n", "Older message $n", own = false, minutesAgo = n + 20) } + live.value
        }
        composeRule.waitForIdle()

        // Tapping returns to the newest message; the button and the spinner are gone.
        composeRule.onNodeWithContentDescription("1 new message, jump to newest").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Are you there?").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Jump to newest message").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("1 new message, jump to newest").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Loading older messages").assertCountEquals(0)
    }

    @Test
    fun stickersShowWithoutABubbleAndTheComposerPanelSendsThem() {
        val image = fixtureImage("sticker.png")
        val party = PackImage(
            shortcode = "party",
            body = "Party",
            url = "mxc://example.org/party",
            sourceJson = "{\"url\":\"mxc://example.org/party\"}",
            mimetype = "image/png",
            width = 512,
            height = 512,
            sizeBytes = image.length(),
            animated = false,
            sticker = true,
        )
        val messages = chat + message(
            "\$sticker",
            "",
            own = false,
            minutesAgo = 1,
            attachment = MatrixAttachment(
                kind = AttachmentKind.Sticker,
                filename = "Party",
                mimetype = "image/png",
                sizeBytes = image.length(),
                durationMillis = null,
                width = 512,
                height = 512,
                sourceJson = party.sourceJson,
                thumbnailSourceJson = null,
            ),
        )
        var sent: Pair<PackImage, Boolean>? = null
        val actions = StickerActions(
            loadPacks = { listOf(ImagePack(MatrixRepository.USER_PACK_ID, "Your stickers", listOf(party))) },
            send = { picked, asGif -> sent = picked to asGif },
            add = { error("Not in this fixture.") },
            remove = {},
        )
        show(messages, loadMedia = { _, _ -> image }, stickers = actions)

        // A received sticker is the picture alone, described for TalkBack, with no text bubble.
        composeRule.onNodeWithContentDescription("Sticker: Party").assertIsDisplayed()
        composeRule.onAllNodesWithText("Party").assertCountEquals(0)

        // The composer panel has Messages' three tabs; Stickers lists the pack, tapping one sends it.
        composeRule.onNodeWithContentDescription("Emoji").performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("Stickers").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("GIFs").assertIsDisplayed()
        composeRule.onNodeWithText("Stickers").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Your stickers").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("Add a sticker from your photos").assertIsDisplayed()
        composeRule.auditShot("sticker-picker")
        composeRule.onNodeWithContentDescription("Send sticker: Party").performClick()
        composeRule.waitUntil(5_000) { sent != null }
        assertEquals(party to false, sent)

        // No animated pictures in the pack: the GIFs tab explains how to add some.
        composeRule.onNodeWithText("GIFs").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("No GIFs yet").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Add GIF").assertIsDisplayed()
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
        // The strip slides in with the keyboard; wait until it is on screen.
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Editing message").assertIsDisplayed() }.isSuccess }
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
