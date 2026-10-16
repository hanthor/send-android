package dev.send.matrix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Top-level screens of [SendScreenHost] / [AdaptiveScreenHost]; a higher number opens forward. */
internal const val SCREEN_LOADING = 0
internal const val SCREEN_SIGN_IN = 1
internal const val SCREEN_LIST = 2
internal const val SCREEN_CONVERSATION = 3

/** Narrowest window that shows the conversation list beside the open conversation. */
private val TwoPaneMinWidth = 600.dp

/** Below this height (a phone in landscape) one pane at a time leaves the conversation room for the keyboard. */
private val TwoPaneMinHeight = 480.dp

/**
 * Width of the conversation list pane when the window is wide enough for list and conversation side by side
 * (at least 600dp wide and 480dp tall), or null for one pane at a time. The list takes 40% of the window, kept
 * between 280dp and 400dp. Follows the window, so folding, unfolding, resizing and rotating switch layouts live.
 */
@Composable
internal fun listPaneWidth(): Dp? {
    val window = LocalWindowInfo.current.containerSize
    val (width, height) = with(LocalDensity.current) { window.width.toDp() to window.height.toDp() }
    if (width < TwoPaneMinWidth || height < TwoPaneMinHeight) return null
    return (width * 0.4f).coerceIn(280.dp, 400.dp)
}

/**
 * [SendScreenHost] with list-detail on large screens. With [listPaneWidth] null it is exactly the single-pane host:
 * [list] is the [SCREEN_LIST] screen and [content] every other one. With a width, the signed-in screens show [list]
 * in a fixed pane on the start side and the host in the remaining detail pane, where [SCREEN_LIST] becomes a quiet
 * placeholder and [SCREEN_CONVERSATION] the open conversation with its usual open/close and predictive back motion.
 *
 * The host keeps its place in the composition whichever layout shows, so switching (fold/unfold) keeps an open
 * conversation's state: scroll position, composer focus, reply and selection. The list is movable content, so it
 * keeps its scroll position, search and new-chat page when it moves between the pane and the host.
 */
@Composable
internal fun AdaptiveScreenHost(
    screen: Int,
    listPaneWidth: Dp?,
    list: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val currentList by rememberUpdatedState(list)
    val movableList = remember { movableContentOf { currentList() } }
    val paneWidth = listPaneWidth?.takeIf { screen >= SCREEN_LIST }
    val twoPane = paneWidth != null
    Row(modifier.fillMaxSize()) {
        if (paneWidth != null) {
            Box(
                Modifier.width(paneWidth).fillMaxHeight().testTag(LIST_PANE_TAG)
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End)),
            ) { movableList() }
        }
        val detail = if (twoPane) {
            Modifier.testTag(DETAIL_PANE_TAG).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
        } else Modifier
        SendScreenHost(screen, Modifier.weight(1f).fillMaxHeight().then(detail)) { target ->
            when {
                target != SCREEN_LIST -> content(target)
                twoPane -> NoConversationPane()
                else -> movableList()
            }
        }
    }
}

internal const val LIST_PANE_TAG = "list-pane"
internal const val DETAIL_PANE_TAG = "detail-pane"
internal const val NO_CONVERSATION_TAG = "no-conversation"

/** The detail pane with no conversation open: a quiet hint, nothing to press. */
@Composable
private fun NoConversationPane() {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp).testTag(NO_CONVERSATION_TAG),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "Pick a conversation to read it here",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
