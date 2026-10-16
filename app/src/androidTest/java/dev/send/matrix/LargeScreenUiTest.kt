package dev.send.matrix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * List-detail on large screens with fixtures: the emulator's display is resized with `wm size` / `wm density` while
 * the test runs (and reset after), so the layout follows a real window change, the screenshots show the whole
 * device, and switching sizes acts like folding and unfolding a foldable (no hinge or posture is emulated).
 */
@RunWith(AndroidJUnit4::class)
class LargeScreenUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<LargeScreenFixtureActivity>()

    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val rooms = motionRooms(8)
    private val chat = motionChat(40)
    private var initialDensity = 0
    private var initialWidthDp = 0

    @Before
    fun rememberDisplay() {
        val configuration = composeRule.activity.resources.configuration
        initialDensity = configuration.densityDpi
        initialWidthDp = configuration.screenWidthDp
    }

    @After
    fun resetDisplay() {
        device.executeShellCommand("wm size reset")
        device.executeShellCommand("wm density reset")
        // Later tests start on the emulator's own display.
        runCatching {
            composeRule.waitUntil(DISPLAY_TIMEOUT_MS) {
                val configuration = composeRule.activity.resources.configuration
                configuration.densityDpi == initialDensity && configuration.screenWidthDp == initialWidthDp
            }
        }
    }

    /**
     * Resizes the emulator's display to [width]×[height] px at [density] dpi and waits for the window to follow and
     * the compositor to show it (screenshots read the display, not the composition).
     */
    private fun display(width: Int, height: Int, density: Int) {
        device.executeShellCommand("wm density $density")
        device.executeShellCommand("wm size ${width}x$height")
        val widthDp = width * 160 / density
        composeRule.waitUntil(DISPLAY_TIMEOUT_MS) {
            val configuration = composeRule.activity.resources.configuration
            configuration.densityDpi == density && kotlin.math.abs(configuration.screenWidthDp - widthDp) <= 48
        }
        settle()
    }

    /** Lets the window and keyboard catch up (the keyboard re-lays itself out for a new density once it shows). */
    private fun settle() {
        composeRule.waitForIdle()
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        composeRule.waitForIdle()
    }

    private fun twoPane(): Boolean = composeRule.onAllNodes(hasTestTag(LIST_PANE_TAG)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForPanes(two: Boolean) {
        composeRule.waitUntil(DISPLAY_TIMEOUT_MS) { twoPane() == two }
        composeRule.waitForIdle()
    }

    private val inListPane = hasAnyAncestor(hasTestTag(LIST_PANE_TAG))
    private val inDetailPane = hasAnyAncestor(hasTestTag(DETAIL_PANE_TAG))
    private fun row(name: String): SemanticsMatcher = hasText(name) and hasClickAction()
    private val composer = hasSetTextAction()

    private fun pressBack() = composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun tabletShowsListBesideConversation() {
        // 1280×800dp (the emulator's display tops out at 1920px a side, so sizes stay within it).
        display(1920, 1200, 240)
        // A saved draft rather than typing: with the keyboard up at this `wm size`/density the CI emulator's keyboard
        // (which still sees the physical 320×640 display) leaves the composer off-screen; the fold test types.
        composeRule.setContent { SendTheme(ThemeChoice.Light) { LargeScreenFixture(rooms, chat, mapOf(rooms[0].id to "See you at 7")) } }
        waitForPanes(true)
        // Nothing open: the list beside a quiet placeholder.
        composeRule.onNode(hasTestTag(NO_CONVERSATION_TAG)).assertIsDisplayed()
        composeRule.onNode(row("Maya") and inListPane).assertIsNotSelected()
        composeRule.auditShot("tablet-no-conversation")

        composeRule.onNode(row("Maya") and inListPane).performClick()
        composeRule.onNode(hasText("Message number 39 from Maya") and inDetailPane).assertIsDisplayed()
        composeRule.onNode(row("Maya") and inListPane).assertIsSelected()
        composeRule.onNode(hasText("See you at 7") and composer and inDetailPane).assertIsDisplayed()
        composeRule.auditShot("tablet-list-detail")

        // Another row replaces the conversation in place, with its own (empty) draft; the first keeps its draft.
        composeRule.onNode(row("Contact 2") and inListPane).performClick()
        composeRule.onNode(row("Contact 2") and inListPane).assertIsSelected()
        composeRule.onNode(row("Maya") and inListPane).assertIsNotSelected()
        composeRule.onAllNodes(hasText("See you at 7") and composer).assertCountEquals(0)
        composeRule.onNode(row("Maya") and inListPane).performClick()
        composeRule.onNode(hasText("See you at 7") and composer and inDetailPane).assertIsDisplayed()

        // Back closes the conversation and stays on two panes.
        pressBack()
        composeRule.waitForIdle()
        composeRule.onNode(hasTestTag(NO_CONVERSATION_TAG)).assertIsDisplayed()
        assertEquals("Still two panes after back", true, twoPane())
        composeRule.onNode(row("Maya") and inListPane).assertIsNotSelected()
    }

    @Test
    fun foldAndUnfoldKeepConversationDraftAndScroll() {
        // Pixel Fold-like sizes at one density: folded 411×857dp, unfolded 841×701dp.
        display(PHONE_WIDTH, PHONE_HEIGHT, FOLD_DENSITY)
        composeRule.setContent { SendTheme(ThemeChoice.Light) { LargeScreenFixture(rooms, chat) } }
        waitForPanes(false)
        composeRule.onNode(row("Maya")).performClick()
        composeRule.onNode(hasText("Message number 39 from Maya")).assertIsDisplayed()
        composeRule.onNode(composer).performTextInput("Draft before unfolding")
        settle()
        // Scroll back into history so the position has something to keep.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Message number 12 from Maya"))
        composeRule.onNode(hasText("Message number 12 from Maya")).assertIsDisplayed()

        // Unfold: the same conversation, selected in the list beside it, with its draft and scroll position.
        display(UNFOLDED_WIDTH, UNFOLDED_HEIGHT, FOLD_DENSITY)
        waitForPanes(true)
        composeRule.onNode(row("Maya") and inListPane).assertIsSelected()
        composeRule.onNode(hasText("Draft before unfolding") and composer and inDetailPane).assertIsDisplayed()
        composeRule.onNode(hasText("Message number 12 from Maya") and inDetailPane).assertIsDisplayed()
        composeRule.auditShot("fold-unfolded")

        // Fold: one pane showing that conversation, draft and position intact.
        display(PHONE_WIDTH, PHONE_HEIGHT, FOLD_DENSITY)
        waitForPanes(false)
        composeRule.onNode(hasText("Draft before unfolding") and composer).assertIsDisplayed()
        composeRule.onNode(hasText("Message number 12 from Maya")).assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(NO_CONVERSATION_TAG)).assertCountEquals(0)
        composeRule.auditShot("fold-folded")

        // The back stack is unchanged: back returns to the list, and unfolding then shows nothing open.
        pressBack()
        composeRule.waitForIdle()
        composeRule.onNode(row("Maya")).assertIsDisplayed()
        composeRule.onAllNodes(composer).assertCountEquals(0)
        display(UNFOLDED_WIDTH, UNFOLDED_HEIGHT, FOLD_DENSITY)
        waitForPanes(true)
        composeRule.onNode(hasTestTag(NO_CONVERSATION_TAG)).assertIsDisplayed()
        composeRule.onNode(row("Maya") and inListPane).assertIsNotSelected()
    }

    @Test
    fun landscapePhoneKeepsOnePane() {
        // 857×411dp: wide enough, but too short to share with a keyboard, so one pane at a time as on the phone.
        display(PHONE_HEIGHT, PHONE_WIDTH, FOLD_DENSITY)
        composeRule.setContent { SendTheme(ThemeChoice.Light) { LargeScreenFixture(rooms, chat) } }
        waitForPanes(false)
        composeRule.onNode(row("Maya")).performClick()
        composeRule.onNode(hasText("Message number 39 from Maya")).assertIsDisplayed()
        assertEquals("One pane in landscape on a phone", false, twoPane())
        composeRule.auditShot("phone-landscape")
    }

    private companion object {
        const val DISPLAY_TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 3_000L
        const val PHONE_WIDTH = 864
        const val PHONE_HEIGHT = 1800
        const val UNFOLDED_WIDTH = 1766
        const val UNFOLDED_HEIGHT = 1472
        const val FOLD_DENSITY = 336
    }
}

