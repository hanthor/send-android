package dev.send.matrix

import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.send.matrix.notifications.IncomingCalls
import kotlinx.coroutines.delay

private val AnswerGreen = Color(0xFF1E8E3E)
private val DeclineRed = Color(0xFFD93025)

/**
 * The ringing screen the "Calls" notification opens full screen (over the lock screen too). It closes itself
 * once the ring stops for any reason; Answer hands over to [MainActivity], which joins the call.
 */
class IncomingCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val call = IncomingCalls.callFrom(intent)
        if (call == null) {
            finish()
            return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        setContent {
            SendTheme {
                val ended by IncomingCalls.ended.collectAsState()
                LaunchedEffect(ended) { if (call.eventId in ended) finish() }
                LaunchedEffect(call.expiresAtMillis) {
                    delay(call.expiresAtMillis - System.currentTimeMillis())
                    finish()
                }
                IncomingCallScreen(
                    callerName = call.callerName,
                    roomName = call.roomName.takeIf { it != call.callerName },
                    video = call.video,
                    onAnswer = {
                        startActivity(IncomingCalls.answerIntent(this, call))
                        finish()
                    },
                    onDecline = {
                        IncomingCalls.decline(this, call.roomId, call.eventId)
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // A newer ring replaces this one.
        if (IncomingCalls.callFrom(intent) != null) {
            setIntent(intent)
            recreate()
        }
    }
}

/** Messages-style incoming call: caller's avatar and name, red Decline and green Answer circles. */
@Composable
internal fun IncomingCallScreen(
    callerName: String,
    roomName: String?,
    video: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            Text(
                text = if (video) "Incoming video call" else "Incoming voice call",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Avatar(name = callerName, size = 120.dp)
            Spacer(Modifier.height(24.dp))
            Text(
                text = callerName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (roomName != null && roomName.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(roomName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 64.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallActionCircle(icon = Icons.Filled.CallEnd, label = "Decline", color = DeclineRed, onClick = onDecline)
                CallActionCircle(icon = if (video) Icons.Filled.Videocam else Icons.Filled.Call, label = "Answer", color = AnswerGreen, onClick = onAnswer)
            }
        }
    }
}

@Composable
private fun CallActionCircle(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A call in the conversation, as Messages shows calls: a centred system row with the call's icon, what
 * happened ("Missed voice call", "Voice call · 2:31", "Call declined") and the time, plus Join for a running
 * call and Call back for a missed one in a 1:1 conversation.
 */
@Composable
internal fun CallEventRow(
    call: CallEvent,
    timestampMillis: Long,
    onJoin: (() -> Unit)?,
    onCallBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val icon = when {
        call.ongoing -> if (call.video) Icons.Filled.Videocam else Icons.Filled.Call
        call.declined -> Icons.Filled.CallEnd
        call.missed -> Icons.AutoMirrored.Filled.CallMissed
        call.outgoing -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (call.missed) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(call.label, style = MaterialTheme.typography.labelLarge, color = if (call.missed) colors.error else colors.onSurfaceVariant)
        }
        Text(
            DateUtils.formatDateTime(context, timestampMillis, DateUtils.FORMAT_SHOW_TIME),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        val action = when {
            call.ongoing && !call.joined && onJoin != null -> "Join" to onJoin
            call.missed && onCallBack != null -> "Call back" to onCallBack
            else -> null
        }
        if (action != null) TextButton(onClick = action.second) { Text(action.first) }
    }
}

/** A MatrixRTC call running in a conversation this device isn't in. */
data class RoomCallBanner(val participantCount: Int, val video: Boolean)

/** "Call in progress" strip above a conversation's messages with Join (`activeRoomCallParticipants`). */
@Composable
internal fun RoomCallBannerRow(banner: RoomCallBanner, onJoin: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (banner.video) Icons.Filled.Videocam else Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (banner.video) "Video call in progress" else "Voice call in progress", style = MaterialTheme.typography.titleSmall)
                val people = banner.participantCount
                Text(if (people == 1) "1 person" else "$people people", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onJoin) { Text("Join", fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** "Calling Bob…" over the call screen while this device's ring is unanswered. */
@Composable
internal fun CallingIndicator(name: String, modifier: Modifier = Modifier) {
    Box(modifier.statusBarsPadding().padding(top = 72.dp)) {
        Text(
            text = "Calling $name…",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.background(Color(0x99000000), CircleShape).padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}
