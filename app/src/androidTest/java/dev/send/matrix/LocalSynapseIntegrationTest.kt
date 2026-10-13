package dev.send.matrix

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZLocalSynapseIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun signsInCreatesEncryptedRoomSendsMessageAndCapturesWalkthrough() {
        composeRule.onNodeWithText("Use username and password").performScrollTo().performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(3)
        composeRule.onAllNodes(hasSetTextAction()).get(0).performTextInput("http://10.0.2.2:8008")
        composeRule.onAllNodes(hasSetTextAction()).get(1).performScrollTo().performTextInput("@alice:send.test")
        composeRule.onAllNodes(hasSetTextAction()).get(2).performScrollTo().performTextInput("local-dev-password")
        composeRule.onNodeWithText("Sign in with password").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText("Your encrypted conversations").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Your encrypted conversations").assertIsDisplayed()


        composeRule.onNodeWithText("New chat").performClick()
        composeRule.onNodeWithText("Matrix user ID").performTextInput("@bob:send.test")
        captureWalkthroughScreenshot("send-demo-create-chat.png")
        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Bob").fetchSemanticsNodes().isNotEmpty()
        }
        captureWalkthroughScreenshot("send-demo-conversations.png")
        composeRule.onNodeWithText("Bob").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Message").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Message").performTextInput("Hello Bob — this is an encrypted demo message.")
        composeRule.onNodeWithText("Send").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Hello Bob — this is an encrypted demo message.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Hello Bob — this is an encrypted demo message.").assertIsDisplayed()
        captureWalkthroughScreenshot("send-demo-conversation.png")
    }

    private fun captureWalkthroughScreenshot(fileName: String) {
        composeRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Send")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create screenshot output.")
        try {
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            val output = resolver.openOutputStream(uri) ?: error("Could not open screenshot output.")
            output.use { stream ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
            }
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1)
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}
