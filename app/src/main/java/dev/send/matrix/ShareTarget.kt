package dev.send.matrix

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A shared or picked file copied into Send's cache: the SDK uploads from a path and uses [name] as the body. */
internal data class SharedFile(val file: File, val name: String, val mimeType: String)

/**
 * Content another app shared into Send, staged in the cache until the user confirms a recipient (or drops it).
 * [shortcutRoomId] is the conversation chosen in the system share sheet (direct share), if any.
 */
internal data class SharePayload(
    val id: String,
    val text: String?,
    val files: List<SharedFile>,
    /** Streams that couldn't be read (or were refused); they are named in the confirmation and not sent. */
    val unreadable: Int,
    val shortcutRoomId: String?,
)

/**
 * Copies [uri] into [dir] under its display name (sanitised), the copy the composer's picker and incoming shares
 * both upload from. [fallbackMimeType] covers providers that don't report a type.
 */
internal fun copyToCache(context: Context, uri: Uri, dir: File, fallbackMimeType: String? = null): SharedFile {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: fallbackMimeType ?: "application/octet-stream"
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    val name = safeFileName(displayName ?: uri.lastPathSegment ?: "attachment")
    dir.mkdirs()
    val file = File(dir, name)
    val input = resolver.openInputStream(uri) ?: error("The selected file could not be read.")
    input.use { source -> file.outputStream().use { source.copyTo(it) } }
    return SharedFile(file, name, mimeType)
}

/** Receiving shares: staging `ACTION_SEND`/`ACTION_SEND_MULTIPLE` content and handing it to [MainActivity]. */
internal object ShareTarget {
    /** MainActivity: show the staged share [EXTRA_SHARE_ID] (only [ShareActivity] creates these). */
    const val ACTION_SHARE = "dev.send.matrix.action.SHARE"
    const val EXTRA_SHARE_ID = "dev.send.matrix.extra.SHARE_ID"
    /** Category of the long-lived conversation shortcuts offered as direct-share targets (res/xml/shortcuts.xml). */
    const val CATEGORY_SHARE_TARGET = "dev.send.matrix.category.SHARE_TARGET"
    private const val DIR = "share"
    private const val MANIFEST = "share.json"

    private fun root(context: Context): File = context.cacheDir.resolve(DIR)

    /** A staged share's directory; null unless [id] is one of ours (a UUID), so extras can't point elsewhere. */
    private fun dir(context: Context, id: String): File? =
        runCatching { UUID.fromString(id) }.getOrNull()?.takeIf { it.toString() == id }?.let { root(context).resolve(id) }

