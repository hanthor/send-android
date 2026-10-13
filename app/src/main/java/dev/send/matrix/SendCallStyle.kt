package dev.send.matrix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.element.android.call.ui.theme.ElementCallColors
import io.element.android.call.ui.theme.ElementCallIcons
import io.element.android.call.ui.theme.ElementCallStyle
import io.element.android.call.ui.theme.ElementCallTypography

/**
 * Send's skin over the Element Call composables: the call canvas stays dark (video
 * letterboxing and tiles require it), while the accent follows Send's lavender identity
 * and avatars match the conversation list.
 */
@Composable
fun rememberSendCallStyle(): ElementCallStyle {
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val icons = ElementCallIcons.default()
    return remember(primary, error, icons) {
        ElementCallStyle(
            colors = ElementCallColors.Dark.copy(
                iconAccent = primary,
                borderActiveSpeaker = SolidColor(primary),
                hangUp = error,
            ),
            typography = ElementCallTypography.Default,
            icons = icons,
            avatar = { data, size -> SendCallAvatar(data.name ?: data.id, size) },
        )
    }
}

@Composable
private fun SendCallAvatar(name: String, size: Dp) {
    Surface(color = Color(0xFFE8EEFF), shape = CircleShape) {
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).uppercase(),
                color = Color(0xFF433675),
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
