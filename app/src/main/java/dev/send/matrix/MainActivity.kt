package dev.send.matrix

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.Timeline
import io.element.android.call.impl.ElementCallPictureInPicture
import io.element.android.call.api.ElementCallController
private val Lavender = Color(0xFFE8EEFF)
private val Ink = Color(0xFF202124)
private val Muted = Color(0xFF65676B)
private val Canvas = Color(0xFFF7F8FA)
private val QuickReactions = listOf("👍", "❤️", "😂")

class MainActivity : ComponentActivity() {
    private val repository by lazy { MatrixRepository.get(applicationContext) }
    val ssoCallback = mutableStateOf<Uri?>(null)
    private var pipController: ElementCallController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptSsoCallback(intent?.data)
        setContent {
            val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(this)
            } else {
                lightColorScheme(primary = Color(0xFF0B57D0))
            }
            MaterialTheme(colorScheme = colors) { SendApp(repository, this@MainActivity) }
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
    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).then(overlayModifier), color = Canvas, contentColor = Ink) {
        when {
            loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Connecting to Matrix…", color = Muted)
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
    var showPasswordLogin by rememberSaveable { mutableStateOf(false) }
    var showLegal by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(animationSpec = tween(420)) +
                slideInVertically(initialOffsetY = { it / 14 }, animationSpec = tween(420)),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = colors.primaryContainer,
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Text("S", color = colors.onPrimaryContainer, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Send", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                        Text("Private, thoughtful messaging", color = Muted, fontSize = 13.sp)
                    }
                }

                Text(
                    "Your conversations,\nwith room to breathe.",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 34.dp),
                )
                Text(
                    "Connect to your Matrix homeserver. Your messages stay end-to-end encrypted.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Muted,
                    modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
                )

                Text(
                    "HOMESERVER",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                OutlinedTextField(
                    value = homeserver,
                    onValueChange = onHomeserver,
                    label = { Text("Homeserver") },
                    placeholder = { Text("https://matrix.example.org") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    shape = RoundedCornerShape(18.dp),
                )
                Button(
                    onClick = onQrLogin,
                    enabled = !busy && homeserver.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp,
                            color = colors.onPrimary,
                        )
                        Text("  Connecting…")
                    } else {
                        Text("Scan QR code", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
                OutlinedButton(
                    onClick = onSsoLogin,
                    enabled = !busy && homeserver.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Continue with SSO", fontSize = 15.sp)
                }

                TextButton(
                    onClick = { showPasswordLogin = !showPasswordLogin },
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                ) {
                    Text(if (showPasswordLogin) "Use another sign-in method" else "Use username and password")
                }

                AnimatedVisibility(
                    visible = showPasswordLogin,
                    enter = fadeIn(tween(220)) + expandVertically(expandFrom = Alignment.Top, animationSpec = tween(260)),
                    exit = fadeOut(tween(150)) + shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(200)),
                ) {
                    Column {
                        OutlinedTextField(
                            value = user,
                            onValueChange = onUser,
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
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
                            enabled = !busy && homeserver.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Sign in with password")
                        }
                    }
                }

                if (error.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.errorContainer,
                    ) {
                        Text(error, color = colors.onErrorContainer, modifier = Modifier.padding(16.dp))
                    }
                }
                if (busy) {
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Cancel sign-in")
                    }
                }
                if (ssoPending) {
                    TextButton(onClick = onCancelSso, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Cancel SSO sign-in")
                    }
                }
                TextButton(onClick = { showLegal = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("About & licenses")
                }
            }
        }
    }
    if (showLegal) {
        LegalNoticesDialog(onDismiss = { showLegal = false })
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
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Send", fontSize = 31.sp, fontWeight = FontWeight.SemiBold)
                Text("Your encrypted conversations", color = Muted, modifier = Modifier.padding(top = 4.dp))
            }
            Button(onClick = { showingNewChat = true }, shape = RoundedCornerShape(26.dp)) {
                Text("New chat")
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
        if (notice.isNotBlank()) Text(notice, color = Muted, modifier = Modifier.padding(top = 10.dp))
        if (rooms.isEmpty()) Text("No conversations are available yet.", color = Muted, modifier = Modifier.padding(top = 24.dp))
        else if (rooms.any { !it.encrypted }) {
            Text("Unencrypted rooms are hidden. Send only opens end-to-end encrypted conversations.", color = Muted, modifier = Modifier.padding(top = 20.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 14.dp)) {
            items(rooms.filter { it.encrypted }, key = { it.id }) { chat ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(chat) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(48.dp), shape = CircleShape, color = Lavender) { Initial(chat.name) }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(chat.name, fontWeight = FontWeight.Medium, fontSize = 17.sp)
                        Text("End-to-end encrypted", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
        TextButton(onClick = { showLegal = true }) { Text("About & licenses") }
    }
    if (showLegal) {
        LegalNoticesDialog(onDismiss = { showLegal = false })
    }
    if (showingNewChat) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!creatingDirectRoom) showingNewChat = false },
            title = { Text("New encrypted conversation") },
            text = {
                OutlinedTextField(
                    value = matrixUserId,
                    onValueChange = { matrixUserId = it },
                    label = { Text("Matrix user ID") },
                    placeholder = { Text("@alice:example.org") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreateDirectRoom(matrixUserId)
                        showingNewChat = false
                    },
                    enabled = !creatingDirectRoom && matrixUserId.trim().let {
                        it.startsWith("@") && it.indexOf(':') > 1 && !it.any(Char::isWhitespace)
                    },
                ) {
                    Text(if (creatingDirectRoom) "Creating…" else "Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showingNewChat = false }, enabled = !creatingDirectRoom) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Initial(name: String) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(name.take(1), color = Color(0xFF433675), fontSize = 19.sp, fontWeight = FontWeight.Medium)
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
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", fontSize = 28.sp) }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                Text("🔒  End-to-end encrypted", fontSize = 12.sp, color = Muted)
            }
            if (isDirect) {
                TextButton(onClick = onAudioCall) { Text("Call") }
            }
        }
        TextButton(onClick = onOlder, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Load older messages")
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true,
        ) {
            items(messages.asReversed(), key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.own) Alignment.End else Alignment.Start) {
                    androidx.compose.foundation.layout.Box {
                        Surface(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { reactionTargetId = message.id },
                            ),
                            color = if (message.own) Lavender else Color(0xFFE9E5F4),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(message.text, Modifier.padding(horizontal = 15.dp, vertical = 11.dp))
                        }
                        DropdownMenu(
                            expanded = reactionTargetId == message.id,
                            onDismissRequest = { reactionTargetId = null },
                        ) {
                            QuickReactions.forEach { emoji ->
                                DropdownMenuItem(
                                    text = { Text(emoji) },
                                    onClick = {
                                        reactionTargetId = null
                                        onReact(message.id, emoji)
                                    },
                                )
                            }
                        }
                    }
                    if (message.reactions.isNotEmpty()) {
                        Text(message.reactions.joinToString(" "), color = Muted, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            DateUtils.formatDateTime(context, message.timestampMillis, DateUtils.FORMAT_SHOW_TIME),
                            color = Muted,
                            fontSize = 11.sp,
                        )
                        if (message.own) Text(" · Sent", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(26.dp),
                maxLines = 4,
            )
            TextButton(onClick = onSend, enabled = draft.isNotBlank(), modifier = Modifier.padding(start = 4.dp)) {
                Text("Send")
            }
        }
    }
}
