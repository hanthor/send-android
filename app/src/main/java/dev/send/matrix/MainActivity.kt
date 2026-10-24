package dev.send.matrix

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.send.matrix.notifications.IncomingCalls
import dev.send.matrix.notifications.MessageNotifier
import dev.send.matrix.notifications.NotificationPermissionDialog
import dev.send.matrix.notifications.NotificationRouter
import dev.send.matrix.notifications.NotificationsDialog
import dev.send.matrix.notifications.PushRegistration
import dev.send.matrix.notifications.markNotificationPermissionPrompted
import dev.send.matrix.notifications.notificationPermissionPrompted
import dev.send.matrix.notifications.notificationSummary
import dev.send.matrix.notifications.notificationsAllowed
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.VerificationState
import io.element.android.call.ui.ElementCallOverlay
import io.element.android.call.impl.ElementCallPictureInPicture
import io.element.android.call.api.ElementCallController

/** A typing notice lapses after this long without changes to the draft. */
private const val TYPING_IDLE_MILLIS = 5_000L

// Messages-style contact avatars: saturated solid fills with a white glyph. Each fill is the darker Google tone
// of Messages' hue so the white initial keeps WCAG AA contrast (≥4.5:1, see docs/accessibility.md).
private val AvatarColors = listOf(
    Color(0xFF1967D2), Color(0xFF188038), Color(0xFFB06000), Color(0xFFD93025),
    Color(0xFF9334E6), Color(0xFF007B83), Color(0xFFB55400), Color(0xFFD01884),
)

// Static palette measured from Google Messages on the comparison emulator (cool
// neutral surfaces, tonal-blue accents, pink voice button). Messages does not
// follow the wallpaper there, so neither does Send. Tokens Messages doesn't show (secondary, tertiary, outline,
// error, background, inverse) come from the same tonal-blue scheme instead of M3's purple baseline, so dark and
// light stay one family; docs/accessibility.md lists the contrast of every text pair.
private val GoogleLightColors = lightColorScheme(
    primary = Color(0xFF415F91),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiaryContainer = Color(0xFFFAD8FD),
    onTertiaryContainer = Color(0xFF2E0D33),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE3E5ED),
    surfaceContainerHighest = Color(0xFFDDDFE7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFE0E2EC),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    secondary = Color(0xFF565F71),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF705575),
    onTertiary = Color(0xFFFFFFFF),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = Color(0xFFAAC7FF),
)
private val GoogleDarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF284777),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiaryContainer = Color(0xFF5C3F61),
    onTertiaryContainer = Color(0xFFFAD8FD),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
    surfaceVariant = Color(0xFF44474E),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    tertiary = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF3F2844),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2E3036),
    inversePrimary = Color(0xFF415F91),
)

/** Whether Send draws dark: Settings → Choose theme, or the system's night mode for "System default". */
@Composable
internal fun sendDarkTheme(theme: ThemeChoice? = null): Boolean {
    val context = LocalContext.current
    val saved by remember(context) { SendSettings.get(context) }.theme.collectAsState()
    return when (theme ?: saved) {
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
        ThemeChoice.System -> isSystemInDarkTheme()
    }
}

/** Send's colour scheme; [theme] overrides the saved choice (fixtures), null follows Settings. */
@Composable
internal fun SendTheme(theme: ThemeChoice? = null, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (sendDarkTheme(theme)) GoogleDarkColors else GoogleLightColors, content = content)
}

