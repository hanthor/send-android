package dev.send.matrix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.BackupState
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.VerificationState

/** Fixture coverage for the account/security surfaces; the live SDK paths run in ZZAccountSecurityIntegrationTest. */
@RunWith(AndroidJUnit4::class)
class SecurityUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accountSheetShowsMatrixIdAndActions() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            SendTheme {
                AccountSheet(
                    userId = "@alice:example.org",
                    securitySummary = securitySummary(VerificationState.VERIFIED, RecoveryState.DISABLED),
                    onSecurity = { opened += "security" },
                    onAbout = { opened += "about" },
                    onSignOut = { opened += "sign-out" },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("@alice:example.org").assertIsDisplayed()
        composeRule.onNodeWithText("Device verified · recovery off").assertIsDisplayed()
        composeRule.auditShot("account")
        composeRule.onNodeWithText("Security").performClick()
        composeRule.onNodeWithText("About & licenses").performClick()
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.runOnIdle { assertEquals(listOf("security", "about", "sign-out"), opened) }
    }

    @Test
    fun signOutWarnsWhenRecoveryIsNotSetUp() {
        var setUp = false
        var signedOut = false
        composeRule.setContent {
            SendTheme {
                SignOutDialog(
                    recoveryEnabled = false,
                    busy = false,
                    error = "",
                    onSetUpRecovery = { setUp = true },
                    onSignOut = { signedOut = true },
                    onSignOutLocally = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Sign out without recovery?").assertIsDisplayed()
        composeRule.onNodeWithText("lose access to encrypted messages", substring = true).assertIsDisplayed()
        composeRule.auditShot("signout")
        composeRule.onNodeWithText("Set up recovery").performClick()
        composeRule.onNodeWithText("Sign out anyway").performClick()
        composeRule.runOnIdle { assertTrue(setUp && signedOut) }
    }

    @Test
    fun recoverySetupShowsTheKeyOnceAfterConfirmation() {
        composeRule.setContent {
            SendTheme {
                SecurityScreen(
                    account = AccountInfo("@alice:example.org", "ALICEDEVICE"),
                    verification = VerificationState.VERIFIED,
                    recovery = RecoveryState.DISABLED,
                    backup = BackupState.UNKNOWN,
                    hasOtherDevices = false,
                    onBack = {},
                    onVerifyWithDevice = {},
                    enableRecovery = { onProgress ->
                        onProgress("Backing up keys (1 of 2)…")
                        "EsTc 1234 abcd EFGH"
                    },
                    recover = {},
                )
            }
        }

        composeRule.onNodeWithText("Verified").assertIsDisplayed()
        composeRule.onNodeWithText("Session ALICEDEVICE").assertIsDisplayed()
        composeRule.onNodeWithText("Set up recovery").performScrollTo().performClick()
        composeRule.onNodeWithText("Save your recovery key").assertIsDisplayed()
        composeRule.onNodeWithTag(RECOVERY_KEY_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("EsTc 1234 abcd EFGH").assertIsDisplayed()
        composeRule.auditShot("recovery-key")
        composeRule.onNodeWithText("Copy").performScrollTo().performClick()
        composeRule.onNodeWithText("Copied").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsNotEnabled()
        composeRule.onNodeWithText("I've saved my recovery key").performScrollTo().performClick()
        composeRule.onNodeWithText("Done").assertIsEnabled().performClick()
        composeRule.onAllNodesWithText("EsTc 1234 abcd EFGH").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        composeRule.onNodeWithText("Security").assertIsDisplayed()
    }

    @Test
    fun unverifiedDeviceOffersOtherDeviceAndRecoveryKey() {
        var requested = false
        var submittedKey: String? = null
        composeRule.setContent {
            SendTheme {
                SecurityScreen(
                    account = AccountInfo("@alice:example.org", "NEWDEVICE"),
                    verification = VerificationState.UNVERIFIED,
                    recovery = RecoveryState.INCOMPLETE,
                    backup = BackupState.UNKNOWN,
                    hasOtherDevices = true,
                    onBack = {},
                    onVerifyWithDevice = { requested = true },
                    enableRecovery = { error("Recovery already exists in this fixture.") },
                    recover = { key -> submittedKey = key },
                )
            }
        }

        composeRule.onNodeWithText("Not verified").assertIsDisplayed()
        composeRule.onNodeWithText("Needs your recovery key").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Enter recovery key").performScrollTo().assertIsDisplayed()
        composeRule.auditShot("security")
        composeRule.onNodeWithText("Verify this device").performScrollTo().performClick()
        composeRule.onNodeWithText("Verify with recovery key").performScrollTo().performClick()
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        composeRule.onNodeWithTag(RECOVERY_KEY_INPUT_TAG).performTextInput("EsTc 1234 abcd EFGH")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitUntil(5_000) { submittedKey != null }
        composeRule.onNodeWithText("Recovery key accepted", substring = true).assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(requested)
            assertEquals("EsTc 1234 abcd EFGH", submittedKey)
        }
    }

    @Test
    fun verificationScreenShowsSevenEmojiWithNamesAndReportsTheAnswer() {
        val emojis = listOf(
            VerificationEmoji("🐶", "Dog"),
            VerificationEmoji("🔑", "Key"),
            VerificationEmoji("🚀", "Rocket"),
            VerificationEmoji("🎸", "Guitar"),
            VerificationEmoji("🌵", "Cactus"),
            VerificationEmoji("📕", "Book"),
            VerificationEmoji("🏁", "Flag"),
        )
        val answers = mutableListOf<Boolean>()
        composeRule.setContent {
            SendTheme {
                VerificationScreen(
                    flow = VerificationFlow.Emojis(emojis),
                    onMatch = { answers += true },
                    onMismatch = { answers += false },
                    onCancel = {},
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithText("Compare emoji").assertIsDisplayed()
        composeRule.auditShot("verification")
        emojis.forEach { emoji ->
            composeRule.onNodeWithText(emoji.symbol).assertIsDisplayed()
            composeRule.onNodeWithText(emoji.description).assertIsDisplayed()
        }
        composeRule.onNodeWithText("They don't match").performClick()
        composeRule.onNodeWithText("They match").performClick()
        composeRule.runOnIdle { assertEquals(listOf(false, true), answers) }
    }

    @Test
    fun incomingVerificationRequestAsksBeforeAccepting() {
        var accepted = false
        composeRule.setContent {
            SendTheme {
                VerificationRequestDialog(
                    request = VerificationFlow.Incoming("@alice:example.org", "PHONEDEVICE", "Send Android"),
                    onAccept = { accepted = true },
                    onDecline = {},
                )
            }
        }

        composeRule.onNodeWithText("Verification request").assertIsDisplayed()
        composeRule.onNodeWithText("Send Android (PHONEDEVICE)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Decline").assertIsDisplayed()
        composeRule.auditShot("verification-request")
        composeRule.onNodeWithText("Accept").performClick()
        composeRule.runOnIdle { assertTrue(accepted) }
    }

    @Test
    fun undecryptableMessagesExplainAndOfferTheFixOnlyWhenItHelps() {
        var fixRequested = false
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = "Alice",
                    isDirect = true,
                    draft = "",
                    onDraft = {},
                    messages = listOf(
                        MatrixMessage(
                            id = "\$joined-later",
                            sender = "@alice:example.org",
                            text = "",
                            own = false,
                            timestampMillis = 1_746_000_000_000L,
                            reactions = emptyList(),
                            decryptionFailure = DecryptionFailure.SentBeforeJoined,
                        ),
                        MatrixMessage(
                            id = "\$history",
                            sender = "@alice:example.org",
                            text = "",
                            own = false,
                            timestampMillis = 1_746_000_060_000L,
                            reactions = emptyList(),
                            decryptionFailure = DecryptionFailure.BackupDisabled,
                        ),
                    ),
                    onBack = {},
                    onSend = {},
                    onOlder = {},
                    onReact = { _, _ -> },
                    onAudioCall = {},
                    onSendAttachment = {},
                    onSendVoice = {},
                    loadMedia = { _, _ -> error("No media in this fixture.") },
                    uploading = false,
                    error = "",
                    decryptionActionLabel = "Enter recovery key",
                    onDecryptionAction = { fixRequested = true },
                )
            }
        }

        assertEquals(2, composeRule.onAllNodesWithText("Waiting for this message…").fetchSemanticsNodes().size)
        composeRule.onNodeWithText(DecryptionFailure.SentBeforeJoined.explanation).assertIsDisplayed()
        composeRule.onNodeWithText(DecryptionFailure.BackupDisabled.explanation).assertIsDisplayed()
        composeRule.auditShot("utd")
        // Only the history message can be fixed here, so exactly one action is offered.
        composeRule.onNodeWithTag(DECRYPTION_ACTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Enter recovery key").performClick()
        composeRule.runOnIdle { assertTrue(fixRequested) }
    }

    @Test
    fun homeBannerLinksAnUnverifiedSessionToSecurity() {
        var verify = false
        composeRule.setContent {
            SendTheme {
                HomeScreen(
                    rooms = emptyList(),
                    onOpen = {},
                    error = "",
                    notice = "",
                    creatingDirectRoom = false,
                    onCreateDirectRoom = {},
                    onCreateGroup = { _, _ -> },
                    onAccount = {},
                    onVerifyDevice = { verify = true },
                )
            }
        }

        composeRule.onNodeWithText("Verify this device").assertIsDisplayed()
        composeRule.auditShot("verify-banner")
        composeRule.onNodeWithText("Verify").performClick()
        composeRule.runOnIdle { assertTrue(verify) }
        composeRule.onNodeWithText("Not now").performClick()
        composeRule.onAllNodesWithText("Verify this device").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }
}
