package com.spendroid.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

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
