package dev.send.matrix

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.performClick
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
        composeRule.onNodeWithText("Homeserver").performTextInput("https://matrix.example.org")
        composeRule.onNodeWithText("Scan QR code").assertIsEnabled()
        composeRule.onNodeWithText("Continue with SSO").assertIsEnabled()
        composeRule.onNodeWithText("Sign in with password").assertIsNotEnabled()

        composeRule.onNodeWithText("Username").performTextInput("@alice:example.org")
        composeRule.onNodeWithText("Password").performTextInput("not-a-real-password")
        composeRule.onNodeWithText("Sign in with password").assertIsEnabled()
    }

    @Test
    fun rejectsPlainHttpHomeserverBeforeLogin() {
        composeRule.onNodeWithText("Homeserver").performTextInput("http://matrix.example.org")
        composeRule.onNodeWithText("Username").performTextInput("@alice:example.org")
        composeRule.onNodeWithText("Password").performTextInput("not-a-real-password")
        composeRule.onNodeWithText("Sign in with password").performClick()

        composeRule.onNodeWithText("Enter a valid HTTPS homeserver URL.").assertExists()
    }
}
