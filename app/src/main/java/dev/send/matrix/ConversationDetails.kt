package dev.send.matrix

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val ADD_PEOPLE_INPUT_TAG = "addPeopleInput"
internal const val DETAILS_TEXT_INPUT_TAG = "detailsTextInput"
internal const val DETAILS_LIST_TAG = "detailsList"
internal const val MEDIA_GRID_TAG = "mediaGrid"
internal const val PERSON_SHEET_TAG = "personSheet"

/**
 * What conversation details can do, bound to one room by SendApp (fixtures pass fakes). Suspending actions report
 * failures by throwing; the others close the conversation or open another one themselves.
 */
internal class ConversationDetailsActions(
    val load: suspend () -> RoomDetails,
    val setNotifications: suspend (NotificationLevel) -> Unit,
    val invite: suspend (userId: String) -> Unit,
    val rename: suspend (String) -> Unit,
    val setTopic: suspend (String) -> Unit,
    val setAvatar: suspend (Uri) -> Unit,
    /** Archives the conversation and returns to the list. */
    val archive: () -> Unit,
    /** DM: delete (leave + forget); group: leave the group. Returns to the list. */
    val delete: () -> Unit,
    /** Blocks someone (a DM's other person also leaves the list with the conversation). */
    val block: (userId: String) -> Unit,
    /** Opens (or starts) the encrypted DM with someone. */
    val message: (userId: String) -> Unit,
)

private enum class DetailsDialog { Notifications, Rename, Topic, AddPeople, Block, Delete }