    /**
     * Copies the text and every stream of a share intent into the cache while the sender's read grant lasts.
     * Returns null for other intents or when nothing was shared. `file:` URIs and Send's own provider are refused,
     * so a share can't pick up Send's private files.
     */
    fun stage(context: Context, intent: Intent): SharePayload? {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
        val text = sharedText(intent)
        val uris = LinkedHashSet<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
        }
        intent.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add) }
        if (text == null && uris.isEmpty()) return null
        val id = UUID.randomUUID().toString()
        val dir = root(context).resolve(id)
        val fallbackType = intent.type?.takeUnless { '*' in it }
        val files = ArrayList<SharedFile>(uris.size)
        var unreadable = 0
        uris.forEachIndexed { index, uri ->
            val allowed = uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.startsWith(context.packageName) == false
            val copied = if (allowed) {
                try {
                    copyToCache(context, uri, dir.resolve(index.toString()), fallbackType)
                } catch (failure: Exception) {
                    android.util.Log.w("ShareTarget", "A shared item could not be read", failure)
                    null
                }
            } else null
            if (copied != null) files += copied else unreadable++
        }
        if (text == null && files.isEmpty()) {
            dir.deleteRecursively()
            return null
        }
        val payload = SharePayload(id, text, files, unreadable, intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        dir.mkdirs()
        dir.resolve(MANIFEST).writeText(
            JSONObject()
                .put("text", payload.text)
                .put("unreadable", payload.unreadable)
                .put("shortcut", payload.shortcutRoomId)
                .put("files", JSONArray(files.map { JSONObject().put("dir", it.file.parentFile?.name).put("name", it.name).put("mime", it.mimeType) }))
                .toString(),
        )
        return payload
    }

    /** Text from `EXTRA_TEXT`, led by `EXTRA_SUBJECT` (a page title, say) when that isn't already part of it. */
    private fun sharedText(intent: Intent): String? {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.ifEmpty { null }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.ifEmpty { null }
        return when {
            text == null -> subject
            subject == null || subject in text -> text
            else -> "$subject\n$text"
        }
    }

    /** The share staged as [id], or null when it is gone (sent, dropped, or the cache was cleared). */
    fun load(context: Context, id: String): SharePayload? {
        val dir = dir(context, id) ?: return null
        val manifest = runCatching { JSONObject(dir.resolve(MANIFEST).readText()) }.getOrNull() ?: return null
        val entries = manifest.getJSONArray("files")
        val files = (0 until entries.length()).mapNotNull { index ->
            val entry = entries.getJSONObject(index)
            // Only a numbered directory inside this share and a sanitised name: the manifest can't point elsewhere.
            val sub = entry.optString("dir").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@mapNotNull null
            val name = safeFileName(entry.getString("name"))
            val file = dir.resolve(sub).resolve(name)
            if (file.isFile) SharedFile(file, name, entry.getString("mime")) else null
        }
        return SharePayload(
            id = id,
            text = manifest.optString("text").takeIf { manifest.has("text") && !manifest.isNull("text") },
            files = files,
            unreadable = manifest.optInt("unreadable") + (entries.length() - files.size),
            shortcutRoomId = manifest.optString("shortcut").takeIf { manifest.has("shortcut") && !manifest.isNull("shortcut") },
        )
    }

    /** Deletes a staged share's copies (after sending or when the user drops it). */
    fun discard(context: Context, id: String) {
        dir(context, id)?.deleteRecursively()
    }

    /** Sign-out: no shared copies outlive the session. */
    fun clearAll(context: Context) {
        root(context).deleteRecursively()
    }

    /** Opens the staged share [id] in Send's own task (a running MainActivity gets it in onNewIntent). */
    fun openIntent(context: Context, id: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_SHARE)
            .putExtra(EXTRA_SHARE_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/**
 * The exported share entry point (and the `<share-target>` class): copies the shared content while it still holds
 * the sender's URI grants, then hands it to [MainActivity] in Send's task, so the conversation list, session and
 * open timeline stay in one activity rather than a second copy inside the sending app's task.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent
        lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) { ShareTarget.stage(applicationContext, shared) }
            if (payload != null) {
                startActivity(ShareTarget.openIntent(this@ShareActivity, payload.id))
            } else {
                Toast.makeText(this@ShareActivity, "Nothing to share", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}

private fun plural(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"

/** "2 photos, 1 file" (plus "and a message" with text), or the text itself for a text-only share. */
internal fun shareSummary(share: SharePayload): String {
    val photos = share.files.count { it.mimeType.startsWith("image/") }
    val videos = share.files.count { it.mimeType.startsWith("video/") }
    val others = share.files.size - photos - videos
    val parts = buildList {
        if (photos > 0) add(plural(photos, "photo"))
        if (videos > 0) add(plural(videos, "video"))
        if (others > 0) add(plural(others, "file"))
    }
    return when {
        parts.isEmpty() -> share.text?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty()
        share.text == null -> parts.joinToString(", ")
        else -> parts.joinToString(", ") + " and a message"
    }
}

/** Conversations a share (or forward) may go to: encrypted rooms, minus DMs with blocked people and [exclude]. */
internal fun encryptedTargets(rooms: List<MatrixRoom>, ignoredUsers: Set<String>, exclude: String? = null): List<MatrixRoom> =
    rooms.filter { it.encrypted && it.id != exclude && (it.dmUserId == null || it.dmUserId !in ignoredUsers) }

/**
 * Incoming share: the Forward picker over the encrypted conversations, then "Send to <name>?" listing exactly what
 * goes out. A direct-share [SharePayload.shortcutRoomId] that is still among [rooms] skips the picker. Nothing is
 * sent until Send is tapped; Back on the picker, or Cancel on a direct share, drops the share.
 */
@Composable
internal fun ShareFlow(
    share: SharePayload,
    rooms: List<MatrixRoom>,
    mutedRoomIds: Set<String>,
    onSend: (MatrixRoom) -> Unit,
    onDiscard: () -> Unit,
) {
    val direct = share.shortcutRoomId?.let { id -> rooms.firstOrNull { it.id == id } }
    var picked by remember(share.id) { mutableStateOf<MatrixRoom?>(null) }
    if (direct == null) {
        Box(Modifier.testTag("share-picker")) {
            ForwardScreen(
                rooms = rooms,
                mutedRoomIds = mutedRoomIds,
                preview = shareSummary(share),
                onPick = { picked = it },
                onBack = onDiscard,
                title = "Share to",
                emptyText = "No encrypted conversations yet.",
            )
        }
    }
    val target = picked ?: direct ?: return
    ShareConfirmDialog(
        share = share,
        recipient = target.name,
        onSend = { onSend(target) },
        // Cancel goes back to the picker; with no picker behind it (direct share) the share is dropped.
        onCancel = { if (picked != null) picked = null else onDiscard() },
    )
}

@Composable
internal fun ShareConfirmDialog(share: SharePayload, recipient: String, onSend: () -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Send to $recipient?") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (share.files.isNotEmpty()) {
                    Text(shareSummary(share), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    for (file in share.files) SharedFileRow(file)
                }
                if (share.text != null) {
                    Surface(shape = RoundedCornerShape(20.dp), color = colors.secondaryContainer) {
                        Text(
                            share.text,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).testTag("share-text"),
                        )
                    }
                }
                if (share.unreadable > 0) {
                    Text(
                        "${plural(share.unreadable, "item")} couldn't be read and won't be sent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Send") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** A file in the confirmation: a thumbnail for photos and videos, an icon otherwise, and its name. */
@Composable
private fun SharedFileRow(file: SharedFile) {
    val colors = MaterialTheme.colorScheme
    val thumbnail by produceState<ImageBitmap?>(null, file.file) {
        value = withContext(Dispatchers.IO) { shareThumbnail(file, 192) }?.asImageBitmap()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val shown = thumbnail
            if (shown != null) {
                Image(shown, contentDescription = "Preview of ${file.name}", contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
            } else {
                Surface(color = colors.surfaceContainerHighest, modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (file.mimeType.startsWith("audio/")) Icons.Filled.GraphicEq else Icons.AutoMirrored.Outlined.InsertDriveFile,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            file.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp).fillMaxWidth(),
        )
    }
}

/** A small preview of a staged photo or video; null for other files or when it can't be decoded. */
private fun shareThumbnail(file: SharedFile, sizePx: Int): Bitmap? = try {
    when {
        file.mimeType.startsWith("image/") -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
            else BitmapFactory.decodeFile(
                file.file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx) },
            )
        }
        file.mimeType.startsWith("video/") -> MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.file.absolutePath)
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx)
        }
        else -> null
    }
} catch (failure: Exception) {
    null
}
