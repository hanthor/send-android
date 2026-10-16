package dev.send.matrix

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntOffset

/**
 * Motion values measured from Google Messages' bytecode and resources
 * (Messages-Motion-Specs workflow). Every value is a public Material 3 /
 * AndroidX default; no Messages code is used.
 */
internal object SendMotion {
    // Material 3 MotionScheme.standard spring tokens (all present in Messages).
    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 1400f)
    fun <T> spatialDefault(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 300f)
    fun <T> effectsFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
    fun <T> effectsDefault(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> effectsSlow(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)

    // Messages' most-used springs: no bounce at StiffnessMediumHigh (26 sites) and
    // StiffnessMedium (14 sites, size changes / expand-shrink).
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1500f)
    fun <T> sizeChange(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 400f)

    /** Lazy list placement (rows moving on reorder, insert, remove): the spatial default spring, settled to the pixel. */
    fun placement(): FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = IntOffset.VisibilityThreshold)

    /** Swipe gestures (reply, archive) settle back or off-screen on the same spring. */
    fun <T> swipeSettle(): FiniteAnimationSpec<T> = spatialDefault()

    /**
     * M3 emphasized easing (`motionEasingEmphasizedInterpolator`, "fast out extra slow in"), used by Messages'
     * screen transitions and MDC's extended FAB change-size specs.
     */
    private val emphasized = PathEasing(
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        },
    )
    private const val SCREEN_MS = 300

    private fun <T> screen() = tween<T>(SCREEN_MS, easing = emphasized)

    // MDC m3_extended_fab_change_size_{collapse,expand}_motion_spec (the resources Messages ships): width, height
    // and padding change over motionDurationMedium4 (400ms); the label fades out after motionDurationShort1 (50ms)
    // over motionDurationShort2 (100ms) on collapse, and in after short2 (100ms) over motionDurationShort4 (200ms)
    // on expand; every property uses the emphasized easing.
    const val FAB_CHANGE_SIZE_MS = 400
    fun <T> fabChangeSize(): FiniteAnimationSpec<T> = tween(FAB_CHANGE_SIZE_MS, easing = emphasized)
    fun <T> fabLabelCollapse(): FiniteAnimationSpec<T> = tween(100, delayMillis = 50, easing = emphasized)
    fun <T> fabLabelExpand(): FiniteAnimationSpec<T> = tween(200, delayMillis = 100, easing = emphasized)

    /** Forward navigation (e.g. list -> conversation): new screen grows in from 85%, old grows to 115%. */
    val open: ContentTransform
        get() = (scaleIn(screen(), initialScale = 0.85f) + fadeIn(tween(50, delayMillis = 50, easing = LinearEasing)))
            .togetherWith(scaleOut(screen(), targetScale = 1.15f) + fadeOut(tween(50, delayMillis = 50, easing = LinearEasing)))

    /** Back navigation: returning screen settles from 110%, leaving screen shrinks to 90%. */
    val close: ContentTransform
        get() = (scaleIn(screen(), initialScale = 1.1f) + fadeIn(tween(50, delayMillis = 66, easing = LinearEasing)))
            .togetherWith(scaleOut(screen(), targetScale = 0.9f) + fadeOut(tween(50, delayMillis = 66, easing = LinearEasing)))

    val openEnter: EnterTransition
        get() = scaleIn(screen(), initialScale = 0.85f) + fadeIn(tween(50, delayMillis = 50, easing = LinearEasing))
    val closeExit: ExitTransition
        get() = scaleOut(screen(), targetScale = 0.9f) + fadeOut(tween(50, delayMillis = 66, easing = LinearEasing))
}
