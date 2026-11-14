package dev.aeon.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * One set of timings for the whole app, so that nothing moves at its own speed.
 *
 * - **Quick** (120 ms): what answers a touch or goes away: a press, a colour, something leaving.
 * - **Standard** (220 ms): a change inside a screen: a row appearing, a card growing.
 * - **Slow** (320 ms): a whole screen or a sheet moving.
 *
 * Things that arrive decelerate (they come in fast and settle); things that leave accelerate and are shorter, so the
 * screen never waits for what is already gone. The system's "remove animations" setting shortens every one of these to
 * nothing, because Compose reads the system's animation scale.
 */
object Motion {
    const val Quick = 120
    const val Standard = 220
    const val Slow = 320

    val Arrive = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Leave = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    fun <T> arrive(ms: Int = Standard, delayMs: Int = 0): FiniteAnimationSpec<T> = tween(ms, delayMs, Arrive)
    fun <T> leave(ms: Int = Quick): FiniteAnimationSpec<T> = tween(ms, 0, Leave)

    /** Where a row or card settles when the list changes around it: a soft spring without bounce. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

/** Three dots that swell one after another: something is being done, and the app has not stopped. */
@Composable
fun WorkingDots(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "working")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val level by transition.animateFloat(
                0.3f, 1f,
                infiniteRepeatable(tween(520, easing = Motion.Arrive), RepeatMode.Reverse, StartOffset(i * 170)),
                label = "dot$i",
            )
            Row(
                Modifier.size(7.dp).graphicsLayer { scaleX = 0.6f + 0.4f * level; scaleY = 0.6f + 0.4f * level; alpha = level }.background(color, CircleShape),
            ) {}
        }
    }
}

/** A row of a lazy list: it fades in when it is new, fades out when it goes, and glides when the others around it come and go. */
fun LazyItemScope.itemMotion(): Modifier =
    Modifier.animateItem(fadeInSpec = Motion.arrive(), placementSpec = Motion.settle(), fadeOutSpec = Motion.leave())

/**
 * A touch sinks the thing a little and it springs back when the finger leaves, so the screen answers before the action
 * happens. Put it on whatever is tapped, with the same interaction source the tap uses.
 */
@Composable
fun Modifier.pressScale(source: InteractionSource, to: Float = 0.96f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) to else 1f,
        // Down is quick and does not overshoot; up springs back with a hint of bounce.
        if (pressed) tween(Motion.Quick, easing = Motion.Arrive) else spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
