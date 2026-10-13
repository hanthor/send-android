package dev.send.matrix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZLocalSynapseIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun signsInToLocalSynapseOverDebugOnlyEmulatorHttp() {
        composeRule.onNodeWithText("Use username and password").performClick()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithText("Use another sign-in method").assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(3)
        composeRule.onAllNodes(hasSetTextAction()).get(0).performTextInput("http://10.0.2.2:8008")
        composeRule.onAllNodes(hasSetTextAction()).get(1).performScrollTo().performTextInput("@alice:send.test")
        composeRule.onAllNodes(hasSetTextAction()).get(2).performScrollTo().performTextInput("local-dev-password")
        composeRule.onNodeWithText("Sign in with password").performClick()

        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText("Your encrypted conversations").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Your encrypted conversations").assertIsDisplayed()
    }
}
