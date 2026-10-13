package dev.send.matrix

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZLocalSynapseIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var receiverClient: Client? = null
    private var receiverSync: SyncService? = null
    private var receiverTimeline: Timeline? = null
    private var receiverTimelineHandle: TaskHandle? = null
    @Volatile private var receiverGotMessage = false
    @Volatile private var receiverGotReaction = false

    @After
    fun closeReceiver() {
        receiverTimelineHandle?.close()
        receiverSync?.close()
        receiverClient?.close()
    }

    @Test
    fun signsInCreatesRoomAndVerifiesEncryptedDeliveryAndReactionOnBob() {
        composeRule.onNodeWithText("Get started").performClick()
        composeRule.onNodeWithText("Use username and password").performScrollTo().performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(3)
        composeRule.onAllNodes(hasSetTextAction()).get(0).performTextInput("http://10.0.2.2:8008")
        composeRule.onAllNodes(hasSetTextAction()).get(1).performScrollTo().performTextInput("@alice:send.test")
        composeRule.onAllNodes(hasSetTextAction()).get(2).performScrollTo().performTextInput("local-dev-password")
        composeRule.onNodeWithText("Sign in with password").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText("Search conversations").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Search conversations").assertIsDisplayed()
        val repository = MatrixRepository.get(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        composeRule.waitUntil(timeoutMillis = 60_000) {
            repository.joinedRooms.value.any { !it.encrypted }
        }
        val unencryptedRoom = repository.joinedRooms.value.first { !it.encrypted }
        composeRule.onAllNodesWithText(unencryptedRoom.name).assertCountEquals(0)
        val message = "Hello Bob — this is an encrypted demo message."
        startBobReceiver()
        composeRule.onNodeWithText("New chat", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Matrix user ID").performTextInput("@bob:send.test")
        captureWalkthroughScreenshot("send-demo-create-chat.png")
        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            repository.joinedRooms.value.any { it.direct && it.encrypted }
        }
        val demoRoom = repository.joinedRooms.value.first { it.direct && it.encrypted }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(demoRoom.name).fetchSemanticsNodes().isNotEmpty()
        }
        captureWalkthroughScreenshot("send-demo-conversations.png")
        joinReceiverRoom(demoRoom.id, message)
        composeRule.onNodeWithText(demoRoom.name).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Message").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Message").performTextInput(message)
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 30_000) { receiverGotMessage }
        captureWalkthroughScreenshot("send-demo-conversation.png")
        composeRule.onNodeWithText(message).performTouchInput {
            down(center)
            advanceEventTime(1_000)
            up()
        }
        composeRule.onNodeWithText("👍").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("👍").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 30_000) { receiverGotReaction }
    }


    private fun startBobReceiver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.filesDir.resolve("matrix-integration-bob")
        root.deleteRecursively()
        root.mkdirs()
        val client = ClientBuilder()
            .homeserverUrl("http://10.0.2.2:8008")
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .sqliteStore(SqliteStoreBuilder(root.resolve("store.db").absolutePath, "").key(ByteArray(32)))
            .sessionPaths(root.resolve("cache").absolutePath, root.resolve("crypto").absolutePath)
            .let { builder -> runBlocking(Dispatchers.IO) { builder.build() } }
        var sync: SyncService? = null
        try {
            runBlocking(Dispatchers.IO) {
                client.login("@bob:send.test", "local-dev-password", "Send integration receiver", null)
                sync = client.syncService().finish()
                sync!!.start()
            }
            receiverClient = client
            receiverSync = sync
        } catch (failure: Exception) {
            sync?.close()
            client.close()
            throw failure
        }
    }

    private fun joinReceiverRoom(roomId: String, message: String) {
        runBlocking(Dispatchers.IO) {
            val room = checkNotNull(receiverClient).joinRoomById(roomId)
            val timeline = room.timeline()
            receiverTimeline = timeline
            receiverTimelineHandle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diffs: List<TimelineDiff>) {
                    for (diff in diffs) {
                        for (item in timelineItems(diff)) {
                            val event = item.asEvent() ?: continue
                            val content = event.content as? TimelineItemContent.MsgLike ?: continue
                            val text = (content.content.kind as? MsgLikeKind.Message)?.content ?: continue
                            if (text.body == message) {
                                receiverGotMessage = true
                                if (event.reactions.any { it.key == "👍" }) receiverGotReaction = true
                            }
                        }
                    }
                }
            })
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
