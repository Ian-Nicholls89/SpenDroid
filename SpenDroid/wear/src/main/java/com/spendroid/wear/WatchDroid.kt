package com.spendroid.wear

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.spendroid.ui.DroidFace
import com.spendroid.ui.DroidMood
import com.spendroid.ui.DroidMoment
import com.spendroid.ui.drawPose
import com.spendroid.ui.droidColour
import com.spendroid.ui.droidStill
import com.spendroid.ui.pennyStill
import com.spendroid.ui.poseAt

/**
 * Penny on the watch: the phone's own - the shared :penny module - so she looks and moves the
 * same on both. The watch has her four budget moods.
 */
internal enum class WatchMood(val mood: DroidMood) { AHEAD(DroidMood.AHEAD), TIGHT(DroidMood.TIGHT), OVER(DroidMood.OVER), PAYDAY(DroidMood.PAYDAY) }

internal fun moodOf(r: BudgetReading): WatchMood = when {
    r.map.getBoolean("payday", false) -> WatchMood.PAYDAY
    r.pace == "OVER" -> WatchMood.OVER
    r.pace == "TIGHT" -> WatchMood.TIGHT
    else -> WatchMood.AHEAD
}

/**
 * How long she moves for as a screen opens: her visor filling to the figure, then a little of her
 * mood. After that she holds still - a watch's battery is small, and she's looked at for seconds.
 */
private const val PLAY_MS = 4_500L

@Composable
internal fun WatchDroid(mood: WatchMood, used: Float?, elapsed: Float?, accent: Int, size: Dp, modifier: Modifier = Modifier) {
    val face = DroidFace(mood.mood, used = if (mood == WatchMood.PAYDAY) 1f else (used ?: 0f), elapsed = elapsed ?: 0.5f)
    val colour = droidColour(face.mood, Color(accent))
    val still = droidStill()
    var ms by remember { mutableLongStateOf(if (still) PLAY_MS else 0L) }
    if (!still) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (ms < PLAY_MS) withFrameNanos { ms = (it - start) / 1_000_000 }
        }
    }
    val said = when (mood) {
        WatchMood.AHEAD -> "On track"
        WatchMood.TIGHT -> "A bit tight"
        WatchMood.OVER -> "Over for the days so far"
        WatchMood.PAYDAY -> "Payday"
    }
    Canvas(modifier.size(size).semantics { contentDescription = said }) {
        // Read here, in drawing, so only her drawing redraws as the clock ticks.
        val t = ms
        val pose = if (t < PLAY_MS) poseAt(face.mood, DroidMoment.NONE, t, t, t, true) else poseAt(face.mood, DroidMoment.NONE, 0L, 0L, null, false)
        scale(this.size.minDimension / 108f, pivot = Offset.Zero) { drawPose(face, pose, colour, Color.White) }
    }
}

/** Penny as a picture, for a tile: tiles take images, not drawing. Her mood's own pose, with a typical fill. */
internal fun watchDroidBitmap(mood: WatchMood, accent: Int, sizePx: Int = 96): Bitmap {
    val fill = when (mood) { WatchMood.AHEAD -> 0.3f; WatchMood.TIGHT -> 0.55f; WatchMood.OVER -> 0.75f; WatchMood.PAYDAY -> 1f }
    return pennyStill(DroidFace(mood.mood, fill, 0.5f), Color(accent), sizePx)
}
