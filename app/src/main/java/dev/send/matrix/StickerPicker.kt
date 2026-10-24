package dev.send.matrix

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The three tabs of Messages' composer panel. */
internal enum class ComposerTab(val label: String) { Emoji("Emoji"), Gifs("GIFs"), Stickers("Stickers") }

/**
 * What the composer panel's GIF and sticker tabs work with: the image packs available here (loaded when a tab
 * opens, re-read after a change), sending a pack image, and adding or removing pictures in the user's own pack.
 */
internal class StickerActions(
    /** Packs available in this conversation (the user's own first). */
    val loadPacks: suspend () -> List<ImagePack>,
    /** Sends the image: as an `m.sticker` from the Stickers tab, as a GIF picture from the GIFs tab. */
    val send: suspend (image: PackImage, asGif: Boolean) -> Unit,
    /** Adds a picture from the device to the user's pack and returns its entry. */
    val add: suspend (Uri) -> PackImage,
    /** Removes an image from the user's pack. */
    val remove: suspend (shortcode: String) -> Unit,
)

/**
 * Messages' composer panel in place of the keyboard: Emoji, GIFs and Stickers tabs over the same 320dp. Without
 * [stickers] (fixtures, unencrypted rooms) it is the emoji picker alone, as before.
 */
@Composable
internal fun ComposerPanel(
    onEmoji: (String) -> Unit,
    stickers: StickerActions?,
    loadMedia: MediaLoader,
    /** Reports a problem under the composer (a failed send or upload). */
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stickers == null) {
        EmojiPickerPanel(onEmoji = onEmoji, modifier = modifier)
        return
    }
    var tab by rememberSaveable { mutableStateOf(ComposerTab.Emoji) }
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            ComposerTab.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    shape = SegmentedButtonDefaults.itemShape(index, ComposerTab.entries.size),
                    icon = {},
                ) { Text(entry.label) }
            }
        }
        when (tab) {
            ComposerTab.Emoji -> EmojiPickerPanel(onEmoji = onEmoji, modifier = Modifier.weight(1f).fillMaxWidth())
            ComposerTab.Gifs -> PackImageGrid(gifs = true, actions = stickers, loadMedia = loadMedia, onError = onError, modifier = Modifier.weight(1f).fillMaxWidth())
            ComposerTab.Stickers -> PackImageGrid(gifs = false, actions = stickers, loadMedia = loadMedia, onError = onError, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * The GIFs or Stickers tab: a four-column grid of the available pack images, each pack under its name, with an
 * Add tile first that takes a picture from the device into the user's pack (long-press a picture you added to
 * remove it). Tapping an image sends it. Empty packs get Messages-style guidance instead of a blank grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PackImageGrid(gifs: Boolean, actions: StickerActions, loadMedia: MediaLoader, onError: (String) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var packs by remember { mutableStateOf<List<ImagePack>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<PackImage?>(null) }
    LaunchedEffect(reload) {
        try {
            packs = actions.loadPacks()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            packs = emptyList()
            onError(failure.message ?: "Stickers could not be loaded.")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            adding = true
            scope.launch {
                try {
                    actions.add(uri)
                    reload += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    onError(failure.message ?: "The picture could not be added.")
                } finally {
                    adding = false
                }
            }
        }
    }
    fun pickFromDevice() {
        val request = if (gifs) {
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif"))
        } else {
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        }
        picker.launch(request)
    }
    fun send(image: PackImage) {
        if (sending != null) return
        sending = image.key
        scope.launch {
            try {
                actions.send(image, gifs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onError(failure.message ?: if (gifs) "The GIF could not be sent." else "The sticker could not be sent.")
            } finally {
                sending = null
            }
        }
    }
    val loaded = packs
    val shown = loaded?.map { pack -> pack.copy(images = pack.images.filter { if (gifs) it.animated else it.sticker }) }?.filter { it.images.isNotEmpty() }
    val noun = if (gifs) "GIFs" else "stickers"
    val addLabel = if (gifs) "Add a GIF from your photos" else "Add a sticker from your photos"
    when {
        loaded == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp) }
        shown.isNullOrEmpty() -> Column(
            modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("No $noun yet", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                if (gifs) "Add GIFs from your photos to send them here and in every conversation."
                else "Add stickers from your photos, or join a room that shares a sticker pack.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = ::pickFromDevice, enabled = !adding) {
                if (adding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                else Text(if (gifs) "Add GIF" else "Add sticker")
            }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "add") {
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)
                        .combinedClickable(enabled = !adding, onClickLabel = addLabel, onClick = ::pickFromDevice)
                        .semantics { contentDescription = addLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    if (adding) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    else Icon(Icons.Filled.Add, contentDescription = null, tint = colors.primary, modifier = Modifier.size(32.dp))
                }
            }
            for (pack in shown) {
                item(key = "pack:${pack.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        pack.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                items(pack.images.size, key = { "${pack.id}:${pack.images[it].shortcode}" }) { index ->
                    val image = pack.images[index]
                    val own = pack.id == MatrixRepository.USER_PACK_ID
                    val label = (if (gifs) "Send GIF: " else "Send sticker: ") + image.body
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)
                            .combinedClickable(
                                onClick = { send(image) },
                                onLongClickLabel = if (own) "Remove from your stickers" else null,
                                onLongClick = if (own) ({ confirmRemove = image }) else null,
                            )
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedOrStillImage(
                            attachment = MatrixAttachment(
                                kind = AttachmentKind.Sticker,
                                filename = image.body,
                                mimetype = image.mimetype,
                                sizeBytes = image.sizeBytes,
                                durationMillis = null,
                                width = image.width,
                                height = image.height,
                                sourceJson = image.sourceJson,
                                thumbnailSourceJson = null,
                            ),
                            loadMedia = loadMedia,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                        )
                        if (sending == image.key) {
                            Box(Modifier.fillMaxSize().background(colors.surface.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
    confirmRemove?.let { image ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(if (gifs) "Remove this GIF?" else "Remove this sticker?") },
            text = { Text("It leaves your ${if (gifs) "GIFs" else "stickers"} on every device. Messages that used it keep it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    scope.launch {
                        try {
                            actions.remove(image.shortcode)
                            reload += 1
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            onError(failure.message ?: "It could not be removed.")
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}