internal fun avatarPalette(name: String): Pair<Color, Color> {
    val index = (name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % AvatarColors.size
    return AvatarColors[index] to Color.White
}

class MainActivity : ComponentActivity() {
    private val repository by lazy { MatrixRepository.get(applicationContext) }
    val ssoCallback = mutableStateOf<Uri?>(null)
    /** Conversation to open from a notification tap or conversation shortcut, consumed by SendApp. */
    val pendingConversation = mutableStateOf<String?>(null)
    /** Ring answered from the call notification or the incoming-call screen, joined by SendApp. */
    val pendingCall = mutableStateOf<PendingCall?>(null)
    /** Content shared from another app (via [ShareActivity]), waiting for a recipient; consumed by SendApp. */
    internal val pendingShare = mutableStateOf<SharePayload?>(null)
    private var pipController: ElementCallController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity restores the conversation it showed; only a fresh launch follows the intent.
        if (savedInstanceState == null) {
            if (!acceptCall(intent) && !acceptConversation(intent)) acceptShare(intent)
        } else {
            savedInstanceState.getString(ShareTarget.EXTRA_SHARE_ID)?.let { pendingShare.value = ShareTarget.load(this, it) }
        }
        enableEdgeToEdge()
        setContent {
            // Status/navigation bar icons follow Send's theme, which can differ from the system's.
            val dark = sendDarkTheme()
            LaunchedEffect(dark) {
                val style = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            SendTheme { SendApp(repository, this@MainActivity) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingShare.value?.let { outState.putString(ShareTarget.EXTRA_SHARE_ID, it.id) }
    }

    internal fun attachCallController(controller: ElementCallController) {
        if (pipController !== controller) {
            pipController = controller
            ElementCallPictureInPicture.attach(this, controller)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pipController?.let { ElementCallPictureInPicture.onUserLeaveHint(this, it) }
    }

    override fun onResume() {
        super.onResume()
        NotificationRouter.activityResumed = true
        conversationVisible(NotificationRouter.openRoomId)
    }

    override fun onPause() {
        NotificationRouter.activityResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        // A recreated activity's UI reports its conversation again.
        NotificationRouter.openRoomId = null
        super.onDestroy()
    }

    /** SendApp reports the conversation it shows (null for the list); see [NotificationRouter.visibleRoomId]. */
    internal fun showConversation(roomId: String?) {
        NotificationRouter.openRoomId = roomId
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) conversationVisible(roomId)
    }

    /** The user sees [roomId]: its notification goes, and the system ties its shortcut/notification to this screen. */
    private fun conversationVisible(roomId: String?) {
        if (roomId != null) {
            MessageNotifier.cancel(this, roomId)
            IncomingCalls.cancelMissed(this, roomId)
        }
        setLocusContext(roomId?.let { android.content.LocusId(it) }, null)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Conversation and call requests are consumed into UI state, so they don't replace the launch intent.
        if (acceptCall(intent) || acceptConversation(intent) || acceptShare(intent)) return
        setIntent(intent)
        acceptSsoCallback(intent.data)
    }

    /** Queues the conversation a notification tap or shortcut asks for; false for any other intent. */
    private fun acceptConversation(intent: android.content.Intent?): Boolean {
        if (intent?.action != MessageNotifier.ACTION_OPEN_CONVERSATION) return false
        pendingConversation.value = intent.getStringExtra(MessageNotifier.EXTRA_ROOM_ID)
        return true
    }

    /** Queues a share staged by [ShareActivity] (a newer share replaces an unsent one); false for any other intent. */
    private fun acceptShare(intent: android.content.Intent?): Boolean {
        if (intent?.action != ShareTarget.ACTION_SHARE) return false
        val share = intent.getStringExtra(ShareTarget.EXTRA_SHARE_ID)?.let { ShareTarget.load(this, it) } ?: return true
        pendingShare.value?.takeIf { it.id != share.id }?.let { ShareTarget.discard(this, it.id) }
        pendingShare.value = share
        return true
    }

    /**
     * Answer from a ring: stops ringing, opens the conversation and queues the join. Call back from a missed-call
     * notification: opens the conversation and queues a new call. False for any other intent.
     */
    private fun acceptCall(intent: android.content.Intent?): Boolean {
        val callBack = intent?.action == IncomingCalls.ACTION_CALL_BACK
        if (intent?.action != IncomingCalls.ACTION_ANSWER && !callBack) return false
        val roomId = intent.getStringExtra(MessageNotifier.EXTRA_ROOM_ID) ?: return true
        if (callBack) IncomingCalls.cancelMissed(this, roomId) else IncomingCalls.stop(this, roomId)
        pendingCall.value = PendingCall(roomId, video = intent.getBooleanExtra(IncomingCalls.EXTRA_VIDEO, false), outgoing = callBack)
        pendingConversation.value = roomId
        // Answered on the lock screen: the call (and the rest of Send) shows once the user unlocks.
        getSystemService(android.app.KeyguardManager::class.java).requestDismissKeyguard(this, null)
        return true
    }

    private fun acceptSsoCallback(uri: Uri?) {
        if (uri?.scheme == "dev.send.matrix" && uri.host == "sso" && uri.path == "/callback") {
            ssoCallback.value = uri
        }
    }
}

@Composable
private fun SendApp(repository: MatrixRepository, activity: MainActivity) {
    var signedIn by rememberSaveable { mutableStateOf(false) }
    var homeserver by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by rememberSaveable { mutableStateOf(true) }
    var error by rememberSaveable { mutableStateOf("") }
    var waitingForSso by rememberSaveable { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var room by remember { mutableStateOf<Room?>(null) }
    var timeline by remember { mutableStateOf<Timeline?>(null) }
    /** The open timeline reached the start of its room's history: scrolling up loads nothing more. */
    var historyStartReached by remember { mutableStateOf(false) }
    var activeRoomId by rememberSaveable { mutableStateOf("") }
    var roomTitle by rememberSaveable { mutableStateOf("") }
    var activeRoomIsDirect by rememberSaveable { mutableStateOf(false) }
    var drafts by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    var notice by rememberSaveable { mutableStateOf("") }
    var pendingUploads by remember { mutableIntStateOf(0) }
    val rooms by repository.joinedRooms.collectAsState()
    var callSession by remember { mutableStateOf<NativeMatrixCallSession?>(null) }
    val account by repository.account.collectAsState()
    val recoveryState by repository.recoveryState.collectAsState()
    val backupState by repository.backupState.collectAsState()
    val verificationState by repository.verificationState.collectAsState()
    val hasDevicesToVerifyAgainst by repository.hasDevicesToVerifyAgainst.collectAsState()
    val verificationFlow by repository.verificationFlow.collectAsState()
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showSecurity by rememberSaveable { mutableStateOf(false) }
    var showSignOut by rememberSaveable { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var signOutError by rememberSaveable { mutableStateOf("") }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    var showNotificationPrompt by rememberSaveable { mutableStateOf(false) }
    val mutedRoomIds by repository.mutedRoomIds.collectAsState()
    val ignoredUsers by repository.ignoredUsers.collectAsState()
    val starredEvents by repository.starredEvents.collectAsState()
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var showBlocked by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    var showDevices by rememberSaveable { mutableStateOf(false) }
    val settings = remember { SendSettings.get(activity) }
    val theme by settings.theme.collectAsState()
    val sounds by settings.sounds.collectAsState()
    val linkPreviews by settings.linkPreviews.collectAsState()
    val ownProfile by repository.ownProfile.collectAsState()
    val pushStatus by PushRegistration.status.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val notificationsOn = remember(lifecycleState) { notificationsAllowed(activity) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PushRegistration.refreshStatus(activity)
    }
    LaunchedEffect(showSecurity) { if (showSecurity) repository.refreshSecurityStatus() }
    // Keyed on the repository's live session, not the saved signedIn flag: after process death signedIn is
    // restored as true before restore() has reopened the client.
    val sessionLive = account != null
    LaunchedEffect(sessionLive) {
        if (sessionLive) {
            callSession = repository.callSession()
            callSession?.let { activity.attachCallController(it.controller) }
        } else callSession = null
    }
    // Answer from the call notification or the incoming-call screen: join once the session is live.
    val answeredCall = activity.pendingCall.value
    LaunchedEffect(callSession, answeredCall) {
        val call = answeredCall ?: return@LaunchedEffect
        val session = callSession ?: return@LaunchedEffect
        if (call.outgoing) session.startCall(call.roomId, call.video) else session.answer(call.roomId, call.video)
        activity.pendingCall.value = null
    }
    val outgoingRing = callSession?.outgoingRing?.collectAsState()?.value
    val runningCall = callSession?.controller?.state?.collectAsState()?.value
    val callNotices = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(callSession) {
        callSession?.notices?.collect { callNotices.showSnackbar(it) }
    }
    // With a live session: UnifiedPush registration (or the background-sync fallback) and, once after the
    // first sign-in, an explanation before Android's notification permission prompt.
    LaunchedEffect(sessionLive) {
        if (!sessionLive) return@LaunchedEffect
        PushRegistration.start(activity)
        if (!notificationsAllowed(activity) && !notificationPermissionPrompted(activity)) showNotificationPrompt = true
    }
    // Read receipts follow the conversation the user is looking at.
    val conversationOnScreen = activeRoomId.takeIf {
        it.isNotBlank() && timeline != null && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    }
    val conversationShown = activeRoomId.takeIf { it.isNotBlank() && timeline != null }
    LaunchedEffect(conversationShown) { activity.showConversation(conversationShown) }
    val messages by repository.messages.collectAsState()
    // Opening a conversation (and every new arrival while it is open) sends a read receipt so unread badges clear.
    val latestMessageId = messages.lastOrNull()?.id
    LaunchedEffect(conversationOnScreen, latestMessageId) {
        val current = timeline ?: return@LaunchedEffect
        if (conversationOnScreen == null) return@LaunchedEffect
        if (latestMessageId == null) return@LaunchedEffect
        try {
            // Leaving right after opening must not cancel the receipt mid-request, or the unread badge stays.
            withContext(NonCancellable) { repository.markAsRead(current, activeRoomId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("SendApp", "Read receipt could not be sent", failure)
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val typingUsers by repository.typingUsers.collectAsState()
    // Typing notices follow the open conversation's draft: typing while it has text, stopped once it is empty
    // (sent or cleared) or after a few idle seconds. The SDK only sends a notice when the state changes or
    // the previous one is about to expire.
    val activeDraft = drafts[activeRoomId].orEmpty()
    LaunchedEffect(room, conversationOnScreen, activeDraft) {
        val current = room ?: return@LaunchedEffect
        val typing = conversationOnScreen != null && activeDraft.isNotBlank()
        repository.setTyping(current, typing)
        if (typing) {
            delay(TYPING_IDLE_MILLIS)
            repository.setTyping(current, false)
        }
    }
    /** Messages picked for forwarding (one from the long-press menu, several from "Select more"). */
    var forwarding by remember { mutableStateOf<List<MatrixMessage>?>(null) }
    val decryptionActionLabel = when {
        verificationState == VerificationState.UNVERIFIED -> "Verify this device"
        recoveryState == RecoveryState.INCOMPLETE -> "Enter recovery key"
        else -> null
    }
    // Wide windows (tablets, unfolded foldables) show the list beside the open conversation; see AdaptiveScreenHost.
    val paneWidth = listPaneWidth()
    val twoPane = paneWidth != null
    /**
     * Beside the list, full-width pages (Settings, Archived, …) give way when a conversation opens so it shows; on
     * one pane they only step aside while it is open (see their visibility below).
     */
    fun showConversationBesideList() {
        if (twoPane) {
            showSettings = false; showDevices = false; showProfile = false; showArchived = false; showBlocked = false
        }
    }
    /** Opens [roomId] (encrypted rooms only); title and kind come from the list when known, else from the SDK. */
    suspend fun openConversation(roomId: String, title: String?, direct: Boolean?) {
        val selectedRoom = repository.openEncryptedRoom(roomId)
        val selectedTimeline = repository.timeline(selectedRoom)
        repository.observeTimeline(selectedTimeline)
        repository.observeTyping(selectedRoom)
        room = selectedRoom
        timeline = selectedTimeline
        historyStartReached = false
        activeRoomId = roomId
        roomTitle = title ?: selectedRoom.displayName().orEmpty().ifBlank { roomId }
        activeRoomIsDirect = direct ?: repository.isDirectRoom(selectedRoom)
        showConversationBesideList()
    }
    // Notification tap or conversation shortcut: open that conversation once the session is live.
    val requestedConversation = activity.pendingConversation.value
    LaunchedEffect(sessionLive, requestedConversation) {
        val roomId = requestedConversation ?: return@LaunchedEffect
        if (!sessionLive) return@LaunchedEffect
        try {
            if (roomId != activeRoomId || timeline == null) {
                val known = rooms.firstOrNull { it.id == roomId }
                openConversation(roomId, known?.name, known?.direct)
                error = ""
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Conversation could not be opened."
        } finally {
            // Cleared only once handled: clearing the key earlier would cancel this effect mid-open.
            if (activity.pendingConversation.value == roomId) activity.pendingConversation.value = null
        }
    }
    /** Runs a list or star action; a failure is reported above the conversation list. */
    fun listAction(failure: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                error = problem.message ?: failure
            }
        }
    }
    val openFromList: (MatrixRoom) -> Unit = open@{ selected ->
        // Beside the list the open conversation's row (in the list or Archived) just shows it as it is.
        if (selected.id == activeRoomId && timeline != null) {
            showConversationBesideList()
            return@open
        }
        scope.launch {
            error = ""
            try {
                openConversation(selected.id, selected.name, selected.direct)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Conversation could not be opened."
            }
        }
    }
    /** Leaves the open conversation for the list. */
    fun closeConversation() {
        room?.let { left -> repository.setTyping(left, false) }
        room = null; timeline = null; activeRoomId = ""; roomTitle = ""; activeRoomIsDirect = false; error = ""
    }
    // Archive, pin, unread and delete are tags/flags/membership on each room; block is the account's ignore list.
    // Beside the list, archiving, deleting or blocking the open conversation there closes it, as from its details.
    val listActions = remember(repository) {
        ConversationListActions(
            setArchived = { picked, archived ->
                listAction(if (archived) "Conversation could not be archived." else "Conversation could not be unarchived.") {
                    repository.setArchived(picked.map { it.id }, archived)
                }
                if (archived && picked.any { it.id == activeRoomId }) closeConversation()
            },
            setPinned = { picked, pinned ->
                listAction(if (pinned) "Conversation could not be pinned." else "Conversation could not be unpinned.") {
                    repository.setPinned(picked.map { it.id }, pinned)
                }
            },
            setUnread = { picked, unread ->
                listAction("Conversation could not be marked ${if (unread) "unread" else "read"}.") {
                    repository.setMarkedUnread(picked.map { it.id }, unread)
                }
            },
            setMuted = { picked, muted ->
                listAction(if (muted) "Conversation could not be muted." else "Conversation could not be unmuted.") {
                    repository.setRoomsMuted(picked.map { it.id }, muted)
                }
            },
            delete = { picked ->
                listAction("Conversation could not be deleted.") { repository.deleteConversations(picked.map { it.id }) }
                if (picked.any { it.id == activeRoomId }) closeConversation()
            },
            block = { userId ->
                listAction("$userId could not be blocked.") { repository.blockUser(userId) }
                if (rooms.firstOrNull { it.id == activeRoomId }?.dmUserId == userId) closeConversation()
            },
        )
    }
    // Conversation details act on the open room; archive, delete and blocking the DM's person return to the list.
    val detailsActions = remember(activeRoomId) {
        val roomId = activeRoomId
        if (roomId.isBlank()) null else ConversationDetailsActions(
            load = { repository.roomDetails(roomId) },
            setNotifications = { level -> repository.setRoomNotifications(roomId, level) },
            invite = { userId -> repository.inviteToRoom(roomId, userId) },
            rename = { name -> repository.renameRoom(roomId, name) },
            setTopic = { topic -> repository.setRoomTopic(roomId, topic) },
            setAvatar = { uri -> repository.setRoomAvatar(roomId, uri) },
            archive = {
                listAction("Conversation could not be archived.") { repository.setArchived(listOf(roomId), true) }
                closeConversation()
            },
            delete = {
                listAction("Conversation could not be deleted.") { repository.deleteConversations(listOf(roomId)) }
                closeConversation()
            },
            block = { userId ->
                listAction("$userId could not be blocked.") { repository.blockUser(userId) }
                if (rooms.firstOrNull { it.id == roomId }?.dmUserId == userId) closeConversation()
            },
            message = { userId ->
                scope.launch {
                    error = ""
                    try {
                        openConversation(repository.directRoomWith(userId), null, true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Conversation could not be opened."
                    }
                }
            },
        )
    }
    fun signOut(serverSide: Boolean) {
        signingOut = true
        signOutError = ""
        scope.launch {
            try {
                repository.logout(serverSide)
                // Back to the sign-in hero with nothing from the old session left in UI state.
                showSignOut = false
                showNotifications = false
                showSecurity = false
                showSettings = false
                showProfile = false
                showDevices = false
                room = null
                timeline = null
                activeRoomId = ""
                roomTitle = ""
                activeRoomIsDirect = false
                drafts = emptyMap()
                notice = ""
                error = ""
                homeserver = ""
                user = ""
                password = ""
                signedIn = false
                activity.pendingShare.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                signOutError = failure.message ?: "Your homeserver couldn't be reached."
            } finally {
                signingOut = false
            }
        }
    }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val payload = result.contents
        if (payload.isNullOrBlank()) {
            error = "QR scanning was cancelled."
        } else {
            error = ""
            activeJob = scope.launch {
                try {
                    repository.qrLogin(homeserver, payload)
                    signedIn = true
                    repository.startSync()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "QR sign-in failed."
                }
            }
        }
    }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            qrScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the Matrix login QR code").setBeepEnabled(false))
        } else error = "Camera permission is required to scan a Matrix login QR code."
    }

    LaunchedEffect(repository, activity.ssoCallback.value) {
        val callback = activity.ssoCallback.value ?: return@LaunchedEffect
        waitingForSso = false
        activeJob = scope.launch {
            try {
                repository.finishSsoLogin(callback.toString())
                signedIn = true
                repository.startSync()
            } catch (failure: Exception) {
                repository.cancelSsoLogin()
                error = failure.message ?: "SSO sign-in was cancelled or could not be completed."
            }
        }
    }

    LaunchedEffect(repository) {
        loading = true
        try {
            signedIn = repository.restore()
            if (signedIn) {
                repository.startSync()
                if (activeRoomId.isNotBlank()) {
                    val restoredRoom = repository.openEncryptedRoom(activeRoomId)
                    val restoredTimeline = repository.timeline(restoredRoom)
                    repository.observeTimeline(restoredTimeline)
                    repository.observeTyping(restoredRoom)
                    room = restoredRoom
                    timeline = restoredTimeline
                    historyStartReached = false
                    activeRoomIsDirect = repository.isDirectRoom(restoredRoom)
                }
            }
        } catch (failure: Exception) {
            error = failure.message ?: "Could not restore the Matrix session."
        } finally { loading = false }
    }

    val sendCallStyle = rememberSendCallStyle()
    val avatarLoader: AvatarLoader? = remember(sessionLive) { if (sessionLive) repository::avatarBitmap else null }
    androidx.compose.runtime.CompositionLocalProvider(LocalAvatarLoader provides avatarLoader) {
    Box(Modifier.fillMaxSize()) {
    ElementCallOverlay(
        controller = callSession?.controller,
        style = sendCallStyle,
    ) { overlayModifier ->
    Surface(Modifier.fillMaxSize().then(overlayModifier), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        // Messages' fragment-style open/close transitions between top-level screens; the conversation's avatar and
        // name travel between its list row and its header.
        val screen = when {
            loading -> 0
            !signedIn -> 1
            room != null && timeline != null -> 3
            else -> 2
        }
        // Closing clears the active room at once; the leaving conversation keeps its room's name, avatar and shared
        // element keys through the close transition.
        val shownRoomId = remember { mutableStateOf(activeRoomId) }.apply { if (activeRoomId.isNotBlank()) value = activeRoomId }.value
        val shownTitle = remember { mutableStateOf(roomTitle) }.apply { if (roomTitle.isNotBlank()) value = roomTitle }.value
        // The conversation list: the list screen on one pane, the start pane beside the conversation on two.
        val conversationList: @Composable () -> Unit = {
            HomeScreen(
                rooms = rooms,
                mutedRoomIds = mutedRoomIds,
                blockedUserIds = ignoredUsers,
                listActions = listActions,
                onOpen = openFromList,
                error = error,
                notice = notice,
                creatingDirectRoom = activeJob?.isActive == true,
                onCreateDirectRoom = { matrixUserId ->
                    error = ""
                    notice = "Creating encrypted conversation…"
                    activeJob = scope.launch {
                        try {
                            repository.createEncryptedDirectRoom(matrixUserId)
                            notice = "Encrypted conversation created."
                        } catch (failure: Exception) {
                            error = failure.message ?: "Conversation could not be created."
                        }
                    }
                },
                onCreateGroup = { name, members ->
                    error = ""
                    notice = "Creating encrypted group…"
                    activeJob = scope.launch {
                        try {
                            repository.createEncryptedGroup(name, members)
                            notice = "Encrypted group created."
                        } catch (failure: Exception) {
                            error = failure.message ?: "Group could not be created."
                        }
                    }
                },
                onAccount = { showAccount = true },
                onVerifyDevice = if (verificationState == VerificationState.UNVERIFIED && hasDevicesToVerifyAgainst) {
                    { showSecurity = true }
                } else null,
                openRoomId = activeRoomId.takeIf { twoPane && it.isNotBlank() },
            )
        }
        AdaptiveScreenHost(screen, paneWidth, list = conversationList) { target ->
        when (target) {
            0 -> Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Connecting to Matrix…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
            1 -> SignInScreen(
                homeserver = homeserver,
                onHomeserver = { homeserver = it },
                user = user,
                onUser = { user = it },
                password = password,
                onPassword = { password = it },
                busy = activeJob?.isActive == true,
                error = error,
                onCancel = { activeJob?.cancel(); activeJob = null; waitingForSso = false; repository.cancelSsoLogin() },
                ssoPending = waitingForSso,
                onCancelSso = { waitingForSso = false; repository.cancelSsoLogin(); error = "SSO sign-in was cancelled." },
                onQrLogin = {
                    error = ""
                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        qrScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the Matrix login QR code").setBeepEnabled(false))
                    } else requestCamera.launch(Manifest.permission.CAMERA)
                },
                onSsoLogin = {
                    error = ""
                    activeJob = scope.launch {
                        try {
                            val url = repository.beginSsoLogin(homeserver)
                            waitingForSso = true
                            CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
                        } catch (failure: Exception) {
                            waitingForSso = false
                            repository.cancelSsoLogin()
                            error = failure.message ?: "Could not start SSO sign-in."
                        }
                    }
                },
                onLogin = {
                    error = ""
                    activeJob = scope.launch {
                        try {
                            repository.passwordLogin(homeserver, user, password)
                            signedIn = true
                            password = ""
                            repository.startSync()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Matrix sign-in failed."
                        }
                    }
                }
            )
            // Keyed by room: beside the list another conversation can open in place of this one.
            else -> androidx.compose.runtime.key(shownRoomId) { ConversationScreen(
                // The list entry follows renames (here or by others); the title from opening covers new rooms.
                title = rooms.firstOrNull { it.id == shownRoomId }?.name ?: shownTitle,
                isDirect = activeRoomIsDirect,
                draft = drafts[activeRoomId].orEmpty(),
                onDraft = { value -> drafts = drafts + (activeRoomId to value) },
                messages = messages,
                onBack = { closeConversation() },
                onSend = { replyTo ->
                    val currentRoom = room
                    val currentTimeline = timeline
                    val roomId = activeRoomId
                    val sentText = drafts[roomId].orEmpty()
                    if (currentRoom != null && currentTimeline != null && sentText.isNotBlank()) {
                        scope.launch {
                            error = ""
                            try {
                                repository.sendText(currentTimeline, currentRoom, sentText, replyTo?.id)
                                if (drafts[roomId] == sentText) drafts = drafts - roomId
                                // Recently messaged conversations are offered in the system share sheet.
                                rooms.firstOrNull { it.id == roomId }?.let { MessageNotifier.publishConversationShortcut(activity, it.id, it.name, it.dmUserId) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) { error = failure.message ?: "Message could not be sent." }
                        }
                    }
                },
                onEdit = { message, text ->
                    val currentRoom = room
                    val currentTimeline = timeline
                    if (currentRoom != null && currentTimeline != null) scope.launch {
                        error = ""
                        try { repository.editText(currentTimeline, currentRoom, message.id, text) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Message could not be edited." }
                    }
                },
                onDelete = { message ->
                    val currentRoom = room
                    val currentTimeline = timeline
                    if (currentRoom != null && currentTimeline != null) scope.launch {
                        error = ""
                        try { repository.redact(currentTimeline, currentRoom, message.id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Message could not be deleted." }
                    }
                },
                onForward = { picked -> forwarding = picked },
                onLoadUntil = { eventId ->
                    val currentTimeline = timeline
                    if (currentTimeline != null) scope.launch {
                        try {
                            if (!repository.paginateUntil(currentTimeline, eventId)) error = "The original message isn't available."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Older messages could not be loaded."
                        }
                    }
                },
                typingUsers = typingUsers,
                loadLinkPreview = if (linkPreviews) repository::linkPreview else null,
                onOlder = {
                    val current = timeline
                    if (current != null) scope.launch {
                        try {
                            if (repository.paginateOlder(current) && timeline === current) historyStartReached = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) { error = failure.message ?: "Older messages could not be loaded." }
                    }
                },
                hasOlder = !historyStartReached,
                onReact = { eventId, emoji ->
                    val currentRoom = room
                    val currentTimeline = timeline
                    if (currentRoom != null && currentTimeline != null) scope.launch {
                        try { repository.react(currentTimeline, currentRoom, eventId, emoji) }
                        catch (failure: Exception) { error = failure.message ?: "Reaction could not be sent." }
                    }
                },
                onCall = { video -> callSession?.startCall(activeRoomId, video) },
                callBanner = rooms.firstOrNull { it.id == activeRoomId }
                    ?.takeIf { it.callActive && it.callParticipants.none { member -> member == account?.userId } && runningCall?.callData?.roomId?.value != activeRoomId }
                    ?.let { RoomCallBanner(participantCount = it.callParticipants.size, video = it.callVideo) },
                onJoinCall = {
                    val video = rooms.firstOrNull { it.id == activeRoomId }?.callVideo == true
                    callSession?.answer(activeRoomId, video)
                },
                onSendAttachment = { uri ->
                    val currentRoom = room
                    val currentTimeline = timeline
                    if (currentRoom != null && currentTimeline != null) scope.launch {
                        error = ""
                        pendingUploads += 1
                        try { repository.sendAttachment(currentTimeline, currentRoom, uri) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Attachment could not be sent." }
                        finally { pendingUploads -= 1 }
                    }
                },
                onSendVoice = { recording ->
                    val currentRoom = room
                    val currentTimeline = timeline
                    if (currentRoom != null && currentTimeline != null) scope.launch {
                        error = ""
                        pendingUploads += 1
                        try { repository.sendVoiceMessage(currentTimeline, currentRoom, recording) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Voice message could not be sent." }
                        finally { pendingUploads -= 1 }
                    } else recording.file.delete()
                },
                loadMedia = repository::mediaFile,
                uploading = pendingUploads > 0,
                error = error,
                decryptionActionLabel = decryptionActionLabel,
                onDecryptionAction = { showSecurity = true },
                muted = activeRoomId in mutedRoomIds,
                onToggleMute = {
                    val roomId = activeRoomId
                    val mute = roomId !in mutedRoomIds
                    scope.launch {
                        try {
                            repository.setRoomMuted(roomId, mute)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: if (mute) "Conversation could not be muted." else "Conversation could not be unmuted."
                        }
                    }
                },
                starredIds = starredEvents[activeRoomId].orEmpty().toSet(),
                onToggleStar = { eventId, star ->
                    val roomId = activeRoomId
                    listAction(if (star) "Message could not be starred." else "Message could not be unstarred.") {
                        repository.setStarred(roomId, eventId, star)
                    }
                },
                loadStarred = { repository.starredMessages(activeRoomId) },
                avatarUrl = rooms.firstOrNull { it.id == shownRoomId }?.avatarUrl,
                details = detailsActions,
                detailsRefreshKey = rooms.firstOrNull { it.id == activeRoomId },
                sounds = sounds,
                sharedKey = shownRoomId,
            ) }
        }
        }
        if (signedIn) {
            // Forward: pick another encrypted conversation; the copy is sent there and that conversation opens.
            val forwardTarget = forwarding
            val shownForward = remember { mutableStateOf<List<MatrixMessage>?>(null) }.apply { if (forwardTarget != null) value = forwardTarget }
            AnimatedVisibility(visible = forwardTarget != null && timeline != null, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
                ForwardScreen(
                    rooms = encryptedTargets(rooms, ignoredUsers, exclude = activeRoomId),
                    mutedRoomIds = mutedRoomIds,
                    preview = shownForward.value?.let { picked -> picked.singleOrNull()?.let(::messagePreviewText) ?: "${picked.size} messages" }.orEmpty(),
                    onPick = { target ->
                        val currentTimeline = timeline
                        val picked = forwardTarget
                        forwarding = null
                        if (currentTimeline != null && picked != null) scope.launch {
                            error = ""
                            try {
                                for (message in picked) repository.forward(currentTimeline, message.id, target.id)
                                openConversation(target.id, target.name, target.direct)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Message could not be forwarded."
                            }
                        }
                    },
                    onBack = { forwarding = null },
                )
            }
            // Shared from another app: once signed in, pick an encrypted conversation (or the direct-share one) and
            // confirm; the content goes out through the encrypted send paths and that conversation opens.
            val share = activity.pendingShare.value
            if (share != null && sessionLive) {
                ShareFlow(
                    share = share,
                    rooms = encryptedTargets(rooms, ignoredUsers),
                    mutedRoomIds = mutedRoomIds,
                    onSend = { target ->
                        activity.pendingShare.value = null
                        scope.launch {
                            error = ""
                            pendingUploads += 1
                            try {
                                openConversation(target.id, target.name, target.direct)
                                repository.sendShared(checkNotNull(timeline), checkNotNull(room), share.text, share.files)
                                MessageNotifier.publishConversationShortcut(activity, target.id, target.name, target.dmUserId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Couldn't share to ${target.name}."
                            } finally {
                                pendingUploads -= 1
                                ShareTarget.discard(activity, share.id)
                            }
                        }
                    },
                    onDiscard = {
                        activity.pendingShare.value = null
                        ShareTarget.discard(activity, share.id)
                    },
                )
            }
            // Account menu → Settings (and its Devices page) and the account card's Profile open full-width. On one
            // pane they, like Archived, step aside while a conversation opened from them is on screen; beside the
            // list they show over it (opening a conversation there closes them, see openConversation).
            val pagesShown = timeline == null || twoPane
            AnimatedVisibility(visible = showSettings && pagesShown, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
                SettingsScreen(
                    notificationSummary = notificationSummary(activity, notificationsOn, pushStatus),
                    onNotifications = { showNotifications = true },
                    sounds = sounds,
                    onSounds = settings::setSounds,
                    theme = theme,
                    onTheme = settings::setTheme,
                    linkPreviews = linkPreviews,
                    onLinkPreviews = settings::setLinkPreviews,
                    securitySummary = securitySummary(verificationState, recoveryState),
                    onSecurity = { showSecurity = true },
                    onDevices = { showDevices = true },
                    onBlocked = { showBlocked = true },
                    onArchived = { showArchived = true },
                    onAbout = { showAbout = true },
                    onBack = { showSettings = false },
                )
            }
            AnimatedVisibility(visible = showDevices && pagesShown, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
                DevicesScreen(
                    load = repository::devices,
                    signOut = { deviceId, password, session -> repository.signOutDevice(deviceId, password, session) },
                    onBack = { showDevices = false },
                )
            }
            AnimatedVisibility(visible = showProfile && pagesShown, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
                ProfileScreen(
                    userId = account?.userId.orEmpty(),
                    profile = ownProfile,
                    setName = { name -> repository.setDisplayName(name) },
                    setAvatar = { uri -> repository.setProfileAvatar(uri) },
                    removeAvatar = { repository.removeProfileAvatar() },
                    onBack = { showProfile = false },
                )
            }
            // Account menu → Archived; on one pane hidden (not closed) while a conversation opened from it is on screen.
            AnimatedVisibility(visible = showArchived && pagesShown, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
                ArchivedScreen(
                    rooms = archivedConversations(rooms, ignoredUsers),
                    mutedRoomIds = mutedRoomIds,
                    actions = listActions,
                    onOpen = openFromList,
                    onBack = { showArchived = false },
                )
            }
            AnimatedVisibility(visible = showBlocked, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
                BlockedContactsScreen(
                    userIds = ignoredUsers.sorted(),
                    onUnblock = { userId -> listAction("$userId could not be unblocked.") { repository.unblockUser(userId) } },
                    onBack = { showBlocked = false },
                )
            }
            // Security and verification sit above both the list and an open conversation.
            AnimatedVisibility(visible = showSecurity, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
                SecurityScreen(
                    account = account,
                    verification = verificationState,
                    recovery = recoveryState,
                    backup = backupState,
                    hasOtherDevices = hasDevicesToVerifyAgainst,
                    onBack = { showSecurity = false },
                    onVerifyWithDevice = { scope.launch { repository.requestDeviceVerification() } },
                    enableRecovery = { onProgress -> repository.enableRecovery(onProgress) },
                    recover = { key -> repository.recover(key) },
                )
            }
            val flow = verificationFlow
            AnimatedVisibility(
                visible = flow != VerificationFlow.Idle && flow !is VerificationFlow.Incoming,
                enter = SendMotion.openEnter,
                exit = SendMotion.closeExit,
            ) {
                VerificationScreen(
                    flow = flow,
                    onMatch = { scope.launch { repository.confirmVerification(matches = true) } },
                    onMismatch = { scope.launch { repository.confirmVerification(matches = false) } },
                    onCancel = { scope.launch { repository.cancelVerification() } },
                    onDone = repository::dismissVerification,
                )
            }
            if (flow is VerificationFlow.Incoming) {
                VerificationRequestDialog(
                    request = flow,
                    onAccept = { scope.launch { repository.acceptVerificationRequest() } },
                    onDecline = { scope.launch { repository.cancelVerification() } },
                )
            }
            if (showAccount) {
                AccountSheet(
                    userId = account?.userId.orEmpty(),
                    profile = ownProfile,
                    onProfile = { showAccount = false; showProfile = true },
                    securitySummary = securitySummary(verificationState, recoveryState),
                    onSecurity = { showAccount = false; showSecurity = true },
                    onSettings = { showAccount = false; showSettings = true },
                    onArchived = { showAccount = false; showArchived = true },
                    onBlocked = { showAccount = false; showBlocked = true },
                    onSignOut = { showAccount = false; signOutError = ""; showSignOut = true },
                    onDismiss = { showAccount = false },
                )
            }
            if (showAbout) LegalNoticesDialog(onDismiss = { showAbout = false })
            if (showNotifications) NotificationsDialog(onDismiss = { showNotifications = false })
            if (showNotificationPrompt) {
                NotificationPermissionDialog(
                    onTurnOn = {
                        markNotificationPermissionPrompted(activity)
                        showNotificationPrompt = false
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onNotNow = {
                        markNotificationPermissionPrompted(activity)
                        showNotificationPrompt = false
                    },
                )
            }
            if (showSignOut) {
                SignOutDialog(
                    recoveryEnabled = recoveryState == RecoveryState.ENABLED,
                    busy = signingOut,
                    error = signOutError,
                    onSetUpRecovery = { showSignOut = false; showSecurity = true },
                    onSignOut = { signOut(serverSide = true) },
                    onSignOutLocally = { signOut(serverSide = false) },
                    onDismiss = { showSignOut = false },
                )
            }
        }
    }
    }
    if (outgoingRing != null && runningCall?.isMaximized == true) {
        CallingIndicator(name = outgoingRing.name, modifier = Modifier.align(Alignment.TopCenter))
    }
    androidx.compose.material3.SnackbarHost(
        hostState = callNotices,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp),
    )
    }
    }
}

@Composable
internal fun SignInScreen(
    homeserver: String,
    onHomeserver: (String) -> Unit,
    user: String,
    onUser: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    busy: Boolean,
    error: String,
    onCancel: () -> Unit,
    ssoPending: Boolean,
    onCancelSso: () -> Unit,
    onQrLogin: () -> Unit,
    onSsoLogin: () -> Unit,
    onLogin: () -> Unit,
) {
    // Step 0 = Pixel welcome hero; Step 1 = homeserver & auth setup.
    var step by rememberSaveable { mutableStateOf(if (homeserver.isNotBlank() || error.isNotBlank() || busy || ssoPending) 1 else 0) }
    var showPasswordLogin by rememberSaveable { mutableStateOf(false) }
    var showLegal by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    androidx.activity.compose.BackHandler(enabled = step == 1 && !busy) { step = 0 }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = colors.primaryContainer.copy(alpha = 0.35f),
                radius = size.minDimension * 0.65f,
                center = Offset(size.width * 0.85f, size.height * 0.1f),
            )
            drawCircle(
                color = colors.secondaryContainer.copy(alpha = 0.25f),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.15f, size.height * 0.35f),
            )
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (if (targetState > initialState) SendMotion.open else SendMotion.close).using(SizeTransform(clip = false))
            },
            label = "OnboardingStepTransition",
        ) { currentStep ->
            if (currentStep == 0) {
                OnboardingHeroStep(onHomeserver = onHomeserver, homeserverBlank = homeserver.isBlank(), onNext = { step = 1 }, onLegal = { showLegal = true })
            } else {
                OnboardingSetupStep(
                    homeserver = homeserver,
                    onHomeserver = onHomeserver,
                    user = user,
                    onUser = onUser,
                    password = password,
                    onPassword = onPassword,
                    busy = busy,
                    error = error,
                    onCancel = onCancel,
                    ssoPending = ssoPending,
                    onCancelSso = onCancelSso,
                    onQrLogin = onQrLogin,
                    onSsoLogin = onSsoLogin,
                    onLogin = onLogin,
                    onBack = { step = 0 },
                    onLegal = { showLegal = true },
                )
            }
        }
    }
    if (showLegal) {
        LegalNoticesDialog(onDismiss = { showLegal = false })
    }
}

@Composable
private fun OnboardingHeroStep(onHomeserver: (String) -> Unit, homeserverBlank: Boolean, onNext: () -> Unit, onLegal: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = colors.surfaceContainerHigh.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Filled.Lock, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(text = "Send · Matrix E2EE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            }
        }
        Spacer(Modifier.height(8.dp))
        // Fixed height: never collapses on narrow viewports, scrolls instead.
        Box(modifier = Modifier.height(240.dp).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            PixelOnboardingHero(modifier = Modifier.size(230.dp))
        }
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Messages that stay yours",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Fast, expressive, end-to-end encrypted messaging powered by your own Matrix homeserver.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FeatureChip("End-to-End Encrypted")
                FeatureChip("Open Matrix Standard")
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (homeserverBlank) onHomeserver("matrix.org")
                    onNext()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text(text = "Get started", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onLegal) {
                Text(text = "About & open-source licenses", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OnboardingSetupStep(
    homeserver: String,
    onHomeserver: (String) -> Unit,
    user: String,
    onUser: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    busy: Boolean,
    error: String,
    onCancel: () -> Unit,
    ssoPending: Boolean,
    onCancelSso: () -> Unit,
    onQrLogin: () -> Unit,
    onSsoLogin: () -> Unit,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onLegal: () -> Unit,
) {
    var showPasswordLogin by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    // Password-manager path: a full Matrix ID in the username field supplies the server.
    val serverReady = effectiveServerInput(homeserver, user).isNotBlank()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                enabled = !busy,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceContainerHigh),
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.onSurface)
            }
            Spacer(Modifier.width(12.dp))
            SendBrandLogo(size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(text = "Send", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = "Matrix Account Setup", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(text = "Connect your homeserver", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Choose where your encrypted conversations live, then sign in with a QR code, SSO, or password.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainerLow,
            border = BorderStroke(1.dp, colors.surfaceContainerHighest),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(text = "Server", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.primary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    listOf("matrix.org" to "https://matrix.org", "element.io" to "https://matrix-client.matrix.org").forEach { (label, url) ->
                        val host = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
                        val typed = homeserver.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
                        val selected = typed.equals(url, ignoreCase = true) || typed.equals(host, ignoreCase = true)
                        Surface(
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !busy) { onHomeserver(url) },
                            shape = RoundedCornerShape(50),
                            color = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = homeserver,
                    onValueChange = onHomeserver,
                    label = { Text("Server address") },
                    placeholder = { Text("reilly.asia or @you:reilly.asia") },
                    supportingText = { Text("Bare server names work — connection details are looked up automatically.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    shape = RoundedCornerShape(18.dp),
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onQrLogin,
                    enabled = !busy && serverReady,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(27.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp, color = colors.onPrimary)
                        Spacer(Modifier.width(10.dp))
                        Text("Connecting…", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    } else {
                        Text("Scan QR code", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedButton(
                    onClick = onSsoLogin,
                    enabled = !busy && serverReady,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                ) {
                    Text("Continue with SSO", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = colors.surfaceContainerLow,
            border = BorderStroke(1.dp, colors.surfaceContainerHighest),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { showPasswordLogin = !showPasswordLogin }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Use username and password", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(text = if (showPasswordLogin) "−" else "+", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                }
                AnimatedVisibility(
                    visible = showPasswordLogin,
                    // Messages: size changes on a no-bounce StiffnessMedium spring, fades on the M3 effects spring.
                    enter = fadeIn(SendMotion.effectsDefault()) + expandVertically(SendMotion.sizeChange(), expandFrom = Alignment.Top),
                    exit = fadeOut(SendMotion.effectsFast()) + shrinkVertically(SendMotion.sizeChange(), shrinkTowards = Alignment.Top),
                ) {
                    Column(modifier = Modifier.padding(bottom = 10.dp)) {
                        OutlinedTextField(
                            value = user,
                            onValueChange = onUser,
                            label = { Text("Username") },
                            placeholder = { Text("you or @you:reilly.asia") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            shape = RoundedCornerShape(18.dp),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = onPassword,
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            enabled = !busy,
                            shape = RoundedCornerShape(18.dp),
                        )
                        Button(
                            onClick = onLogin,
                            enabled = !busy && serverReady && user.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                        ) {
                            Text("Sign in with password")
                        }
                    }
                }
            }
        }
        if (error.isNotBlank()) {
            Surface(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(16.dp), color = colors.errorContainer) {
                Text(error, color = colors.onErrorContainer, modifier = Modifier.padding(16.dp))
            }
        }
        if (busy) {
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel sign-in") }
        }
        if (ssoPending) {
            TextButton(onClick = onCancelSso, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel SSO sign-in") }
        }
        TextButton(onClick = onLegal, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("About & licenses") }
    }
}

@Composable
private fun FeatureChip(label: String) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(50), color = colors.surfaceContainerHigh) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
internal fun SendBrandLogo(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    // Google-style vector mark: two overlapping translucent bubbles with a darker
    // lens intersection, drawn dynamically in Material You theme colors.
    val colors = MaterialTheme.colorScheme
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val radius = 0.31f * w
        val leftCenter = Offset(0.38f * w, 0.44f * h)
        val rightCenter = Offset(0.62f * w, 0.44f * h)
        drawCircle(color = colors.primary.copy(alpha = 0.82f), radius = radius, center = leftCenter)
        drawCircle(color = colors.tertiary.copy(alpha = 0.82f), radius = radius, center = rightCenter)
        drawCircle(color = colors.primary.copy(alpha = 0.55f), radius = radius * 0.52f, center = Offset((leftCenter.x + rightCenter.x) / 2f, (leftCenter.y + rightCenter.y) / 2f))
        val tailPath = Path().apply {
            moveTo(0.30f * w, 0.78f * h)
            lineTo(0.42f * w, 0.78f * h)
            lineTo(0.33f * w, 0.94f * h)
            close()
        }
        drawPath(path = tailPath, color = colors.primary.copy(alpha = 0.82f))
        val shield = Offset(0.74f * w, 0.76f * h)
        val shieldR = 0.16f * w
        drawCircle(color = colors.surface, radius = shieldR * 1.35f, center = shield)
        drawCircle(color = colors.primary, radius = shieldR, center = shield)
        drawLine(
            color = colors.onPrimary,
            start = Offset(shield.x - shieldR * 0.35f, shield.y),
            end = Offset(shield.x - shieldR * 0.02f, shield.y + shieldR * 0.35f),
            strokeWidth = shieldR * 0.28f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = colors.onPrimary,
            start = Offset(shield.x - shieldR * 0.02f, shield.y + shieldR * 0.35f),
            end = Offset(shield.x + shieldR * 0.42f, shield.y - shieldR * 0.32f),
            strokeWidth = shieldR * 0.28f,
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            color = colors.outline.copy(alpha = 0.35f),
            topLeft = Offset(0.06f * w, 0.06f * h),
            size = Size(0.88f * w, 0.88f * h),
            cornerRadius = CornerRadius(0.26f * w, 0.26f * h),
            style = Stroke(width = (0.035f * w).coerceAtLeast(2f)),
        )
    }
}

@Composable
internal fun PixelOnboardingHero(modifier: Modifier = Modifier) {
    // Pixel hero: rotating 8-sided Material scallop, sinusoidally bobbing orbital
    // chat bubbles, and a softly orbiting E2EE lock badge.
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "PixelHero")
    val rotation by transition.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(animation = tween(24000, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "ScallopRotation")
    val bobA by transition.animateFloat(initialValue = -1f, targetValue = 1f, animationSpec = infiniteRepeatable(animation = tween(1800, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "BobA")
    val bobB by transition.animateFloat(initialValue = 1f, targetValue = -1f, animationSpec = infiniteRepeatable(animation = tween(2200, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "BobB")
    val orbit by transition.animateFloat(initialValue = 0f, targetValue = (2f * PI).toFloat(), animationSpec = infiniteRepeatable(animation = tween(9000, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "LockOrbit")
    Box(modifier = Modifier.size(280.dp).then(modifier), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation }) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseR = size.minDimension * 0.38f
            val points = 8
            val scallop = Path()
            val steps = points * 16
            for (i in 0..steps) {
                val angle = (i.toFloat() / steps) * 2f * PI.toFloat()
                val lobe = 0.5f + 0.5f * cos(points * angle)
                val r = baseR * (0.86f + 0.14f * lobe)
                val x = cx + r * cos(angle)
                val y = cy + r * sin(angle)
                if (i == 0) scallop.moveTo(x, y) else scallop.lineTo(x, y)
            }
            scallop.close()
            drawPath(path = scallop, color = colors.primaryContainer.copy(alpha = 0.75f))
            rotate(degrees = 22.5f) {
                drawPath(path = scallop, color = colors.secondaryContainer.copy(alpha = 0.35f))
            }
        }
        val bubbleOffsetA = (bobA * 10f).dp
        val bubbleOffsetB = (bobB * 12f).dp
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp),
            color = colors.surfaceContainerHigh,
            tonalElevation = 2.dp,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 36.dp, start = 8.dp).offset(y = bubbleOffsetA),
        ) {
            Text(text = "Hey, are we still on? ✨", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp),
            color = colors.primary,
            contentColor = colors.onPrimary,
            tonalElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 52.dp, end = 4.dp).offset(y = bubbleOffsetB),
        ) {
            Text(text = "Absolutely 🙌", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val orbitR = with(LocalDensity.current) { 96.dp.toPx() }
            Surface(
                shape = CircleShape,
                color = colors.primary,
                contentColor = colors.onPrimary,
                tonalElevation = 4.dp,
                modifier = Modifier.graphicsLayer {
                    translationX = cos(orbit) * orbitR
                    translationY = sin(orbit) * orbitR
                }.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(imageVector = Icons.Filled.Lock, contentDescription = "End-to-end encrypted", modifier = Modifier.size(20.dp))
                }
            }
        }
        SendBrandLogo(size = 64.dp, modifier = Modifier.align(Alignment.Center))
    }
}


@Composable
internal fun HomeScreen(
    rooms: List<MatrixRoom>,
    onOpen: (MatrixRoom) -> Unit,
    error: String,
    notice: String,
    creatingDirectRoom: Boolean,
    onCreateDirectRoom: (String) -> Unit,
    onCreateGroup: (name: String, members: List<String>) -> Unit,
    onAccount: () -> Unit,
    /** Non-null while this session is unverified and another session could verify it; shows the banner. */
    onVerifyDevice: (() -> Unit)?,
    /** Conversations muted with a MUTE push rule; their rows show the muted icon. */
    mutedRoomIds: Set<String> = emptySet(),
    /** People this account blocked; their DMs stay out of the list. */
    blockedUserIds: Set<String> = emptySet(),
    /** Swipe-to-archive, long-press selection and its bar; without them rows only open. */
    listActions: ConversationListActions? = null,
    /** The conversation open beside the list (two panes); its row is highlighted. */
    openRoomId: String? = null,
) {
    var showingNewChat by rememberSaveable { mutableStateOf(false) }
    var matrixUserId by rememberSaveable { mutableStateOf("") }
    var verifyBannerDismissed by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val inboxRooms = inboxConversations(rooms, blockedUserIds)
    val visibleRooms = if (query.isBlank()) inboxRooms else inboxRooms.filter { it.name.contains(query, ignoreCase = true) }
    val colors = MaterialTheme.colorScheme
    val listState = rememberConversationListState()
    val rows = rememberTopAnchoredListState(visibleRooms.firstOrNull()?.id)
    val fabExpanded = rememberFabExpanded(rows)
    PruneSelection(listState, visibleRooms)
    val selectedRooms = listState.selectedIds.mapNotNull { id -> visibleRooms.firstOrNull { it.id == id } }
    BackHandler(enabled = searching && !listState.selecting) { searching = false; query = "" }
    Scaffold(
        containerColor = colors.surfaceContainer,
        snackbarHost = { androidx.compose.material3.SnackbarHost(listState.snackbar) },
        topBar = { if (listActions != null && listState.selecting) {
            ConversationSelectionBar(selectedRooms, mutedRoomIds, listState, listActions, archivedView = false)
        } else {
            // Messages: plain top bar — wordmark left, search + account right; no pill.
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    IconButton(onClick = { searching = false; query = "" }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                    }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search conversations") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                } else {
                    SendBrandLogo(size = 28.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Send",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { searching = true }) {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = "Search", tint = colors.onSurface)
                    }
                    IconButton(onClick = onAccount) {
                        Icon(imageVector = Icons.Filled.AccountCircle, contentDescription = "Account", tint = colors.primary, modifier = Modifier.size(32.dp))
                    }
                }
            }
        } },
        floatingActionButton = {
            // Messages: "Start chat" collapses to its icon while scrolling down and expands scrolling up or at the top.
            CollapsingExtendedFab(
                expanded = fabExpanded,
                onClick = { showingNewChat = true },
                containerColor = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                text = { Text("Start chat", style = MaterialTheme.typography.titleMedium, maxLines = 1) },
            )
        },
    ) { innerPadding ->
        // List sits on a rounded-top sheet below the header, as in Messages.
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.surface,
        ) {
        LazyColumn(
            state = rows,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 96.dp),
        ) {
            if (onVerifyDevice != null && !verifyBannerDismissed) {
                item(key = "verify-banner") {
                    VerifyDeviceBanner(onVerify = onVerifyDevice, onDismiss = { verifyBannerDismissed = true })
                }
            }
            if (error.isNotBlank()) item { Text(error, color = colors.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            if (notice.isNotBlank()) item { Text(notice, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            if (visibleRooms.isEmpty()) {
                item {
                    Text(
                        when {
                            inboxRooms.isNotEmpty() -> "No conversations match “$query”."
                            rooms.any { it.encrypted && it.archived } -> "No conversations here. Archived conversations are under Archived in the account menu."
                            rooms.any { !it.encrypted } -> "Unencrypted rooms are hidden. Send only opens end-to-end encrypted conversations."
                            else -> "No conversations are available yet."
                        },
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            } else {
                managedConversationRows(visibleRooms, mutedRoomIds, listState, listActions, archivedView = false, onOpen = onOpen, openRoomId = openRoomId)
            }
        }
        }
    }
    if (listActions != null) ConversationListDialogs(listState, listActions)
    BackHandler(enabled = showingNewChat && !creatingDirectRoom) { showingNewChat = false }
    // Messages opens "New chat" with its fragment open transition and closes it with the close one.
    AnimatedVisibility(visible = showingNewChat, enter = SendMotion.openEnter, exit = SendMotion.closeExit) {
        NewConversationScreen(
            value = matrixUserId,
            onValue = { matrixUserId = it },
            creating = creatingDirectRoom,
            onBack = { if (!creatingDirectRoom) showingNewChat = false },
            onCreate = {
                onCreateDirectRoom(matrixUserId)
                showingNewChat = false
            },
            onCreateGroup = { name, members ->
                onCreateGroup(name, members)
                matrixUserId = ""
                showingNewChat = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewConversationScreen(
    value: String,
    onValue: (String) -> Unit,
    creating: Boolean,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onCreateGroup: (name: String, members: List<String>) -> Unit,
) {
    // Full-screen Messages-style picker: To: bar on top, contact row below. No popup dialog.
    val colors = MaterialTheme.colorScheme
    var groupMode by rememberSaveable { mutableStateOf(false) }
    var members by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var groupName by rememberSaveable { mutableStateOf("") }
    val typed = value.trim()
    val typedValid = isMatrixUserId(typed)
    val valid = !creating && if (groupMode) members.isNotEmpty() || typedValid else typedValid
    fun addTypedMember() {
        if (typedValid && typed !in members) members = members + typed
        if (typedValid) onValue("")
    }
    fun leaveGroupMode() {
        groupMode = false
        members = emptyList()
        groupName = ""
    }
    val create = {
        if (groupMode) {
            val everyone = if (typedValid) (members + typed).distinct() else members
            onCreateGroup(groupName, everyone)
        } else onCreate()
    }
    BackHandler(enabled = groupMode && !creating) { leaveGroupMode() }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )
    Scaffold(
        containerColor = colors.surfaceContainer,
        topBar = {
            Surface(color = colors.surfaceContainer, modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (groupMode) leaveGroupMode() else onBack() }, enabled = !creating) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = if (groupMode) "New group chat" else "New chat",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = create, enabled = valid) {
                        Text(if (creating) "Creating…" else "Create")
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Messages: light "To:" pill on the tinted page, keyboard up immediately; group members become chips.
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(28.dp),
                color = colors.surface,
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center,
                    itemVerticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = "To:", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    members.forEach { member ->
                        InputChip(
                            selected = false,
                            onClick = { members = members - member },
                            enabled = !creating,
                            label = { Text(member, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $member", modifier = Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(50),
                        )
                    }
                    TextField(
                        value = value,
                        onValueChange = onValue,
                        placeholder = { Text("Type a Matrix ID") },
                        singleLine = true,
                        enabled = !creating,
                        keyboardOptions = KeyboardOptions(imeAction = if (groupMode) ImeAction.Next else ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onNext = { addTypedMember() },
                            onDone = { if (valid) create() },
                        ),
                        modifier = Modifier.weight(1f).widthIn(min = 160.dp).focusRequester(focus),
                        colors = fieldColors,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (groupMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = colors.surface,
                ) {
                    TextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        placeholder = { Text("Group name (optional)") },
                        singleLine = true,
                        enabled = !creating,
                        leadingIcon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                        modifier = Modifier.fillMaxSize(),
                        colors = fieldColors,
                    )
                }
                Spacer(Modifier.height(12.dp))
            } else {
                // Messages: tonal "Create group" pill under the To: field.
                FilledTonalButton(
                    onClick = {
                        groupMode = true
                        addTypedMember()
                    },
                    enabled = !creating,
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(Icons.Outlined.GroupAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create group")
                }
                Spacer(Modifier.height(12.dp))
            }
            if (typed.isBlank()) {
                Text(
                    text = if (groupMode) {
                        "Add everyone by full Matrix ID. The group is private and end-to-end encrypted."
                    } else {
                        "Enter a full Matrix ID like @bob:example.org to start an encrypted conversation."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            } else if (typedValid) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .clickable(enabled = !creating) { if (groupMode) addTypedMember() else onCreate() }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (avatarBg, avatarFg) = avatarPalette(typed)
                    Box(
                        modifier = Modifier.size(48.dp).background(avatarBg, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "?", style = MaterialTheme.typography.titleMedium, color = avatarFg)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (groupMode) "Add $typed" else "Create chat with $typed",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                        Text(text = "End-to-end encrypted", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            } else {
                Text(
                    text = "That doesn't look like a Matrix ID yet — keep typing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (creating) {
                Spacer(Modifier.height(12.dp))
                Text(text = "Creating encrypted conversation…", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    room: MatrixRoom,
    muted: Boolean,
    onClick: () -> Unit,
    /** Picked in the list's selection mode: tinted row, check mark in place of the avatar. */
    selected: Boolean = false,
    /** Long-press (starts or extends the selection); null when the list has no selection mode. */
    onLongClick: (() -> Unit)? = null,
    /** Being swiped: Messages lifts the row onto a tinted pill above the archive track. */
    lifted: Boolean = false,
    /** TalkBack's equivalents of the row's gestures (swipe to archive), offered in its actions menu. */
    accessibilityActions: List<androidx.compose.ui.semantics.CustomAccessibilityAction> = emptyList(),
    /** Open beside the list (two panes): tonal row, announced as selected. */
    open: Boolean = false,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val unread = room.isUnread
    // Messages: unread conversations get a bold name, bold snippet and timestamp, and a count badge.
    val emphasis = if (unread) FontWeight.Bold else FontWeight.Normal
    Row(
        // Messages: pressed (and selected) rows show a rounded pill highlight inset from the screen edges.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(28.dp))
            .background(
                when {
                    selected -> colors.surfaceContainer
                    lifted -> colors.surfaceContainerHigh
                    open -> colors.secondaryContainer
                    else -> colors.surface
                },
            )
            .combinedClickable(
                onClickLabel = if (onLongClick != null && selected) "Deselect" else null,
                onClick = onClick,
                onLongClickLabel = if (onLongClick != null) (if (selected) "Deselect" else "Select") else null,
                onLongClick = onLongClick,
            )
            .semantics {
                // Picked in selection mode, or (two panes) the conversation open beside the list.
                this.selected = selected || open
                if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
            }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.animation.Crossfade(targetState = selected, animationSpec = SendMotion.effectsFast(), label = "rowSelection") { checked ->
            if (checked) {
                Box(
                    Modifier.size(52.dp).background(colors.primary, CircleShape).clearAndSetSemantics { contentDescription = "Selected" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary)
                }
            } else {
                Box(Modifier.sendSharedElement(sharedAvatarKey(room.id))) { Avatar(name = room.name, size = 52.dp, url = room.avatarUrl) }
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Text(
                        text = room.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        fontWeight = if (unread) FontWeight.Bold else null,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.sendSharedBounds(sharedTitleKey(room.id)),
                    )
                }
                if (room.pinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
                if (muted) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = "Muted",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
                if (room.callActive) {
                    // A MatrixRTC call is running in this conversation (activeRoomCallParticipants).
                    Icon(
                        imageVector = if (room.callVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                        contentDescription = "Call in progress",
                        tint = colors.primary,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
                room.lastTimestampMillis?.let { timestamp ->
                    Text(
                        text = conversationTimestamp(context, timestamp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = emphasis,
                        color = if (unread) colors.onSurface else colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = room.lastMessage ?: "End-to-end encrypted",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = emphasis,
                    color = if (unread) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (room.unreadCount > 0) {
                    val label = if (room.unreadCount > 99) "99+" else room.unreadCount.toString()
                    Box(
                        modifier = Modifier.padding(start = 8.dp).heightIn(min = 22.dp).widthIn(min = 22.dp)
                            .background(colors.primary, CircleShape)
                            .clearAndSetSemantics { contentDescription = "${room.unreadCount} unread" }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onPrimary)
                    }
                } else if (room.markedUnread) {
                    // Marked unread without new messages: Messages' plain dot instead of a count.
                    Box(
                        Modifier.padding(start = 8.dp).size(12.dp).background(colors.primary, CircleShape)
                            .clearAndSetSemantics { contentDescription = "Marked unread" },
                    )
                }
            }
        }
    }
}

/** Messages-style list timestamp: "Now", time today, weekday this week, otherwise the date. */
internal fun conversationTimestamp(context: android.content.Context, timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val age = nowMillis - timestampMillis
    if (age in -DateUtils.MINUTE_IN_MILLIS until DateUtils.MINUTE_IN_MILLIS) return "Now"
    if (DateUtils.isToday(timestampMillis)) return DateUtils.formatDateTime(context, timestampMillis, DateUtils.FORMAT_SHOW_TIME)
    if (age in 0 until 6 * DateUtils.DAY_IN_MILLIS) {
        return DateUtils.formatDateTime(context, timestampMillis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
    }
    val sameYear = java.util.Calendar.getInstance().run {
        timeInMillis = nowMillis
        val year = get(java.util.Calendar.YEAR)
        timeInMillis = timestampMillis
        year == get(java.util.Calendar.YEAR)
    }
    val yearFlag = if (sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    return DateUtils.formatDateTime(context, timestampMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or yearFlag)
}

/** Loads an avatar (`mxc://` URL) as a bitmap about [sizePx] square; SendApp provides the live session's loader. */
internal typealias AvatarLoader = suspend (url: String, sizePx: Int) -> android.graphics.Bitmap?

internal val LocalAvatarLoader = androidx.compose.runtime.staticCompositionLocalOf<AvatarLoader?> { null }

/** Decoded avatars by URL and size, so lists don't flash initials while scrolling. */
private val AvatarBitmaps = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(96)

@Composable
internal fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, url: String? = null) {
    val loader = LocalAvatarLoader.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val key = url?.let { "$it@$sizePx" }
    var image by remember(key) { mutableStateOf(key?.let { AvatarBitmaps.get(it) }) }
    LaunchedEffect(key, loader) {
        if (key == null || url == null || loader == null || image != null) return@LaunchedEffect
        try {
            image = loader(url, sizePx)?.asImageBitmap()?.also { AvatarBitmaps.put(key, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.i("Avatar", "Avatar $url unavailable: ${failure.message}")
        }
    }
    val bitmap = image
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        return
    }
    // Messages: saturated solid circle, white initial (person glyph when there is no name).
    val (bg, fg) = avatarPalette(name)
    Box(modifier = Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercase()
        if (initial != null) {
            // Large avatars (details, profile) scale the initial with the circle.
            val style = if (size > 64.dp) MaterialTheme.typography.titleLarge.copy(fontSize = (size.value * 0.42f).sp) else MaterialTheme.typography.titleLarge
            Text(text = initial, style = style, fontWeight = FontWeight.Medium, color = fg)
        } else {
            Icon(imageVector = Icons.Filled.Person, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.6f))
        }
    }
}


/** Google Messages grouped corner logic: outer corners 20.dp, adjacent-to-group corners 4.dp. */
internal fun bubbleShape(isFromMe: Boolean, isFirstInGroup: Boolean, isLastInGroup: Boolean): RoundedCornerShape = if (isFromMe) {
    RoundedCornerShape(
        topStart = 20.dp,
        bottomStart = 20.dp,
        topEnd = if (isFirstInGroup) 20.dp else 4.dp,
        bottomEnd = if (isLastInGroup) 20.dp else 4.dp,
    )
} else {
    RoundedCornerShape(
        topStart = if (isFirstInGroup) 20.dp else 4.dp,
        bottomStart = if (isLastInGroup) 20.dp else 4.dp,
        topEnd = 20.dp,
        bottomEnd = 20.dp,
    )
}

