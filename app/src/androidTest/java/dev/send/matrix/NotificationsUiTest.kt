package dev.send.matrix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.send.matrix.notifications.NotificationPermissionDialog
import dev.send.matrix.notifications.NotificationsDialog
import dev.send.matrix.notifications.PushRegistration
import dev.send.matrix.notifications.parsePushPayload
import dev.send.matrix.notifications.PushedEvent
import dev.send.matrix.notifications.matrixGatewayCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.VerificationState

/** Fixture coverage for the notification surfaces; live delivery runs in ZZNotificationsIntegrationTest. */
@RunWith(AndroidJUnit4::class)
class NotificationsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsShowHowNotificationsArrive() {
        var opened = false
        composeRule.setContent {
            SendTheme {
                SettingsScreen(
                    notificationSummary = "Background sync every 15 minutes",
                    onNotifications = { opened = true },
                    sounds = true,
                    onSounds = {},
                    theme = ThemeChoice.System,
                    onTheme = {},
                    linkPreviews = true,
                    onLinkPreviews = {},
                    securitySummary = securitySummary(VerificationState.VERIFIED, RecoveryState.ENABLED),
                    onSecurity = {},
                    onDevices = {},
                    onBlocked = {},
                    onArchived = {},
                    onAbout = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Background sync every 15 minutes").assertIsDisplayed()
        composeRule.onNodeWithText("Notifications").performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun notificationsDialogOffersBackgroundSyncWithoutADistributor() {
        // The CI emulator has no UnifiedPush distributor installed.
        assertTrue(PushRegistration.distributors(InstrumentationRegistry.getInstrumentation().targetContext).isEmpty())
        var dismissed = false
        composeRule.setContent {
            SendTheme { NotificationsDialog(onDismiss = { dismissed = true }) }
        }

        composeRule.onNodeWithText("Deliver new messages with").assertIsDisplayed()
        composeRule.onNodeWithText("Background sync only").assertIsSelected()
        composeRule.onNodeWithText("install a UnifiedPush distributor such as ntfy", substring = true).assertIsDisplayed()
        composeRule.auditShot("notifications-settings")
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun permissionExplanationComesBeforeTheSystemPrompt() {
        var choice = ""
        composeRule.setContent {
            SendTheme { NotificationPermissionDialog(onTurnOn = { choice = "on" }, onNotNow = { choice = "later" }) }
        }

        composeRule.onNodeWithText("Get notified about new messages").assertIsDisplayed()
        composeRule.onNodeWithText("text stays hidden on the lock screen", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Turn on").performClick()
        composeRule.runOnIdle { assertEquals("on", choice) }
    }

    @Test
    fun mutedConversationsShowTheIconAndTheMenuTogglesMute() {
        var toggled = 0
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = "Bob",
                    isDirect = true,
                    draft = "",
                    onDraft = {},
                    messages = emptyList(),
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
                    muted = true,
                    onToggleMute = { toggled++ },
                )
            }
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.auditShot("conversation-menu")
        composeRule.onNodeWithText("Unmute").performClick()
        composeRule.runOnIdle { assertEquals(1, toggled) }
    }

    @Test
    fun conversationListMarksMutedRooms() {
        composeRule.setContent {
            SendTheme {
                HomeScreen(
                    rooms = listOf(MatrixRoom("!quiet:example.org", "Quiet group", encrypted = true, direct = false, lastMessage = "Shh")),
                    onOpen = {},
                    error = "",
                    notice = "",
                    creatingDirectRoom = false,
                    onCreateDirectRoom = {},
                    onCreateGroup = { _, _ -> },
                    onAccount = {},
                    onVerifyDevice = null,
                    mutedRoomIds = setOf("!quiet:example.org"),
                )
            }
        }
        composeRule.onNodeWithContentDescription("Muted").assertIsDisplayed()
        composeRule.auditShot("muted-row")
    }

    @Test
    fun pushPayloadsAndGatewayCandidatesParse() {
        val payload = """{"notification":{"event_id":"${'$'}ev:send.test","room_id":"!room:send.test","counts":{"unread":2},"prio":"high"}}"""
        assertEquals(PushedEvent("!room:send.test", "${'$'}ev:send.test"), parsePushPayload(payload.toByteArray()))
        // Badge-only pushes carry no event.
        assertNull(parsePushPayload("""{"notification":{"counts":{"unread":0}}}""".toByteArray()))
        assertNull(parsePushPayload("not json".toByteArray()))
        assertEquals(
            "https://ntfy.example.org:8443/_matrix/push/v1/notify",
            matrixGatewayCandidate("https://ntfy.example.org:8443/upAbC123?up=1"),
        )
        assertNull(matrixGatewayCandidate("content://not-a-push-server"))
    }
}
