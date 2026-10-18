package com.spendroid.wear

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp

/**
 * The phone's droid, on the watch: an outline head, the visor as the pace bar (the budget used,
 * a white tick where the days are) and a mouth for the mood. The same drawing as the phone's
 * ui/Droid.kt - the two are separate apps, so it's drawn here again.
 */
internal enum class WatchMood { AHEAD, TIGHT, OVER, PAYDAY }

internal fun moodOf(r: BudgetReading): WatchMood = when {
    r.map.getBoolean("payday", false) -> WatchMood.PAYDAY
    r.pace == "OVER" -> WatchMood.OVER
    r.pace == "TIGHT" -> WatchMood.TIGHT
    else -> WatchMood.AHEAD
}

private const val AMBER = 0xFFF5A623.toInt()
private const val RED = 0xFFE5534B.toInt()

private fun colourOf(mood: WatchMood, accent: Int) = when (mood) {
    WatchMood.TIGHT -> AMBER
    WatchMood.OVER -> RED
    else -> accent
}

@Composable
internal fun WatchDroid(mood: WatchMood, used: Float?, elapsed: Float?, accent: Int, size: Dp, modifier: Modifier = Modifier) {
    val colour = Color(colourOf(mood, accent))
    Canvas(modifier.size(size)) {
        scale(this.size.minDimension / 108f, pivot = Offset.Zero) {
            val white = Color.White
            drawLine(white, Offset(62f, 25f), Offset(67f, 15f), 4f, StrokeCap.Round)
            drawCircle(colour, 4.5f, Offset(68f, 13f))
            drawRoundRect(white, Offset(26f, 27f), Size(56f, 52f), CornerRadius(16f), style = Stroke(5f))
            drawRoundRect(Color(0xFF3A3D45), Offset(33f, 40f), Size(42f, 14f), CornerRadius(4.2f))
            val fill = if (mood == WatchMood.PAYDAY) 1f else (used ?: 0f).coerceIn(0f, 1f)
            if (fill > 0f) drawRoundRect(colour, Offset(33f, 40f), Size(42f * fill, 14f), CornerRadius(4.2f))
            if (mood != WatchMood.PAYDAY) {
                val x = (33f + 42f * (elapsed ?: 0.5f).coerceIn(0f, 1f) - 2f).coerceIn(33f, 73f)
                drawRoundRect(white, Offset(x, 36f), Size(4f, 22f), CornerRadius(2f))
            }
            val mouth = Path()
            when (mood) {
                WatchMood.AHEAD -> { mouth.moveTo(45f, 66f); mouth.quadraticTo(54f, 72f, 63f, 66f) }
                WatchMood.TIGHT -> { mouth.moveTo(46f, 68f); mouth.lineTo(62f, 68f) }
                WatchMood.OVER -> { mouth.moveTo(45f, 70f); mouth.quadraticTo(54f, 64f, 63f, 70f) }
                WatchMood.PAYDAY -> { mouth.moveTo(43f, 64f); mouth.quadraticTo(54f, 78f, 65f, 64f); mouth.close() }
            }
            if (mood == WatchMood.PAYDAY) drawPath(mouth, white) else drawPath(mouth, white, style = Stroke(4.5f, cap = StrokeCap.Round))
        }
    }
}

/** The droid as a picture, for a tile: tiles take images, not drawing. A typical fill per mood. */
internal fun watchDroidBitmap(mood: WatchMood, accent: Int, sizePx: Int = 96): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bitmap)
    c.scale(sizePx / 108f, sizePx / 108f)
    fun paint(colour: Int, stroke: Float? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        if (stroke != null) { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
    }
    val white = android.graphics.Color.WHITE
    val colour = colourOf(mood, accent)
    c.drawLine(62f, 25f, 67f, 15f, paint(white, 4f))
    c.drawCircle(68f, 13f, 4.5f, paint(colour))
    c.drawRoundRect(RectF(26f, 27f, 82f, 79f), 16f, 16f, paint(white, 5f))
    c.drawRoundRect(RectF(33f, 40f, 75f, 54f), 4.2f, 4.2f, paint(0xFF3A3D45.toInt()))
    val fill = when (mood) { WatchMood.AHEAD -> 0.3f; WatchMood.TIGHT -> 0.55f; WatchMood.OVER -> 0.75f; WatchMood.PAYDAY -> 1f }
    c.drawRoundRect(RectF(33f, 40f, 33f + 42f * fill, 54f), 4.2f, 4.2f, paint(colour))
    if (mood != WatchMood.PAYDAY) c.drawRoundRect(RectF(52f, 36f, 56f, 58f), 2f, 2f, paint(white))
    val mouth = android.graphics.Path()
    when (mood) {
        WatchMood.AHEAD -> { mouth.moveTo(45f, 66f); mouth.quadTo(54f, 72f, 63f, 66f) }
        WatchMood.TIGHT -> { mouth.moveTo(46f, 68f); mouth.lineTo(62f, 68f) }
        WatchMood.OVER -> { mouth.moveTo(45f, 70f); mouth.quadTo(54f, 64f, 63f, 70f) }
        WatchMood.PAYDAY -> { mouth.moveTo(43f, 64f); mouth.quadTo(54f, 78f, 65f, 64f); mouth.close() }
    }
    c.drawPath(mouth, if (mood == WatchMood.PAYDAY) paint(white) else paint(white, 4.5f))
    return bitmap
}
