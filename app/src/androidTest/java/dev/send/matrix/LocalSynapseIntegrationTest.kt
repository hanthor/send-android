package dev.send.matrix

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.TextMessageContent
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
    // Granted before the activity starts, so the post-sign-in notification explainer stays out of the way.
    @get:Rule(order = 0)
    val notificationPermission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var receiverClient: Client? = null
    private var receiverSync: SyncService? = null
    private var receiverTimeline: Timeline? = null
    private var receiverTimelineHandle: TaskHandle? = null
    @Volatile private var receiverGotMessage = false
    @Volatile private var receiverGotReaction = false
    @Volatile private var receiverGotPhoto = false
    @Volatile private var receiverGotVoice = false

    @After
    fun closeReceiver() {
        receiverTimelineHandle?.close()
        receiverSync?.close()
        receiverClient?.close()
    }

    @Test
    fun signsInCreatesRoomAndVerifiesEncryptedDeliveryAndReactionOnBob() {
        composeRule.onNodeWithText("Get started").performScrollTo().performClick()
        composeRule.onNodeWithText("Use username and password").performScrollTo().performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(3)
        composeRule.onAllNodes(hasSetTextAction()).get(0).performTextClearance()
        composeRule.onAllNodes(hasSetTextAction()).get(0).performTextInput("http://10.0.2.2:8008")
        composeRule.onAllNodes(hasSetTextAction()).get(1).performScrollTo().performTextInput("@alice:send.test")
        composeRule.onAllNodes(hasSetTextAction()).get(2).performScrollTo().performTextInput("local-dev-password")
        composeRule.onNodeWithText("Sign in with password").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
        }
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
        composeRule.onNodeWithText("Start chat", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Type a Matrix ID").performTextInput("@bob:send.test")
        captureWalkthroughScreenshot("send-demo-create-chat.png")
        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            repository.joinedRooms.value.any { it.direct && it.encrypted }
        }
        val demoRoom = repository.joinedRooms.value.first { it.direct && it.encrypted }
        // The list shows a local placeholder name ("bob") until sync publishes the room's display name.
        fun demoRoomName() = repository.joinedRooms.value.firstOrNull { it.id == demoRoom.id }?.name ?: demoRoom.name
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(demoRoomName()).fetchSemanticsNodes().isNotEmpty()
        }
        captureWalkthroughScreenshot("send-demo-conversations.png")
        joinReceiverRoom(demoRoom.id, message)
        // Retried: the row can be renamed between reading the name and the click.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching { composeRule.onNodeWithText(demoRoomName()).performClick() }.isSuccess
        }
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
        // Local echoes render immediately; reactions unlock once the homeserver acknowledged the event.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            repository.messages.value.any { it.text == message && it.status == DeliveryStatus.Sent }
        }
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

        // Attachments go through the same repository paths the composer's picker / recorder use:
        // a real PNG from MediaStore (content:// Uri) and an Ogg voice clip.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photoUri = insertTestPhoto()
        try {
            runBlocking(Dispatchers.IO) {
                repository.openEncryptedRoom(demoRoom.id).use { room ->
                    repository.timeline(room).use { timeline ->
                        repository.sendAttachment(timeline, room, photoUri)
                        val clip = java.io.File(context.cacheDir, "integration-voice.ogg")
                        // Fixture bytes: the homeserver and SDK treat the clip as opaque encrypted media.
                        clip.writeBytes(ByteArray(4096) { (it % 251).toByte() })
                        repository.sendVoiceMessage(timeline, room, VoiceRecording(clip, 1_500L, List(40) { it / 40f }))
                    }
                }
            }
        } finally {
            context.contentResolver.delete(photoUri, null, null)
        }
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithContentDescription("Photo").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithContentDescription("Play voice message", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 60_000) { receiverGotPhoto && receiverGotVoice }
        // The encrypted image round-trips through the SDK media API back to the original pixels.
        fun sentPhoto() = repository.messages.value.firstOrNull { it.status == DeliveryStatus.Sent && it.attachment?.kind == AttachmentKind.Image }?.attachment
        composeRule.waitUntil(timeoutMillis = 30_000) { sentPhoto() != null }
        val photo = checkNotNull(sentPhoto())
        val downloaded = runBlocking { repository.mediaFile(photo.sourceJson, photo.filename) }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(downloaded.absolutePath, bounds)
        org.junit.Assert.assertEquals(64 to 48, bounds.outWidth to bounds.outHeight)
        captureWalkthroughScreenshot("send-demo-attachments.png")

        // Conversation list: Bob's reply becomes the snippet and raises an unread badge that clears once read.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
        }
        val bobReply = "Bob here — free later?"
        runBlocking(Dispatchers.IO) {
            val timeline = checkNotNull(receiverTimeline)
            timeline.send(checkNotNull(timeline.createMessageContent(MessageType.Text(TextMessageContent(bobReply, null)))))
        }
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText(bobReply).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithContentDescription("unread", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        captureWalkthroughScreenshot("send-demo-unread.png")
        composeRule.onNodeWithText(demoRoomName()).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(bobReply).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Message").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription("unread", substring = true).fetchSemanticsNodes().isEmpty()
        }

        // Create group: encrypted, non-direct private room created from the New chat page.
        val groupName = "Send test group"
        composeRule.onNodeWithText("Start chat", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Create group").performClick()
        composeRule.onNodeWithText("Type a Matrix ID").performTextInput("@bob:send.test")
        composeRule.onNodeWithText("Add @bob:send.test").performClick()
        composeRule.onNodeWithText("Group name (optional)").performTextInput(groupName)
        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitUntil(timeoutMillis = 60_000) {
            repository.joinedRooms.value.any { it.name == groupName && it.encrypted && !it.direct }
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(groupName).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun insertTestPhoto(): android.net.Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "send-integration-photo.png")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Send")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the test photo.")
        val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(66, 133, 244))
        checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        }
        return uri
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
            val room = checkNotNull(receiverClient).joinWhenSynced(roomId)
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
                            when (val type = text.msgType) {
                                is MessageType.Image -> receiverGotPhoto = true
                                is MessageType.Audio -> if (type.content.voice != null) receiverGotVoice = true
                                else -> Unit
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
