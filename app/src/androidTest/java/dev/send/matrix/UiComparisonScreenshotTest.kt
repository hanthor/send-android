package dev.send.matrix

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import io.element.android.call.ui.ElementCallOverlay
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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
            SendTheme {
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

        saveScreenshot("send-login-hero-screen.png")
        composeRule.onNodeWithText("Get started").performScrollTo().performClick()
        saveScreenshot("send-login-screen.png")
        composeRule.onNodeWithText("Scan QR code").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with SSO").assertIsDisplayed()
    }

    @Test
    fun newChatRequiresAValidMatrixUserIdAndCapturesHomeScreen() {
        var createdGroup: Pair<String, List<String>>? = null
        composeRule.setContent {
            SendTheme {
                HomeScreen(
                    rooms = listOf(
                        MatrixRoom(
                            "!alice:example.org",
                            "Alice",
                            encrypted = true,
                            direct = true,
                            lastMessage = "See you at 6?",
                            lastTimestampMillis = System.currentTimeMillis(),
                            unreadCount = 3,
                        ),
                    ),
                    onOpen = {},
                    error = "",
                    notice = "",
                    creatingDirectRoom = false,
                    onCreateDirectRoom = {},
                    onCreateGroup = { name, members -> createdGroup = name to members },
                    onAccount = {},
                    onVerifyDevice = null,
                )
            }
        }

        saveScreenshot("send-home-screen.png")
        composeRule.onNodeWithText("See you at 6?").assertIsDisplayed()
        composeRule.onNodeWithText("Now").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("3 unread").assertIsDisplayed()
        composeRule.onNodeWithText("Start chat", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Create").assertIsNotEnabled()
        composeRule.onNodeWithText("Type a Matrix ID").performTextInput("@alice:example.org")
        saveScreenshot("send-new-chat-dialog.png")
        composeRule.onNodeWithText("Create").assertIsEnabled()

        // Create group: the typed ID becomes the first member chip, more can be added, then an optional name.
        composeRule.onNodeWithText("Create group").performClick()
        composeRule.onNodeWithText("New group").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove @alice:example.org").assertIsDisplayed()
        composeRule.onNodeWithText("Type a Matrix ID").performTextInput("@bob:example.org")
        composeRule.onNodeWithText("Add @bob:example.org").performClick()
        composeRule.onNodeWithText("Group name (optional)").performTextInput("Weekend plans")
        composeRule.onNodeWithText("Create").performClick()
        composeRule.runOnIdle {
            assertEquals("Weekend plans" to listOf("@alice:example.org", "@bob:example.org"), createdGroup)
        }
    }

    @Test
    fun capturesConversationAndLongPressSendsSelectedReaction() {
        var selectedReaction: Pair<String, String>? = null
        composeRule.setContent {
            SendTheme {
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
                        onCall = {},
                        onSendAttachment = {},
                        onSendVoice = {},
                        loadMedia = { _, _ -> error("No media in this fixture.") },
                        uploading = false,
                        error = "",
                        decryptionActionLabel = null,
                        onDecryptionAction = {},
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
        saveScreenshot("send-reaction-picker.png")
        composeRule.onNodeWithText("😂").performClick()
        composeRule.runOnIdle {
            assertEquals("\$event-one" to "😂", selectedReaction)
        }
    }

    @Test
    fun rendersAttachmentsAndComposerActions() {
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = "Alice",
                    isDirect = true,
                    draft = "",
                    onDraft = {},
                    messages = listOf(
                        MatrixMessage(
                            id = "\$file",
                            sender = "@alice:example.org",
                            text = "",
                            own = false,
                            timestampMillis = 1_746_000_000_000L,
                            reactions = emptyList(),
                            attachment = MatrixAttachment(
                                kind = AttachmentKind.File,
                                filename = "itinerary.pdf",
                                mimetype = "application/pdf",
                                sizeBytes = 48_000L,
                                durationMillis = null,
                                width = null,
                                height = null,
                                sourceJson = "{}",
                                thumbnailSourceJson = null,
                            ),
                        ),
                        MatrixMessage(
                            id = "\$voice",
                            sender = "@me:example.org",
                            text = "",
                            own = true,
                            timestampMillis = 1_746_000_060_000L,
                            reactions = emptyList(),
                            attachment = MatrixAttachment(
                                kind = AttachmentKind.Voice,
                                filename = "voice.ogg",
                                mimetype = "audio/ogg",
                                sizeBytes = 9_000L,
                                durationMillis = 7_000L,
                                width = null,
                                height = null,
                                sourceJson = "{}",
                                thumbnailSourceJson = null,
                                waveform = List(40) { (it % 8) / 8f },
                            ),
                        ),
                    ),
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
                )
            }
        }

        composeRule.onNodeWithText("itinerary.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("0:07").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Play voice message", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Attach file").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Gallery").assertIsDisplayed()
        // Empty draft: the round action is the voice recorder; a plain tap explains the hold gesture.
        composeRule.onNodeWithContentDescription("Voice message").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Hold to record, release to send").assertIsDisplayed()
    }

    private fun saveScreenshot(fileName: String) {
        composeRule.waitForIdle()
        writeDownload(fileName, "image/png") { stream ->
            check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        }
        // Full-device capture + accessibility hierarchy for the Google Messages comparison
        // (same coordinate space as the reference uiautomator dumps).
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val stem = fileName.removeSuffix(".png")
        val deviceShot = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$stem-device.png")
        check(device.takeScreenshot(deviceShot))
        writeDownload("$stem-device.png", "image/png") { stream -> deviceShot.inputStream().use { it.copyTo(stream) } }
        deviceShot.delete()
        writeDownload("$stem-device.xml", "text/xml") { stream -> device.dumpWindowHierarchy(stream) }
    }

    private fun writeDownload(fileName: String, mimeType: String, write: (java.io.OutputStream) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Send")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create $fileName output.")
        try {
            val output = resolver.openOutputStream(uri) ?: error("Could not open $fileName output.")
            output.use(write)
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1)
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}
