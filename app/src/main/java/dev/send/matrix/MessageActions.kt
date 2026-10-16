package dev.send.matrix

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Messages' quick reactions, shown above a long-pressed message. */
internal val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "😡", "👎")

/** The floating emoji row over a selected message, spread across its width; the trailing button opens the full emoji picker. */
@Composable
internal fun ReactionRow(onReact: (String) -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickReactions.forEach { emoji ->
                Box(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape)
                        .clickable(onClickLabel = "React", role = androidx.compose.ui.semantics.Role.Button) { onReact(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 26.sp)
                }
            }
            IconButton(onClick = onMore, modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Outlined.AddReaction, contentDescription = "More reactions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What can be done with a long-pressed message. */
internal data class MessageActionSet(
    val reply: Boolean,
    val copy: Boolean,
    val edit: Boolean,
    val delete: Boolean,
    val forward: Boolean,
    val react: Boolean,
    /** Star / Unstar (needs an event ID; the screen hides it when starring isn't wired). */
    val star: Boolean,
)

internal fun actionsFor(message: MatrixMessage): MessageActionSet {
    val readable = !message.deleted && message.decryptionFailure == null && message.call == null
    val onServer = message.status == DeliveryStatus.Sent && message.id.startsWith("$")
    return MessageActionSet(
        reply = readable && onServer,
        copy = readable && message.text.isNotBlank(),
        edit = readable && message.own && message.editable && message.attachment == null,
        delete = message.own && !message.deleted && message.call == null,
        forward = readable && onServer && (message.text.isNotBlank() || message.attachment != null),
        react = readable && onServer,
        star = readable && onServer,
    )
}

/** On-screen bounds of a laid-out element, comparable across the app window and popups. */
internal fun LayoutCoordinates.screenBounds(): Rect =
    Rect(localToScreen(Offset.Zero), localToScreen(Offset(size.width.toFloat(), size.height.toFloat())))

private object FullWindowPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/**
 * Messages' long-press treatment: everything but the pressed bubble (at [bubbles], screen bounds) dims, the
 * reaction pill floats just above it and the action menu card sits just below. Tap outside or back dismisses.
 */
@Composable
internal fun MessageActionsOverlay(
    bubbles: List<Rect>,
    alignEnd: Boolean,
    actions: MessageActionSet,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onMoreReactions: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    /** Whether the message is starred: the Star action then reads Unstar. */
    starred: Boolean = false,
    onStar: () -> Unit = {},
    /** Starts multi-select with this message; null hides "Select more". */
    onSelectMore: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val anchor = bubbles.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
    val progress = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, SendMotion.effectsDefault()) }
        progress.animateTo(1f, SendMotion.spatialDefault())
    }
    Popup(
        popupPositionProvider = FullWindowPosition,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        var origin by remember { mutableStateOf<Offset?>(null) }
        val corner = with(density) { 20.dp.toPx() }
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.localToScreen(Offset.Zero) }
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .drawBehind {
                    val o = origin ?: return@drawBehind
                    val holes = Path().apply { bubbles.forEach { addRoundRect(RoundRect(it.translate(-o), CornerRadius(corner))) } }
                    clipPath(holes, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.4f * fade.value)) }
                },
            content = {
                val pillOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 1f)
                val menuOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)
                Box(Modifier.popIn(progress.value, fade.value, pillOrigin)) {
                    if (actions.react) ReactionRow(onReact = onReact, onMore = onMoreReactions, modifier = Modifier.fillMaxWidth())
                }
                Box(Modifier.popIn(progress.value, fade.value, menuOrigin)) {
                    MessageActionMenu(actions, onReply, onForward, onCopy, onEdit, onDelete, onInfo, starred, onStar, onSelectMore)
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            // Messages' pill spans the screen less a small margin.
            val pillWidth = (constraints.maxWidth - 2 * 8.dp.roundToPx()).coerceAtLeast(0)
            val pill = measurables[0].measure(loose.copy(minWidth = pillWidth, maxWidth = pillWidth))
            val menu = measurables[1].measure(loose)
            layout(constraints.maxWidth, constraints.maxHeight) {
                val o = origin ?: return@layout
                val hole = anchor.translate(-o)
                val gap = 8.dp.roundToPx()
                val margin = 12.dp.roundToPx()
                fun x(width: Int) = (if (alignEnd) hole.right.toInt() - width else hole.left.toInt())
                    .coerceIn(margin, (constraints.maxWidth - width - margin).coerceAtLeast(margin))
                // Menu below the bubble when it fits; otherwise above it, with the pill above the menu.
                val below = hole.bottom.toInt() + gap
                val menuBelow = below + menu.height <= constraints.maxHeight - margin
                val menuY = if (menuBelow) below else (hole.top.toInt() - gap - menu.height).coerceAtLeast(margin)
                val pillY = ((if (menuBelow) hole.top.toInt() else menuY) - gap - pill.height).coerceAtLeast(0)
                pill.place((constraints.maxWidth - pill.width) / 2, pillY)
                menu.place(x(menu.width), menuY)
            }
        }
    }
}

