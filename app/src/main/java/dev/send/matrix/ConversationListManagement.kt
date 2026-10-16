package dev.send.matrix

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the conversation list can do to conversations: swipe, the selection bar and its confirmations. */
internal class ConversationListActions(
    val setArchived: (rooms: List<MatrixRoom>, archived: Boolean) -> Unit,
    val setPinned: (rooms: List<MatrixRoom>, pinned: Boolean) -> Unit,
    val setUnread: (rooms: List<MatrixRoom>, unread: Boolean) -> Unit,
    val setMuted: (rooms: List<MatrixRoom>, muted: Boolean) -> Unit,
    /** Leaves and forgets the conversations (after the user confirmed). */
    val delete: (rooms: List<MatrixRoom>) -> Unit,
    /** Blocks the other person of a DM (after the user confirmed). */
    val block: (userId: String) -> Unit,
)

/** Unread for the list: new messages, or marked unread by the user. */
internal val MatrixRoom.isUnread: Boolean get() = unreadCount > 0 || markedUnread

private fun MatrixRoom.blockedBy(blockedUserIds: Set<String>): Boolean = dmUserId != null && dmUserId in blockedUserIds

/** The main list: encrypted, not archived, no blocked person's DM; pinned first, otherwise the SDK's recency order. */
internal fun inboxConversations(rooms: List<MatrixRoom>, blockedUserIds: Set<String>): List<MatrixRoom> =
    rooms.filter { it.encrypted && !it.archived && !it.blockedBy(blockedUserIds) }.sortedByDescending { it.pinned }

/** The Archived screen's list, in the SDK's recency order. */
internal fun archivedConversations(rooms: List<MatrixRoom>, blockedUserIds: Set<String>): List<MatrixRoom> =
    rooms.filter { it.encrypted && it.archived && !it.blockedBy(blockedUserIds) }

private fun conversationCount(count: Int, what: String) = if (count == 1) "1 conversation $what" else "$count conversations $what"

/** Selection, pending confirmations and the undo snackbar of one conversation list. */
@Stable
internal class ConversationListState(private val scope: CoroutineScope) {
    val snackbar = SnackbarHostState()
    /** Selected conversation IDs, in the order they were picked. */
    var selectedIds by mutableStateOf<List<String>>(emptyList())
    var confirmDelete by mutableStateOf<List<MatrixRoom>?>(null)
    var confirmBlock by mutableStateOf<MatrixRoom?>(null)
    val selecting: Boolean get() = selectedIds.isNotEmpty()

    fun toggle(room: MatrixRoom) {
        selectedIds = if (room.id in selectedIds) selectedIds - room.id else selectedIds + room.id
    }

    fun clearSelection() {
        selectedIds = emptyList()
    }

    /** Archives (or unarchives) [rooms] and offers Undo, which also re-pins what archiving unpinned. */
    fun archive(rooms: List<MatrixRoom>, archived: Boolean, actions: ConversationListActions) {
        if (rooms.isEmpty()) return
        clearSelection()
        actions.setArchived(rooms, archived)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                conversationCount(rooms.size, if (archived) "archived" else "unarchived"),
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                actions.setArchived(rooms, !archived)
                if (archived) rooms.filter { it.pinned }.takeIf { it.isNotEmpty() }?.let { actions.setPinned(it, true) }
            }
        }
    }
}

@Composable
internal fun rememberConversationListState(): ConversationListState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ConversationListState(scope) }
}

/**
 * Scroll state for a conversation list that stays at the top while rows move up (a new message, a pin, a new
 * conversation) and the user left it at the top, as in Messages; LazyColumn would otherwise keep its first visible
 * row in place and push the new one above the viewport. [topKey] is the first row's key.
 *
 * Where the user left the list is recorded when a scroll ends (row insertions don't scroll), and the list returns
 * to the top after layout. Requesting the position during composition isn't safe here: the list is subcomposed
 * inside Scaffold's measure pass, and `requestScrollToItem` forces a remeasure.
 */
@Composable
internal fun rememberTopAnchoredListState(topKey: Any?): LazyListState {
    val state = rememberLazyListState()
    var leftAtTop by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) leftAtTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
        }
    }
    LaunchedEffect(topKey) {
        // scrollToItem forgets the remembered first-row key, so this holds whether or not the new layout ran yet.
        if (leftAtTop) state.scrollToItem(0)
    }
    return state
}

