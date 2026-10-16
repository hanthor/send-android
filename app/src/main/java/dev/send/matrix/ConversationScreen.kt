package dev.send.matrix

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.blur
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Playback id of a locked recording being previewed before it is sent. */
private const val RECORDED_CLIP_ID = "composer-recording"

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    title: String,
    isDirect: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    messages: List<MatrixMessage>,
    onBack: () -> Unit,
    /** Sends the draft, as a reply to the given message when it isn't null. */
    onSend: (replyTo: MatrixMessage?) -> Unit,
    onOlder: () -> Unit,
    onReact: (String, String) -> Unit,
    /** Starts a MatrixRTC call here: false for voice, true for video. */
    onCall: (video: Boolean) -> Unit,
    onSendAttachment: (Uri) -> Unit,
    onSendVoice: (VoiceRecording) -> Unit,
    loadMedia: MediaLoader,
    uploading: Boolean,
    error: String,
    /** Label of the fix offered on undecryptable messages ("Verify this device", …), or null when none applies. */
    decryptionActionLabel: String?,
    onDecryptionAction: () -> Unit,
    /** Whether this conversation is muted; with [onToggleMute] the ⋮ menu offers Mute / Unmute. */
    muted: Boolean = false,
    onToggleMute: (() -> Unit)? = null,
    /** A call running in this conversation that this device isn't in, with [onJoinCall] to join it. */
    callBanner: RoomCallBanner? = null,
    onJoinCall: () -> Unit = {},
    /** Replaces the text of an own message. */
    onEdit: (message: MatrixMessage, text: String) -> Unit = { _, _ -> },
    /** Deletes an own message for everyone (after the user confirmed). */
    onDelete: (MatrixMessage) -> Unit = {},
    /** Opens the conversation picker to forward messages (one from the long-press menu, several from "Select more"). */
    onForward: (List<MatrixMessage>) -> Unit = {},
    /** Loads older history until the event is in [messages] (the original of a quoted reply). */
    onLoadUntil: (eventId: String) -> Unit = {},
    /** Other people typing here right now. */
    typingUsers: List<String> = emptyList(),
    /** Homeserver URL preview for a link in a message; null disables previews. */
    loadLinkPreview: (suspend (String) -> LinkPreview?)? = null,
    /** Voice recorder for the composer; the microphone recorder when null. */
    voiceCapture: VoiceCapture? = null,
    /** Event IDs starred in this conversation (account data); their bubbles show a star. */
    starredIds: Set<String> = emptySet(),
    /** Stars or unstars an event; null hides Star from the long-press menu. */
    onToggleStar: ((eventId: String, starred: Boolean) -> Unit)? = null,
    /** Loads this conversation's starred messages for ⋮ → Starred; null hides the entry. */
    loadStarred: (suspend () -> List<StarredMessage>)? = null,
    /** The conversation's avatar (`mxc://`) for the header and details. */
    avatarUrl: String? = null,
    /** Conversation details (⋮ → Details, or tapping the header); null hides them. */
    details: ConversationDetailsActions? = null,
    /** Changes when the room changes on the server, so open details reload. */
    detailsRefreshKey: Any? = null,
    /** Play Messages' sent/received sounds while this conversation is open (Settings). */
    sounds: Boolean = false,
    /** The room ID when opened from the list: the header's avatar and name continue the row's (shared elements). */
    sharedKey: String? = null,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> messages.firstOrNull { it.id == id } }
    var expandedTimeId by remember { mutableStateOf<String?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<MatrixMessage?>(null) }
    var editing by remember { mutableStateOf<MatrixMessage?>(null) }
    var draftBeforeEdit by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<MatrixMessage?>(null) }
    var infoFor by remember { mutableStateOf<MatrixMessage?>(null) }
    var viewing by remember { mutableStateOf<MatrixMessage?>(null) }
    var reactionPickerFor by remember { mutableStateOf<String?>(null) }
    var showEmojiPanel by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchPosition by remember { mutableIntStateOf(0) }
    var flashId by remember { mutableStateOf<String?>(null) }
    var pendingJump by remember { mutableStateOf<String?>(null) }
    /** Messages picked after "Select more", in the order they were picked; non-empty means selection mode. */
    var multiSelected by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDeleteMany by remember { mutableStateOf<List<MatrixMessage>?>(null) }
    var showStarred by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showMediaGrid by remember { mutableStateOf(false) }
    PlayConversationSounds(messages, sounds)
    val composerFocus = remember { FocusRequester() }

    val playback = rememberVoicePlayback()
    val recorder = remember(voiceCapture) { voiceCapture ?: VoiceRecorder(context.applicationContext) }
    var recording by remember { mutableStateOf(false) }
    var recordingLocked by remember { mutableStateOf(false) }
    /** The locked recording was attached to the composer (panel closed); the round button sends it. */
    var recordingAttached by remember { mutableStateOf(false) }
    val liveLevels = remember { mutableStateListOf<Float>() }
    var recordedClip by remember { mutableStateOf<VoiceRecording?>(null) }
    var recordingElapsed by remember { mutableLongStateOf(0L) }
    var cancellingRecording by remember { mutableStateOf(false) }
    var composerHint by remember { mutableStateOf("") }
    val clipToDiscard by rememberUpdatedState(recordedClip)
    DisposableEffect(recorder) {
        onDispose {
            recorder.cancel()
            clipToDiscard?.file?.delete()
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            liveLevels.add(recorder.sampleAmplitude())
            if (liveLevels.size > LIVE_LEVELS) liveLevels.removeAt(0)
            recordingElapsed = recorder.elapsedMillis
            delay(100)
        }
    }
    // A selection, edit or reply whose message left the timeline (redacted elsewhere, history reset) ends.
    LaunchedEffect(messages) {
        if (selectedId != null && selected == null) selectedId = null
        if (multiSelected.isNotEmpty()) {
            val present = messages.mapTo(HashSet()) { it.id }
            if (multiSelected.any { it !in present }) multiSelected = multiSelected.filter { it in present }
        }
    }
    fun toggleMulti(message: MatrixMessage) {
        multiSelected = if (message.id in multiSelected) multiSelected - message.id else multiSelected + message.id
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSendAttachment(uri)
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onSendAttachment(uri)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        composerHint = if (granted) "Hold to record, release to send" else "Microphone access is needed to record voice messages."
    }

    // Newest first, as the list is laid out bottom-up; the typing bubble sits below the newest message.
    val reversed = messages.asReversed()
    val typingShown = typingUsers.isNotEmpty()
    // At the newest message (the list's start) a new bubble or typing dots scroll into view as they arrive.
    val listState = rememberTopAnchoredListState(if (typingShown) "typing" else reversed.firstOrNull()?.key)
    val bubbleEntries = rememberBubbleEntries()
    fun listIndexOf(id: String): Int? {
        val index = reversed.indexOfFirst { it.id == id }
        return if (index < 0) null else index + if (typingShown) 1 else 0
    }
    fun jumpTo(eventId: String) {
        val index = listIndexOf(eventId)
        if (index != null) {
            scope.launch { listState.animateScrollToItem(index) }
            flashId = eventId
        } else {
            pendingJump = eventId
            onLoadUntil(eventId)
        }
    }
    LaunchedEffect(pendingJump, messages) {
        val id = pendingJump ?: return@LaunchedEffect
        val index = listIndexOf(id) ?: return@LaunchedEffect
        pendingJump = null
        listState.animateScrollToItem(index)
        flashId = id
    }
    LaunchedEffect(flashId) {
        if (flashId != null) {
            delay(1_600)
            flashId = null
        }
    }

    // In-conversation search over the loaded timeline, newest match first.
    val matches = if (searching && query.isNotBlank()) {
        reversed.filter { !it.deleted && it.call == null && it.text.contains(query, ignoreCase = true) }.map { it.id }
    } else emptyList()
    val currentMatch = matches.getOrNull(matchPosition)
    LaunchedEffect(query) { matchPosition = 0 }
    LaunchedEffect(currentMatch) {
        val id = currentMatch ?: return@LaunchedEffect
        listIndexOf(id)?.let { listState.animateScrollToItem(it) }
    }

    fun closeSearch() {
        searching = false
        query = ""
    }
    fun startReply(message: MatrixMessage) {
        selectedId = null
        if (editing != null) {
            onDraft(draftBeforeEdit)
            editing = null
        }
        replyingTo = message
        runCatching { composerFocus.requestFocus() }
    }
    fun startEdit(message: MatrixMessage) {
        selectedId = null
        replyingTo = null
        if (editing == null) draftBeforeEdit = draft
        editing = message
        onDraft(message.text)
        runCatching { composerFocus.requestFocus() }
    }
    fun cancelEdit() {
        onDraft(draftBeforeEdit)
        editing = null
    }
    fun sendDraft() {
        val target = editing
        if (target != null) {
            if (draft.isNotBlank() && draft != target.text) onEdit(target, draft)
            cancelEdit()
        } else {
            onSend(replyingTo)
            replyingTo = null
        }
    }
    fun discardRecording(hint: String) {
        playback.stop()
        recorder.cancel()
        recordedClip?.file?.delete()
        recordedClip = null
        recording = false
        recordingLocked = false
        recordingAttached = false
        cancellingRecording = false
        composerHint = hint
    }
    fun startRecording(): Boolean = try {
        playback.stop()
        recorder.start().also { started ->
            recording = started
            cancellingRecording = false
            recordingElapsed = 0L
            liveLevels.clear()
            composerHint = ""
        }
    } catch (failure: Exception) {
        composerHint = failure.message ?: "The microphone could not be started."
        false
    }
    fun togglePlayRecording() {
        val clip = recordedClip
        if (playback.activeId == RECORDED_CLIP_ID) {
            playback.stop()
        } else if (clip != null) {
            runCatching { playback.play(RECORDED_CLIP_ID, clip.file) }
                .onFailure { composerHint = it.message ?: "This recording could not be played." }
        }
    }
    /** Stops a locked recording, keeping the clip; false (and back to the composer) when it was too short. */
    fun stopLockedRecording(): Boolean {
        val clip = recorder.stop()
        recording = false
        if (clip != null) {
            recordedClip = clip
            return true
        }
        recordingLocked = false
        composerHint = "Voice message too short"
        return false
    }

    // Composer, selection and search states close first; with none left back leaves with predictive back.
    val nestedBack = selectedId != null || multiSelected.isNotEmpty() || showEmojiPanel || searching || recordingLocked ||
        editing != null || replyingTo != null
    BackHandler(enabled = nestedBack) {
        when {
            selectedId != null -> selectedId = null
            multiSelected.isNotEmpty() -> multiSelected = emptyList()
            showEmojiPanel -> showEmojiPanel = false
            searching -> closeSearch()
            recordingLocked -> discardRecording("Voice message discarded")
            editing != null -> cancelEdit()
            replyingTo != null -> replyingTo = null
        }
    }
    val leaving = rememberPredictiveBack(enabled = !nestedBack, onBack = onBack)

    var selectedBubbles by remember { mutableStateOf<List<Rect>>(emptyList()) }
    LaunchedEffect(selectedId) { if (selectedId == null) selectedBubbles = emptyList() }
    if (selected != null && selectedBubbles.isNotEmpty()) {
        MessageActionsOverlay(
            bubbles = selectedBubbles,
            alignEnd = selected.own,
            actions = actionsFor(selected).let { it.copy(star = it.star && onToggleStar != null) },
            onDismiss = { selectedId = null },
            onReact = { emoji ->
                selectedId = null
                onReact(selected.id, emoji)
            },
            onMoreReactions = {
                selectedId = null
                reactionPickerFor = selected.id
            },
            onReply = { startReply(selected) },
            onForward = {
                selectedId = null
                onForward(listOf(selected))
            },
            starred = selected.id in starredIds,
            onStar = {
                selectedId = null
                onToggleStar?.invoke(selected.id, selected.id !in starredIds)
            },
            onSelectMore = {
                selectedId = null
                multiSelected = listOf(selected.id)
            },
            onCopy = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Message", selected.text))
                selectedId = null
            },
            onEdit = { startEdit(selected) },
            onDelete = {
                confirmDelete = selected
                selectedId = null
            },
            onInfo = {
                infoFor = selected
                selectedId = null
            },
        )
    }

    // Messages blurs what's behind the long-press overlay (API 31+; dimming alone below). The selected bubble stays sharp.
    val backdropBlur by animateDpAsState(if (selected != null) 6.dp else 0.dp, SendMotion.effectsDefault(), label = "backdropBlur")
    val backdrop = if (backdropBlur > 0.dp) Modifier.blur(backdropBlur) else Modifier
    Scaffold(
        modifier = Modifier.predictiveBack(leaving),
        containerColor = colors.surfaceContainer,
        topBar = { Box(backdrop) {
            when {
                multiSelected.isNotEmpty() -> {
                    val picked = messages.filter { it.id in multiSelected }
                    val pickedActions = picked.map(::actionsFor)
                    MessageSelectionBar(
                        count = picked.size,
                        onClose = { multiSelected = emptyList() },
                        onCopy = if (pickedActions.any { it.copy }) ({
                            val text = picked.filter { actionsFor(it).copy }.joinToString("\n") { it.text }
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Messages", text))
                            multiSelected = emptyList()
                        }) else null,
                        onForward = if (picked.isNotEmpty() && pickedActions.all { it.forward }) ({
                            multiSelected = emptyList()
                            onForward(picked)
                        }) else null,
                        onDelete = if (picked.isNotEmpty() && pickedActions.all { it.delete }) ({ confirmDeleteMany = picked }) else null,
                    )
                }
                searching -> ConversationSearchBar(
                    query = query,
                    onQuery = { query = it },
                    position = matchPosition,
                    count = matches.size,
                    onOlder = {
                        // Past the oldest loaded match, older history is loaded and searched too.
                        if (matchPosition + 1 < matches.size) matchPosition += 1 else onOlder()
                    },
                    onNewer = { if (matchPosition > 0) matchPosition -= 1 },
                    onClose = ::closeSearch,
                )
                // Messages: back, avatar, title only, call, overflow. Body is a rounded sheet below.
                else -> Surface(color = colors.surfaceContainer, modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        // Messages: tapping the contact's avatar or name opens the conversation's details.
                        Row(
                            modifier = Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp))
                                .clickable(enabled = details != null, onClickLabel = "Details") {
                                    showEmojiPanel = false
                                    showDetails = true
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val avatarModifier = if (sharedKey != null) Modifier.sendSharedElement(sharedAvatarKey(sharedKey)) else Modifier
                            Box(avatarModifier) { Avatar(name = title, size = 40.dp, url = avatarUrl) }
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                title,
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (sharedKey != null) Modifier.sendSharedBounds(sharedTitleKey(sharedKey)) else Modifier,
                            )
                        }
                        // Messages: video and voice call buttons; both start a MatrixRTC call through Element Call.
                        IconButton(onClick = { onCall(true) }) {
                            Icon(imageVector = Icons.Outlined.Videocam, contentDescription = "Video call")
                        }
                        IconButton(onClick = { onCall(false) }) {
                            Icon(imageVector = Icons.Outlined.Call, contentDescription = "Voice call")
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("End-to-end encrypted", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                                }
                                if (details != null) {
                                    DropdownMenuItem(
                                        text = { Text("Details") },
                                        leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showEmojiPanel = false
                                            showDetails = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Search") },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        showEmojiPanel = false
                                        searching = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Load older messages") },
                                    onClick = { showMenu = false; onOlder() },
                                )
                                if (loadStarred != null) {
                                    DropdownMenuItem(
                                        text = { Text("Starred") },
                                        leadingIcon = { Icon(Icons.Outlined.StarOutline, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showEmojiPanel = false
                                            showStarred = true
                                        },
                                    )
                                }
                                if (onToggleMute != null) {
                                    DropdownMenuItem(
                                        text = { Text(if (muted) "Unmute" else "Mute") },
                                        leadingIcon = {
                                            Icon(if (muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff, contentDescription = null)
                                        },
                                        onClick = { showMenu = false; onToggleMute() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } },
        bottomBar = {
            Surface(color = colors.surface, modifier = backdrop.navigationBarsPadding().imePadding()) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
                    if (error.isNotBlank()) {
                        Text(error, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    val status = if (uploading) "Sending attachment…" else composerHint
                    if (status.isNotBlank()) {
                        Text(status, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
                    }
                    val editTarget = editing
                    val replyTarget = replyingTo
                    if (editTarget != null) {
                        ComposerContextBar("Editing message", messagePreviewText(editTarget), "Cancel edit", ::cancelEdit)
                    } else if (replyTarget != null) {
                        val name = when {
                            replyTarget.own -> "yourself"
                            replyTarget.senderName != null -> replyTarget.senderName
                            isDirect -> title
                            else -> replyTarget.sender
                        }
                        ComposerContextBar("Replying to $name", messagePreviewText(replyTarget), "Cancel reply") { replyingTo = null }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        // Messages composer: 56dp pill with attach, field, emoji and gallery actions.
                        Surface(
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            shape = RoundedCornerShape(28.dp),
                            color = colors.surfaceContainerHigh,
                        ) {
                            val clip = recordedClip
                            if (recordingLocked && recordingAttached && clip != null) {
                                AttachedRecordingBar(
                                    clip = clip,
                                    playing = playback.activeId == RECORDED_CLIP_ID,
                                    onDelete = { discardRecording("Voice message discarded") },
                                    onTogglePlay = ::togglePlayRecording,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (recording && !recordingLocked) {
                                RecordingIndicator(
                                    elapsedMillis = recordingElapsed,
                                    cancelling = cancellingRecording,
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                )
                            } else Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { documentPicker.launch(arrayOf("*/*")) }) {
                                    Icon(imageVector = Icons.Outlined.AddCircleOutline, contentDescription = "Attach file", tint = colors.onSurfaceVariant)
                                }
                                // Same decoration as TextField but with Messages' tight inner padding so the
                                // placeholder stays on one line next to three inline actions.
                                val composerInteraction = remember { MutableInteractionSource() }
                                LaunchedEffect(composerInteraction) {
                                    // Tapping the field brings the keyboard back in place of the emoji panel.
                                    composerInteraction.interactions.collect { if (it is PressInteraction.Press) showEmojiPanel = false }
                                }
                                val composerColors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                )
                                BasicTextField(
                                    value = draft,
                                    onValueChange = onDraft,
                                    modifier = Modifier.weight(1f).focusRequester(composerFocus),
                                    maxLines = 4,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                                    cursorBrush = SolidColor(colors.primary),
                                    interactionSource = composerInteraction,
                                ) { innerTextField ->
                                    TextFieldDefaults.DecorationBox(
                                        value = draft,
                                        innerTextField = innerTextField,
                                        enabled = true,
                                        singleLine = false,
                                        visualTransformation = VisualTransformation.None,
                                        interactionSource = composerInteraction,
                                        placeholder = { Text("Message", maxLines = 1, softWrap = false) },
                                        colors = composerColors,
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 16.dp),
                                        container = {},
                                    )
                                }
                                // Messages swaps the keyboard for its emoji panel; the button then switches back.
                                IconButton(
                                    onClick = {
                                        if (showEmojiPanel) {
                                            showEmojiPanel = false
                                            runCatching { composerFocus.requestFocus() }
                                            keyboard?.show()
                                        } else {
                                            keyboard?.hide()
                                            showEmojiPanel = true
                                        }
                                    },
                                ) {
                                    if (showEmojiPanel) {
                                        Icon(Icons.Outlined.Keyboard, contentDescription = "Keyboard", tint = colors.onSurfaceVariant)
                                    } else {
                                        Icon(Icons.Filled.SentimentSatisfied, contentDescription = "Emoji", tint = colors.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) {
                                    Icon(imageVector = Icons.Outlined.Image, contentDescription = "Gallery", tint = colors.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Messages' 56dp circular action: Send with a draft (or an attached recording), hold-to-record otherwise;
                        // while the locked recorder is open it closes it, discarding the take.
                        val sendColors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                        if (recordingLocked && recordingAttached) {
                            FilledIconButton(
                                onClick = {
                                    val clip = recordedClip
                                    playback.stop()
                                    recordedClip = null
                                    recordingLocked = false
                                    recordingAttached = false
                                    if (clip != null) onSendVoice(clip) else composerHint = "Voice message too short"
                                },
                                modifier = Modifier.size(56.dp),
                                colors = sendColors,
                            ) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice message")
                            }
                        } else if (recordingLocked) {
                            FilledIconButton(
                                onClick = { discardRecording("Voice message discarded") },
                                modifier = Modifier.size(56.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer),
                            ) {
                                Icon(imageVector = Icons.Filled.GraphicEq, contentDescription = "Hide voice recorder")
                            }
                        } else if (draft.isNotBlank()) {
                            FilledIconButton(onClick = ::sendDraft, modifier = Modifier.size(56.dp), colors = sendColors) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        } else {
                            VoiceRecordButton(
                                recording = recording,
                                onStart = {
                                    if (voiceCapture == null && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                        false
                                    } else startRecording()
                                },
                                onSlideOutside = { cancellingRecording = it },
                                onFinish = { send ->
                                    recording = false
                                    cancellingRecording = false
                                    if (send) {
                                        val clip = recorder.stop()
                                        if (clip != null) onSendVoice(clip) else composerHint = "Hold to record, release to send"
                                    } else {
                                        recorder.cancel()
                                        composerHint = "Voice message discarded"
                                    }
                                },
                                onTap = { composerHint = "Hold to record, release to send" },
                                onLock = {
                                    recordingLocked = true
                                    cancellingRecording = false
                                    composerHint = ""
                                },
                            )
                        }
                    }
                    if (recordingLocked && !recordingAttached) {
                        LockedRecordingPanel(
                            elapsedMillis = recordingElapsed,
                            levels = liveLevels,
                            clip = recordedClip,
                            playback = playback,
                            playing = playback.activeId == RECORDED_CLIP_ID,
                            onRestart = {
                                playback.stop()
                                recorder.cancel()
                                recordedClip?.file?.delete()
                                recordedClip = null
                                recording = false
                                if (!startRecording()) {
                                    recordingLocked = false
                                }
                            },
                            onStop = { stopLockedRecording() },
                            onTogglePlay = ::togglePlayRecording,
                            onAttach = {
                                if (recordedClip != null || stopLockedRecording()) {
                                    playback.stop()
                                    recordingAttached = true
                                }
                            },
                        )
                    }
                    if (showEmojiPanel) {
                        EmojiPickerPanel(
                            onEmoji = { emoji -> onDraft(draft + emoji) },
                            modifier = Modifier.fillMaxWidth().height(320.dp).padding(top = 8.dp),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (callBanner != null) Box(backdrop) { RoomCallBannerRow(callBanner, onJoin = onJoinCall) }
                // "Read" / "Seen by N" belongs under the newest own message.
                val newestOwnIndex = messages.indexOfLast { it.own && it.call == null && !it.deleted }
                val newestOwnReaders = remember(messages, newestOwnIndex) { readersFrom(messages, newestOwnIndex) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                ) {
                    if (typingShown) {
                        item(key = "typing") { Box(sendItemPlacement().then(backdrop)) { TypingBubble(typingUsers) } }
                    }
                    items(reversed.size, key = { reversed[it].key }) { index ->
                        val message = reversed[index]
                        // Grouping is computed in chronological order: neighbours are the adjacent
                        // items in the non-reversed list, i.e. reversed neighbours flipped.
                        val chronoIndex = messages.lastIndex - index
                        // Call rows stand alone: they neither join nor split a sender's bubble group.
                        fun sameGroup(other: MatrixMessage?) = other != null && other.call == null && message.call == null && other.own == message.own
                        val isFirstInGroup = !sameGroup(messages.getOrNull(chronoIndex - 1))
                        val isLastInGroup = !sameGroup(messages.getOrNull(chronoIndex + 1))
                        // Centered section header whenever the calendar day changes.
                        val dayKey = android.text.format.DateFormat.format("yyyyMMdd", message.timestampMillis).toString()
                        val prevDayKey = messages.getOrNull(chronoIndex - 1)?.let {
                            android.text.format.DateFormat.format("yyyyMMdd", it.timestampMillis).toString()
                        }
                        // New bubbles enter themselves (bubbleEntry); older ones slide up to make room.
                        Column(
                            modifier = sendItemPlacement(fadeIn = false).fillMaxWidth().bubbleEntry(bubbleEntries, message)
                                .then(if (selectedId == message.id) Modifier else backdrop),
                            horizontalAlignment = if (message.call != null) Alignment.CenterHorizontally else if (message.own) Alignment.End else Alignment.Start,
                        ) {
                            if (prevDayKey != dayKey) {
                                Text(
                                    text = dayHeaderLabel(context, message.timestampMillis),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                )
                            }
                            val call = message.call
                            if (call != null) {
                                CallEventRow(
                                    call = call,
                                    timestampMillis = message.timestampMillis,
                                    onJoin = onJoinCall,
                                    onCallBack = if (isDirect) ({ onCall(call.video) }) else null,
                                )
                            } else {
                                MessageItem(
                                    message = message,
                                    isFirstInGroup = isFirstInGroup,
                                    isLastInGroup = isLastInGroup,
                                    selected = selectedId == message.id,
                                    highlighted = flashId == message.id || currentMatch == message.id || message.id in multiSelected,
                                    swipeEnabled = selectedId == null && multiSelected.isEmpty() && editing == null && !recordingLocked,
                                    query = if (searching) query else "",
                                    loadMedia = loadMedia,
                                    playback = playback,
                                    loadLinkPreview = loadLinkPreview,
                                    decryptionActionLabel = decryptionActionLabel,
                                    onDecryptionAction = onDecryptionAction,
                                    onTap = {
                                        when {
                                            multiSelected.isNotEmpty() -> toggleMulti(message)
                                            selectedId != null -> selectedId = null
                                            else -> expandedTimeId = if (expandedTimeId == message.id) null else message.id
                                        }
                                    },
                                    onLongPress = {
                                        if (multiSelected.isNotEmpty()) {
                                            toggleMulti(message)
                                        } else {
                                            keyboard?.hide()
                                            showEmojiPanel = false
                                            selectedId = message.id
                                        }
                                    },
                                    onReply = { startReply(message) },
                                    onQuote = { eventId -> if (multiSelected.isEmpty()) jumpTo(eventId) else toggleMulti(message) },
                                    onOpenVisual = {
                                        when {
                                            multiSelected.isNotEmpty() -> toggleMulti(message)
                                            selectedId != null -> selectedId = null
                                            else -> viewing = message
                                        }
                                    },
                                    reportBounds = if (selectedId == message.id) ({ selectedBubbles = it }) else null,
                                )
                                if (message.reactions.isNotEmpty()) {
                                    // Overlapping rounded reaction pill at the bubble's bottom corner.
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = colors.surfaceContainerLow,
                                        tonalElevation = 1.dp,
                                        modifier = Modifier.padding(horizontal = 20.dp).offset(y = (-8).dp),
                                    ) {
                                        Text(
                                            message.reactions.joinToString(" "),
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                // Messages: time under every cluster (or a tapped message), delivery/read state for own
                                // messages, "Edited" on replaced ones and a star on starred ones.
                                val starred = message.id in starredIds
                                val showTime = isLastInGroup || expandedTimeId == message.id || message.status == DeliveryStatus.Failed
                                if (showTime || message.edited || starred) {
                                    val time = DateUtils.formatDateTime(context, message.timestampMillis, DateUtils.FORMAT_SHOW_TIME)
                                    val label = if (message.own && message.status == DeliveryStatus.Sending) {
                                        "Sending…"
                                    } else {
                                        val readers = if (chronoIndex == newestOwnIndex) newestOwnReaders.size else 0
                                        listOfNotNull(
                                            time.takeIf { showTime },
                                            "Edited".takeIf { message.edited },
                                            deliveryLabel(message, readers, isDirect).takeIf { message.own && showTime && !message.deleted },
                                        ).joinToString(" · ")
                                    }
                                    Row(
                                        modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 2.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (starred) {
                                            Icon(
                                                Icons.Filled.Star,
                                                contentDescription = "Starred",
                                                tint = colors.primary,
                                                modifier = Modifier.padding(end = 4.dp).size(14.dp),
                                            )
                                        }
                                        if (label.isNotEmpty()) {
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (message.status == DeliveryStatus.Failed) colors.error else colors.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { message ->
        DeleteMessageDialog(
            onDelete = {
                confirmDelete = null
                if (replyingTo?.id == message.id) replyingTo = null
                if (editing?.id == message.id) cancelEdit()
                onDelete(message)
            },
            onDismiss = { confirmDelete = null },
        )
    }
    confirmDeleteMany?.let { picked ->
        DeleteMessageDialog(
            count = picked.size,
            onDelete = {
                confirmDeleteMany = null
                multiSelected = emptyList()
                if (picked.any { it.id == replyingTo?.id }) replyingTo = null
                if (picked.any { it.id == editing?.id }) cancelEdit()
                picked.forEach(onDelete)
            },
            onDismiss = { confirmDeleteMany = null },
        )
    }
    if (details != null) {
        val media = remember(messages) { messages.asReversed().filter { it.attachment != null && !it.deleted } }
        // Photos and videos open in the viewer; files and audio in the app that handles them.
        fun openMedia(message: MatrixMessage) {
            val attachment = message.attachment ?: return
            if (attachment.kind == AttachmentKind.Image || attachment.kind == AttachmentKind.Video) {
                viewing = message
            } else scope.launch {
                try {
                    openMediaExternally(context, loadMedia(attachment.sourceJson, attachment.filename), attachment.mimetype ?: "application/octet-stream")
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.widget.Toast.makeText(context, failure.message ?: "This file couldn't be opened.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        AnimatedVisibility(visible = showDetails, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
            ConversationDetailsScreen(
                title = title,
                isDirect = isDirect,
                avatarUrl = avatarUrl,
                actions = details,
                refreshKey = detailsRefreshKey,
                muted = muted,
                onToggleMute = onToggleMute,
                media = media,
                loadMedia = loadMedia,
                onCall = { video -> showDetails = false; onCall(video) },
                onSearch = {
                    showDetails = false
                    searching = true
                },
                onStarred = if (loadStarred != null) ({ showStarred = true }) else null,
                onMedia = { showMediaGrid = true },
                onOpenMedia = { message -> openMedia(message) },
                onBack = { showDetails = false },
            )
        }
        AnimatedVisibility(visible = showMediaGrid, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
            MediaGridScreen(
                media = media,
                loadMedia = loadMedia,
                onOpen = { message -> openMedia(message) },
                onLoadOlder = onOlder,
                onBack = { showMediaGrid = false },
            )
        }
    }
    if (loadStarred != null) {
        AnimatedVisibility(visible = showStarred, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
            StarredMessagesScreen(
                title = title,
                isDirect = isDirect,
                starredIds = starredIds,
                load = loadStarred,
                onOpen = { eventId ->
                    showStarred = false
                    showDetails = false
                    jumpTo(eventId)
                },
                onUnstar = { eventId -> onToggleStar?.invoke(eventId, false) },
                onBack = { showStarred = false },
            )
        }
    }
    infoFor?.let { message ->
        val index = messages.indexOfFirst { it.id == message.id }
        MessageInfoDialog(
            message = messages.getOrNull(index) ?: message,
            readers = remember(messages, index) { readersFrom(messages, index) },
            isDirect = isDirect,
            onDismiss = { infoFor = null },
        )
    }
    viewing?.let { message ->
        val attachment = message.attachment
        if (attachment != null) {
            MediaViewer(
                attachment = attachment,
                senderName = if (message.own) "You" else message.senderName ?: if (isDirect) title else message.sender,
                timestampMillis = message.timestampMillis,
                loadMedia = loadMedia,
                onDismiss = { viewing = null },
            )
        }
    }
    reactionPickerFor?.let { eventId ->
        ModalBottomSheet(onDismissRequest = { reactionPickerFor = null }) {
            EmojiPickerPanel(
                onEmoji = { emoji ->
                    reactionPickerFor = null
                    onReact(eventId, emoji)
                },
                modifier = Modifier.fillMaxWidth().height(420.dp),
            )
        }
    }
}

/**
 * One message in the timeline: swipe-to-reply wrapper, selection highlight, the bubble for its kind (deleted,
 * undecryptable, media, text with quote/links/preview).
 */
@Composable
private fun MessageItem(
    message: MatrixMessage,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    selected: Boolean,
    highlighted: Boolean,
    swipeEnabled: Boolean,
    query: String,
    loadMedia: MediaLoader,
    playback: VoicePlayback,
    loadLinkPreview: (suspend (String) -> LinkPreview?)?,
    decryptionActionLabel: String?,
    onDecryptionAction: () -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onReply: () -> Unit,
    onQuote: (String) -> Unit,
    onOpenVisual: () -> Unit,
    /** Non-null while selected: receives the on-screen bounds of the bubble's pieces. */
    reportBounds: ((List<Rect>) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val actions = actionsFor(message)
    val parts = remember { mutableStateMapOf<Int, Rect>() }
    // While selected, report where each piece of the bubble is on screen, for the long-press overlay.
    fun Modifier.part(key: Int): Modifier = if (reportBounds == null) this else onGloballyPositioned {
        parts[key] = it.screenBounds()
        reportBounds(parts.values.toList())
    }
    val shape = bubbleShape(message.own, isFirstInGroup, isLastInGroup)
    SwipeToReply(enabled = swipeEnabled && actions.reply, onReply = onReply) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (highlighted) colors.primary.copy(alpha = 0.12f) else Color.Transparent),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.own) Alignment.End else Alignment.Start) {
                val attachment = message.attachment
                val decryptionFailure = message.decryptionFailure
                when {
                    message.deleted -> Box(Modifier.combinedClickableCompat(onTap, onLongPress).part(0)) {
                        DeletedBubble(isFromMe = message.own, shape = shape, isLastInGroup = isLastInGroup)
                    }
                    decryptionFailure != null -> Box(Modifier.part(0)) { UndecryptableBubble(
                        failure = decryptionFailure,
                        isFromMe = message.own,
                        shape = shape,
                        isLastInGroup = isLastInGroup,
                        actionLabel = decryptionActionLabel?.takeIf { decryptionFailure.fixableOnThisDevice },
                        onAction = onDecryptionAction,
                    ) }
                    else -> {
                        if (attachment != null) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (isLastInGroup && message.text.isBlank()) 4.dp else 1.dp),
                                contentAlignment = if (message.own) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                Box(Modifier.part(1)) { AttachmentBubble(
                                    messageId = message.id,
                                    attachment = attachment,
                                    isFromMe = message.own,
                                    shape = shape,
                                    loadMedia = loadMedia,
                                    playback = playback,
                                    onLongPress = onLongPress,
                                    onOpenVisual = onOpenVisual.takeIf { attachment.kind == AttachmentKind.Image || attachment.kind == AttachmentKind.Video },
                                ) }
                            }
                        }
                        if (attachment == null || message.text.isNotBlank()) {
                            MessageBubble(
                                text = message.text,
                                isFromMe = message.own,
                                isFirstInGroup = isFirstInGroup,
                                isLastInGroup = isLastInGroup,
                                onClick = onTap,
                                onLongPress = onLongPress,
                                replyTo = message.replyTo,
                                onQuoteClick = onQuote,
                                query = query,
                                loadLinkPreview = loadLinkPreview,
                                bubbleModifier = Modifier.part(2),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

private fun dayHeaderLabel(context: android.content.Context, timestampMillis: Long): String {
    // Messages: time only for today, otherwise "Tuesday, Oct 6 • 4:46 PM".
    val time = DateUtils.formatDateTime(context, timestampMillis, DateUtils.FORMAT_SHOW_TIME)
    if (DateUtils.isToday(timestampMillis)) return time
    val day = DateUtils.formatDateTime(
        context,
        timestampMillis,
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
    return "$day • $time"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    text: String,
    isFromMe: Boolean,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    /** The message this one replies to, shown as a quote that [onQuoteClick] jumps to. */
    replyTo: ReplyQuote? = null,
    onQuoteClick: (eventId: String) -> Unit = {},
    /** In-conversation search query to highlight. */
    query: String = "",
    loadLinkPreview: (suspend (String) -> LinkPreview?)? = null,
    /** Applied to the bubble surface itself (not the full-width row). */
    bubbleModifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val content = if (isFromMe) colors.onPrimaryContainer else colors.onSurface
    val links = remember(text) { findLinks(text) }
    val annotated = remember(text, links, query, content) {
        messageText(text, links, query, linkColor = content, highlight = colors.tertiaryContainer)
    }
    val previewUrl = links.firstOrNull { it.url.startsWith("http://") || it.url.startsWith("https://") }?.url
    val preview by produceState<LinkPreview?>(null, previewUrl, loadLinkPreview) {
        value = if (previewUrl != null && loadLinkPreview != null) runCatching { loadLinkPreview(previewUrl) }.getOrNull() else null
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (isLastInGroup) 4.dp else 1.dp),
        contentAlignment = if (isFromMe) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = bubbleShape(isFromMe, isFirstInGroup, isLastInGroup),
            // Messages uses tonal blue for sent bubbles, not a saturated primary fill.
            color = if (isFromMe) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = content,
            border = if (query.isNotBlank() && annotated.spanStyles.isNotEmpty()) BorderStroke(1.dp, colors.tertiary) else null,
            // Tap reveals the message's time/status (as in Messages); long-press selects it for actions.
            modifier = bubbleModifier.widthIn(max = 280.dp).combinedClickable(onClick = onClick, onLongClick = onLongPress),
        ) {
            Column(Modifier.padding(horizontal = if (replyTo != null || preview != null) 6.dp else 0.dp, vertical = if (replyTo != null || preview != null) 6.dp else 0.dp)) {
                if (replyTo != null) {
                    ReplyQuoteBlock(quote = replyTo, isFromMe = isFromMe, onClick = { onQuoteClick(replyTo.eventId) }, onLongClick = onLongPress)
                }
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(
                        horizontal = if (replyTo != null || preview != null) 10.dp else 16.dp,
                        vertical = if (replyTo != null || preview != null) 6.dp else 10.dp,
                    ),
                )
                preview?.let { LinkPreviewCard(it, isFromMe) }
            }
        }
    }
}
