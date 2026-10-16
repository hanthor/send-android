package dev.send.matrix

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.send.matrix.notifications.NotificationRouter
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener

/**
 * Phase 8 share target against the local Synapse: another app's ACTION_SEND (text + a MediaStore photo, with the
 * read grant) goes to Send's exported share entry, which hands it to MainActivity; Alice picks the encrypted room,
 * confirms, and Bob — an independent SDK client — receives the text and the image. The conversation opens, the
 * staged copies are gone, and the room's pushed shortcut carries the direct-share category.
 */
@RunWith(AndroidJUnit4::class)
class ZZShareTargetIntegrationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = MatrixRepository.get(context)

    /** One message as Bob's client sees it. */
    private data class BobEvent(val roomId: String, val body: String?, val image: Boolean)

    private var bobClient: Client? = null
    private var bobSync: SyncService? = null
    private val bobRooms = ConcurrentHashMap<String, Room>()
    private val bobTimelines = ConcurrentHashMap<String, Timeline>()
    private val bobHandles = mutableListOf<TaskHandle>()
    private val bobEvents = ConcurrentHashMap<String, BobEvent>()
    private var photo: Uri? = null

    @After
    fun cleanUp() {
        bobHandles.forEach { it.close() }
        bobTimelines.values.forEach { it.close() }
        bobRooms.values.forEach { it.close() }
        bobSync?.close()
        bobClient?.close()
        photo?.let { context.contentResolver.delete(it, null, null) }
        // The share started Send's own task outside the test rule: close it so later tests see only their activity.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED)) {
                monitor.getActivitiesInStage(stage).forEach { it.finish() }
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun sharedTextAndImageReachBob() {
        val stamp = System.currentTimeMillis()
        val text = "Shared from another app $stamp"
        val imageName = "share-$stamp.png"
        val uri = insertPhoto(imageName).also { photo = it }

        // What a gallery's share sheet sends: explicit-to-Send here, since the system chooser can't be driven in CI.
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setClass(context, ShareActivity::class.java)
                .setType("image/png")
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri(null, uri) },
        )
        // Signed out, the share waits behind sign-in and continues afterwards.
        composeRule.signInAsAliceIfNeeded()
        waitFor("Alice's session", 60_000, { "account=${repository.account.value}" }) { repository.account.value != null }
        waitForUi("the share picker", 60_000, ::screenState) { hasNodeWithText("Share to") }

        val roomName = "$SHARE_ROOM $stamp"
        val roomId = runBlocking { repository.createEncryptedGroup(roomName, listOf(BOB)) }
        startBob(roomId)
        waitFor("the room in Alice's list", 60_000, ::roomsState) {
            repository.joinedRooms.value.any { it.id == roomId && it.encrypted }
        }
        // The conversation list underneath has the same row: pick it in the share picker.
        val pickerRow = hasText(roomName) and hasAnyAncestor(hasTestTag("share-picker"))
        waitForUi("the room in the share picker", 60_000, ::screenState) { composeRule.onAllNodes(pickerRow).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(pickerRow).performClick()

        // Nothing goes out before the confirmation, which shows the text and the photo's name.
        waitForUi("the confirmation", 10_000, ::screenState) { hasNodeWithText("Send to $roomName?") }
        composeRule.onNodeWithTag("share-text", useUnmergedTree = true).assertTextEquals(text)
        composeRule.onNodeWithText(imageName).assertExists()
        assertTrue("Sent before confirming: ${bobState()}", bobEvents.values.none { it.roomId == roomId })
        composeRule.auditShot("live-share-confirm")
        composeRule.onNode(hasText("Send") and hasClickAction()).performClick()

        waitFor("Bob to receive the shared text", 90_000, ::bobState) { bobEvents.values.any { it.roomId == roomId && it.body == text } }
        waitFor("Bob to receive the shared image", 90_000, ::bobState) {
            bobEvents.values.any { it.roomId == roomId && it.image && it.body == imageName }
        }
        waitForUi("the conversation open", 60_000, ::screenState) { NotificationRouter.visibleRoomId == roomId }
        waitFor("the staged copies to be deleted", 30_000, { "share=${context.cacheDir.resolve("share").list()?.toList()}" }) {
            context.cacheDir.resolve("share").list().isNullOrEmpty()
        }

        // The room's conversation shortcut is a direct-share target for the next share.
        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).firstOrNull { it.id == roomId }
        checkNotNull(shortcut) { "No shortcut for $roomId: ${ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }}" }
        val categories = shortcut.categories.orEmpty()
        assertTrue("categories=$categories", ShareTarget.CATEGORY_SHARE_TARGET in categories)
        assertTrue("categories=$categories", ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION in categories)
        composeRule.auditShot("live-share-sent")
    }

    private fun hasNodeWithText(text: String): Boolean = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun bobState(): String = "bob=${bobEvents.values.map { "${it.roomId}:${it.body}:image=${it.image}" }}"

    private fun roomsState(): String = "rooms=${repository.joinedRooms.value.map { "${it.id}:${it.name}:${it.encrypted}" }}"

    private fun screenState(): String =
        "account=${repository.account.value?.userId} visible=${NotificationRouter.visibleRoomId} ${roomsState()} messages=${repository.messages.value.map { "${it.text}:${it.status}" }}"

    /** Compose's waitUntil (keeps the app's UI composing), reporting app state on timeout. */
    private fun waitForUi(what: String, timeoutMillis: Long, state: () -> String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $what; ${state()}", timeout)
        }
    }

    private fun waitFor(what: String, timeoutMillis: Long, state: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what; ${state()}")
            Thread.sleep(250)
        }
    }

    /** A real photo in MediaStore, as a gallery app would share it. */
    private fun insertPhoto(name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SendTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawCircle(160f, 120f, 100f, Paint().apply { color = 0xFF5BB974.toInt() })
        val bytes = ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        check(resolver.update(uri, values, null, null) == 1)
        return uri
    }

    private fun startBob(roomId: String) {
        val root = context.filesDir.resolve("matrix-share-bob")
        root.deleteRecursively()
        root.mkdirs()
        val client = runBlocking(Dispatchers.IO) {
            ClientBuilder()
                .homeserverUrl(SYNAPSE_URL)
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
                .sqliteStore(SqliteStoreBuilder(root.resolve("store.db").absolutePath, "").key(ByteArray(32)))
                .sessionPaths(root.resolve("cache").absolutePath, root.resolve("crypto").absolutePath)
                .build()
        }
        bobClient = client
        runBlocking(Dispatchers.IO) {
            client.login(BOB, DEMO_PASSWORD, "Send share test", null)
            val sync = client.syncService().finish()
            bobSync = sync
            sync.start()
            val room = client.joinRoomById(roomId)
            bobRooms[roomId] = room
            val timeline = room.timeline()
            bobTimelines[roomId] = timeline
            bobHandles += timeline.addListener(object : TimelineListener {
                override fun onUpdate(diffs: List<TimelineDiff>) {
                    for (diff in diffs) for (item in timelineItems(diff)) record(roomId, item)
                }
            })
        }
    }

    private fun record(roomId: String, item: TimelineItem) {
        val event = item.asEvent() ?: return
        val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return
        val content = event.content as? TimelineItemContent.MsgLike ?: return
        val message = (content.content.kind as? MsgLikeKind.Message)?.content ?: return
        bobEvents[eventId] = BobEvent(roomId, message.body, image = message.msgType is MessageType.Image)
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

    private companion object {
        const val SHARE_ROOM = "Share check"
    }
}