private fun Modifier.popIn(scale: Float, alpha: Float, origin: TransformOrigin) = graphicsLayer {
    val s = 0.8f + 0.2f * scale
    scaleX = s
    scaleY = s
    this.alpha = alpha
    transformOrigin = origin
}

/** Messages' rounded popup menu of message actions, each with a leading icon. */
@Composable
private fun MessageActionMenu(
    actions: MessageActionSet,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    starred: Boolean,
    onStar: () -> Unit,
    onSelectMore: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = 200.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (actions.reply) MessageActionItem(Icons.AutoMirrored.Filled.Reply, "Reply", onReply)
            if (actions.forward) MessageActionItem(Icons.AutoMirrored.Filled.Forward, "Forward", onForward)
            if (actions.copy) MessageActionItem(Icons.Outlined.ContentCopy, "Copy", onCopy)
            if (actions.star) MessageActionItem(if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline, if (starred) "Unstar" else "Star", onStar)
            if (actions.edit) MessageActionItem(Icons.Outlined.Edit, "Edit", onEdit)
            if (actions.delete) MessageActionItem(Icons.Outlined.Delete, "Delete", onDelete)
            if (onSelectMore != null) MessageActionItem(Icons.Outlined.CheckCircle, "Select more", onSelectMore)
            MessageActionItem(Icons.Outlined.Info, "Info", onInfo)
        }
    }
}

@Composable
private fun MessageActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
internal fun DeleteMessageDialog(onDelete: () -> Unit, onDismiss: () -> Unit, count: Int = 1) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete message?" else "Delete $count messages?") },
        text = {
            Text(
                if (count == 1) "This message will be deleted for everyone in this conversation."
                else "These messages will be deleted for everyone in this conversation.",
            )
        },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Top bar while several messages are selected ("Select more"): close, the count, and Copy, Forward and Delete when
 * they apply to every selected message.
 */
@Composable
internal fun MessageSelectionBar(
    count: Int,
    onClose: () -> Unit,
    onCopy: (() -> Unit)?,
    onForward: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Text(count.toString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (onCopy != null) IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy") }
            if (onForward != null) IconButton(onClick = onForward) { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = "Forward") }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
        }
    }
}

/** Messages' "Message details": sender, times, delivery and read receipts. */
@Composable
internal fun MessageInfoDialog(message: MatrixMessage, readers: Map<String, Long>, isDirect: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun time(millis: Long) = DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
    )
    val kind = when {
        message.deleted -> "Deleted message"
        message.decryptionFailure != null -> "Encrypted message (not decrypted yet)"
        message.attachment?.kind == AttachmentKind.Image -> "Photo"
        message.attachment?.kind == AttachmentKind.Video -> "Video"
        message.attachment?.kind == AttachmentKind.Voice -> "Voice message"
        message.attachment != null -> "File"
        else -> "Text message"
    }
    val rows = buildList {
        add("Type" to kind)
        add("From" to if (message.own) "You" else (message.senderName?.let { "$it (${message.sender})" } ?: message.sender))
        add((if (message.own) "Sent" else "Received") to time(message.timestampMillis))
        if (message.own) add("Status" to deliveryLabel(message, readers.size, isDirect))
        if (message.edited) add("Edited" to "Yes")
        add("Encryption" to "End-to-end encrypted")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (message.own && readers.isNotEmpty()) {
                    Column {
                        Text("Read by", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        readers.forEach { (user, at) ->
                            Text(if (at > 0) "$user · ${time(at)}" else user, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Strip above the composer while replying to or editing a message, with a cancel button. */
@Composable
internal fun ComposerContextBar(title: String, text: String, cancelDescription: String, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Row(
            Modifier.height(IntrinsicSize.Min).padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).heightIn(min = 40.dp).fillMaxHeight().background(colors.primary, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = cancelDescription) }
        }
    }
}

/** Short description of a message for reply/edit strips and the forward picker. */
internal fun messagePreviewText(message: MatrixMessage): String = when {
    message.deleted -> "Message deleted"
    message.text.isNotBlank() -> message.text
    message.attachment?.kind == AttachmentKind.Image -> "Photo"
    message.attachment?.kind == AttachmentKind.Video -> "Video"
    message.attachment?.kind == AttachmentKind.Voice -> "Voice message"
    message.attachment?.kind == AttachmentKind.Audio -> "Audio"
    message.attachment != null -> message.attachment.filename
    else -> "Message"
}

/** Top bar of in-conversation search: query field, "n of m", older/newer match and close. */
@Composable
internal fun ConversationSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    position: Int,
    count: Int,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search messages") },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (query.isNotBlank()) {
                Text(
                    if (count == 0) "No results" else "${position + 1} of $count",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOlder, enabled = query.isNotBlank()) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Older match") }
            IconButton(onClick = onNewer, enabled = count > 0 && position > 0) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Newer match") }
        }
    }
}