/**
 * SendApp's navigation on fixtures: [AdaptiveScreenHost] with the conversation list and a conversation keyed by
 * room, per-room drafts, open from a row and back to the list, the open row highlighted beside the list.
 */
@Composable
private fun LargeScreenFixture(rooms: List<MatrixRoom>, messages: List<MatrixMessage>, savedDrafts: Map<String, String> = emptyMap()) {
    var openId by remember { mutableStateOf<String?>(null) }
    var drafts by remember { mutableStateOf(savedDrafts) }
    // Like SendApp, the leaving conversation keeps its room through the close transition.
    val shown = remember { mutableStateOf<MatrixRoom?>(null) }.apply { openId?.let { id -> value = rooms.first { it.id == id } } }.value
    val paneWidth = listPaneWidth()
    AdaptiveScreenHost(
        screen = if (openId != null) SCREEN_CONVERSATION else SCREEN_LIST,
        listPaneWidth = paneWidth,
        list = {
            HomeScreen(
                rooms = rooms,
                onOpen = { openId = it.id },
                error = "",
                notice = "",
                creatingDirectRoom = false,
                onCreateDirectRoom = {},
                onCreateGroup = { _, _ -> },
                onAccount = {},
                onVerifyDevice = null,
                openRoomId = openId.takeIf { paneWidth != null },
            )
        },
    ) {
        if (shown != null) key(shown.id) {
            ConversationScreen(
                title = shown.name,
                isDirect = shown.direct,
                draft = drafts[shown.id].orEmpty(),
                onDraft = { value -> drafts = drafts + (shown.id to value) },
                messages = messages,
                onBack = { openId = null },
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
        }
    }
}
