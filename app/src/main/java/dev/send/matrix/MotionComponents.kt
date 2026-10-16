package dev.send.matrix

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---- Top-level screens and the shared conversation avatar/name -------------------------------------------------

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSendSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The top-level screen's enter/exit scope; null outside [SendScreenHost] (overlays, fixtures), where nothing is shared. */
internal val LocalSendScreenScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Top-level screens (list, conversation, sign-in) switch with Messages' fragment open/close transitions; elements
 * marked with [sendSharedElement] / [sendSharedBounds] (the conversation's avatar and name) move between them.
 * Screens are ordered by [screen]: a higher number opens forward, a lower one goes back.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SendScreenHost(screen: Int, modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(LocalSendSharedTransitionScope provides this) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = { if (targetState > initialState) SendMotion.open else SendMotion.close },
                label = "SendScreen",
            ) { target ->
                CompositionLocalProvider(LocalSendScreenScope provides this) { content(target) }
            }
        }
    }
}

/** Shared elements travel on the spatial default spring, which settles with the 300ms screen transition. */
@OptIn(ExperimentalSharedTransitionApi::class)
private val SharedBounds = BoundsTransform { _, _ -> SendMotion.spatialDefault() }

/** Same content on both screens (the avatar): it moves and resizes between them. No-op outside [SendScreenHost]. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sendSharedElement(key: Any): Modifier {
    val transition = LocalSendSharedTransitionScope.current ?: return this
    val screen = LocalSendScreenScope.current ?: return this
    return with(transition) {
        this@sendSharedElement.sharedElement(rememberSharedContentState(key), screen, boundsTransform = SharedBounds)
    }
}

/** Different-looking content on both screens (row name → header title): bounds move, the two cross-fade. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sendSharedBounds(key: Any): Modifier {
    val transition = LocalSendSharedTransitionScope.current ?: return this
    val screen = LocalSendScreenScope.current ?: return this
    return with(transition) {
        this@sendSharedBounds.sharedBounds(rememberSharedContentState(key), screen, boundsTransform = SharedBounds)
    }
}

internal fun sharedAvatarKey(roomId: String) = "avatar-$roomId"
internal fun sharedTitleKey(roomId: String) = "title-$roomId"

// ---- Predictive back -------------------------------------------------------------------------------------------

/** Progress of a back gesture over a screen (0 when none), from the system's [BackEventCompat]s. */
internal class PredictiveBackState {
    var progress by mutableFloatStateOf(0f)
        internal set
    var fromLeftEdge by mutableStateOf(true)
        internal set
}

/**
 * Predictive back for a screen that leaves with [onBack]: while the gesture runs the screen follows its progress
 * ([predictiveBack]); releasing commits [onBack] (the caller's SendMotion close transition plays from where the
 * gesture left the screen), cancelling springs it back. A plain back press runs [onBack] directly.
 */
@Composable
internal fun rememberPredictiveBack(enabled: Boolean = true, onBack: () -> Unit): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val scope = rememberCoroutineScope()
    val back by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                state.fromLeftEdge = event.swipeEdge == BackEventCompat.EDGE_LEFT
                state.progress = event.progress
            }
            back()
            if (state.progress > 0f) scope.launch {
                // Normally the screen has left by now (this scope ends with it); if it stayed, it settles back.
                delay(PREDICTIVE_BACK_SETTLE_DELAY_MS)
                settle(state)
            }
        } catch (cancelled: CancellationException) {
            scope.launch { settle(state) }
            throw cancelled
        }
    }
    return state
}

private suspend fun settle(state: PredictiveBackState) {
    animate(state.progress, 0f, animationSpec = SendMotion.spatialFast()) { value, _ -> state.progress = value }
}

/**
 * The system's in-app predictive back look: the screen shrinks to 90% and shifts toward the swipe, keeping an 8dp
 * margin from the edge it moves to, with rounded corners while the gesture runs.
 */
internal fun Modifier.predictiveBack(state: PredictiveBackState): Modifier = this.graphicsLayer {
    val progress = state.progress
    val scale = 1f - PREDICTIVE_BACK_SCALE_DROP * progress
    scaleX = scale
    scaleY = scale
    val shift = (size.width * PREDICTIVE_BACK_SCALE_DROP / 2f - PREDICTIVE_BACK_MARGIN.toPx()).coerceAtLeast(0f)
    translationX = (if (state.fromLeftEdge) shift else -shift) * progress
    shape = PredictiveBackShape
    clip = progress > 0f
}

private const val PREDICTIVE_BACK_SCALE_DROP = 0.1f
private val PREDICTIVE_BACK_MARGIN = 8.dp
private val PredictiveBackShape = RoundedCornerShape(28.dp)
private const val PREDICTIVE_BACK_SETTLE_DELAY_MS = 500L

// ---- Extended FAB ----------------------------------------------------------------------------------------------

/**
 * Messages' list FAB state: expanded at the top of the list and while scrolling back up, collapsed to its icon
 * while scrolling down.
 */