/** Forward (and share) picker: Messages' list rows for the encrypted conversations in [rooms]. */
@Composable
internal fun ForwardScreen(
    rooms: List<MatrixRoom>,
    mutedRoomIds: Set<String>,
    preview: String,
    onPick: (MatrixRoom) -> Unit,
    onBack: () -> Unit,
    title: String = "Forward to",
    emptyText: String = "No other encrypted conversations.",
) {
    BackHandler(onBack = onBack)
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.surfaceContainer,
        topBar = {
            Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.padding(start = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(preview, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.surface,
        ) {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                if (rooms.isEmpty()) {
                    item {
                        Text(
                            emptyText,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    }
                }
                items(rooms, key = { it.id }) { room ->
                    ConversationRow(room = room, muted = room.id in mutedRoomIds, onClick = { onPick(room) })
                }
            }
        }
    }
}

/**
 * Messages' locked voice recording panel above the composer: red dot and timer, a live level line, then Restart,
 * a red Stop pill and Attach. Once stopped, the pill plays the clip back over its waveform; Attach puts the clip
 * in the composer for the round send button.
 */
@Composable
internal fun LockedRecordingPanel(
    elapsedMillis: Long,
    levels: List<Float>,
    clip: VoiceRecording?,
    playback: VoicePlayback,
    playing: Boolean,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onTogglePlay: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(playing) {
        while (playing) {
            playback.tick()
            delay(50)
        }
    }
    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (clip == null) {
                        Box(Modifier.size(12.dp).background(colors.error, CircleShape))
                        Spacer(Modifier.width(10.dp))
                    }
                    val shown = when {
                        clip == null -> elapsedMillis
                        playing -> playback.positionMillis
                        else -> clip.durationMillis
                    }
                    val seconds = shown / 1000
                    Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
                }
                Spacer(Modifier.height(16.dp))
                val waveformModifier = Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = "Audio recording waveform" }
                if (clip == null) {
                    LevelDots(levels, colors.onSurface, waveformModifier)
                } else {
                    Waveform(
                        samples = clip.waveform,
                        progress = if (playing) playback.progress else 0f,
                        played = colors.primary,
                        pending = colors.outline,
                        modifier = waveformModifier,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Messages: three equal pills in their own row under the panel.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pill = Modifier.weight(1f).height(56.dp)
            FilledTonalButton(onClick = onRestart, modifier = pill) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Restart")
            }
            Button(
                onClick = if (clip == null) onStop else onTogglePlay,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                modifier = pill,
            ) {
                when {
                    clip == null -> Icon(Icons.Filled.Stop, contentDescription = "Stop recording")
                    playing -> Icon(Icons.Filled.Pause, contentDescription = "Pause recording")
                    else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Play recording")
                }
            }
            Button(onClick = onAttach, modifier = pill) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Attach")
            }
        }
    }
}

/** The live microphone level as Messages' dotted line: one dot per slot, stretched into a bar by louder samples, newest on the right. */
@Composable
private fun LevelDots(levels: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val dot = 3.dp.toPx()
        val step = size.width / LIVE_LEVELS
        val mid = size.height / 2f
        val offset = LIVE_LEVELS - levels.size
        for (slot in 0 until LIVE_LEVELS) {
            val level = levels.getOrNull(slot - offset) ?: 0f
            val height = (size.height * level.coerceIn(0f, 1f)).coerceAtLeast(dot)
            val x = step * slot + (step - dot) / 2f
            drawRoundRect(color, topLeft = Offset(x, mid - height / 2f), size = Size(dot, height), cornerRadius = CornerRadius(dot / 2f))
        }
    }
}

/** How many recent levels [LevelDots] shows across its width. */
internal const val LIVE_LEVELS = 56

/** A finished clip attached to the composer, sent with the round send button. */
@Composable
internal fun AttachedRecordingBar(clip: VoiceRecording, playing: Boolean, onDelete: () -> Unit, onTogglePlay: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete recording", tint = colors.onSurfaceVariant) }
        IconButton(onClick = onTogglePlay) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause recording" else "Play recording",
            )
        }
        Text("Voice message · ${formatDuration(clip.durationMillis)}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
    }
}
