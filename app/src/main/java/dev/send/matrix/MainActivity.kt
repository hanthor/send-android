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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.Timeline
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallSnapshot
private val Ink = Color(0xFF202124)
private val Muted = Color(0xFF65676B)
private val Canvas = Color(0xFFF7F8FA)

class MainActivity : ComponentActivity() {
    private val repository by lazy { MatrixRepository.get(applicationContext) }
    val ssoCallback = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptSsoCallback(intent?.data)
        setContent {
            val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(this)
            } else {
                lightColorScheme(primary = Color(0xFF0B57D0))
            }
            MaterialTheme(colorScheme = colors) { SendApp(repository) }
        }
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
private fun SendApp(repository: MatrixRepository) {
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
    var drafts by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    val rooms by repository.joinedRooms.collectAsState()
    var callSession by remember { mutableStateOf<NativeMatrixCallSession?>(null) }
    val noCall = remember { kotlinx.coroutines.flow.MutableStateFlow<ElementCallSnapshot?>(null) }
    val callStateFlow = callSession?.controller?.state ?: noCall
    val callState by callStateFlow.collectAsState()
    val messages by repository.messages.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        callSession?.controller?.setMicrophonePermissionGranted(granted)
        if (!granted) error = "Microphone permission is required for an audio call."
    }
    LaunchedEffect(signedIn) {
        if (signedIn) callSession = repository.callSession()
        else callSession = null
    }
    LaunchedEffect(callSession, callState?.connection) {
        if (callState?.connection == ElementCallConnection.RequestingPermission) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                callSession?.controller?.setMicrophonePermissionGranted(true)
            } else {
                requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
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
                    room = restoredRoom
                    timeline = restoredTimeline
                }
            }
        } catch (failure: Exception) {
            error = failure.message ?: "Could not restore the Matrix session."
        } finally { loading = false }
    }

    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = Canvas, contentColor = Ink) {
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
                draft = drafts[activeRoomId].orEmpty(),
                onDraft = { value -> drafts = drafts + (activeRoomId to value) },
                messages = messages,
                onBack = { room = null; timeline = null; activeRoomId = ""; roomTitle = ""; error = "" },
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
                onReact = { eventId ->
                    val currentRoom = room
                    val currentTimeline = timeline
                    if (currentRoom != null && currentTimeline != null) scope.launch {
                        try { repository.react(currentTimeline, currentRoom, eventId, "👍") }
                        catch (failure: Exception) { error = failure.message ?: "Reaction could not be sent." }
                    }
                },
                callState = callState,
                onAudioCall = { callSession?.startAudioCall(activeRoomId) },
                onMute = { callSession?.controller?.setMicrophoneMuted(!(callState?.isMicrophoneMuted ?: false)) },
                onAudioOutput = {
                    val snapshot = callState
                    val devices = snapshot?.audioDevices.orEmpty()
                    if (snapshot != null && devices.isNotEmpty()) {
                        val current = devices.indexOf(snapshot.selectedAudioDevice)
                        callSession?.controller?.selectAudioDevice(devices[(current + 1).mod(devices.size)])
                    }
                },
                onHangUp = { callSession?.controller?.hangUp() },
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
                        } catch (failure: Exception) {
                            error = failure.message ?: "Conversation could not be opened."
                        }
                    }
                },
                error = error
            )
        }
    }
}

@Composable
private fun SignInScreen(
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
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
        Text("Send", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
        Text("Private conversations, on your terms.", color = Muted, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))
        OutlinedTextField(homeserver, onHomeserver, label = { Text("Homeserver") }, placeholder = { Text("https://matrix.example.org") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Button(onClick = onQrLogin, enabled = !busy && homeserver.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(26.dp)) {
            Text(if (busy) "Signing in…" else "Scan QR code")
        }
        TextButton(onClick = onSsoLogin, enabled = !busy && homeserver.isNotBlank(), modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Continue with SSO") }
        OutlinedTextField(user, onUser, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy)
        OutlinedTextField(password, onPassword, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
        Button(onClick = onLogin, enabled = !busy && homeserver.isNotBlank() && user.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 18.dp), shape = RoundedCornerShape(26.dp)) {
            Text(if (busy) "Signing in…" else "Sign in with password")
        }
        if (busy) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
        if (ssoPending) TextButton(onClick = onCancelSso, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel SSO sign-in") }
    }
}

@Composable
private fun HomeScreen(rooms: List<MatrixRoom>, onOpen: (MatrixRoom) -> Unit, error: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Send", fontSize = 31.sp, fontWeight = FontWeight.SemiBold)
                Text("Your encrypted conversations", color = Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
        if (rooms.isEmpty()) Text("No conversations are available yet.", color = Muted, modifier = Modifier.padding(top = 24.dp))
        else if (rooms.none { it.encrypted }) {
            Text("These rooms are unencrypted. Send hides them and cannot open them for messaging.", color = Muted, modifier = Modifier.padding(top = 20.dp))
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
    }
}

@Composable
private fun Initial(name: String) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(name.take(1), color = Color(0xFF433675), fontSize = 19.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConversationScreen(
    title: String,
    draft: String,
    onDraft: (String) -> Unit,
    messages: List<MatrixMessage>,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onOlder: () -> Unit,
    onReact: (String) -> Unit,
    callState: ElementCallSnapshot?,
    onAudioCall: () -> Unit,
    onMute: () -> Unit,
    onAudioOutput: () -> Unit,
    onHangUp: () -> Unit,
    error: String,
) {
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", fontSize = 28.sp) }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                Text("🔒  End-to-end encrypted", fontSize = 12.sp, color = Muted)
            }
        }
            TextButton(onClick = onAudioCall, enabled = callState == null) { Text("Call") }
        TextButton(onClick = onOlder, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Load older messages") }
        callState?.let { call ->
            Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(18.dp), color = Color(0xFFE9E5F4)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        when (call.connection) {
                            ElementCallConnection.RequestingPermission -> "Microphone permission required"
                            ElementCallConnection.Joining -> "Calling…"
                            ElementCallConnection.ConnectingMedia -> "Connecting audio…"
                            ElementCallConnection.Connected -> "Audio call"
                            ElementCallConnection.Degraded -> "Audio connection unstable"
                            is ElementCallConnection.Failed -> call.connection.message
                            ElementCallConnection.Ended -> "Call ended"
                        },
                        fontWeight = FontWeight.Medium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onMute, enabled = call.isMicrophonePermissionGranted) {
                            Text(if (call.isMicrophoneMuted) "Unmute" else "Mute")
                        }
                        TextButton(onClick = onAudioOutput, enabled = call.audioDevices.size > 1) {
                            Text("Audio output ${call.audioDevices.indexOf(call.selectedAudioDevice) + 1}/${call.audioDevices.size}")
                        }
                        TextButton(onClick = onHangUp) { Text("End call") }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), reverseLayout = true) {
            items(messages.asReversed(), key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.own) Alignment.End else Alignment.Start) {
                    Surface(color = if (message.own) Lavender else Color(0xFFE9E5F4), shape = RoundedCornerShape(18.dp)) {
                        Text(message.text, Modifier.padding(horizontal = 15.dp, vertical = 11.dp))
                    }
                    if (message.reactions.isNotEmpty()) Text(message.reactions.joinToString(" "), color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { onReact(message.id) }) { Text("👍 React") }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = onDraft, placeholder = { Text("Message") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(26.dp), maxLines = 4)
            TextButton(onClick = onSend, enabled = draft.isNotBlank(), modifier = Modifier.padding(start = 4.dp)) { Text("Send") }
        }
    }
}
