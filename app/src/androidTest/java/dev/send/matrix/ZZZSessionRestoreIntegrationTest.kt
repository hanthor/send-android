package dev.send.matrix

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.Room

/**
 * Cold-start session restore, simulated in-process: the repository releases every SDK object (as process
 * death would), leaving only the store and the sealed session on disk, then the relaunched activity has to
 * reopen the same session through the app's own startup path.
 */
@RunWith(AndroidJUnit4::class)
class ZZZSessionRestoreIntegrationTest {
    @get:Rule(order = 0)
    val notificationPermission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun coldStartRestoresTheSessionAndSignOutClearsIt() {
        composeRule.signInAsAliceIfNeeded()
        val repository = MatrixRepository.get(context)
        composeRule.waitUntil(timeoutMillis = 60_000) { repository.account.value != null }
        val deviceId = checkNotNull(repository.account.value).deviceId

        // An encrypted message from before the restart.
        val message = "Sent before the restart ${System.currentTimeMillis()}"
        val roomId = runBlocking { repository.createEncryptedGroup(GROUP_NAME, listOf(BOB)) }
        var room: Room? = null
        composeRule.waitUntil(timeoutMillis = 60_000) {
            room = runBlocking { runCatching { repository.openEncryptedRoom(roomId) }.getOrNull() }
            room != null
        }
        val sendRoom = checkNotNull(room)
        val sendTimeline = runBlocking { repository.timeline(sendRoom) }
        try {
            runBlocking {
                repository.observeTimeline(sendTimeline)
                repository.sendText(sendTimeline, sendRoom, message)
            }
            composeRule.waitUntil(timeoutMillis = 60_000) {
                repository.messages.value.any { it.text == message && it.status == DeliveryStatus.Sent }
            }
        } finally {
            sendTimeline.close()
            sendRoom.close()
        }

        // Process death: nothing in memory survives.
        runBlocking { repository.close() }
        assertNull(repository.account.value)
        assertTrue(repository.joinedRooms.value.isEmpty())

        // Cold start through MainActivity: same session (not a new login), sync resumes, rooms come back.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty() &&
                repository.account.value != null
        }
        assertEquals(deviceId, repository.account.value?.deviceId)
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText(GROUP_NAME).fetchSemanticsNodes().isNotEmpty()
        }

        // The earlier encrypted message still decrypts, and the restored tokens carry new traffic.
        composeRule.onNodeWithText(GROUP_NAME).performClick()
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(message).assertIsDisplayed()
        val reply = "Sent after the restart ${System.currentTimeMillis()}"
        composeRule.onNodeWithText("Message").performTextInput(reply)
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil(timeoutMillis = 60_000) {
            repository.messages.value.any { it.text == reply && it.status == DeliveryStatus.Sent }
        }

        // Sign-out deletes the saved session with the store: nothing restores, and a relaunch shows the hero.
        runBlocking { repository.logout() }
        assertFalse(runBlocking { repository.restore() })
        assertTrue(context.getSharedPreferences("matrix_session_locator", Context.MODE_PRIVATE).all.isEmpty())
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val GROUP_NAME = "Restart check"
    }
}