/**
 * Messages' conversation Details: large avatar and name, call/search/mute actions, notifications, people (groups),
 * media, starred messages, encryption state, and archive / block / delete or leave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationDetailsScreen(
    /** Name shown until details load (the conversation's title). */
    title: String,
    isDirect: Boolean,
    avatarUrl: String?,
    actions: ConversationDetailsActions,
    /** Changes whenever the room changes on the server (its list entry); details reload then. */
    refreshKey: Any?,
    muted: Boolean,
    onToggleMute: (() -> Unit)?,
    /** Messages with attachments in the loaded timeline, newest first. */
    media: List<MatrixMessage>,
    loadMedia: MediaLoader,
    onCall: (video: Boolean) -> Unit,
    onSearch: () -> Unit,
    onStarred: (() -> Unit)?,
    onMedia: () -> Unit,
    onOpenMedia: (MatrixMessage) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var reload by remember { mutableIntStateOf(0) }
    var details by remember { mutableStateOf<RoomDetails?>(null) }
    var loadError by remember { mutableStateOf("") }
    LaunchedEffect(refreshKey, muted, reload) {
        try {
            details = actions.load()
            loadError = ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: "Details couldn't be loaded."
        }
    }
    var dialog by remember { mutableStateOf<DetailsDialog?>(null) }
    var person by remember { mutableStateOf<RoomPerson?>(null) }
    var blockTarget by remember { mutableStateOf<RoomPerson?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf("") }
    /** Runs a details change; [done] closes its dialog, a failure stays in the dialog (or the snackbar). */
    fun change(failure: String, done: String? = null, action: suspend () -> Unit) {
        scope.launch {
            busy = true
            dialogError = ""
            val message = try {
                action()
                dialog = null
                reload++
                done
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                dialogError = problem.message ?: failure
                dialogError.takeIf { dialog == null }
            } finally {
                busy = false
            }
            // The snackbar outlives the change; the page is usable again meanwhile.
            if (message != null) snackbar.showSnackbar(message)
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) change("The group photo couldn't be changed.", "Group photo updated") { actions.setAvatar(uri) }
    }
    val leaving = rememberPredictiveBack(onBack = onBack)
    val shown = details
    val name = shown?.name ?: title
    val direct = shown?.direct ?: isDirect
    Scaffold(
        modifier = Modifier.predictiveBack(leaving),
        containerColor = colors.surfaceContainer,
        // Messages: back arrow only; the large header names the conversation.
        topBar = { ListScreenTopBar("", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag(DETAILS_LIST_TAG),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        Avatar(name = name, size = 96.dp, url = shown?.avatarUrl ?: avatarUrl)
                        if (shown?.canSetAvatar == true && !direct) {
                            FilledTonalIconButton(
                                onClick = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                enabled = !busy,
                                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp).size(48.dp),
                            ) { Icon(Icons.Outlined.CameraAlt, contentDescription = "Change group photo") }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(name, style = MaterialTheme.typography.headlineMedium, color = colors.onPrimaryContainer, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    val subtitle = when {
                        shown == null -> null
                        direct -> shown.dmUserId
                        else -> shown.people.size.let { if (it == 1) "1 person" else "$it people" }
                    }
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    shown?.topic?.let { topic ->
                        Text(
                            topic,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DetailsAction(Icons.Outlined.Call, "Voice call") { onCall(false) }
                        DetailsAction(Icons.Outlined.Videocam, "Video call") { onCall(true) }
                        DetailsAction(Icons.Outlined.Search, "Search", onSearch)
                        if (onToggleMute != null) {
                            DetailsAction(if (muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff, if (muted) "Unmute" else "Mute", onToggleMute)
                        }
                    }
                }
            }
            if (shown == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        if (loadError.isBlank()) CircularProgressIndicator() else Text(loadError, color = colors.error)
                    }
                }
            } else {
                item(key = "settings") {
                    SettingsGroup {
                        row {
                            SettingsRow("Notifications", shown.notifications.label, icon = Icons.Outlined.Notifications, onClick = { dialogError = ""; dialog = DetailsDialog.Notifications })
                        }
                        if (!direct && shown.canRename) {
                            row { SettingsRow("Group name", shown.name, icon = Icons.Outlined.Edit, onClick = { dialogError = ""; dialog = DetailsDialog.Rename }) }
                        }
                        if (!direct && shown.canSetTopic) {
                            row {
                                SettingsRow("Description", shown.topic ?: "Add a description", icon = Icons.Outlined.ChatBubbleOutline, onClick = { dialogError = ""; dialog = DetailsDialog.Topic })
                            }
                        }
                    }
                }
                if (!direct) {
                    item(key = "people-label") { DetailsSectionLabel("People") }
                    item(key = "people") {
                        SettingsGroup {
                            if (shown.canInvite) {
                                row { SettingsRow("Add people", icon = Icons.Outlined.PersonAdd, onClick = { dialogError = ""; dialog = DetailsDialog.AddPeople }) }
                            }
                            shown.people.forEach { member ->
                                row { PersonRow(member, onClick = if (member.isMe) null else ({ person = member })) }
                            }
                        }
                    }
                }
                item(key = "media") {
                    SettingsGroup {
                        row {
                            Column(Modifier.fillMaxWidth().clickable(onClick = onMedia)) {
                                SettingsRow(
                                    "Media & files",
                                    if (media.isEmpty()) "None in loaded messages" else if (media.size == 1) "1 item" else "${media.size} items",
                                    icon = Icons.Outlined.PermMedia,
                                )
                                if (media.isNotEmpty()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        media.take(4).forEach { message ->
                                            val attachment = message.attachment ?: return@forEach
                                            MediaTile(
                                                attachment,
                                                loadMedia,
                                                Modifier.weight(1f).aspectRatio(1f).clickable { onOpenMedia(message) },
                                            )
                                        }
                                        repeat(4 - media.take(4).size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                        if (onStarred != null) row { SettingsRow("Starred messages", icon = Icons.Outlined.StarOutline, onClick = onStarred) }
                    }
                }
                item(key = "encryption") {
                    SettingsGroup {
                        row {
                            SettingsRow(
                                "End-to-end encrypted",
                                encryptionSummary(shown, name),
                                icon = Icons.Outlined.Lock,
                            )
                        }
                    }
                }
                item(key = "actions") {
                    SettingsGroup {
                        row { SettingsRow("Archive", icon = Icons.Outlined.Archive, onClick = actions.archive) }
                        val other = shown.dmUserId
                        if (direct && other != null) {
                            row {
                                SettingsRow("Block", icon = Icons.Outlined.Block, titleColor = colors.error, onClick = {
                                    blockTarget = shown.people.firstOrNull { it.userId == other } ?: RoomPerson(other, name, null)
                                    dialog = DetailsDialog.Block
                                })
                            }
                        }
                        row {
                            SettingsRow(
                                if (direct) "Delete conversation" else "Leave group",
                                icon = if (direct) Icons.Outlined.Delete else Icons.AutoMirrored.Outlined.Logout,
                                titleColor = colors.error,
                                onClick = { dialog = DetailsDialog.Delete },
                            )
                        }
                    }
                }
            }
        }
    }

    val current = details
    when (dialog) {
        DetailsDialog.Notifications -> if (current != null) {
            ChoiceDialog(
                title = "Notifications",
                options = NotificationLevel.entries.map { it.label },
                summaries = listOf("Every new message", "Only when someone mentions you", "No notifications from this conversation"),
                selected = current.notifications.ordinal,
                onSelect = { index ->
                    val level = NotificationLevel.entries[index]
                    dialog = null
                    change("Notifications couldn't be changed.") { actions.setNotifications(level) }
                },
                onDismiss = { dialog = null },
            )
        }
        DetailsDialog.Rename -> TextEntryDialog(
            title = "Group name",
            initial = current?.name.orEmpty(),
            label = "Name",
            confirm = "Save",
            busy = busy,
            error = dialogError,
            onConfirm = { value -> change("The group couldn't be renamed.", "Group renamed") { actions.rename(value) } },
            onDismiss = { dialog = null },
        )
        DetailsDialog.Topic -> TextEntryDialog(
            title = "Description",
            initial = current?.topic.orEmpty(),
            label = "Description",
            confirm = "Save",
            busy = busy,
            error = dialogError,
            allowBlank = true,
            onConfirm = { value -> change("The description couldn't be changed.", "Description updated") { actions.setTopic(value) } },
            onDismiss = { dialog = null },
        )
        DetailsDialog.AddPeople -> TextEntryDialog(
            title = "Add people",
            initial = "",
            label = "Matrix ID",
            placeholder = "@name:example.org",
            confirm = "Invite",
            busy = busy,
            error = dialogError,
            supporting = "They'll get an invitation to join this group. Messages sent before they join stay unreadable to them.",
            keyboardType = KeyboardType.Email,
            inputTag = ADD_PEOPLE_INPUT_TAG,
            validate = { if (isMatrixUserId(it.trim())) null else "Enter a Matrix ID such as @alice:example.org." },
            onConfirm = { value -> change("They couldn't be invited.", "Invited ${value.trim()}") { actions.invite(value.trim()) } },
            onDismiss = { dialog = null },
        )
        DetailsDialog.Block -> blockTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Block ${target.name}?") },
                text = {
                    Text(
                        "You won't receive calls or messages from ${target.userId} on any of your devices, and your conversations " +
                            "with them leave your list. Unblock them any time from Spam & blocked.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        person = null
                        actions.block(target.userId)
                    }) { Text("Yes") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        DetailsDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (direct) "Delete this conversation?" else "Leave this group?") },
            text = {
                Text(
                    if (direct) "You'll leave this conversation and it will be removed from your list on all your devices."
                    else "You'll stop getting its messages, and it will be removed from your list on all your devices.",
                )
            },
            confirmButton = {
                TextButton(onClick = { dialog = null; actions.delete() }) { Text(if (direct) "Delete" else "Leave") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }

    person?.let { member ->
        ModalBottomSheet(onDismissRequest = { person = null }) {
            PersonSheetContent(
                person = member,
                onMessage = {
                    person = null
                    actions.message(member.userId)
                },
                onBlock = {
                    blockTarget = member
                    dialog = DetailsDialog.Block
                },
            )
        }
    }
}

/** Line under "End-to-end encrypted": the other person's identity in a DM, the group's scope otherwise. */
private fun encryptionSummary(details: RoomDetails, name: String): String = when {
    !details.direct -> "Messages and calls are encrypted for the people in this group"
    details.dmIdentity == IdentityState.Verified -> "You verified $name's identity"
    details.dmIdentity == IdentityState.Changed -> "$name's identity changed since you verified it"
    details.dmIdentity == IdentityState.NotVerified -> "$name's identity isn't verified"
    else -> "Messages and calls are encrypted"
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DetailsAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    // Messages: equal-width tonal pills with the label underneath.
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Surface(
            onClick = onClick,
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label) }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
    }
}

@Composable
private fun DetailsSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp),
    )
}

