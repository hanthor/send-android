package dev.send.matrix

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.VerificationState

/**
 * Phase 1 E2EE acceptance against the local Synapse (runs after ZLocalSynapseIntegrationTest, whose
 * Alice session it continues): recovery set up in the UI, SAS emoji verification of a second Alice
 * SDK client accepted in the UI, history decrypted from key backup on that second client, sign-out,
 * and a fresh sign-in that shows the unverified-device banner and decrypts history with the recovery key.
 */
@RunWith(AndroidJUnit4::class)
class ZZAccountSecurityIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var secondClient: Client? = null
    private var secondSync: SyncService? = null
    private var secondController: SessionVerificationController? = null
    private var secondRoom: Room? = null
    private var secondTimeline: Timeline? = null
    private var secondTimelineHandle: TaskHandle? = null
    @Volatile private var secondAccepted = false
    @Volatile private var secondEmojis: List<String>? = null
    @Volatile private var secondFinished = false
    @Volatile private var secondCancelled = false
    @Volatile private var secondDecrypted = false

    @After
    fun closeSecondDevice() {
        secondTimelineHandle?.close()
        secondTimeline?.close()
        secondRoom?.close()
        secondController?.setDelegate(null)
        secondController?.close()
        secondSync?.close()
        secondClient?.close()
    }

    @Test
    fun recoveryVerificationBackupRestoreAndSignOut() {
        val repository = MatrixRepository.get(context)
        signInIfNeeded()

        // 1. Alice's first device sets up recovery from the account menu and is shown the key once.
        composeRule.waitUntil(timeoutMillis = 120_000) {
            repository.verificationState.value == VerificationState.VERIFIED &&
                repository.recoveryState.value == RecoveryState.DISABLED
        }
        composeRule.onNodeWithContentDescription("Account").performClick()
        composeRule.onNodeWithText(ALICE).assertIsDisplayed()
        composeRule.onNodeWithText("About & licenses").assertIsDisplayed()
        composeRule.onNodeWithText("Security").performClick()
        composeRule.onNodeWithText("Set up recovery").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 120_000) {
            composeRule.onAllNodesWithTag(RECOVERY_KEY_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val recoveryKey = composeRule.onNodeWithTag(RECOVERY_KEY_TAG, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("Recovery key should be shown", recoveryKey.isNotBlank())
        composeRule.onNodeWithText("Done").assertIsNotEnabled()
        composeRule.onNodeWithText("I've saved my recovery key").performScrollTo().performClick()
        composeRule.onNodeWithText("Done").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 60_000) { repository.recoveryState.value == RecoveryState.ENABLED }

        // 2. An encrypted message whose room key reaches the backup.
        val message = "Backed up for the second device ${System.currentTimeMillis()}"
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
            runBlocking { repository.waitForKeyBackup() }
        } finally {
            sendTimeline.close()
            sendRoom.close()
        }

        // 3. A second Alice device (independent SDK client) asks to be verified; the app accepts and compares emoji.
        val second = startSecondDevice()
        val controller = secondVerificationController(second)
        runBlocking(Dispatchers.IO) { controller.requestDeviceVerification() }
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText("Verification request").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Accept").performClick()
        composeRule.waitUntil(timeoutMillis = 60_000) { checkSecondNotCancelled(); secondAccepted }
        runBlocking(Dispatchers.IO) { controller.startSasVerification() }
        composeRule.waitUntil(timeoutMillis = 60_000) {
            checkSecondNotCancelled()
            secondEmojis != null && repository.verificationFlow.value is VerificationFlow.Emojis
        }
        val appEmojis = (repository.verificationFlow.value as VerificationFlow.Emojis).emojis.map { it.description }
        assertEquals(7, appEmojis.size)
        assertEquals(secondEmojis, appEmojis)
        appEmojis.forEach { name ->
            assertTrue(composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty())
        }
        composeRule.onNodeWithText("They match").performClick()
        runBlocking(Dispatchers.IO) { controller.approveVerification() }
        composeRule.waitUntil(timeoutMillis = 60_000) {
            checkSecondNotCancelled()
            secondFinished && repository.verificationFlow.value == VerificationFlow.Verified
        }
        composeRule.onNodeWithText("Device verified").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()

        // 4. The second device recovers with the key and decrypts the earlier message from key backup.
        second.encryption().use { encryption ->
            runBlocking(Dispatchers.IO) { encryption.recover(recoveryKey) }
            composeRule.waitUntil(timeoutMillis = 60_000) {
                encryption.recoveryState() == RecoveryState.ENABLED && encryption.verificationState() == VerificationState.VERIFIED
            }
        }
        observeSecondDeviceRoom(second, roomId, message)
        composeRule.waitUntil(timeoutMillis = 120_000) { secondDecrypted }

        // 5. Sign out from the account menu: back to the sign-in hero with the session and stores gone.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            repository.verificationFlow.value == VerificationFlow.Idle &&
                composeRule.onAllNodesWithText("Verify device").fetchSemanticsNodes().isEmpty()
        }
        closeSecurity()
        composeRule.onNodeWithContentDescription("Account").performClick()
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.onNodeWithText("Sign out?").assertIsDisplayed()
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.waitUntil(timeoutMillis = 90_000) {
            composeRule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get started").assertIsDisplayed()
        assertFalse(runBlocking { repository.restore() })
        assertFalse(context.filesDir.resolve("matrix").exists())
        assertEquals(null, repository.account.value)

        // 6. Signing in again is a new, unverified device: banner on the list, waiting bubble in the
        //    conversation, recovery key from the bubble's action, then the message decrypts in place.
        signInWithPassword()
        composeRule.waitUntil(timeoutMillis = 120_000) {
            repository.verificationState.value == VerificationState.UNVERIFIED &&
                repository.recoveryState.value == RecoveryState.INCOMPLETE &&
                repository.hasDevicesToVerifyAgainst.value
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Verify this device").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText(GROUP_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(GROUP_NAME).performClick()
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText("Waiting for this message…").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag(DECRYPTION_ACTION_TAG).onFirst().performClick()
        composeRule.onNodeWithText("Verify with recovery key").performScrollTo().performClick()
        composeRule.onNodeWithTag(RECOVERY_KEY_INPUT_TAG).performTextInput(recoveryKey)
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitUntil(timeoutMillis = 120_000) {
            repository.verificationState.value == VerificationState.VERIFIED &&
                repository.recoveryState.value == RecoveryState.ENABLED
        }
        closeSecurity()
        composeRule.waitUntil(timeoutMillis = 120_000) {
            composeRule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    private fun checkSecondNotCancelled() {
        check(!secondCancelled) { "The second device's verification was cancelled or failed." }
    }

    /** Taps the Security page's own Back button (the last "Back" node: the overlay sits above any open conversation). */
    private fun closeSecurity() {
        composeRule.onAllNodesWithContentDescription("Back").onLast().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Security").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun signInIfNeeded() {
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isEmpty()) signInWithPassword()
    }

    private fun signInWithPassword() {
        composeRule.onNodeWithText("Get started").performScrollTo().performClick()
        composeRule.onNodeWithText("Use username and password").performScrollTo().performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(3)
        composeRule.onAllNodes(hasSetTextAction()).get(0).performTextClearance()
        composeRule.onAllNodes(hasSetTextAction()).get(0).performTextInput(HOMESERVER)
        composeRule.onAllNodes(hasSetTextAction()).get(1).performScrollTo().performTextInput(ALICE)
        composeRule.onAllNodes(hasSetTextAction()).get(2).performScrollTo().performTextInput(PASSWORD)
        composeRule.onNodeWithText("Sign in with password").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun startSecondDevice(): Client {
        val root = context.filesDir.resolve("matrix-integration-alice-second")
        root.deleteRecursively()
        root.mkdirs()
        val client = runBlocking(Dispatchers.IO) {
            ClientBuilder()
                .homeserverUrl(HOMESERVER)
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
                .sqliteStore(SqliteStoreBuilder(root.resolve("store.db").absolutePath, "").key(ByteArray(32)))
                .sessionPaths(root.resolve("cache").absolutePath, root.resolve("crypto").absolutePath)
                .build()
        }
        secondClient = client
        runBlocking(Dispatchers.IO) {
            client.login(ALICE, PASSWORD, "Send second Alice device", null)
            val sync = client.syncService().finish()
            secondSync = sync
            sync.start()
            client.encryption().use { it.waitForE2eeInitializationTasks() }
        }
        return client
    }

    /** The controller needs Alice's cross-signing identity, which arrives with the second device's first key query. */
    private fun secondVerificationController(client: Client): SessionVerificationController {
        var controller: SessionVerificationController? = null
        composeRule.waitUntil(timeoutMillis = 60_000) {
            controller = runBlocking(Dispatchers.IO) { runCatching { client.getSessionVerificationController() }.getOrNull() }
            if (controller == null) Thread.sleep(500)
            controller != null
        }
        val ready = checkNotNull(controller)
        ready.setDelegate(object : SessionVerificationControllerDelegate {
            override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) = Unit
            override fun didAcceptVerificationRequest() { secondAccepted = true }
            override fun didStartSasVerification() = Unit
            override fun didReceiveVerificationData(data: SessionVerificationData) {
                if (data is SessionVerificationData.Emojis) secondEmojis = data.emojis.map { it.description() }
            }
            override fun didFail() { secondCancelled = true }
            override fun didCancel() { secondCancelled = true }
            override fun didFinish() { secondFinished = true }
        })
        secondController = ready
        return ready
    }

    private fun observeSecondDeviceRoom(client: Client, roomId: String, message: String) {
        composeRule.waitUntil(timeoutMillis = 60_000) {
            secondRoom = client.getRoom(roomId)
            if (secondRoom == null) Thread.sleep(500)
            secondRoom != null
        }
        runBlocking(Dispatchers.IO) {
            val timeline = checkNotNull(secondRoom).timeline()
            secondTimeline = timeline
            secondTimelineHandle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diffs: List<TimelineDiff>) {
                    for (diff in diffs) {
                        for (item in timelineItems(diff)) {
                            val content = item.asEvent()?.content as? TimelineItemContent.MsgLike ?: continue
                            val text = (content.content.kind as? MsgLikeKind.Message)?.content ?: continue
                            if (text.body == message) secondDecrypted = true
                        }
                    }
                }
            })
            timeline.paginateBackwards(20u.toUShort())
        }
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
        const val HOMESERVER = "http://10.0.2.2:8008"
        const val ALICE = "@alice:send.test"
        const val BOB = "@bob:send.test"
        const val PASSWORD = "local-dev-password"
        const val GROUP_NAME = "Backup check"
    }
}
