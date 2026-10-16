package dev.send.matrix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8 accessibility on fixtures: home, conversation (bubbles, reactions, reply quote), the composer with a draft
 * and reply strip, and Settings at 200% font scale — key labels shown whole and inside the screen, 48dp touch
 * targets, and a TalkBack label on every clickable element — plus the dark variants of home, conversation, details
 * and Settings. TalkBack's custom actions for the swipe gestures (Archive, Reply) are invoked like TalkBack would.
 * Screens are saved with [auditShot] as `a11y-font200-*` and `dark-*`.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = System.currentTimeMillis()

    private val rooms = listOf(
        MatrixRoom("!maya:example.org", "Maya", encrypted = true, direct = true, lastMessage = "See you at 7!", lastTimestampMillis = now - 60_000, unreadCount = 2, dmUserId = "@maya:example.org"),
        MatrixRoom("!family:example.org", "Family", encrypted = true, direct = false, lastMessage = "Sam: Photo", lastTimestampMillis = now - 3_600_000, pinned = true),
        MatrixRoom("!club:example.org", "Book club", encrypted = true, direct = false, lastMessage = "You: Chapter 4 next week", lastTimestampMillis = now - 86_400_000),
    )

    private val messages = listOf(
        MatrixMessage("\$m1", "@maya:example.org", "Are we still on for dinner?", own = false, timestampMillis = now - 9 * 60_000, reactions = emptyList(), senderName = "Maya"),
        MatrixMessage("\$m2", "@me:example.org", "Yes! 7 works", own = true, timestampMillis = now - 8 * 60_000, reactions = listOf("❤️")),
        MatrixMessage(
            "\$m3", "@maya:example.org", "See you at 7!", own = false, timestampMillis = now - 7 * 60_000, reactions = emptyList(), senderName = "Maya",
            replyTo = ReplyQuote("\$m2", sender = "You", own = true, text = "Yes! 7 works"),
        ),
    )

    private val details = RoomDetails(
        roomId = "!weekend:example.org",
        name = "Weekend plans",
        topic = "Cabin trip, 14–16 Nov",
        avatarUrl = null,
        direct = false,
        encrypted = true,
        people = listOf(
            RoomPerson("@me:example.org", "Me", null, role = PersonRole.Admin, isMe = true),
            RoomPerson("@maya:example.org", "Maya", null),
        ),
        notifications = NotificationLevel.All,
        canInvite = true,
        canRename = true,
        canSetTopic = true,
        canSetAvatar = true,
        dmUserId = null,
        dmIdentity = null,
    )

    private val detailsActions = ConversationDetailsActions(
        load = { details }, setNotifications = {}, invite = {}, rename = {}, setTopic = {}, setAvatar = {},
        archive = {}, delete = {}, block = {}, message = {},
    )

    /** Content at [fontScale] (Settings → Display size and text, "Font size" at its 200% maximum). */
    @Composable
    private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
    }

    private fun showHome(theme: ThemeChoice, fontScale: Float, archived: MutableList<String> = mutableListOf()) {
        val actions = ConversationListActions(
            setArchived = { picked, value -> if (value) archived += picked.map { it.name } },
            setPinned = { _, _ -> }, setUnread = { _, _ -> }, setMuted = { _, _ -> }, delete = {}, block = {},
        )
        composeRule.setContent {
            SendTheme(theme = theme) {
                Scaled(fontScale) {
                    HomeScreen(
                        rooms = rooms,
                        onOpen = {},
                        error = "",
                        notice = "",
                        creatingDirectRoom = false,
                        onCreateDirectRoom = {},
                        onCreateGroup = { _, _ -> },
                        onAccount = {},
                        onVerifyDevice = null,
                        mutedRoomIds = setOf("!club:example.org"),
                        listActions = actions,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun showConversation(theme: ThemeChoice, fontScale: Float, title: String = "Maya", direct: Boolean = true) {
        val draft = mutableStateOf("Running late, save me a seat")
        composeRule.setContent {
            SendTheme(theme = theme) {
                Scaled(fontScale) {
                    ConversationScreen(
                        title = title,
                        isDirect = direct,
                        draft = draft.value,
                        onDraft = { draft.value = it },
                        messages = messages,
                        onBack = {},
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
                        onToggleMute = {},
                        details = detailsActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun showSettings(theme: ThemeChoice, fontScale: Float) {
        composeRule.setContent {
            SendTheme(theme = theme) {
                Scaled(fontScale) {
                    SettingsScreen(
                        notificationSummary = "Background sync every 15 minutes",
                        onNotifications = {},
                        sounds = true,
                        onSounds = {},
                        theme = theme,
                        onTheme = {},
                        linkPreviews = true,
                        onLinkPreviews = {},
                        securitySummary = "Device verified · recovery on",
                        onSecurity = {},
                        onDevices = {},
                        onBlocked = {},
                        onArchived = {},
                        onAbout = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** [text] is on screen, inside the window horizontally, and laid out without clipping or ellipsis. */
    private fun assertShownWhole(text: String) {
        val interaction = composeRule.onNodeWithText(text, useUnmergedTree = true)
        interaction.assertIsDisplayed()
        val node = interaction.fetchSemanticsNode()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = node.boundsInRoot
        assertTrue("\"$text\" sticks out of the screen: $bounds in $root", bounds.left >= root.left - 1f && bounds.right <= root.right + 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        onMain { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) }
        assertTrue("\"$text\" has no text layout", layouts.isNotEmpty())
        layouts.forEach { assertTrue("\"$text\" is clipped or ellipsized at this font scale", !it.hasVisualOverflow) }
    }

    private fun assertTarget(description: String) {
        composeRule.onNodeWithContentDescription(description).assertTouchTarget(description, width = true)
    }

    /** The node's touch bounds (including Compose's minimum touch-target expansion) are at least 48dp tall (and wide). */
    private fun SemanticsNodeInteraction.assertTouchTarget(name: String, width: Boolean = false) {
        val node = fetchSemanticsNode()
        val min = with(node.layoutInfo.density) { 48.dp.toPx() } - 0.5f
        val bounds = node.touchBoundsInRoot
        assertTrue("\"$name\" touch target is ${bounds.height}px tall, under 48dp ($min px)", bounds.height >= min)
        if (width) assertTrue("\"$name\" touch target is ${bounds.width}px wide, under 48dp ($min px)", bounds.width >= min)
    }

    private fun SemanticsNode.label(): String = listOfNotNull(
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        config.getOrNull(SemanticsProperties.EditableText)?.text,
    ).joinToString(" ").trim()

    /** Every clickable element on screen has something for TalkBack to read: its text or a content description. */
    private fun assertClickablesLabelled() {
        val nodes = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("No clickable elements found", nodes.isNotEmpty())
        val unlabelled = nodes.filter { it.label().isEmpty() }
        assertTrue(
            "Clickable elements without a label: " + unlabelled.joinToString { "${it.boundsInRoot} ${it.config}" },
            unlabelled.isEmpty(),
        )
    }

    /** Runs the TalkBack custom action [label] on the clickable node showing [text]. */
    private fun customAction(text: String, label: String) {
        val node = composeRule.onNode(hasText(text) and hasClickAction()).fetchSemanticsNode()
        val actions = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val action = actions.firstOrNull { it.label == label } ?: throw AssertionError("No \"$label\" action on \"$text\": ${actions.map { it.label }}")
        onMain { action.action() }
        composeRule.waitForIdle()
    }

    @Test
    fun homeAtDoubleFontScale() {
        val archived = mutableListOf<String>()
        showHome(ThemeChoice.Light, fontScale = 2f, archived = archived)
        listOf("Send", "Maya", "Family", "Start chat").forEach(::assertShownWhole)
        assertTarget("Search")
        assertTarget("Account")
        composeRule.onNode(hasText("Maya") and hasClickAction()).assertTouchTarget("Maya row")
        // Unread count, pinned and muted are read with the row.
        composeRule.onNode(hasText("Maya") and hasClickAction()).fetchSemanticsNode().label().let { assertTrue(it, "2 unread" in it) }
        composeRule.onNode(hasText("Family") and hasClickAction()).fetchSemanticsNode().label().let { assertTrue(it, "Pinned" in it) }
        composeRule.onNode(hasText("Book club") and hasClickAction()).fetchSemanticsNode().label().let { assertTrue(it, "Muted" in it) }
        assertClickablesLabelled()
        composeRule.auditShot("a11y-font200-home")
        // TalkBack can't swipe: Archive is a custom action on the row.
        customAction("Book club", "Archive")
        composeRule.runOnIdle { assertEquals(listOf("Book club"), archived) }
    }

    @Test
    fun conversationAndComposerAtDoubleFontScale() {
        showConversation(ThemeChoice.Light, fontScale = 2f)
        listOf("Maya", "See you at 7!", "Running late, save me a seat").forEach(::assertShownWhole)
        composeRule.onNodeWithContentDescription("Reply to You").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Reactions").assertIsDisplayed()
        listOf("Back", "Video call", "Voice call", "More options", "Attach file", "Emoji", "Gallery", "Send").forEach(::assertTarget)
        assertClickablesLabelled()
        composeRule.auditShot("a11y-font200-conversation")
        // TalkBack can't swipe a bubble: Reply is a custom action on it, and opens the reply strip.
        customAction("See you at 7!", "Reply")
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Replying to Maya").fetchSemanticsNodes().isNotEmpty() }
        assertShownWhole("Replying to Maya")
        assertShownWhole("Running late, save me a seat")
        assertTarget("Cancel reply")
        assertTarget("Send")
        assertClickablesLabelled()
        composeRule.auditShot("a11y-font200-composer")
    }

    @Test
    fun settingsAtDoubleFontScale() {
        showSettings(ThemeChoice.Light, fontScale = 2f)
        listOf("Settings", "Choose theme", "Hear outgoing and incoming message sounds").forEach(::assertShownWhole)
        assertTarget("Back")
        composeRule.onNode(hasText("Choose theme") and hasClickAction()).assertTouchTarget("Choose theme row")
        assertClickablesLabelled()
        composeRule.auditShot("a11y-font200-settings")
    }

    @Test
    fun darkHome() {
        showHome(ThemeChoice.Dark, fontScale = 1f)
        composeRule.onNodeWithText("Maya").assertIsDisplayed()
        composeRule.auditShot("dark-home")
    }

    @Test
    fun darkConversationAndDetails() {
        showConversation(ThemeChoice.Dark, fontScale = 1f, title = "Weekend plans", direct = false)
        composeRule.onNodeWithText("See you at 7!").assertIsDisplayed()
        composeRule.auditShot("dark-conversation")
        // The header opens the details; a tap landing while the screen settles is retried.
        for (attempt in 1..3) {
            composeRule.onNode(hasText("Weekend plans") and hasClickAction()).performClick()
            val opened = runCatching {
                composeRule.waitUntil(4_000) { composeRule.onAllNodesWithText("Cabin trip, 14–16 Nov").fetchSemanticsNodes().isNotEmpty() }
            }.isSuccess
            if (opened) break
        }
        composeRule.onNodeWithText("Cabin trip, 14–16 Nov").assertIsDisplayed()
        assertTarget("Change group photo")
        composeRule.auditShot("dark-details")
    }

    @Test
    fun darkSettings() {
        showSettings(ThemeChoice.Dark, fontScale = 1f)
        composeRule.onNodeWithText("Choose theme").assertIsDisplayed()
        composeRule.auditShot("dark-settings")
    }
}
