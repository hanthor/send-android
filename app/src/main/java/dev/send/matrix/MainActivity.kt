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
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.graphics.Color
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.Timeline
import io.element.android.call.ui.ElementCallOverlay
import io.element.android.call.impl.ElementCallPictureInPicture
import io.element.android.call.api.ElementCallController
private val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

// Messages-style contact avatars: saturated solid fills with a white glyph.
private val AvatarColors = listOf(
    Color(0xFF4285F4), Color(0xFF5BB974), Color(0xFFFCC934), Color(0xFFEE675C),
    Color(0xFFAF5CF7), Color(0xFF4ECDE6), Color(0xFFFA903E), Color(0xFFFF63B8),
)

// Static palette measured from Google Messages on the comparison emulator (cool
// neutral surfaces, tonal-blue accents, pink voice button). Messages does not
// follow the wallpaper there, so neither does Send.
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
)

@Composable
internal fun SendTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) GoogleDarkColors else GoogleLightColors, content = content)
}

private fun avatarPalette(name: String): Pair<Color, Color> {
    val index = (name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % AvatarColors.size
    return AvatarColors[index] to Color.White
}

class MainActivity : ComponentActivity() {
    private val repository by lazy { MatrixRepository.get(applicationContext) }
    val ssoCallback = mutableStateOf<Uri?>(null)
    private var pipController: ElementCallController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SendTheme { SendApp(repository, this@MainActivity) }
        }
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSsoCallback(intent.data)
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
    var activeRoomId by rememberSaveable { mutableStateOf("") }
    var roomTitle by rememberSaveable { mutableStateOf("") }
    var activeRoomIsDirect by rememberSaveable { mutableStateOf(false) }
    var drafts by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    var notice by rememberSaveable { mutableStateOf("") }
    val rooms by repository.joinedRooms.collectAsState()
    var callSession by remember { mutableStateOf<NativeMatrixCallSession?>(null) }
    LaunchedEffect(signedIn) {
        if (signedIn) {
            callSession = repository.callSession()
            callSession?.let { activity.attachCallController(it.controller) }
        } else callSession = null
    }
    val messages by repository.messages.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
                    room = restoredRoom
                    timeline = restoredTimeline
                    activeRoomIsDirect = repository.isDirectRoom(restoredRoom)
                }
            }
        } catch (failure: Exception) {
            error = failure.message ?: "Could not restore the Matrix session."
        } finally { loading = false }
    }

    val sendCallStyle = rememberSendCallStyle()
    ElementCallOverlay(
        controller = callSession?.controller,
        style = sendCallStyle,
    ) { overlayModifier ->
    Surface(Modifier.fillMaxSize().then(overlayModifier), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        when {
            loading -> Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Connecting to Matrix…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
            !signedIn -> SignInScreen(
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
            room != null && timeline != null -> ConversationScreen(
                title = roomTitle,
                isDirect = activeRoomIsDirect,
                draft = drafts[activeRoomId].orEmpty(),
                onDraft = { value -> drafts = drafts + (activeRoomId to value) },
                messages = messages,
                onBack = { room = null; timeline = null; activeRoomId = ""; roomTitle = ""; activeRoomIsDirect = false; error = "" },
                onSend = {
                    val currentRoom = room
                    val currentTimeline = timeline
                    val roomId = activeRoomId
                    val sentText = drafts[roomId].orEmpty()
                    if (currentRoom != null && currentTimeline != null && sentText.isNotBlank()) {
                        scope.launch {
                            error = ""
                            try {
                                repository.sendText(currentTimeline, currentRoom, sentText)
                                if (drafts[roomId] == sentText) drafts = drafts - roomId
                            } catch (failure: Exception) { error = failure.message ?: "Message could not be sent." }
                        }
                    }
                },
                onOlder = {
                    val current = timeline
                    if (current != null) scope.launch {
                        try { repository.paginateOlder(current) }
                        catch (failure: Exception) { error = failure.message ?: "Older messages could not be loaded." }
                    }
                },
                onReact = { eventId, emoji ->
                    val currentRoom = room
                    val currentTimeline = timeline
                    if (currentRoom != null && currentTimeline != null) scope.launch {
                        try { repository.react(currentTimeline, currentRoom, eventId, emoji) }
                        catch (failure: Exception) { error = failure.message ?: "Reaction could not be sent." }
                    }
                },
                onAudioCall = { callSession?.startAudioCall(activeRoomId) },
                error = error
            )
            else -> HomeScreen(
                rooms = rooms,
                onOpen = { selected ->
                    scope.launch {
                        error = ""
                        try {
                            val selectedRoom = repository.openEncryptedRoom(selected.id)
                            val selectedTimeline = repository.timeline(selectedRoom)
                            repository.observeTimeline(selectedTimeline)
                            room = selectedRoom
                            timeline = selectedTimeline
                            activeRoomId = selected.id
                            roomTitle = selected.name
                            activeRoomIsDirect = selected.direct
                        } catch (failure: Exception) {
                            error = failure.message ?: "Conversation could not be opened."
                        }
                    }
                },
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
                }
            )
        }
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
                if (targetState > initialState) {
                    (slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(300)))
                        .togetherWith(slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(200)))
                } else {
                    (slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(300)))
                        .togetherWith(slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(200)))
                }.using(SizeTransform(clip = false))
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
                modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceContainerHigh),
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
                    enter = fadeIn(tween(220)) + expandVertically(expandFrom = Alignment.Top, animationSpec = tween(260)),
                    exit = fadeOut(tween(150)) + shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(200)),
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
) {
    var showingNewChat by rememberSaveable { mutableStateOf(false) }
    var matrixUserId by rememberSaveable { mutableStateOf("") }
    var showLegal by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val encryptedRooms = rooms.filter { it.encrypted }
    val visibleRooms = if (query.isBlank()) encryptedRooms else encryptedRooms.filter { it.name.contains(query, ignoreCase = true) }
    val colors = MaterialTheme.colorScheme
    BackHandler(enabled = searching) { searching = false; query = "" }
    Scaffold(
        containerColor = colors.surfaceContainer,
        topBar = {
            // Messages: plain top bar — wordmark left, search + account right; no pill.
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 16.dp, end = 4.dp),
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
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { searching = true }) {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = "Search", tint = colors.onSurface)
                    }
                    IconButton(onClick = { showLegal = true }) {
                        Icon(imageVector = Icons.Filled.AccountCircle, contentDescription = "Account", tint = colors.primary, modifier = Modifier.size(32.dp))
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showingNewChat = true },
                containerColor = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null) },
                text = { Text("Start chat", style = MaterialTheme.typography.titleMedium) },
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 96.dp),
        ) {
            if (error.isNotBlank()) item { Text(error, color = colors.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            if (notice.isNotBlank()) item { Text(notice, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            if (visibleRooms.isEmpty()) {
                item {
                    Text(
                        if (encryptedRooms.isNotEmpty()) "No conversations match “$query”." else if (rooms.any { !it.encrypted }) "Unencrypted rooms are hidden. Send only opens end-to-end encrypted conversations." else "No conversations are available yet.",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            } else {
                items(visibleRooms, key = { it.id }) { chat ->
                    ConversationRow(name = chat.name, snippet = "End-to-end encrypted", onClick = { onOpen(chat) })
                }
            }
        }
        }
    }
    if (showLegal) {
        LegalNoticesDialog(onDismiss = { showLegal = false })
    }
    if (showingNewChat) {
        BackHandler(enabled = !creatingDirectRoom) { showingNewChat = false }
        NewConversationScreen(
            value = matrixUserId,
            onValue = { matrixUserId = it },
            creating = creatingDirectRoom,
            onBack = { if (!creatingDirectRoom) showingNewChat = false },
            onCreate = {
                onCreateDirectRoom(matrixUserId)
                showingNewChat = false
            },
        )
    }
}

@Composable
private fun NewConversationScreen(
    value: String,
    onValue: (String) -> Unit,
    creating: Boolean,
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    // Full-screen Messages-style picker: To: bar on top, contact row below. No popup dialog.
    val colors = MaterialTheme.colorScheme
    val valid = !creating && value.trim().let {
        it.startsWith("@") && it.indexOf(':') > 1 && !it.any(Char::isWhitespace)
    }
    Scaffold(
        containerColor = colors.surface,
        topBar = {
            Surface(color = colors.surface, modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, enabled = !creating) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = "New conversation",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCreate, enabled = valid) {
                        Text(if (creating) "Creating…" else "Create")
                    }
                }
            }
        },
    ) { innerPadding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                color = colors.surfaceContainerHigh,
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "To", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(12.dp))
                    TextField(
                        value = value,
                        onValueChange = onValue,
                        placeholder = { Text("Matrix user ID") },
                        singleLine = true,
                        enabled = !creating,
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (value.isBlank()) {
                Text(
                    text = "Enter a full Matrix ID like @bob:example.org to start an encrypted conversation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            } else if (valid) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onCreate() }.padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (avatarBg, avatarFg) = avatarPalette(value.trim())
                    Box(
                        modifier = Modifier.size(48.dp).background(avatarBg, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "?", style = MaterialTheme.typography.titleMedium, color = avatarFg)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Create chat with ${value.trim()}", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
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

@Composable
private fun ConversationRow(name: String, snippet: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = name, size = 52.dp)
        Spacer(modifier = Modifier.width(16.dp))
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Encrypted",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp).padding(start = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp) {
    // Messages: saturated solid circle, white initial (person glyph when there is no name).
    val (bg, fg) = avatarPalette(name)
    Box(modifier = Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercase()
        if (initial != null) {
            Text(text = initial, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = fg)
        } else {
            Icon(imageVector = Icons.Filled.Person, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.6f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationScreen(
    title: String,
    isDirect: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    messages: List<MatrixMessage>,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onOlder: () -> Unit,
    onReact: (String, String) -> Unit,
    onAudioCall: () -> Unit,
    error: String,
) {
    var reactionTargetId by remember { mutableStateOf<String?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    androidx.activity.compose.BackHandler { onBack() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            // Messages: back, avatar, title only, call, overflow. Body is a rounded sheet below.
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Avatar(name = title, size = 40.dp)
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isDirect) {
                        IconButton(onClick = onAudioCall) {
                            Icon(imageVector = Icons.Filled.Call, contentDescription = "Call")
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("End-to-end encrypted") },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                enabled = false,
                                onClick = {},
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Load older messages") },
                                onClick = { showMenu = false; onOlder() },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.navigationBarsPadding().imePadding()) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
                    if (error.isNotBlank()) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    var showEmoji by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        // Messages composer: 56dp pill with the field and inline emoji action.
                        Surface(
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextField(
                                    value = draft,
                                    onValueChange = onDraft,
                                    placeholder = { Text("Message") },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 4,
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                )
                                Box {
                                    IconButton(onClick = { showEmoji = true }) {
                                        Icon(imageVector = Icons.Filled.SentimentSatisfied, contentDescription = "Emoji", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    DropdownMenu(
                                        expanded = showEmoji,
                                        onDismissRequest = { showEmoji = false },
                                        shape = RoundedCornerShape(24.dp),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    ) {
                                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                            QuickReactions.forEach { emoji ->
                                                TextButton(onClick = { onDraft(draft + emoji); showEmoji = false }) {
                                                    Text(emoji, fontSize = 20.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Messages' 56dp circular action button.
                        FilledIconButton(
                            onClick = onSend,
                            enabled = draft.isNotBlank(),
                            modifier = Modifier.size(56.dp),
                            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                disabledContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
            ) {
                val reversed = messages.asReversed()
                items(reversed.size, key = { reversed[it].id }) { index ->
                    val message = reversed[index]
                    // Grouping is computed in chronological order: neighbours are the adjacent
                    // items in the non-reversed list, i.e. reversed neighbours flipped.
                    val chronological = messages
                    val chronoIndex = chronological.indexOfFirst { it.id == message.id }
                    val prevSame = chronoIndex > 0 && chronological[chronoIndex - 1].own == message.own
                    val nextSame = chronoIndex < chronological.lastIndex && chronological[chronoIndex + 1].own == message.own
                    val isFirstInGroup = !prevSame
                    val isLastInGroup = !nextSame
                    // Centered section header whenever the calendar day changes.
                    val dayKey = android.text.format.DateFormat.format("yyyyMMdd", message.timestampMillis).toString()
                    val prevDayKey = chronological.getOrNull(chronoIndex - 1)?.let {
                        android.text.format.DateFormat.format("yyyyMMdd", it.timestampMillis).toString()
                    }
                    val isNewDay = prevDayKey != dayKey
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (message.own) Alignment.End else Alignment.Start,
                    ) {
                        if (isNewDay) {
                            Text(
                                text = dayHeaderLabel(context, message.timestampMillis),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            )
                        }
                        MessageBubble(
                            text = message.text,
                            isFromMe = message.own,
                            isFirstInGroup = isFirstInGroup,
                            isLastInGroup = isLastInGroup,
                            onLongPress = { reactionTargetId = message.id },
                        )
                        DropdownMenu(
                            expanded = reactionTargetId == message.id,
                            onDismissRequest = { reactionTargetId = null },
                            shape = RoundedCornerShape(24.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            // Horizontal emoji bar, Messages-style.
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                QuickReactions.forEach { emoji ->
                                    TextButton(
                                        onClick = {
                                            reactionTargetId = null
                                            onReact(message.id, emoji)
                                        },
                                    ) {
                                        Text(emoji, fontSize = 20.sp)
                                    }
                                }
                            }
                        }
                        if (message.reactions.isNotEmpty()) {
                            // Overlapping rounded reaction pill at the bubble's bottom corner.
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
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
                        // Messages: time under every cluster, plus delivery state for own messages.
                        if (isLastInGroup) {
                            val time = DateUtils.formatDateTime(context, message.timestampMillis, DateUtils.FORMAT_SHOW_TIME)
                            Text(
                                if (message.own) "$time · Sent" else time,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 2.dp, bottom = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
}
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

@Composable
fun MessageBubble(text: String, isFromMe: Boolean, isFirstInGroup: Boolean, isLastInGroup: Boolean, onLongPress: () -> Unit = {}) {
    // Google Messages grouped corner logic: outer corners 20.dp, adjacent-to-group corners 4.dp.
    val shape = if (isFromMe) {
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
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (isLastInGroup) 4.dp else 1.dp),
        contentAlignment = if (isFromMe) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = shape,
            // Messages uses tonal blue for sent bubbles, not a saturated primary fill.
            color = if (isFromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isFromMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 280.dp).combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}
