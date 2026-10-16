package dev.send.matrix

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads decrypted media for an attachment source (SDK JSON) into a local file. */
typealias MediaLoader = suspend (sourceJson: String, filename: String) -> File

private const val PREVIEW_PX = 720

/** Decoded thumbnails, keyed by media source JSON, bounded by bitmap bytes. */
private object MediaBitmapCache : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

private fun decodePreview(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PREVIEW_PX) }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

internal fun formatDuration(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 500) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Hands a downloaded file to the system viewer through this app's FileProvider. */
internal fun openMediaExternally(context: Context, file: File, mimetype: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.media", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimetype)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this device can open ${file.name}.", Toast.LENGTH_LONG).show()
    }
}

/** Single shared player so starting one voice message stops any other. */
@Stable
internal class VoicePlayback {
    var activeId by mutableStateOf<String?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var positionMillis by mutableLongStateOf(0L)
        private set
    private var player: MediaPlayer? = null

    fun play(id: String, file: File) {
        stop()
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare()
        } catch (failure: Exception) {
            mediaPlayer.release()
            throw IllegalStateException("This audio clip could not be played.", failure)
        }
        mediaPlayer.setOnCompletionListener { stop() }
        mediaPlayer.start()
        player = mediaPlayer
        activeId = id
    }

    fun tick() {
        val mediaPlayer = player ?: return
        positionMillis = mediaPlayer.currentPosition.toLong()
        val duration = mediaPlayer.duration
        progress = if (duration > 0) (positionMillis.toFloat() / duration).coerceIn(0f, 1f) else 0f
    }

    fun stop() {
        player?.release()
        player = null
        activeId = null
        progress = 0f
        positionMillis = 0L
    }
}

@Composable
internal fun rememberVoicePlayback(): VoicePlayback {
    val playback = remember { VoicePlayback() }
    DisposableEffect(playback) { onDispose { playback.stop() } }
    LaunchedEffect(playback.activeId) {
        while (playback.activeId != null) {
            playback.tick()
            delay(100)
        }
    }
    return playback
}

/**
 * Messages-style media item: rounded thumbnail for photos/videos, play row with waveform for voice/audio,
 * icon + name + size for other files. Tapping opens or plays; long-press reacts.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentBubble(
    messageId: String,
    attachment: MatrixAttachment,
    isFromMe: Boolean,
    shape: Shape,
    loadMedia: MediaLoader,
    playback: VoicePlayback,
    onLongPress: () -> Unit,
    /** Opens a photo or video in the in-app viewer; without it they open in another app. */
    onOpenVisual: (() -> Unit)? = null,
    /** Applied to the clickable media element itself (TalkBack custom actions). */
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(attachment.sourceJson) { mutableStateOf(false) }
    fun open() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val file = loadMedia(attachment.sourceJson, attachment.filename)
                openMediaExternally(context, file, attachment.mimetype ?: "application/octet-stream")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Toast.makeText(context, failure.message ?: "This attachment could not be downloaded.", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }
    when (attachment.kind) {
        AttachmentKind.Image, AttachmentKind.Video -> VisualMedia(
            attachment = attachment,
            shape = shape,
            loadMedia = loadMedia,
            busy = busy,
            modifier = modifier.combinedClickable(
                onClick = { if (onOpenVisual != null) onOpenVisual() else open() },
                onLongClickLabel = "Select message",
                onLongClick = onLongPress,
            ),
        )
        AttachmentKind.Voice, AttachmentKind.Audio -> AudioRow(
            messageId = messageId,
            attachment = attachment,
            isFromMe = isFromMe,
            shape = shape,
            loadMedia = loadMedia,
            playback = playback,
            onLongPress = onLongPress,
            modifier = modifier,
        )
        AttachmentKind.File -> FileRow(
            attachment = attachment,
            isFromMe = isFromMe,
            shape = shape,
            busy = busy,
            modifier = modifier.combinedClickable(onClick = ::open, onLongClickLabel = "Select message", onLongClick = onLongPress),
        )
    }
}

/** A photo/video's decoded preview: [bitmap] once loaded, [failed] when there is none to show. */
internal class MediaPreview(val bitmap: ImageBitmap?, val failed: Boolean)

