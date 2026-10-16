package dev.send.matrix

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8 share target on fixtures: real ACTION_SEND / ACTION_SEND_MULTIPLE intents are staged by
 * [ShareTarget.stage] (MediaStore content URIs, ClipData, a refused `file:` URI and a refused Send-provider URI),
 * reloaded the way MainActivity does, and fed to [ShareFlow]: the picker lists only encrypted conversations (no
 * plain rooms, no blocked DMs), the confirmation names the recipient and shows the text, photos and files, Cancel
 * sends nothing, and a direct-share shortcut ID skips the picker for an encrypted room only.
 */
@RunWith(AndroidJUnit4::class)
class ShareTargetUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val stamp = System.currentTimeMillis()
    private val inserted = mutableListOf<Uri>()

    private val rooms = listOf(
        MatrixRoom("!alice:example.org", "Alice", encrypted = true, direct = true, lastMessage = "See you at 7", lastTimestampMillis = stamp - 300_000, dmUserId = "@alice:example.org"),
        MatrixRoom("!family:example.org", "Family", encrypted = true, direct = false, lastMessage = "Mum: See you Sunday", lastTimestampMillis = stamp - 3_600_000),
        MatrixRoom("!lobby:example.org", "Public lobby", encrypted = false, direct = false, lastMessage = "Welcome!", lastTimestampMillis = stamp - 7_200_000),
        MatrixRoom("!spam:example.org", "Spammer", encrypted = true, direct = true, lastMessage = "Click this", lastTimestampMillis = stamp - 9_000_000, dmUserId = "@spam:example.org"),
    )
    private val blocked = setOf("@spam:example.org")

    /** Recipients Send was tapped for, and how often the share was dropped. */
    private val sent = mutableListOf<String>()
    private var discarded = 0
    private var shown by mutableStateOf<SharePayload?>(null)

    @After
    fun cleanUp() {
        inserted.forEach { context.contentResolver.delete(it, null, null) }
        ShareTarget.clearAll(context)
    }

    /** Hosts [ShareFlow] as SendApp does: dropping the share removes it, the picker sees the encrypted targets. */
    private fun show(share: SharePayload) {
        shown = share
        composeRule.setContent {
            SendTheme {
                shown?.let { current ->
                    ShareFlow(
                        share = current,
                        rooms = encryptedTargets(rooms, blocked),
                        mutedRoomIds = emptySet(),
                        onSend = { sent += it.id },
                        onDiscard = { discarded++; shown = null },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun stage(intent: Intent): SharePayload {
        val staged = checkNotNull(ShareTarget.stage(context, intent))
        // MainActivity only ever sees the share through its ID: reload it from the staged copy.
        val loaded = checkNotNull(ShareTarget.load(context, staged.id))
        assertEquals(staged, loaded)
        return loaded
    }

    private fun assertPickerShowsOnlyEncryptedRooms() {
        composeRule.onNodeWithText("Share to").assertIsDisplayed()
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Family").assertIsDisplayed()
        composeRule.onAllNodesWithText("Public lobby").assertCountEquals(0)
        composeRule.onAllNodesWithText("Spammer").assertCountEquals(0)
    }

    @Test
    fun textShareListsEncryptedRoomsAndCancelSendsNothing() {
        val share = stage(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Dinner spot")
                .putExtra(Intent.EXTRA_TEXT, "https://example.org/menu"),
        )
        assertEquals("Dinner spot\nhttps://example.org/menu", share.text)
        assertTrue(share.files.isEmpty())
        show(share)
        assertPickerShowsOnlyEncryptedRooms()
        composeRule.auditShot("share-picker")

        composeRule.onNodeWithText("Alice").performClick()
        composeRule.onNodeWithText("Send to Alice?").assertIsDisplayed()
        composeRule.onNodeWithTag("share-text", useUnmergedTree = true).assertTextEquals("Dinner spot\nhttps://example.org/menu")
        // Cancel returns to the picker without sending or dropping the share.
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Send to Alice?").assertCountEquals(0)
        composeRule.onNodeWithText("Share to").assertIsDisplayed()
        assertTrue(sent.isEmpty())
        assertEquals(0, discarded)

        composeRule.onNodeWithText("Family").performClick()
        composeRule.onNodeWithText("Send to Family?").assertIsDisplayed()
        composeRule.onNodeWithText("Send").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("!family:example.org"), sent)
    }

    @Test
    fun oneImageShowsItsThumbnailBeforeSending() {
        val name = "share-photo-$stamp.png"
        val photo = insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/SendTest", name, "image/png", png(640, 480))
        val share = stage(
            Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, photo)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri(null, photo) },
        )
        // EXTRA_STREAM and the same URI in ClipData make one copy, in Send's cache, named and typed by the provider.
        assertNull(share.text)
        assertEquals(1, share.files.size)
        assertEquals(name, share.files.single().name)
        assertEquals("image/png", share.files.single().mimeType)
        assertTrue(share.files.single().file.canonicalPath.startsWith(context.cacheDir.canonicalPath))
        show(share)
        assertPickerShowsOnlyEncryptedRooms()
        composeRule.onNodeWithText("1 photo").assertIsDisplayed()

        composeRule.onNodeWithText("Alice").performClick()
        composeRule.onNodeWithText("Send to Alice?").assertIsDisplayed()
        composeRule.onNodeWithText(name).assertIsDisplayed()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithContentDescription("Preview of $name").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun multipleFilesWithTextAreListedAndUnsafeUrisRefused() {
        val first = "share-beach-$stamp.png"
        val second = "share-sunset-$stamp.png"
        val document = "share-menu-$stamp.pdf"
        // Two refused URIs, never read: a raw file path and Send's own provider (which could expose its private
        // files). The first sits between readable items, so the staged copies keep their own directories.
        val uris = arrayListOf(
            insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/SendTest", first, "image/png", png(800, 600)),
            Uri.fromFile(File(context.filesDir, "secret.db")),
            insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/SendTest", second, "image/png", png(600, 800)),
            insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/SendTest", document, "application/pdf", "%PDF-1.4\n%Send share test\n".toByteArray()),
            Uri.parse("content://${context.packageName}.media/media/secret.jpg"),
        )
        val share = stage(
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .putExtra(Intent.EXTRA_TEXT, "Holiday pictures and the menu")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        assertEquals(listOf(first, second, document), share.files.map { it.name })
        assertEquals(listOf("image/png", "image/png", "application/pdf"), share.files.map { it.mimeType })
        assertEquals(2, share.unreadable)
        assertEquals("2 photos, 1 file and a message", shareSummary(share))
        show(share)
        assertPickerShowsOnlyEncryptedRooms()
        composeRule.onNodeWithText("2 photos, 1 file and a message").assertIsDisplayed()

        composeRule.onNodeWithText("Alice").performClick()
        composeRule.onNodeWithText("Send to Alice?").assertIsDisplayed()
        composeRule.onNodeWithText(first).assertIsDisplayed()
        composeRule.onNodeWithText(document).assertExists()
        composeRule.onNodeWithText("2 items couldn't be read and won't be sent.").assertExists()
        composeRule.onNodeWithTag("share-text", useUnmergedTree = true).assertTextEquals("Holiday pictures and the menu")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription("Preview of $first").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription("Preview of $second").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.auditShot("share-confirm")
        composeRule.onNodeWithText("Send").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("!alice:example.org"), sent)
    }

    @Test
    fun directShareSkipsThePickerOnlyForAnEncryptedRoom() {
        val direct = stage(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Running 10 minutes late")
                .putExtra(Intent.EXTRA_SHORTCUT_ID, "!alice:example.org"),
        )
        assertEquals("!alice:example.org", direct.shortcutRoomId)
        show(direct)
        composeRule.onNodeWithText("Send to Alice?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Share to").assertCountEquals(0)
        // With no picker behind it, Cancel drops the share and nothing is sent.
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Send to Alice?").assertCountEquals(0)
        assertTrue(sent.isEmpty())
        assertEquals(1, discarded)

        // A shortcut for a room that isn't an encrypted target (here a plain room) falls back to the picker.
        val plain = stage(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Hello lobby")
                .putExtra(Intent.EXTRA_SHORTCUT_ID, "!lobby:example.org"),
        )
        composeRule.runOnIdle { shown = plain }
        composeRule.waitForIdle()
        assertPickerShowsOnlyEncryptedRooms()
        composeRule.onAllNodesWithText("Send to", substring = true).assertCountEquals(0)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun otherIntentsAndEmptySharesAreIgnored() {
        assertNull(ShareTarget.stage(context, Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "not a share")))
        assertNull(ShareTarget.stage(context, Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertNull(ShareTarget.load(context, "../../files"))
        val share = stage(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Kept until sent"))
        ShareTarget.discard(context, share.id)
        assertNull(ShareTarget.load(context, share.id))
        assertNotNull(ShareTarget.openIntent(context, share.id).component)
    }

    /** Adds a file to MediaStore (owned by this app, removed after the test) and returns its content URI. */
    private fun insert(collection: Uri, relativePath: String, name: String, mimeType: String, bytes: ByteArray): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "Could not create $name" }
        inserted += uri
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        check(resolver.update(uri, values, null, null) == 1)
        return uri
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            Paint().apply { shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF4285F4.toInt(), 0xFFFCC934.toInt(), Shader.TileMode.CLAMP) },
        )
        return ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
    }
}
