package dev.send.matrix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class AuthenticationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun authenticationActionsRequireHomeserverAndPasswordFields() {
        composeRule.onNodeWithText("Use username and password").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Username").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Homeserver").performTextInput("https://matrix.example.org")
        composeRule.onNodeWithText("Scan QR code").assertIsEnabled()
        composeRule.onNodeWithText("Continue with SSO").assertIsEnabled()
        composeRule.onNodeWithText("Username").performTextInput("@alice:example.org")
        composeRule.onNodeWithText("Password").performTextInput("not-a-real-password")
        composeRule.onNodeWithText("Sign in with password").assertIsEnabled()
    }

    @Test
    fun rejectsPlainHttpHomeserverBeforeLogin() {
        composeRule.onNodeWithText("Use username and password").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Username").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Homeserver").performTextInput("http://matrix.example.org")
        composeRule.onNodeWithText("Username").performTextInput("@alice:example.org")
        composeRule.onNodeWithText("Password").performTextInput("not-a-real-password")
        composeRule.onNodeWithText("Sign in with password").performClick()
        composeRule.onNodeWithText("Enter a valid HTTPS homeserver URL.").assertIsDisplayed()
    }
}