/** Loads (and caches) the thumbnail of a photo or video; videos are previewed through their thumbnail only. */
@Composable
internal fun rememberMediaPreview(attachment: MatrixAttachment, loadMedia: MediaLoader): MediaPreview {
    val isVideo = attachment.kind == AttachmentKind.Video
    val previewSource = attachment.thumbnailSourceJson ?: attachment.sourceJson.takeIf { !isVideo }
    var preview by remember(previewSource) { mutableStateOf<ImageBitmap?>(previewSource?.let { MediaBitmapCache.get(it) }?.asImageBitmap()) }
    var failed by remember(previewSource) { mutableStateOf(previewSource == null) }
    LaunchedEffect(previewSource) {
        if (previewSource == null || preview != null) return@LaunchedEffect
        try {
            val file = loadMedia(previewSource, if (previewSource == attachment.sourceJson) attachment.filename else "thumbnail-${attachment.filename}")
            val bitmap = withContext(Dispatchers.IO) { decodePreview(file) }
            if (bitmap != null) MediaBitmapCache.put(previewSource, bitmap)
            preview = bitmap?.asImageBitmap()
            failed = bitmap == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    return MediaPreview(preview, failed)
}

/**
 * Square tile for conversation details' Media & files: the photo/video thumbnail cropped (play badge on videos), or
 * the file's icon and name for anything else.
 */
@Composable
internal fun MediaTile(attachment: MatrixAttachment, loadMedia: MediaLoader, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val visual = attachment.kind == AttachmentKind.Image || attachment.kind == AttachmentKind.Video
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHighest)
            .semantics { contentDescription = if (visual) (if (attachment.kind == AttachmentKind.Video) "Video" else "Photo") else attachment.filename },
        contentAlignment = Alignment.Center,
    ) {
        if (visual) {
            val preview = rememberMediaPreview(attachment, loadMedia)
            val bitmap = preview.bitmap
            when {
                bitmap != null -> Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                preview.failed -> Icon(Icons.Outlined.ImageIcon, contentDescription = null, tint = colors.onSurfaceVariant)
                else -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            if (attachment.kind == AttachmentKind.Video) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(4.dp),
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Icon(
                    if (attachment.kind == AttachmentKind.Voice || attachment.kind == AttachmentKind.Audio) Icons.Filled.GraphicEq else Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                )
                Text(
                    if (attachment.kind == AttachmentKind.Voice) "Voice message" else attachment.filename,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun VisualMedia(attachment: MatrixAttachment, shape: Shape, loadMedia: MediaLoader, busy: Boolean, modifier: Modifier) {
    val isVideo = attachment.kind == AttachmentKind.Video
    val loaded = rememberMediaPreview(attachment, loadMedia)
    val preview = loaded.bitmap
    val failed = loaded.failed
    val aspect = if (attachment.width != null && attachment.height != null && attachment.width > 0 && attachment.height > 0) {
        (attachment.width.toFloat() / attachment.height).coerceIn(0.6f, 1.8f)
    } else 4f / 3f
    val width = 240.dp
    Box(
        modifier = Modifier
            .size(width, width / aspect)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(modifier)
            .semantics { contentDescription = if (isVideo) "Video" else "Photo" },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = preview
        when {
            bitmap != null -> Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            failed -> Icon(Icons.Outlined.ImageIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            else -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        }
        if (isVideo) {
            Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            attachment.durationMillis?.let { duration ->
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        } else if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioRow(
    messageId: String,
    attachment: MatrixAttachment,
    isFromMe: Boolean,
    shape: Shape,
    loadMedia: MediaLoader,
    playback: VoicePlayback,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember(messageId) { mutableStateOf(false) }
    val playing = playback.activeId == messageId
    val colors = MaterialTheme.colorScheme
    val container = if (isFromMe) colors.primaryContainer else colors.surfaceContainerHigh
    val content = if (isFromMe) colors.onPrimaryContainer else colors.onSurface
    fun toggle() {
        if (playing) {
            playback.stop()
            return
        }
        if (loading) return
        loading = true
        scope.launch {
            try {
                val file = loadMedia(attachment.sourceJson, attachment.filename)
                playback.play(messageId, file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Toast.makeText(context, failure.message ?: "This audio clip could not be played.", Toast.LENGTH_LONG).show()
            } finally {
                loading = false
            }
        }
    }
    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        modifier = modifier.widthIn(max = 280.dp).combinedClickable(
            onClickLabel = if (playing) "Pause" else "Play",
            onClick = ::toggle,
            onLongClickLabel = "Select message",
            onLongClick = onLongPress,
        ),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).background(content, CircleShape)
                    .semantics { contentDescription = if (playing) "Pause voice message" else "Play voice message"; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = container)
                } else {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = container)
                }
            }
            Spacer(Modifier.width(10.dp))
            if (attachment.kind == AttachmentKind.Voice) {
                Waveform(
                    samples = attachment.waveform,
                    progress = if (playing) playback.progress else 0f,
                    played = content,
                    pending = content.copy(alpha = 0.35f),
                    modifier = Modifier.width(140.dp).height(32.dp),
                )
            } else {
                Text(
                    attachment.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            val shown = if (playing) playback.positionMillis else attachment.durationMillis ?: 0L
            Text(formatDuration(shown), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun Waveform(samples: List<Float>, progress: Float, played: Color, pending: Color, modifier: Modifier) {
    val bars = if (samples.isEmpty()) List(32) { 0.25f } else samples
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
        bars.forEachIndexed { index, sample ->
            val barHeight = (size.height * sample.coerceIn(0.08f, 1f))
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = if ((index + 0.5f) / bars.size <= progress) played else pending,
                topLeft = Offset(x, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun FileRow(attachment: MatrixAttachment, isFromMe: Boolean, shape: Shape, busy: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val container = if (isFromMe) colors.primaryContainer else colors.surfaceContainerHigh
    val content = if (isFromMe) colors.onPrimaryContainer else colors.onSurface
    Surface(shape = shape, color = container, contentColor = content, modifier = Modifier.widthIn(max = 280.dp).then(modifier)) {
        Row(Modifier.padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(content.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = "File")
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(attachment.filename, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                attachment.sizeBytes?.let { size ->
                    Text(
                        android.text.format.Formatter.formatShortFileSize(context, size),
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/**
 * Messages' hold-to-record button: press starts recording, releasing over the button sends, sliding away
 * before release discards, and sliding up past the lock hint keeps recording hands-free ([onLock]); the
 * recorder panel below the composer then takes over (restart, stop/playback, attach). A plain tap explains the gesture.
 */
@Composable
internal fun VoiceRecordButton(
    recording: Boolean,
    onStart: () -> Boolean,
    onFinish: (send: Boolean) -> Unit,
    onSlideOutside: (Boolean) -> Unit,
    onTap: () -> Unit,
    onLock: () -> Unit = {},
) {
    val start by rememberUpdatedState(onStart)
    val finish by rememberUpdatedState(onFinish)
    val outside by rememberUpdatedState(onSlideOutside)
    val tap by rememberUpdatedState(onTap)
    val lock by rememberUpdatedState(onLock)
    val colors = MaterialTheme.colorScheme
    var lockProgress by remember { mutableFloatStateOf(0f) }
    Box(contentAlignment = Alignment.Center) {
        if (recording) {
            // Messages: a lock pill above the button that fills as the finger slides up.
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = colors.surfaceContainerHighest,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .offset(y = -(VOICE_LOCK_DISTANCE + 8.dp) * (0.5f + 0.5f * lockProgress))
                    .semantics { contentDescription = "Slide up to lock" },
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = if (lockProgress >= 1f) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .scale(if (recording) 1.15f else 1f)
                // Messages: tonal pink at rest, solid red while recording.
                .background(if (recording) colors.error else colors.tertiaryContainer, CircleShape)
                .semantics {
                    contentDescription = "Voice message"
                    role = Role.Button
                    onClick(label = "Hold to record") { tap(); true }
                    // TalkBack can't hold and slide: this starts the recording already locked, so the recorder
                    // panel's stop/attach and the composer's send button finish it.
                    customActions = listOf(CustomAccessibilityAction("Record hands-free") { if (start()) lock(); true })
                }
                .pointerInput(Unit) {
                    val slop = 48.dp.toPx()
                    val lockPx = VOICE_LOCK_DISTANCE.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        if (!start()) return@awaitEachGesture
                        var inside = true
                        var locked = false
                        lockProgress = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            val position = change.position
                            if (!locked) {
                                lockProgress = (-position.y / lockPx).coerceIn(0f, 1f)
                                if (position.y <= -lockPx) {
                                    locked = true
                                    if (!inside) outside(false)
                                    lock()
                                } else {
                                    val nowInside = position.x in -slop..(size.width + slop) && position.y in -(lockPx + slop)..(size.height + slop)
                                    if (nowInside != inside) {
                                        inside = nowInside
                                        outside(!inside)
                                    }
                                }
                            }
                            if (!change.pressed) break
                        }
                        lockProgress = 0f
                        // Locked recordings carry on after release; the composer's controls finish them.
                        if (!locked) finish(inside)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // Messages uses a waveform glyph rather than a microphone.
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = if (recording) colors.onError else colors.onTertiaryContainer,
            )
        }
    }
}

/** How far above the voice button the finger slides to lock a recording. */
internal val VOICE_LOCK_DISTANCE = 72.dp

/** Composer status while a voice clip is being recorded. */
@Composable
internal fun RecordingIndicator(elapsedMillis: Long, cancelling: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(colors.error, CircleShape))
        Spacer(Modifier.width(8.dp))
        val seconds = elapsedMillis / 1000
        Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Spacer(Modifier.width(16.dp))
        // Messages: trash + chevron hint the slide-to-cancel gesture.
        Icon(Icons.Outlined.Delete, contentDescription = null, tint = if (cancelling) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (cancelling) "Release to cancel" else "Slide to cancel",
            style = MaterialTheme.typography.bodyLarge,
            color = if (cancelling) colors.error else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
