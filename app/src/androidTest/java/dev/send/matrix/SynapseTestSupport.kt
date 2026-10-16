package dev.send.matrix

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.Room

/** Disposable Synapse seeded by dev/matrix/seed-demo.sh, reached from the emulator. */
internal const val SYNAPSE_URL = "http://10.0.2.2:8008"
internal const val ALICE = "@alice:send.test"
internal const val BOB = "@bob:send.test"
internal const val DEMO_PASSWORD = "local-dev-password"

internal typealias SendComposeRule<A> = AndroidComposeTestRule<ActivityScenarioRule<A>, A>

/**
 * Joins [roomId] with a test client whose sync is running. The invite has to arrive through that sync first: a sync
 * response computed before the join (typically the slow initial one) but applied after it puts the room back to
 * "invited" for a moment, and a send in that moment fails with `SendQueueError(RoomNotJoined)`. Returns once the
 * client sees itself joined.
 */
internal suspend fun Client.joinWhenSynced(roomId: String): Room {
    withTimeout(60_000) {
        while (getRoom(roomId)?.use { true } != true) delay(100)
    }
    val room = joinRoomById(roomId)
    withTimeout(60_000) {
        while (room.membership() != Membership.JOINED) delay(100)
    }
    return room
}

/** Signs Alice in through the password form unless a session is already showing the conversation list. */
internal fun <A : ComponentActivity> SendComposeRule<A>.signInAsAliceIfNeeded() {
    waitUntil(timeoutMillis = 60_000) {
        onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isEmpty()) signInAsAlice()
}

/** Signs Alice in from the sign-in hero with the password form and waits for the conversation list. */
internal fun <A : ComponentActivity> SendComposeRule<A>.signInAsAlice() {
    onNodeWithText("Get started").performScrollTo().performClick()
    onNodeWithText("Use username and password").performScrollTo().performTouchInput { click() }
    mainClock.advanceTimeBy(600)
    onAllNodes(hasSetTextAction()).assertCountEquals(3)
    onAllNodes(hasSetTextAction()).get(0).performTextClearance()
    onAllNodes(hasSetTextAction()).get(0).performTextInput(SYNAPSE_URL)
    onAllNodes(hasSetTextAction()).get(1).performScrollTo().performTextInput(ALICE)
    onAllNodes(hasSetTextAction()).get(2).performScrollTo().performTextInput(DEMO_PASSWORD)
    onNodeWithText("Sign in with password").performScrollTo().performClick()
    waitUntil(timeoutMillis = 60_000) {
        onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
    }
}
