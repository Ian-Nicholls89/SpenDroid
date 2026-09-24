package com.spendroid.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onFirstVisible
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * One set of timings for every animation in the app, so they feel like one app.
 *
 * Material's emphasised curves: things arriving decelerate hard, things leaving get out of
 * the way quickly. Compose already honours the system "Remove animations" setting for all of
 * these, since each runs on the frame clock the system scales.
 */
object Motion {
    /** Arriving on screen: fast start, long gentle settle. */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Changing in place: balanced. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Leaving: quick. */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 500

    /** For values that should settle rather than stop, such as the ring. */
    fun <T> settle() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)

    fun <T> arrive(duration: Int = MEDIUM, delay: Int = 0) =
        tween<T>(durationMillis = duration, delayMillis = delay, easing = EmphasizedDecelerate)

    fun <T> change(duration: Int = MEDIUM) = tween<T>(durationMillis = duration, easing = Emphasized)
}

/**
 * Whether something has been seen yet, so its entrance plays where it can be watched.
 *
 * An animation that runs while its element is scrolled out of view is wasted, and worse, the
 * element then sits there looking as static as it always did. So an entrance waits until at
 * least half of it has been on screen for a moment. Remembered rather than saved: leaving the
 * tab and coming back plays it again, while scrolling past it and back does not.
 */
class Reveal {
    var shown by mutableStateOf(false)
        internal set
}

@Composable
fun rememberReveal(): Reveal = remember { Reveal() }

fun Modifier.revealWhenSeen(reveal: Reveal): Modifier =
    onFirstVisible(minDurationMs = 100, minFractionVisible = 0.5f) { reveal.shown = true }

/**
 * A list row's arrival: a fade and a short rise, each row a beat after the one above. Null
 * means the row is already in place - past the first screenful, or after the list has
 * finished arriving - so scrolling never replays it.
 */
@Composable
fun Modifier.entrance(order: Int?): Modifier {
    if (order == null) return this
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order * 35L)
        shown.animateTo(1f, Motion.arrive(320))
    }
    return graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 16.dp.toPx()
    }
}
