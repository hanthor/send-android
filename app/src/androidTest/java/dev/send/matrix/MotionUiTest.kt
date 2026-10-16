package dev.send.matrix

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 motion on fixtures, with the test clock paused where timing matters: the "Start chat" FAB collapsing and
 * expanding, the avatar/name shared element mid-transition, predictive back following the gesture, and new
 * bubbles part-way through their entry at about half their spring.
 */
@RunWith(AndroidJUnit4::class)
class MotionUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val density get() = composeRule.density

    private fun bounds(text: String, unmerged: Boolean = false): Rect =
        composeRule.onNodeWithText(text, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private fun descBounds(description: String): Rect =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    private fun hasText(text: String) = composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun fabWidth(): Float = bounds("Start chat").width

    @Test
    fun fabCollapsesScrollingDownAndExpandsScrollingUpOrAtTop() {
        // Long enough that no scroll below is clamped at the end of the list.
        composeRule.setContent { SendTheme { MotionFixture(motionRooms(120), motionChat(4)) } }
        composeRule.waitForIdle()
        val collapsed = with(density) { 56.dp.toPx() }
        val expanded = fabWidth()
        assertTrue("Expanded FAB is wider than its icon: $expanded", expanded > collapsed + with(density) { 40.dp.toPx() })

        composeRule.mainClock.autoAdvance = false
        val list = composeRule.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(20)
        composeRule.mainClock.advanceTimeByFrame()
        // MDC's change-size spec runs 400ms on the emphasized curve: about a quarter in, the width is part-way.
        composeRule.mainClock.advanceTimeBy(128)
        val midway = fabWidth()
        assertTrue("FAB mid-collapse: $collapsed < $midway < $expanded", midway > collapsed + 1f && midway < expanded - 1f)
        composeRule.mainClock.advanceTimeBy(600)
        assertEquals("Collapsed to the 56dp icon FAB", collapsed, fabWidth(), 1.5f)

        // Further down it stays collapsed; scrolling back up (not yet at the top) expands it.
        scrollTo(30)
        assertEquals("Stays collapsed scrolling further down", collapsed, fabWidth(), 1.5f)
        scrollTo(25)
        assertEquals("Expanded scrolling up", expanded, fabWidth(), 1.5f)
        scrollTo(35)
        assertEquals("Collapsed scrolling down again", collapsed, fabWidth(), 1.5f)
        scrollTo(0)
        assertEquals("Expanded at the top", expanded, fabWidth(), 1.5f)
        composeRule.mainClock.autoAdvance = true
    }

    /** Scrolls the conversation list (paused clock) so row [index] is first, then lets the FAB settle. */
    private fun scrollTo(index: Int) {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
        composeRule.mainClock.advanceTimeBy(600)
        val name = if (index == 0) "Maya" else "Contact ${index + 1}"
        assertTrue("Row $name is on screen after scrolling to $index", hasText(name))
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun avatarAndNameAreSharedIntoTheConversationAndBack() {
        var transitions: SharedTransitionScope? = null
        composeRule.setContent { SendTheme { MotionFixture(motionRooms(10), motionChat(6), onTransitionScope = { transitions = it }) } }
        composeRule.waitForIdle()
        fun active() = composeRule.runOnUiThread<Boolean> { transitions!!.isTransitionActive }
        assertFalse(active())

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Maya").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(100)
        assertTrue("Row avatar/name are mid-flight into the header", active())
        composeRule.auditShot("motion-shared-open")
        composeRule.mainClock.advanceTimeBy(1_500)
        assertFalse(active())
        assertTrue(composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty())

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(100)
        assertTrue("Header avatar/name are mid-flight back into the row", active())
        composeRule.mainClock.advanceTimeBy(1_500)
        assertFalse(active())
        assertTrue(composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty())
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun predictiveBackScalesTheConversationWithTheGesture() {
        composeRule.setContent { SendTheme { MotionFixture(motionRooms(10), motionChat(6)) } }
        composeRule.onNodeWithText("Maya").performClick()
        composeRule.waitForIdle()
        val resting = descBounds("Back")
        val dispatcher = composeRule.activity.onBackPressedDispatcher

        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 800f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        composeRule.waitForIdle()
        val halfway = descBounds("Back")
        // Half the gesture: 95% scale, shifted toward the swipe (right, from the left edge).
        assertEquals("Scale at progress 0.5", 0.95f, halfway.width / resting.width, 0.01f)
        assertTrue("Shifted right: ${halfway.left} > ${resting.left}", halfway.left > resting.left)

        composeRule.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(240f, 800f, 1f, BackEventCompat.EDGE_LEFT)) }
        composeRule.waitForIdle()
        assertEquals("Scale at the end of the gesture", 0.9f, descBounds("Back").width / resting.width, 0.01f)

        // Cancelled, the conversation springs back to full size and stays.
        composeRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()
        assertEquals(resting.width, descBounds("Back").width, 0.5f)
        assertEquals(resting.left, descBounds("Back").left, 0.5f)

        // Committed, it leaves for the list with the close transition.
        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 800f, 0.7f, BackEventCompat.EDGE_LEFT))
        }
        composeRule.waitForIdle()
        composeRule.auditShot("motion-predictive-back")
        composeRule.runOnUiThread { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty())
        assertTrue(hasText("Maya"))
    }

    /** Adds [message] with the clock paused and returns its text bounds about half-way through the entry spring and settled. */
    private fun entryBounds(messages: androidx.compose.runtime.MutableState<List<MatrixMessage>>, message: MatrixMessage): Pair<Rect, Rect> {
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { messages.value = messages.value + message }
        var frames = 0
        while (!hasText(message.text) && frames < 20) {
            composeRule.mainClock.advanceTimeByFrame()
            frames++
        }
        assertTrue("The new bubble is on screen", hasText(message.text))
        // spatialDefault (damping 0.9, stiffness 700) covers half its distance ~60ms in.
        composeRule.mainClock.advanceTimeBy(48)
        val midway = bounds(message.text, unmerged = true)
        composeRule.mainClock.advanceTimeBy(2_000)
        val settled = bounds(message.text, unmerged = true)
        composeRule.mainClock.autoAdvance = true
        return midway to settled
    }

    private fun showConversation(messages: androidx.compose.runtime.MutableState<List<MatrixMessage>>) {
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = "Maya",
                    isDirect = true,
                    draft = "",
                    onDraft = {},
                    messages = messages.value,
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
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun ownBubbleRisesFromTheComposer() {
        val messages = mutableStateOf(motionChat(6))
        showConversation(messages)
        val sent = MatrixMessage("\$sent", "@me:example.org", "On my way now", own = true, timestampMillis = System.currentTimeMillis(), reactions = emptyList())
        val (midway, settled) = entryBounds(messages, sent)
        val rise = with(density) { BUBBLE_RISE.toPx() }
        val remaining = (midway.top - settled.top) / rise
        assertTrue("Own bubble part-way up from the composer: ${midway.top} vs ${settled.top} (${remaining * 100}% left)", remaining in 0.1f..0.9f)
    }

    @Test
    fun incomingBubbleFadesAndGrowsIn() {
        val messages = mutableStateOf(motionChat(6))
        showConversation(messages)
        val received = MatrixMessage(
            "\$received", "@maya:example.org", "Running five minutes late", own = false,
            timestampMillis = System.currentTimeMillis(), reactions = emptyList(), senderName = "Maya",
        )
        val (midway, settled) = entryBounds(messages, received)
        val scale = midway.width / settled.width
        assertTrue("Incoming bubble part-way grown: scale $scale", scale > INCOMING_START_SCALE + 0.02f && scale < 0.99f)
    }
}