/**
 * Conversation rows with Messages' list gestures: swipe either way archives ([archivedView]: unarchives), long-press
 * starts selection and taps then add or remove rows. Without [actions] the rows only open.
 */
internal fun LazyListScope.managedConversationRows(
    rooms: List<MatrixRoom>,
    mutedRoomIds: Set<String>,
    state: ConversationListState,
    actions: ConversationListActions?,
    archivedView: Boolean,
    onOpen: (MatrixRoom) -> Unit,
    /** The conversation open beside the list (two panes), highlighted. */
    openRoomId: String? = null,
) {
    items(rooms, key = { it.id }) { room ->
        val selecting = state.selecting
        Box(sendItemPlacement()) {
            SwipeToArchive(
                enabled = actions != null && !selecting,
                unarchive = archivedView,
                onSwiped = { if (actions != null) state.archive(listOf(room), archived = !archivedView, actions) },
            ) { lifted ->
                ConversationRow(
                    room = room,
                    muted = room.id in mutedRoomIds,
                    selected = room.id in state.selectedIds,
                    open = room.id == openRoomId,
                    lifted = lifted,
                    onClick = { if (selecting) state.toggle(room) else onOpen(room) },
                    onLongClick = if (actions != null) ({ state.toggle(room) }) else null,
                )
            }
        }
    }
}

/** Keeps the selection to rooms still on screen (another device may archive or delete one meanwhile). */
@Composable
internal fun PruneSelection(state: ConversationListState, rooms: List<MatrixRoom>) {
    LaunchedEffect(rooms) {
        val visible = rooms.mapTo(HashSet()) { it.id }
        if (state.selectedIds.any { it !in visible }) state.selectedIds = state.selectedIds.filter { it in visible }
    }
    BackHandler(enabled = state.selecting) { state.clearSelection() }
}

/**
 * Messages' conversation swipe: the row lifts onto a tinted pill and follows the finger either way over a
 * primary-coloured track whose archive glyph grows in on the uncovered side; a haptic tick marks the threshold.
 * Released past it (or flung), the row springs off-screen and [onSwiped] runs; otherwise it springs back.
 */
@Composable
internal fun SwipeToArchive(enabled: Boolean, unarchive: Boolean, onSwiped: () -> Unit, content: @Composable (lifted: Boolean) -> Unit) {
    val swipeThreshold = rememberSwipeThreshold()
    val density = LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    val swiped by rememberUpdatedState(onSwiped)
    val threshold = width * ARCHIVE_SWIPE_FRACTION
    val flingVelocity = with(density) { ARCHIVE_FLING_VELOCITY.toPx() }
    val dragState = rememberDraggableState { delta ->
        offset = (offset + delta).coerceIn(-width.toFloat(), width.toFloat())
        swipeThreshold.update(width > 0 && abs(offset) >= threshold)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStopped = { velocity ->
                    val flung = abs(velocity) > flingVelocity && sign(velocity) == sign(offset) && abs(offset) > threshold / 3f
                    val target = if (swipeThreshold.release() || flung) sign(offset) * width else 0f
                    animate(offset, target, initialVelocity = velocity, animationSpec = SendMotion.swipeSettle()) { value, _ -> offset = value }
                    if (target != 0f) swiped()
                },
            ),
    ) {
        if (offset != 0f) {
            val colors = MaterialTheme.colorScheme
            val progress = if (threshold > 0f) (abs(offset) / threshold).coerceIn(0f, 1f) else 0f
            Box(
                Modifier.matchParentSize().clip(RoundedCornerShape(24.dp)).background(colors.primary),
                contentAlignment = if (offset > 0f) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    if (unarchive) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    contentDescription = if (unarchive) "Unarchive" else "Archive",
                    tint = colors.onPrimary,
                    modifier = Modifier.padding(horizontal = 24.dp).graphicsLayer {
                        val scale = 0.7f + 0.3f * progress
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.4f + 0.6f * progress
                    },
                )
            }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }) { content(offset != 0f) }
    }
}

