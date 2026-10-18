package com.spendroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.domain.BudgetPace
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.ui.theme.Charcoal
import com.spendroid.ui.theme.Grotesk

/** The droid's face: how the budget is going, said with a mouth. */
enum class DroidMood { AHEAD, TIGHT, OVER, PAYDAY, SYNCING, ASLEEP }

/**
 * What the droid shows: its mood, and its visor - the pace bar, [used] the share of the budget
 * gone, the white tick at [elapsed], the share of the cycle gone.
 */
data class DroidFace(
    val mood: DroidMood = DroidMood.AHEAD,
    val used: Float = 0.28f,
    val elapsed: Float = 0.5f,
)

/** The droid as the budget has it now, shared by every header so they all wear the same face. */
object DroidNow {
    var face by androidx.compose.runtime.mutableStateOf(DroidFace())
}

/** The face for [budget]: payday on the day pay came, otherwise its pace; syncing while it syncs. */
fun droidFaceOf(budget: BudgetSnapshot?, syncing: Boolean = false): DroidFace {
    if (budget == null) return DroidFace(if (syncing) DroidMood.SYNCING else DroidMood.AHEAD)
    val used = BudgetPace.usedFraction(budget) ?: 0f
    val elapsed = BudgetPace.elapsedFraction(budget) ?: 0.5f
    val mood = when {
        syncing -> DroidMood.SYNCING
        budget.asOf == budget.cycleStart -> DroidMood.PAYDAY
        else -> when (BudgetPace.of(budget)) {
            BudgetPace.Pace.ON_TRACK -> DroidMood.AHEAD
            BudgetPace.Pace.TIGHT -> DroidMood.TIGHT
            BudgetPace.Pace.OVER -> DroidMood.OVER
        }
    }
    return DroidFace(mood, used, elapsed)
}

/** The visor's colour for a mood: the app's own colour, unless it's a warning. */
fun droidColour(mood: DroidMood, accent: Color): Color = when (mood) {
    DroidMood.TIGHT -> Charcoal.Warn
    DroidMood.OVER -> Charcoal.Bad
    else -> accent
}

/**
 * The droid: an outline head with an angled antenna, its eyes one visor that is the pace bar,
 * and a mouth for the mood. Drawn on the same 108-unit square as the launcher icon.
 */
@Composable
fun Droid(
    face: DroidFace,
    size: Dp,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    line: Color = Color.White,
) {
    val colour = droidColour(face.mood, accent)
    Canvas(
        modifier.size(size).semantics {
            contentDescription = when (face.mood) {
                DroidMood.AHEAD -> "On track"
                DroidMood.TIGHT -> "A bit tight"
                DroidMood.OVER -> "Over for the days so far"
                DroidMood.PAYDAY -> "Payday"
                DroidMood.SYNCING -> "Syncing"
                DroidMood.ASLEEP -> "Overnight"
            }
        },
    ) {
        scale(this.size.minDimension / 108f, pivot = Offset.Zero) { drawDroid(face, colour, line) }
    }
}

/** The drawing itself, in 108 units. Shared with nothing else, so the widget draws its own copy. */
private fun DrawScope.drawDroid(face: DroidFace, colour: Color, line: Color) {
    val track = Color(0xFF3A3D45)
    val cap = StrokeCap.Round
    // Antenna and bulb.
    drawLine(line, Offset(62f, 25f), Offset(67f, 15f), strokeWidth = 4f, cap = cap)
    drawCircle(colour, radius = 4.5f, center = Offset(68f, 13f))
    // Head.
    drawRoundRect(line, Offset(26f, 27f), Size(56f, 52f), CornerRadius(16f), style = Stroke(5f))
    // Visor: the budget used, then the tick where the days are. Payday: full, no tick.
    val fill = if (face.mood == DroidMood.PAYDAY) 1f else face.used.coerceIn(0f, 1f)
    val alpha = if (face.mood == DroidMood.ASLEEP) 0.45f else 1f
    drawRoundRect(track, Offset(33f, 40f), Size(42f, 14f), CornerRadius(4.2f))
    if (fill > 0f) drawRoundRect(colour.copy(alpha = alpha), Offset(33f, 40f), Size(42f * fill, 14f), CornerRadius(4.2f))
    if (face.mood != DroidMood.PAYDAY) {
        val x = 33f + 42f * face.elapsed.coerceIn(0f, 1f)
        drawRoundRect(line, Offset((x - 2f).coerceIn(33f, 73f), 36f), Size(4f, 22f), CornerRadius(2f))
    }
    // Mouth.
    val mouth = Path()
    when (face.mood) {
        DroidMood.AHEAD -> { mouth.moveTo(45f, 66f); mouth.quadraticTo(54f, 72f, 63f, 66f) }
        DroidMood.TIGHT -> { mouth.moveTo(46f, 68f); mouth.lineTo(62f, 68f) }
        DroidMood.OVER -> { mouth.moveTo(45f, 70f); mouth.quadraticTo(54f, 64f, 63f, 70f) }
        DroidMood.ASLEEP -> { mouth.moveTo(48f, 68f); mouth.quadraticTo(54f, 70f, 60f, 68f) }
        DroidMood.PAYDAY -> {
            val open = Path().apply { moveTo(43f, 64f); quadraticTo(54f, 78f, 65f, 64f); close() }
            drawPath(open, line)
        }
        DroidMood.SYNCING -> drawCircle(line, radius = 3.5f, center = Offset(54f, 68f))
    }
    if (face.mood in setOf(DroidMood.AHEAD, DroidMood.TIGHT, DroidMood.OVER, DroidMood.ASLEEP)) {
        drawPath(mouth, line, style = Stroke(4.5f, cap = cap))
    }
    when (face.mood) {
        DroidMood.PAYDAY -> listOf(Triple(12f, 32f, Charcoal.Warn), Triple(92f, 36f, line), Triple(14f, 76f, line), Triple(92f, 80f, Charcoal.Warn), Triple(22f, 14f, colour))
            .forEach { (x, y, c) -> drawRect(c, Offset(x, y), Size(5f, 5f)) }
        DroidMood.SYNCING -> {
            drawArc(line, 30f, 290f, false, Offset(80f, 16f), Size(16f, 16f), style = Stroke(3f, cap = cap))
        }
        DroidMood.ASLEEP -> {
            drawLine(line, Offset(82f, 18f), Offset(88f, 18f), 2.5f, cap); drawLine(line, Offset(88f, 18f), Offset(82f, 25f), 2.5f, cap); drawLine(line, Offset(82f, 25f), Offset(88f, 25f), 2.5f, cap)
        }
        else -> Unit
    }
}

/**
 * The header's name: the droid, then the screen's name in Space Grotesk. The app's own name
 * splits, "spen" white and "droid" in the app's colour.
 */
@Composable
fun Wordmark(text: String, colour: Color, modifier: Modifier = Modifier) {
    val face = DroidNow.face
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Droid(face, 30.dp, accent = colour)
        Spacer(Modifier.width(10.dp))
        Text(
            if (text.equals("SpenDroid", ignoreCase = true)) {
                buildAnnotatedString {
                    append("spen")
                    withStyle(SpanStyle(color = colour)) { append("droid") }
                }
            } else {
                buildAnnotatedString { withStyle(SpanStyle(color = colour)) { append(text.lowercase()) } }
            },
            fontFamily = Grotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            letterSpacing = (-0.3).sp,
            color = Color.White,
            maxLines = 1,
        )
    }
}
