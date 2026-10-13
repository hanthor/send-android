package dev.send.matrix

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import io.element.android.call.ui.ElementCallOverlay
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiComparisonScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun capturesMaterialSignInScreen() {
        composeRule.setContent {
            MaterialTheme {
                SignInScreen(
                    homeserver = "",
                    onHomeserver = {},
                    user = "",
                    onUser = {},
                    password = "",
                    onPassword = {},
                    busy = false,
                    error = "",
                    onCancel = {},
                    ssoPending = false,
                    onCancelSso = {},
                    onQrLogin = {},
                    onSsoLogin = {},
                    onLogin = {},
                )
            }
        }

        saveScreenshot("send-login-screen.png")
        composeRule.onNodeWithText("Scan QR code").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with SSO").assertIsDisplayed()
    }

    @Test
    fun newChatRequiresAValidMatrixUserIdAndCapturesHomeScreen() {
        composeRule.setContent {
            MaterialTheme {
                HomeScreen(
                    rooms = listOf(MatrixRoom("!alice:example.org", "Alice", encrypted = true, direct = true)),
                    onOpen = {},
                    error = "",
                    notice = "",
                    creatingDirectRoom = false,
                    onCreateDirectRoom = {},
                )
            }
        }

        saveScreenshot("send-home-screen.png")
        composeRule.onNodeWithText("New chat").performClick()
        composeRule.onNodeWithText("Create").assertIsNotEnabled()
        composeRule.onNodeWithText("Matrix user ID").performTextInput("@alice:example.org")
        composeRule.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun capturesConversationAndLongPressSendsSelectedReaction() {
        var selectedReaction: Pair<String, String>? = null
        composeRule.setContent {
            MaterialTheme {
                ElementCallOverlay(controller = null) { _ ->
                    ConversationScreen(
                        title = "Alice",
                        isDirect = true,
                        draft = "",
                        onDraft = {},
                        messages = listOf(
                            MatrixMessage(
                                id = "\$event-one",
                                sender = "@alice:example.org",
                                text = "Hey, are you free to talk?",
                                own = false,
                                timestampMillis = 1_746_000_000_000L,
                                reactions = listOf("❤️"),
                            ),
                            MatrixMessage(
                                id = "\$event-two",
                                sender = "@me:example.org",
                                text = "Sure, what's up?",
                                own = true,
                                timestampMillis = 1_746_000_060_000L,
                                reactions = emptyList(),
                            ),
                        ),
                        onBack = {},
                        onSend = {},
                        onOlder = {},
                        onReact = { eventId, emoji -> selectedReaction = eventId to emoji },
                        onAudioCall = {},
                        error = "",
                    )
                }
            }
        }

        saveScreenshot("send-conversation-screen.png")
        composeRule.onNodeWithText("Sent", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Hey, are you free to talk?").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            up()
        }
        composeRule.onNodeWithText("😂").performClick()
        composeRule.runOnIdle {
            assertEquals("\$event-one" to "😂", selectedReaction)
        }
    }

    private fun saveScreenshot(fileName: String) {
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
            val output = resolver.openOutputStream(uri) ?: error("Could not open screenshot output.")
            output.use { stream ->
                check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
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