/** Fraction of the row width past which a released swipe archives. */
private const val ARCHIVE_SWIPE_FRACTION = 0.35f
private val ARCHIVE_FLING_VELOCITY = 600.dp

/**
 * Messages' contextual top bar while conversations are selected: close, the count, Pin, Archive and Delete, with
 * Mark as read/unread, Mute and (for one DM) Block under ⋮ as plain text items. The Archived screen offers
 * Unarchive and no Pin.
 */
@Composable
internal fun ConversationSelectionBar(
    selected: List<MatrixRoom>,
    mutedRoomIds: Set<String>,
    state: ConversationListState,
    actions: ConversationListActions,
    archivedView: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    var showMenu by remember { mutableStateOf(false) }
    Surface(color = colors.surfaceContainer, modifier = Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = state::clearSelection) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Text(
                selected.size.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            if (!archivedView) {
                val unpin = selected.isNotEmpty() && selected.all { it.pinned }
                IconButton(onClick = { actions.setPinned(selected, !unpin); state.clearSelection() }) {
                    Icon(if (unpin) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = if (unpin) "Unpin" else "Pin")
                }
            }
            IconButton(onClick = { state.archive(selected, archived = !archivedView, actions) }) {
                Icon(
                    if (archivedView) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    contentDescription = if (archivedView) "Unarchive" else "Archive",
                )
            }
            IconButton(onClick = { state.confirmDelete = selected }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    val markRead = selected.any { it.isUnread }
                    DropdownMenuItem(
                        text = { Text(if (markRead) "Mark as read" else "Mark as unread") },
                        onClick = { showMenu = false; actions.setUnread(selected, !markRead); state.clearSelection() },
                    )
                    val unmute = selected.isNotEmpty() && selected.all { it.id in mutedRoomIds }
                    DropdownMenuItem(
                        text = { Text(if (unmute) "Unmute" else "Mute") },
                        onClick = { showMenu = false; actions.setMuted(selected, !unmute); state.clearSelection() },
                    )
                    val dm = selected.singleOrNull()?.takeIf { it.dmUserId != null }
                    if (dm != null) {
                        DropdownMenuItem(
                            text = { Text("Block") },
                            onClick = { showMenu = false; state.confirmBlock = dm },
                        )
                    }
                }
            }
        }
    }
}

/** Delete and Block confirmations for a conversation list. */
@Composable
internal fun ConversationListDialogs(state: ConversationListState, actions: ConversationListActions) {
    state.confirmDelete?.let { rooms ->
        AlertDialog(
            onDismissRequest = { state.confirmDelete = null },
            title = { Text(if (rooms.size == 1) "Delete 1 conversation?" else "Delete ${rooms.size} conversations?") },
            text = {
                Text(
                    if (rooms.size == 1) "You'll leave this conversation and it will be removed from your list on all your devices."
                    else "You'll leave these conversations and they will be removed from your list on all your devices.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.confirmDelete = null
                    state.clearSelection()
                    actions.delete(rooms)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { state.confirmDelete = null }) { Text("Cancel") } },
        )
    }
    state.confirmBlock?.let { room ->
        val userId = room.dmUserId ?: return@let
        // Messages' wording: "Block …?", what blocking does, Cancel / Yes.
        AlertDialog(
            onDismissRequest = { state.confirmBlock = null },
            title = { Text("Block ${room.name}?") },
            text = {
                Text(
                    "You won't receive calls or messages from $userId on any of your devices, and your conversations " +
                        "with them leave your list. Unblock them any time from Blocked contacts.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.confirmBlock = null
                    state.clearSelection()
                    actions.block(userId)
                }) { Text("Yes") }
            },
            dismissButton = { TextButton(onClick = { state.confirmBlock = null }) { Text("Cancel") } },
        )
    }
}

