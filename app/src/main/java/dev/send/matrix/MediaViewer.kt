package dev.send.matrix

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.IOException
import java.net.URLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MEDIA_VIEWER_TAG = "media-viewer"

/** Longest edge of the decoded full-screen photo; keeps huge images well inside the heap and GPU texture limits. */
private const val FULL_IMAGE_PX = 4096
private const val THUMBNAIL_PX = 1280
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private val BarScrim = Color.Black.copy(alpha = 0.5f)

/**
 * Google Messages-style full-screen viewer for an image or video attachment: black, edge to edge,
 * translucent top bar (back, sender + time, share, save) that a tap on the media toggles.
 */
@Composable
internal fun MediaViewer(
    attachment: MatrixAttachment,
    senderName: String,
    timestampMillis: Long,
    loadMedia: MediaLoader,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val context = LocalContext.current
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            if (window == null) return@LaunchedEffect
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }

        var barsVisible by remember { mutableStateOf(true) }
        var file by remember(attachment.sourceJson) { mutableStateOf<File?>(null) }
        var loadFailed by remember(attachment.sourceJson) { mutableStateOf(false) }
        LaunchedEffect(attachment.sourceJson) {
            try {
                file = loadMedia(attachment.sourceJson, attachment.filename)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                loadFailed = true
            }
        }
        val toggleBars = { barsVisible = !barsVisible }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag(MEDIA_VIEWER_TAG),
        ) {
            if (attachment.kind == AttachmentKind.Video) {
                VideoContent(attachment, file, loadFailed, loadMedia, barsVisible, toggleBars)
            } else {
                PhotoContent(file, loadFailed, toggleBars)
            }
            AnimatedVisibility(
                visible = barsVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(SendMotion.effectsDefault()),
                exit = fadeOut(SendMotion.effectsDefault()),
            ) {
                ViewerTopBar(
                    attachment = attachment,
                    file = file,
                    title = senderName,
                    subtitle = remember(timestampMillis) {
                        DateUtils.formatDateTime(
                            context,
                            timestampMillis,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                        )
                    },
                    onBack = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun ViewerTopBar(attachment: MatrixAttachment, file: File?, title: String, subtitle: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val buttonColors = IconButtonDefaults.iconButtonColors(
        contentColor = Color.White,
        disabledContentColor = Color.White.copy(alpha = 0.38f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BarScrim)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, colors = buttonColors) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(
            onClick = {
                val media = file ?: return@IconButton
                shareMedia(context, media, attachment.galleryMimeType())
            },
            enabled = file != null,
            colors = buttonColors,
        ) {
            Icon(Icons.Outlined.Share, contentDescription = "Share")
        }
        IconButton(
            onClick = {
                val media = file ?: return@IconButton
                saving = true
                scope.launch {
                    val message = try {
                        withContext(Dispatchers.IO) { saveToGallery(context, media, attachment) }
                        if (attachment.kind == AttachmentKind.Video) "Saved to Movies" else "Saved to Pictures"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        "Couldn't save ${attachment.filename}"
                    } finally {
                        saving = false
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            enabled = file != null && !saving,
            colors = buttonColors,
        ) {
            Icon(Icons.Outlined.Download, contentDescription = "Save")
        }
    }
}

@Composable
private fun PhotoContent(file: File?, loadFailed: Boolean, onToggleBars: () -> Unit) {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var decodeFailed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        val source = file ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) { decodeSampled(source, FULL_IMAGE_PX) }
        bitmap = decoded?.asImageBitmap()
        decodeFailed = decoded == null
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null -> ZoomableImage(image, onToggleBars)
            loadFailed || decodeFailed -> ViewerError("Couldn't load this photo")
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Pinch-zoom (1x–5x) and pan clamped to the photo's edges; double-tap toggles 1x / 2.5x around the tap. */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap, onTap: () -> Unit) {
    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTap)
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    val zoomAnimation = remember { arrayOfNulls<Job>(1) }
    // Size of the photo at 1x, as laid out by ContentScale.Fit.
    val fitted = remember(bitmap, container) {
        if (container.width == 0 || container.height == 0 || bitmap.width == 0 || bitmap.height == 0) {
            Size.Zero
        } else {
            val ratio = minOf(container.width.toFloat() / bitmap.width, container.height.toFloat() / bitmap.height)
            Size(bitmap.width * ratio, bitmap.height * ratio)
        }
    }
    val center = Offset(container.width / 2f, container.height / 2f)
    val clamp = { candidate: Offset, zoom: Float ->
        val maxX = ((fitted.width * zoom - container.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fitted.height * zoom - container.height) / 2f).coerceAtLeast(0f)
        Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }
    Image(
        bitmap = bitmap,
        contentDescription = "Photo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { container = it }
            .pointerInput(fitted, container) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { tap ->
                        val fromScale = scale
                        val fromOffset = offset
                        val toScale = if (scale > 1.01f) 1f else DOUBLE_TAP_ZOOM
                        // Keep the tapped point under the finger: screen = content * scale + offset (around the centre).
                        val anchor = tap - center
                        val toOffset = if (toScale == 1f) Offset.Zero else clamp(anchor - (anchor - offset) * (toScale / scale), toScale)
                        zoomAnimation[0]?.cancel()
                        zoomAnimation[0] = scope.launch {
                            animate(0f, 1f, animationSpec = SendMotion.spatialDefault()) { t, _ ->
                                scale = fromScale + (toScale - fromScale) * t
                                offset = fromOffset + (toOffset - fromOffset) * t
                            }
                        }
                    },
                )
            }
            .pointerInput(fitted, container) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    zoomAnimation[0]?.cancel()
                    val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    val anchor = centroid - center
                    offset = clamp(anchor - (anchor - offset) * (newScale / scale) + pan, newScale)
                    scale = newScale
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

@Composable
private fun VideoContent(
    attachment: MatrixAttachment,
    file: File?,
    loadFailed: Boolean,
    loadMedia: MediaLoader,
    barsVisible: Boolean,
    onToggleBars: () -> Unit,
) {
    val currentOnToggle by rememberUpdatedState(onToggleBars)
    var thumbnail by remember(attachment.thumbnailSourceJson) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(attachment.thumbnailSourceJson) {
        val source = attachment.thumbnailSourceJson ?: return@LaunchedEffect
        try {
            val thumbFile = loadMedia(source, "thumbnail-${attachment.filename}")
            thumbnail = withContext(Dispatchers.IO) { decodeSampled(thumbFile, THUMBNAIL_PX) }?.asImageBitmap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The thumbnail is only a placeholder; the spinner keeps showing until the video renders.
        }
    }

    val initialAspect = if (attachment.width != null && attachment.height != null && attachment.width > 0 && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height
    } else 16f / 9f
    var aspect by remember { mutableFloatStateOf(initialAspect) }
    var playing by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf(false) }
    var playbackFailed by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(attachment.durationMillis?.toInt() ?: 0) }
    var positionMs by remember { mutableIntStateOf(0) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    // Where to continue after the surface is torn down (app backgrounded) and the player re-prepares.
    var resumeAt by remember { mutableIntStateOf(0) }
    var autoPlay by remember { mutableStateOf(true) }
    val failed = loadFailed || playbackFailed

    Box(
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Video" },
        contentAlignment = Alignment.Center,
    ) {
        var player: VideoView? = null
        if (file != null && !playbackFailed) {
            val context = LocalContext.current
            val videoView = remember(file) {
                VideoView(context).apply {
                    setOnPreparedListener { mp ->
                        durationMs = mp.duration.coerceAtLeast(0)
                        if (mp.videoWidth > 0 && mp.videoHeight > 0) aspect = mp.videoWidth.toFloat() / mp.videoHeight
                        if (resumeAt > 0) seekTo(resumeAt)
                        if (autoPlay) {
                            start()
                            playing = true
                        }
                    }
                    setOnInfoListener { _, what, _ ->
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered = true
                        false
                    }
                    setOnCompletionListener {
                        playing = false
                        positionMs = durationMs
                        resumeAt = 0
                        autoPlay = false
                    }
                    setOnErrorListener { _, _, _ ->
                        playing = false
                        playbackFailed = true
                        true
                    }
                    setVideoPath(file.absolutePath)
                }
            }
            player = videoView
            DisposableEffect(videoView) {
                onDispose { videoView.stopPlayback() }
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, videoView) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE) {
                        if (durationMs > 0) resumeAt = videoView.currentPosition
                        videoView.pause()
                        playing = false
                        autoPlay = false
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(videoView, playing) {
                while (playing) {
                    positionMs = videoView.currentPosition
                    delay(200)
                }
            }
            AndroidView(factory = { videoView }, modifier = Modifier.aspectRatio(aspect))
        }
        if (!rendered && !failed) {
            thumbnail?.let {
                Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            CircularProgressIndicator(color = Color.White)
        }
        if (failed) ViewerError("Couldn't play this video")
        // Tap anywhere on the video toggles the bars, as in Messages.
        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures(onTap = { currentOnToggle() }) })

        val controls = player
        AnimatedVisibility(
            visible = barsVisible && controls != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(SendMotion.effectsDefault()),
            exit = fadeOut(SendMotion.effectsDefault()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BarScrim)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        val videoView = controls ?: return@IconButton
                        if (playing) {
                            videoView.pause()
                            positionMs = videoView.currentPosition
                            playing = false
                        } else {
                            videoView.start()
                            autoPlay = true
                            playing = true
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                    )
                }
                val fraction = scrub ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                Text(
                    text = formatDuration((fraction * durationMs).toLong()),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    textAlign = TextAlign.End,
                )
                Slider(
                    value = fraction,
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        val target = scrub
                        val videoView = controls
                        if (target != null && videoView != null) {
                            val millis = (target * durationMs).toInt()
                            videoView.seekTo(millis)
                            positionMs = millis
                            resumeAt = millis
                        }
                        scrub = null
                    },
                    enabled = durationMs > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(
                    text = formatDuration(durationMs.toLong()),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ViewerError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp),
    )
}

/** Decodes [file] (EXIF orientation applied) with power-of-two sampling so its longest edge is at most [maxEdgePx]. */
private fun decodeSampled(file: File, maxEdgePx: Int): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        var sample = 1
        while (longest / sample > maxEdgePx) sample *= 2
        decoder.setTargetSampleSize(sample)
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/** A MIME type MediaStore accepts for this attachment's collection (Images or Video). */
private fun MatrixAttachment.galleryMimeType(): String {
    val video = kind == AttachmentKind.Video
    val prefix = if (video) "video/" else "image/"
    return mimetype?.takeIf { it.startsWith(prefix) }
        ?: URLConnection.guessContentTypeFromName(filename)?.takeIf { it.startsWith(prefix) }
        ?: if (video) "video/mp4" else "image/jpeg"
}

private fun shareMedia(context: Context, file: File, mimetype: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.media", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimetype)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, null))
}

/** Copies [file] into Pictures/Send or Movies/Send through MediaStore (no storage permission on API 29+). */
private fun saveToGallery(context: Context, file: File, attachment: MatrixAttachment) {
    val video = attachment.kind == AttachmentKind.Video
    val collection = if (video) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    val directory = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
    val name = attachment.filename.replace('/', '_').trim().ifEmpty { "Send-${System.currentTimeMillis()}" }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, attachment.galleryMimeType())
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/Send")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $name")
    try {
        val output = resolver.openOutputStream(uri) ?: throw IOException("Can't write $uri")
        output.use { sink -> file.inputStream().use { it.copyTo(sink) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (failure: Exception) {
        resolver.delete(uri, null, null)
        throw failure
    }
}
