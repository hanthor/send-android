package dev.send.matrix

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
internal val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** The floating emoji row over a selected message; the trailing button opens the full emoji picker. */
@Composable
internal fun ReactionRow(onReact: (String) -> Unit, onMore: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuickReactions.forEach { emoji ->
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onReact(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 24.sp)
                }
            }
            IconButton(onClick = onMore, modifier = Modifier.size(44.dp)) {
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
    )
}

/** Messages' contextual top bar while a message is selected: close on the left, actions on the right. */
@Composable
internal fun MessageActionBar(
    actions: MessageActionSet,
    onClose: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onForward: () -> Unit,
    onInfo: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Text("1", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (actions.reply) IconButton(onClick = onReply) { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply") }
            if (actions.copy) IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy") }
            if (actions.edit) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
            if (actions.forward) IconButton(onClick = onForward) { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = "Forward") }
            if (actions.delete) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
            IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, contentDescription = "Info") }
        }
    }
}

@Composable
internal fun DeleteMessageDialog(onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete message?") },
        text = { Text("This message will be deleted for everyone in this conversation.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
        Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(40.dp).background(colors.primary, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
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

/** Forward picker: Messages' list rows for every encrypted conversation except the current one. */
@Composable
internal fun ForwardScreen(rooms: List<MatrixRoom>, mutedRoomIds: Set<String>, preview: String, onPick: (MatrixRoom) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.surfaceContainer,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.padding(start = 8.dp)) {
                    Text("Forward to", style = MaterialTheme.typography.titleLarge)
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
                            "No other encrypted conversations.",
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
 * Messages' locked voice recording in the composer: delete on the left, a running timer, and stop; once stopped,
 * the clip can be played back before it is sent with the round send button.
 */
@Composable
internal fun LockedRecordingBar(
    elapsedMillis: Long,
    clip: VoiceRecording?,
    playing: Boolean,
    onDelete: () -> Unit,
    onStop: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete recording", tint = colors.onSurfaceVariant) }
        if (clip == null) {
            Box(Modifier.size(8.dp).background(colors.error, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(formatDuration(elapsedMillis), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            Spacer(Modifier.width(8.dp))
            Text("Recording", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, contentDescription = "Stop recording", tint = colors.error) }
        } else {
            IconButton(onClick = onTogglePlay) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause recording" else "Play recording",
                )
            }
            Text("Voice message · ${formatDuration(clip.durationMillis)}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
        }
    }
}