private fun roleLabel(person: RoomPerson): String? = when {
    person.invited -> "Invited"
    person.role == PersonRole.Admin -> "Admin"
    person.role == PersonRole.Moderator -> "Moderator"
    else -> null
}

@Composable
private fun PersonRow(person: RoomPerson, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = person.name, size = 40.dp, url = person.avatarUrl)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(if (person.isMe) "You" else person.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(person.userId.takeIf { person.displayName != null || person.isMe }, roleLabel(person)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A group member's card (Details → People → someone): avatar, name, Matrix ID, Message and Block. */
@Composable
internal fun PersonSheetContent(person: RoomPerson, onMessage: () -> Unit, onBlock: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp).testTag(PERSON_SHEET_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(name = person.name, size = 80.dp, url = person.avatarUrl)
        Text(person.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Text(
            listOfNotNull(person.userId, roleLabel(person)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onMessage) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Message")
            }
            OutlinedButton(onClick = onBlock) {
                Icon(Icons.Outlined.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Block")
            }
        }
    }
}

/** One-field dialog for group name, description and Add people. */
@Composable
internal fun TextEntryDialog(
    title: String,
    initial: String,
    label: String,
    confirm: String,
    busy: Boolean,
    error: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String? = null,
    supporting: String? = null,
    allowBlank: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    inputTag: String = DETAILS_TEXT_INPUT_TAG,
    validate: (String) -> String? = { null },
) {
    var value by remember(initial) { mutableStateOf(initial) }
    var showValidation by remember { mutableStateOf(false) }
    val problem = validate(value)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; showValidation = false },
                    label = { Text(label) },
                    placeholder = if (placeholder != null) {
                        { Text(placeholder) }
                    } else null,
                    singleLine = true,
                    enabled = !busy,
                    isError = error.isNotBlank() || (showValidation && problem != null),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag(inputTag),
                )
                val message = error.ifBlank { if (showValidation) problem.orEmpty() else "" }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (problem != null) showValidation = true else onConfirm(value) },
                enabled = !busy && (allowBlank || value.isNotBlank()) && value != initial,
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** Details → Media & files: every attachment in the loaded messages, newest first; tap to view or open. */
@Composable
internal fun MediaGridScreen(
    media: List<MatrixMessage>,
    loadMedia: MediaLoader,
    onOpen: (MatrixMessage) -> Unit,
    onLoadOlder: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val leaving = rememberPredictiveBack(onBack = onBack)
    Scaffold(
        modifier = Modifier.predictiveBack(leaving),
        containerColor = colors.surfaceContainer,
        topBar = { ListScreenTopBar("Media & files", onBack) },
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.surface,
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.testTag(MEDIA_GRID_TAG),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (media.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyListText("Photos, videos and files in this conversation appear here.")
                    }
                }
                items(media, key = { it.id }) { message ->
                    val attachment = message.attachment ?: return@items
                    MediaTile(attachment, loadMedia, Modifier.fillMaxWidth().aspectRatio(1f).clickable { onOpen(message) })
                }
                item(key = "older", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onLoadOlder) { Text("Load older messages") }
                    }
                }
            }
        }
    }
}
