package dev.send.matrix

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
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
            avatar = { data, size -> Avatar(name = data.name ?: data.id, size = size) },
        )
    }
}