/** Messages' Archived screen (account menu → Archived): swipe or select to unarchive, tap to open. */
@Composable
internal fun ArchivedScreen(
    rooms: List<MatrixRoom>,
    mutedRoomIds: Set<String>,
    actions: ConversationListActions,
    onOpen: (MatrixRoom) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val state = rememberConversationListState()
    val leaving = rememberPredictiveBack(onBack = onBack)
    PruneSelection(state, rooms)
    val selected = state.selectedIds.mapNotNull { id -> rooms.firstOrNull { it.id == id } }
    Scaffold(
        modifier = Modifier.predictiveBack(leaving),
        containerColor = colors.surfaceContainer,
        snackbarHost = { SnackbarHost(state.snackbar) },
        topBar = {
            if (state.selecting) {
                ConversationSelectionBar(selected, mutedRoomIds, state, actions, archivedView = true)
            } else {
                ListScreenTopBar("Archived", onBack)
            }
        },
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.surface,
        ) {
            LazyColumn(state = rememberTopAnchoredListState(rooms.firstOrNull()?.id), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                if (rooms.isEmpty()) {
                    // Messages centres this line on the empty sheet.
                    item(key = "empty") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyListText("Archived conversations appear here")
                        }
                    }
                }
                managedConversationRows(rooms, mutedRoomIds, state, actions, archivedView = true, onOpen = onOpen)
            }
        }
    }
    ConversationListDialogs(state, actions)
}

/** Account menu → Blocked contacts: the account's ignored users, each with Unblock. */
@Composable
internal fun BlockedContactsScreen(userIds: List<String>, onUnblock: (String) -> Unit, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val leaving = rememberPredictiveBack(onBack = onBack)
    Scaffold(
        modifier = Modifier.predictiveBack(leaving),
        containerColor = colors.surfaceContainer,
        topBar = { ListScreenTopBar("Blocked contacts", onBack) },
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.surface,
        ) {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                item(key = "about") {
                    Text(
                        "You won't receive calls or messages from blocked contacts on any of your devices.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
                if (userIds.isEmpty()) {
                    item(key = "empty") { EmptyListText("No blocked contacts. Block someone from a conversation's details or selection menu.") }
                }
                items(userIds, key = { it }) { userId ->
                    Row(
                        sendItemPlacement().fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val name = userId.removePrefix("@").substringBefore(':')
                        Avatar(name = name, size = 52.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(userId, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onUnblock(userId) }) { Text("Unblock") }
                    }
                }
            }
        }
    }
}

/**
 * Conversation ⋮ → Starred: this conversation's starred messages, most recently starred first. Tapping one jumps to
 * it in the thread; the star unstars it.
 */
@Composable
internal fun StarredMessagesScreen(
    title: String,
    isDirect: Boolean,
    /** Starred event IDs; the list reloads when they change. */
    starredIds: Set<String>,
    load: suspend () -> List<StarredMessage>,
    onOpen: (eventId: String) -> Unit,
    onUnstar: (eventId: String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val leaving = rememberPredictiveBack(onBack = onBack)
    val loaded by produceState<Result<List<StarredMessage>>?>(null, starredIds) {
        value = runCatching { load() }
    }
    Scaffold(
        modifier = Modifier.predictiveBack(leaving),
        containerColor = colors.surfaceContainer,
        topBar = { ListScreenTopBar("Starred", onBack) },
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.surface,
        ) {
            val result = loaded
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                when {
                    result == null -> item(key = "loading") { EmptyListText("Loading starred messages…") }
                    result.isFailure -> item(key = "error") {
                        EmptyListText(result.exceptionOrNull()?.message ?: "Starred messages could not be loaded.")
                    }
                    result.getOrThrow().isEmpty() -> item(key = "empty") {
                        EmptyListText("No starred messages. Touch and hold a message, then tap Star.")
                    }
                    else -> items(result.getOrThrow(), key = { it.eventId }) { starred ->
                        val sender = when {
                            starred.own -> "You"
                            isDirect -> title
                            else -> starred.sender.ifBlank { title }
                        }
                        Row(
                            sendItemPlacement().fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(28.dp))
                                .clickable { onOpen(starred.eventId) }.padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(name = sender, size = 40.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(sender, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                                    starred.timestampMillis?.let { time ->
                                        Text(
                                            " · " + DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colors.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Text(
                                    starred.text ?: "Message unavailable",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (starred.text == null) colors.onSurfaceVariant else colors.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onUnstar(starred.eventId) }) {
                                Icon(Icons.Filled.Star, contentDescription = "Unstar", tint = colors.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Back arrow and title, as on Messages' secondary list screens. */
@Composable
internal fun ListScreenTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun EmptyListText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
    )
}
