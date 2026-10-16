package dev.send.matrix

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyPolicyUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aboutOpensTheBundledPrivacyPolicyAndItsGitHubCopy() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        composeRule.setContent {
            SendTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    LegalNoticesDialog(onDismiss = {})
                }
            }
        }

        composeRule.onNodeWithText("Privacy policy").performClick()
        composeRule.onNodeWithText("Send privacy policy", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("no analytics, no crash reporting", substring = true).assertIsDisplayed()
        composeRule.auditShot("privacy-policy")
        composeRule.onNodeWithText("matrix.gateway.unifiedpush.org", substring = true).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Open on GitHub").performClick()
        composeRule.runOnIdle { assertEquals(listOf(PRIVACY_POLICY_URL), opened) }

        // Close goes back to About & licenses.
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("About & licenses").assertIsDisplayed()
    }

    @Test
    fun policyTextKeepsEveryParagraphOfTheBundledFile() {
        val markdown = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("PRIVACY.md").bufferedReader().use { it.readText() }
        val text = privacyPolicyText(markdown).text
        assertTrue(text.startsWith("Send privacy policy\n\n"))
        assertTrue(text.contains("• Internet: to talk to your homeserver and the servers above."))
        assertTrue(text.contains("open an issue at https://github.com/hanthor/send-android/issues"))
        assertTrue("No Markdown markers may remain", "`" !in text && "**" !in text && "\n#" !in text)
    }
}
