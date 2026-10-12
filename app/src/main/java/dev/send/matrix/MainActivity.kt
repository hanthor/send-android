package dev.send.matrix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF202124)
private val Muted = Color(0xFF65676B)
private val Canvas = Color(0xFFF7F8FA)
private val Lavender = Color(0xFFE8E1FF)
private val Bubble = Color(0xFFE9E5F4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SendApp() } }
    }
}

@Composable
private fun SendApp() {
    var homeserver by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var screen by rememberSaveable { mutableStateOf("welcome") }
    var roomName by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    val conversations = listOf("Mira", "Theo", "Weekend plans")

    Surface(Modifier.fillMaxSize(), color = Canvas, contentColor = Ink) {
        when (screen) {
            "welcome" -> SignInScreen(homeserver, { homeserver = it }, user, { user = it }, password, { password = it }, { screen = "home" })
            "home" -> Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp)) {
                Text("Send", fontSize = 31.sp, fontWeight = FontWeight.SemiBold)
                Text("Your conversations", color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 22.dp))
                Button(onClick = { roomName = "New conversation"; screen = "chat" }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) { Text("＋   New conversation") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 16.dp)) {
                    items(conversations) { name ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(48.dp), shape = CircleShape, color = Lavender) { BoxInitial(name) }
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(name, fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                Text("Encrypted conversation", color = Muted, fontSize = 13.sp)
                            }
                            Text("9:41", fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
            else -> ConversationScreen(roomName, draft, { draft = it }, { screen = "home" })
        }
    }
}

@Composable
private fun BoxInitial(name: String) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(name.take(1), color = Color(0xFF433675), fontSize = 19.sp, fontWeight = FontWeight.Medium)
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
    onContinue: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
        Text("Send", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
        Text("Private conversations, on your terms.", color = Muted, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))
        OutlinedTextField(homeserver, onHomeserver, label = { Text("Homeserver") }, placeholder = { Text("https://matrix.example.org") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, onUser, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(password, onPassword, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(top = 18.dp), shape = RoundedCornerShape(26.dp)) { Text("Sign in") }
        TextButton(onClick = { }) { Text("Sign in with QR code") }
    }
}

@Composable
private fun ConversationScreen(roomName: String, draft: String, onDraft: (String) -> Unit, onBack: () -> Unit) {
    var sent by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", fontSize = 28.sp) }
            Column(Modifier.weight(1f)) {
                Text(roomName, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                Text("🔒  End-to-end encrypted", fontSize = 12.sp, color = Muted)
            }
            TextButton(onClick = { }) { Text("☎") }
        }
        Spacer(Modifier.weight(1f))
        Text("Messages are end-to-end encrypted", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 18.dp), fontSize = 12.sp, color = Muted)
        if (sent) Card(colors = CardDefaults.cardColors(containerColor = Bubble), shape = RoundedCornerShape(20.dp), modifier = Modifier.align(Alignment.End)) {
            Text(draft.ifBlank { "Message sent" }, Modifier.padding(horizontal = 15.dp, vertical = 11.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = onDraft, placeholder = { Text("Message") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(26.dp), maxLines = 4)
            TextButton(onClick = { if (draft.isNotBlank()) sent = true }, modifier = Modifier.padding(start = 4.dp)) { Text("Send") }
        }
    }
}