@Composable
internal fun rememberFabExpanded(list: LazyListState): Boolean {
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        var previousIndex = list.firstVisibleItemIndex
        var previousOffset = list.firstVisibleItemScrollOffset
        // Index and offset packed in one Long so each scroll frame doesn't allocate.
        snapshotFlow { (list.firstVisibleItemIndex.toLong() shl 32) or list.firstVisibleItemScrollOffset.toLong() }.collect { packed ->
            val index = (packed ushr 32).toInt()
            val offset = packed.toInt()
            expanded = when {
                index == 0 && offset == 0 -> true
                index != previousIndex -> index < previousIndex
                offset != previousOffset -> offset < previousOffset
                else -> expanded
            }
            previousIndex = index
            previousOffset = offset
        }
    }
    return expanded
}

/**
 * M3 extended FAB that collapses to its icon ([expanded] false) with MDC's change-size motion: the width shrinks
 * or grows over 400ms while the label fades on its own delayed timing ([SendMotion.fabChangeSize]).
 */
@Composable
internal fun CollapsingExtendedFab(
    expanded: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    containerColor: Color,
    contentColor: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val transition = updateTransition(expanded, label = "fabExpanded")
    val size = transition.animateFloat({ SendMotion.fabChangeSize() }, label = "fabSize") { if (it) 1f else 0f }
    val labelAlpha = transition.animateFloat(
        { if (targetState) SendMotion.fabLabelExpand() else SendMotion.fabLabelCollapse() },
        label = "fabLabel",
    ) { if (it) 1f else 0f }
    FloatingActionButton(onClick = onClick, modifier = modifier, shape = shape, containerColor = containerColor, contentColor = contentColor) {
        // M3 extended FAB: 16dp start, 12dp icon-label gap, 20dp end; collapsed it is the 56dp icon FAB.
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Layout(
                content = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(12.dp))
                        text()
                        Spacer(Modifier.width(4.dp))
                    }
                },
                modifier = Modifier.clipToBounds().graphicsLayer { alpha = labelAlpha.value },
            ) { measurables, constraints ->
                val label = measurables.single().measure(Constraints(maxHeight = constraints.maxHeight))
                layout((label.width * size.value).roundToInt(), label.height) { label.placeRelative(0, 0) }
            }
        }
    }
}

// ---- Lists -----------------------------------------------------------------------------------------------------

/** `animateItem` on SendMotion's tokens: rows fade in/out and slide to new places (reorder, insert, remove). */
internal fun LazyItemScope.sendItemPlacement(fadeIn: Boolean = true): Modifier = Modifier.animateItem(
    fadeInSpec = if (fadeIn) SendMotion.effectsDefault() else null,
    placementSpec = SendMotion.placement(),
    fadeOutSpec = SendMotion.effectsFast(),
)

/**
 * Messages that arrive while a conversation is open animate in once ([bubbleEntry]); loaded or paginated history
 * doesn't. "Arriving" means newer than the moment the conversation opened, first seen under its [MatrixMessage.key].
 */
internal class BubbleEntries(private val openedAtMillis: Long) {
    private val entered = HashSet<String>()

    fun arriving(message: MatrixMessage): Boolean = message.timestampMillis >= openedAtMillis && entered.add(message.key)
}

@Composable
internal fun rememberBubbleEntries(): BubbleEntries = remember { BubbleEntries(System.currentTimeMillis()) }

/**
 * Entry of a new bubble: own messages rise from the composer on the spatial spring while fading in; incoming ones
 * fade and grow from their bottom corner.
 */
@Composable
internal fun Modifier.bubbleEntry(entries: BubbleEntries, message: MatrixMessage): Modifier {
    val rise = remember(message.key) { Animatable(if (entries.arriving(message)) 0f else 1f) }
    val fade = remember(rise) { Animatable(rise.value) }
    LaunchedEffect(rise) {
        if (rise.value < 1f) {
            launch { fade.animateTo(1f, SendMotion.effectsDefault()) }
            rise.animateTo(1f, SendMotion.spatialDefault())
        }
    }
    val own = message.own
    val origin = if (message.call != null) TransformOrigin(0.5f, 1f) else TransformOrigin(0f, 1f)
    val distance = with(LocalDensity.current) { BUBBLE_RISE.toPx() }
    return this.graphicsLayer {
        val progress = rise.value
        if (own) {
            translationY = (1f - progress) * distance
        } else {
            val scale = INCOMING_START_SCALE + (1f - INCOMING_START_SCALE) * progress
            scaleX = scale
            scaleY = scale
            transformOrigin = origin
        }
        alpha = fade.value
    }
}

/** How far below its place (into the composer) an own bubble starts. */
internal val BUBBLE_RISE = 64.dp
internal const val INCOMING_START_SCALE = 0.8f

// ---- Swipe gestures --------------------------------------------------------------------------------------------

/**
 * Threshold feedback shared by swipe-to-reply and swipe-to-archive: a `GestureThresholdActivate` tick crossing the
 * threshold, `GestureThresholdDeactivate` crossing back.
 */
internal class SwipeThreshold(private val haptics: HapticFeedback) {
    var armed by mutableStateOf(false)
        private set

    fun update(nowArmed: Boolean) {
        if (nowArmed == armed) return
        haptics.performHapticFeedback(if (nowArmed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.GestureThresholdDeactivate)
        armed = nowArmed
    }

    /** Ends the gesture; true when it was released past the threshold. */
    fun release(): Boolean = armed.also { armed = false }
}

@Composable
internal fun rememberSwipeThreshold(): SwipeThreshold {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { SwipeThreshold(haptics) }
}
