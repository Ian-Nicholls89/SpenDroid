package com.spendroid.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min


/*
 * Penny, shared by the phone and the watch: her face, her poses frame by frame, and drawing them.
 * In the phone's package, so the phone's code reads as it did before she moved here.
 */

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

/** Amber and red, as the phone's theme has them: Penny's visor when it's tight, and when over. */
val PENNY_WARN = Color(0xFFF5A623)
val PENNY_BAD = Color(0xFFE5534B)
/** Green, for her tick when she's noted something. */
val PENNY_GOOD = Color(0xFF43B05C)

/** The visor's colour for a mood: the app's own colour, unless it's a warning. */
fun droidColour(mood: DroidMood, accent: Color): Color = when (mood) {
    DroidMood.TIGHT -> PENNY_WARN
    DroidMood.OVER -> PENNY_BAD
    else -> accent
}

/**
 * Penny, moving. Her moods are how she carries on all the time; moments play when something
 * happens - she's met for the first time, has a question, notices something, notes an answer.
 * The user chose each one from mock-ups (proposals/penny-final.html): elastic for idle, tight,
 * meeting her, noticing and noting; expressive for the rest; a glow throughout but asleep, as
 * she's off. Glows come only from the coloured parts, and the bulb is never faded - it would
 * vanish against the dark.
 */
enum class DroidMoment { NONE, HELLO, QUESTION, NOTICED, NOTED }

/** Penny drawn still: for screenshots, and wherever motion shouldn't run. */
val LocalDroidStill = staticCompositionLocalOf { false }

/** For screenshots: every Penny drawn as she is this many ms into her mood and moment. */
val LocalDroidAt = staticCompositionLocalOf<Long?> { null }

/**
 * Everything that moves, in the 108-unit square she's drawn in. Neutral by default, so a pose
 * only says what differs.
 */
data class Pose(
    val bodyDy: Float = 0f, val bodySx: Float = 1f, val bodySy: Float = 1f, val bodyRot: Float = 0f, val alpha: Float = 1f,
    val headDy: Float = 0f, val headRot: Float = 0f, val headSx: Float = 1f, val headSy: Float = 1f,
    val antennaRot: Float = 0f,
    val visorSy: Float = 1f, val visorAlpha: Float = 1f,
    val fillScale: Float = 1f, val fillAlpha: Float = 1f, val fillGlow: Float = 0f, val hue: Float = 0f,
    val tickDy: Float = 0f, val tickAlpha: Float = 1f,
    val shine: Float = -1f, val shineAlpha: Float = 0f,
    val bulbGlow: Float = 0f, val bulbAmber: Float = 0f,
    val mouthAlpha: Float = 1f,
    val confetti: Float = -1f, val sweat: Float = -1f, val zzz: Float = -1f, val spin: Float = -1f,
    val spark: Float = 0f, val check: Float = -1f, val checkAlpha: Float = 1f, val question: Float = 0f,
)

/** Through (time, value) pairs, eased in and out between each; held before the first and after the last. */
fun kf(t: Float, vararg p: Float): Float {
    if (t <= p[0]) return p[1]
    var i = 0
    while (i + 2 < p.size) {
        val t0 = p[i]; val v0 = p[i + 1]; val t1 = p[i + 2]; val v1 = p[i + 3]
        if (t <= t1) {
            val u = if (t1 > t0) (t - t0) / (t1 - t0) else 1f
            val e = u * u * (3 - 2 * u)
            return v0 + (v1 - v0) * e
        }
        i += 2
    }
    return p[p.size - 1]
}

private fun loop(ms: Long, period: Int): Float = (ms % period) / period.toFloat()

/** How long each moment plays before she goes back to her mood; the question stays while asked. */
fun momentLength(moment: DroidMoment): Long = when (moment) {
    DroidMoment.HELLO -> 2_600
    DroidMoment.NOTICED, DroidMoment.NOTED -> 3_000
    else -> 0
}

/**
 * Penny's pose at [ms] since she appeared, [sinceMoment] since the moment began, and [sinceOpen]
 * since the screen opened (the visor's fill), for [mood].
 */
fun poseAt(mood: DroidMood, moment: DroidMoment, ms: Long, sinceMoment: Long, sinceOpen: Long?, fillOnOpen: Boolean): Pose {
    val playing = moment == DroidMoment.QUESTION || (moment != DroidMoment.NONE && sinceMoment < momentLength(moment))
    var p = if (playing) momentPose(moment, sinceMoment) else moodPose(mood, ms)
    if (fillOnOpen && sinceOpen != null && sinceOpen < 3_000) p = withFillOnOpen(p, sinceOpen)
    return p
}

private fun moodPose(mood: DroidMood, ms: Long): Pose = when (mood) {
    // Idle, elastic: a jelly wobble, a springy antenna, a cartoon blink; and, being ahead, a hop
    // now and then (expressive), with a warm glow.
    DroidMood.AHEAD -> {
        val j = loop(ms, 3_000)
        val hop = loop(ms, 6_000)
        val h = if (hop > 0.8f) (hop - 0.8f) / 0.2f else 0f
        Pose(
            bodyDy = kf(h, 0f, 0f, 0.25f, -8f, 0.5f, 0f, 1f, 0f),
            bodySx = kf(j, 0f, 1f, 0.25f, 1.03f, 0.5f, 0.98f, 0.75f, 1.02f, 1f, 1f) * kf(h, 0f, 1f, 0.1f, 1.06f, 0.25f, 0.97f, 0.5f, 1.04f, 0.65f, 1f),
            bodySy = kf(j, 0f, 1f, 0.25f, 0.97f, 0.5f, 1.03f, 0.75f, 0.98f, 1f, 1f) * kf(h, 0f, 1f, 0.1f, 0.94f, 0.25f, 1.03f, 0.5f, 0.96f, 0.65f, 1f),
            antennaRot = kf(j, 0f, 0f, 0.2f, -18f, 0.35f, 10f, 0.5f, -6f, 0.62f, 3f, 0.75f, 0f),
            visorSy = kf(loop(ms, 5_000), 0.46f, 1f, 0.5f, 0.15f, 0.56f, 1f),
            fillGlow = kf(loop(ms, 2_600), 0f, 0.15f, 0.5f, 1f, 1f, 0.15f),
            bulbGlow = kf(loop(ms, 3_000), 0f, 0.2f, 0.5f, 1f, 1f, 0.2f),
        )
    }
    // Tight, elastic: she shivers and squints, sweats; an amber glow breathing fast.
    DroidMood.TIGHT -> {
        val s = loop(ms, 2_000)
        Pose(
            bodyRot = kf(loop(ms, 250), 0f, 0f, 0.25f, 1.5f, 0.75f, -1.5f, 1f, 0f),
            visorSy = kf(s, 0f, 1f, 0.4f, 0.55f, 0.7f, 0.55f, 1f, 1f),
            sweat = s,
            fillGlow = kf(loop(ms, 1_400), 0f, 0.15f, 0.5f, 1f, 1f, 0.15f),
            bulbGlow = kf(loop(ms, 1_400), 0f, 0.2f, 0.5f, 1f, 1f, 0.2f),
        )
    }
    // Over, expressive: she sags, the antenna wilts, the visor flickers; a heavy red glow.
    DroidMood.OVER -> {
        val o = loop(ms, 3_000)
        Pose(
            headDy = kf(o, 0f, 0f, 0.4f, 4f, 0.75f, 4f, 1f, 0f),
            headSx = kf(o, 0f, 1f, 0.4f, 1.02f, 0.75f, 1.02f, 1f, 1f),
            headSy = kf(o, 0f, 1f, 0.4f, 0.97f, 0.75f, 0.97f, 1f, 1f),
            antennaRot = kf(o, 0f, 0f, 0.4f, 28f, 0.75f, 28f, 1f, 0f),
            fillAlpha = kf(o, 0.4f, 1f, 0.45f, 0.55f, 0.5f, 1f, 0.55f, 0.55f, 0.6f, 1f),
            fillGlow = kf(o, 0f, 0.1f, 0.5f, 1f, 1f, 0.1f),
        )
    }
    // Payday, expressive: she jumps and confetti bursts out; the visor glows through the colours.
    DroidMood.PAYDAY -> {
        val j = loop(ms, 2_400)
        Pose(
            bodyDy = kf(j, 0f, 0f, 0.1f, 0f, 0.25f, -14f, 0.4f, 0f),
            bodySx = kf(j, 0f, 1f, 0.1f, 1.08f, 0.25f, 0.95f, 0.4f, 1.06f, 0.5f, 1f),
            bodySy = kf(j, 0f, 1f, 0.1f, 0.9f, 0.25f, 1.06f, 0.4f, 0.94f, 0.5f, 1f),
            confetti = j, hue = j * 360f, fillGlow = 0.7f,
        )
    }
    // Syncing, expressive: a glowing light scans the visor; the antenna twitches, the bulb pulses.
    DroidMood.SYNCING -> {
        val s = loop(ms, 1_100)
        Pose(
            shine = kf(s, 0f, 0f, 0.5f, 1f, 1f, 0f), shineAlpha = 0.6f,
            spin = loop(ms, 1_000) * 360f,
            antennaRot = kf(loop(ms, 1_000), 0f, 0f, 0.5f, -14f, 1f, 0f),
            bulbGlow = kf(loop(ms, 1_000), 0f, 0.2f, 0.5f, 1f, 1f, 0.2f),
        )
    }
    // Asleep, expressive: she nods off and z's float up. No glow - she's off.
    DroidMood.ASLEEP -> {
        val n = loop(ms, 5_000)
        Pose(
            headRot = kf(n, 0f, 0f, 0.3f, 6f, 0.6f, 6f, 0.65f, -1f, 1f, 0f),
            headDy = kf(n, 0f, 0f, 0.3f, 2f, 0.6f, 2f, 0.65f, 0f),
            zzz = loop(ms, 3_000),
        )
    }
}

private fun momentPose(moment: DroidMoment, t: Long): Pose = when (moment) {
    // Meet Penny, elastic: she drops in, lands with a squash, lights up and waves. The bulb is lit
    // throughout and only flares.
    DroidMoment.HELLO -> {
        val m = min(t / 4_000f, 0.65f)
        Pose(
            alpha = kf(m, 0f, 0f, 0.1f, 1f),
            bodyDy = kf(m, 0f, -60f, 0.18f, 0f, 0.24f, -10f, 0.3f, 0f),
            bodySx = kf(m, 0f, 0.9f, 0.18f, 1.25f, 0.24f, 0.92f, 0.3f, 1.08f, 0.35f, 1f),
            bodySy = kf(m, 0f, 1.1f, 0.18f, 0.75f, 0.24f, 1.08f, 0.3f, 0.94f, 0.35f, 1f),
            antennaRot = kf(m, 0.25f, 0f, 0.32f, -24f, 0.4f, 14f, 0.48f, -24f, 0.56f, 14f, 0.62f, 0f),
            visorAlpha = kf(m, 0.15f, 0f, 0.3f, 1f), tickAlpha = kf(m, 0.15f, 0f, 0.3f, 1f),
            fillGlow = kf(m, 0.15f, 0f, 0.3f, 1f, 0.55f, 0f),
            bulbGlow = kf(m, 0.3f, 0f, 0.42f, 1f, 0.65f, 0f),
            mouthAlpha = kf(m, 0.4f, 0f, 0.55f, 1f),
        )
    }
    // A question, expressive: her head tilts, the '?' bounces in, the bulb turns amber and its
    // glow pulses - for as long as the question is there.
    DroidMoment.QUESTION -> {
        val q = loop(t, 3_000)
        Pose(
            headRot = kf(q, 0.1f, 0f, 0.2f, -10f, 0.35f, -6f, 0.5f, -10f, 0.9f, -10f, 1f, 0f),
            question = kf(q, 0.08f, 0f, 0.16f, 1.15f, 0.22f, 1f, 0.85f, 1f, 1f, 0f),
            bulbAmber = kf(q, 0.12f, 0f, 0.16f, 1f, 0.86f, 1f, 0.9f, 0f),
            bulbGlow = kf(loop(t, 600), 0f, 0.25f, 0.5f, 1f, 1f, 0.25f),
        )
    }
    // Noticed something, elastic: she jumps in surprise, the antenna boings, the bulb flares.
    DroidMoment.NOTICED -> {
        val n = min(t / 3_000f, 1f)
        Pose(
            bodyDy = kf(n, 0.25f, 0f, 0.3f, -8f, 0.38f, 0f),
            bodySx = kf(n, 0.25f, 1f, 0.3f, 0.88f, 0.38f, 1.1f, 0.45f, 1f),
            bodySy = kf(n, 0.25f, 1f, 0.3f, 1.2f, 0.38f, 0.92f, 0.45f, 1f),
            antennaRot = kf(n, 0.28f, 0f, 0.34f, -40f, 0.4f, -15f, 0.46f, -26f, 0.9f, -26f, 1f, 0f),
            spark = kf(n, 0.3f, 0f, 0.38f, 1f, 0.52f, 1f, 0.6f, 0f),
            bulbGlow = kf(n, 0.25f, 0f, 0.32f, 1f, 0.6f, 0.25f, 1f, 0f),
        )
    }
    // Noted it, elastic: squashy nods, a green glow, and a tick drawn beside her.
    DroidMoment.NOTED -> {
        val n = min(t / 3_000f, 1f)
        Pose(
            headDy = kf(n, 0.15f, 0f, 0.22f, 5f, 0.31f, -3f, 0.4f, 5f, 0.49f, -3f, 0.58f, 5f, 0.67f, -3f, 0.75f, 0f),
            headSx = kf(n, 0.15f, 1f, 0.22f, 1.08f, 0.31f, 0.96f, 0.4f, 1.08f, 0.49f, 0.96f, 0.58f, 1.08f, 0.67f, 0.96f, 0.75f, 1f),
            headSy = kf(n, 0.15f, 1f, 0.22f, 0.9f, 0.31f, 1.05f, 0.4f, 0.9f, 0.49f, 1.05f, 0.58f, 0.9f, 0.67f, 1.05f, 0.75f, 1f),
            check = kf(n, 0.25f, 0f, 0.45f, 1f), checkAlpha = kf(n, 0.85f, 1f, 1f, 0f),
            fillGlow = kf(n, 0.2f, 0f, 0.35f, 1f, 0.7f, 0.2f, 1f, 0f),
        )
    }
    DroidMoment.NONE -> Pose()
}

/** The visor filling as a screen opens, expressive: it springs past and settles, glows, then shines. */
private fun withFillOnOpen(p: Pose, t: Long): Pose {
    val f = t / 4_000f
    return p.copy(
        fillScale = kf(f, 0.08f, 0f, 0.3f, 1.1f, 0.4f, 1f),
        tickDy = kf(f, 0.2f, -12f, 0.36f, 1.5f, 0.45f, 0f),
        tickAlpha = kf(f, 0.2f, 0f, 0.3f, 1f),
        shine = kf(f, 0.4f, 0f, 0.7f, 1f), shineAlpha = kf(f, 0.4f, 0f, 0.5f, 0.5f, 0.7f, 0f),
        fillGlow = max(p.fillGlow, kf(f, 0.3f, 0f, 0.45f, 1f, 0.75f, 0.1f)),
    )
}

/** Animations switched off on the phone, or Penny asked to keep still. */
@Composable
fun droidStill(): Boolean {
    val context = LocalContext.current
    val asked = LocalDroidStill.current
    return asked || remember {
        android.os.Build.FINGERPRINT == "robolectric" ||
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Penny alive: her clock runs while she's on screen, and only her drawing redraws as it ticks. */
@Composable
fun MovingDroid(face: DroidFace, size: Dp, modifier: Modifier, colour: Color, line: Color, moment: DroidMoment, fillOnOpen: Boolean, description: String, bulb: Color? = null) {
    val clock = remember { mutableLongStateOf(0L) }
    val momentAt = remember(moment) { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) androidx.compose.runtime.withFrameMillis { clock.longValue = it - start }
    }
    LaunchedEffect(moment) { momentAt.longValue = clock.longValue }
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        val ms = clock.longValue
        val since = if (momentAt.longValue < 0) 0L else ms - momentAt.longValue
        val pose = poseAt(face.mood, moment, ms, since, ms, fillOnOpen)
        scale(this.size.minDimension / 108f, pivot = Offset.Zero) { drawPose(face, pose, colour, line, bulb = bulb) }
    }
}

private val PIVOT_BASE = Offset(54f, 79f)
private val PIVOT_ANTENNA = Offset(62f, 25f)

/** [bulb]: her bulb in its own colour - amber when what she's saying needs the user - not the visor's. */
fun DrawScope.drawPose(face: DroidFace, p: Pose, colour: Color, line: Color, still: Boolean = false, bulb: Color? = null) {
    val track = Color(0xFF3A3D45)
    val cap = StrokeCap.Round
    val a = p.alpha
    if (a <= 0.01f) return
    val bulbColour = lerp(bulb ?: colour, PENNY_WARN, p.bulbAmber)
    val fillColour = if (p.hue != 0f) hueShift(colour, p.hue) else colour
    withTransform({
        translate(0f, p.bodyDy)
        rotate(p.bodyRot, PIVOT_BASE)
        scale(p.bodySx, p.bodySy, PIVOT_BASE)
    }) {
        withTransform({
            translate(0f, p.headDy)
            rotate(p.headRot, PIVOT_BASE)
            scale(p.headSx, p.headSy, PIVOT_BASE)
        }) {
            // Antenna, bulb and the spark around it, pivoting where the antenna meets the head.
            rotate(p.antennaRot, PIVOT_ANTENNA) {
                drawLine(line.copy(alpha = a), Offset(62f, 25f), Offset(67f, 15f), strokeWidth = 4f, cap = cap)
                glowCircle(bulbColour, Offset(68f, 13f), 4.5f, p.bulbGlow * a)
                drawCircle(bulbColour.copy(alpha = a), radius = 4.5f, center = Offset(68f, 13f))
                if (p.spark > 0f) {
                    val s = PENNY_WARN.copy(alpha = p.spark * a)
                    drawLine(s, Offset(68f, 4f), Offset(68f, -2f), 2.5f, cap)
                    drawLine(s, Offset(75.5f, 7f), Offset(80f, 3f), 2.5f, cap)
                    drawLine(s, Offset(60.5f, 7f), Offset(56f, 3f), 2.5f, cap)
                    drawLine(s, Offset(77f, 14f), Offset(83f, 14f), 2.5f, cap)
                }
            }
            drawRoundRect(line.copy(alpha = a), Offset(26f, 27f), Size(56f, 52f), CornerRadius(16f), style = Stroke(5f))
            // The visor: track, the fill (budget used) with its glow, a passing shine.
            scale(1f, p.visorSy, Offset(54f, 47f)) {
                val va = a * p.visorAlpha
                drawRoundRect(track.copy(alpha = va), Offset(33f, 40f), Size(42f, 14f), CornerRadius(4.2f))
                val fill = (if (face.mood == DroidMood.PAYDAY) 1f else face.used.coerceIn(0f, 1f)) * p.fillScale
                val asleep = if (face.mood == DroidMood.ASLEEP) 0.45f else 1f
                if (fill > 0f) {
                    val w = min(42f * fill, 46f)
                    glowRect(fillColour, Offset(33f, 40f), Size(w, 14f), 4.2f, p.fillGlow * va)
                    drawRoundRect(fillColour.copy(alpha = va * p.fillAlpha * asleep), Offset(33f, 40f), Size(w, 14f), CornerRadius(4.2f))
                }
                if (p.shine >= 0f && p.shineAlpha > 0f) {
                    drawRoundRect(Color.White.copy(alpha = p.shineAlpha * va), Offset(33f + 34f * p.shine, 40f), Size(8f, 14f), CornerRadius(3f))
                }
            }
            if (face.mood != DroidMood.PAYDAY) {
                val x = 33f + 42f * face.elapsed.coerceIn(0f, 1f)
                drawRoundRect(line.copy(alpha = a * p.tickAlpha), Offset((x - 2f).coerceIn(33f, 73f), 36f + p.tickDy), Size(4f, 22f), CornerRadius(2f))
            }
            drawMouth(face.mood, line.copy(alpha = a * p.mouthAlpha), cap)
            if (p.question > 0f) {
                scale(p.question, Offset(90f, 13f)) {
                    drawCircle(PENNY_WARN.copy(alpha = a), 11f, Offset(90f, 2f))
                    drawIntoNative { canvas ->
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color(0xFF111111).toArgb(); textSize = 15f; textAlign = android.graphics.Paint.Align.CENTER
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        canvas.drawText("?", 90f, 7.5f, paint)
                    }
                }
            }
        }
        // Around her: confetti, sweat, sleep, the sync arrow, the tick.
        if (p.confetti >= 0f) drawConfetti(p.confetti, colour, line, still)
        if (p.sweat >= 0f) {
            val s = p.sweat
            val alpha = kf(s, 0.4f, 0f, 0.55f, 1f, 1f, 0f)
            val dy = kf(s, 0f, -3f, 1f, 10f)
            if (alpha > 0f) drawPath(Path().apply { moveTo(84f, 34f + dy); quadraticTo(87f, 39f + dy, 84f, 41f + dy); quadraticTo(81f, 39f + dy, 84f, 34f + dy); close() }, Color(0xFF7CC4FF).copy(alpha = alpha))
        }
        if (p.zzz >= 0f) {
            drawZ(p.zzz, Offset(82f, 18f), 1f, line)
            drawZ((p.zzz + 0.5f) % 1f, Offset(90f, 8f), 0.75f, line)
        }
        if (p.spin >= 0f) rotate(p.spin, Offset(88f, 24f)) { drawArc(line, 30f, 290f, false, Offset(80f, 16f), Size(16f, 16f), style = Stroke(3f, cap = cap)) }
        if (p.check > 0.01f) {
            val c = PENNY_GOOD.copy(alpha = p.checkAlpha)
            val total = 17.7f
            val first = min(p.check * total, 5.66f) / 5.66f
            drawLine(c, Offset(86f, 8f), Offset(86f + 4f * first, 8f + 4f * first), 3.5f, cap)
            val second = ((p.check * total - 5.66f) / 12.04f).coerceIn(0f, 1f)
            if (second > 0f) drawLine(c, Offset(90f, 12f), Offset(90f + 8f * second, 12f - 9f * second), 3.5f, cap)
        }
    }
}

private fun DrawScope.drawMouth(mood: DroidMood, line: Color, cap: StrokeCap) {
    val mouth = Path()
    when (mood) {
        DroidMood.AHEAD -> { mouth.moveTo(45f, 66f); mouth.quadraticTo(54f, 72f, 63f, 66f) }
        DroidMood.TIGHT -> { mouth.moveTo(46f, 68f); mouth.lineTo(62f, 68f) }
        DroidMood.OVER -> { mouth.moveTo(45f, 70f); mouth.quadraticTo(54f, 64f, 63f, 70f) }
        DroidMood.ASLEEP -> { mouth.moveTo(48f, 68f); mouth.quadraticTo(54f, 70f, 60f, 68f) }
        DroidMood.PAYDAY -> drawPath(Path().apply { moveTo(43f, 64f); quadraticTo(54f, 78f, 65f, 64f); close() }, line)
        DroidMood.SYNCING -> drawCircle(line, radius = 3.5f, center = Offset(54f, 68f))
    }
    if (mood in setOf(DroidMood.AHEAD, DroidMood.TIGHT, DroidMood.OVER, DroidMood.ASLEEP)) drawPath(mouth, line, style = Stroke(4.5f, cap = cap))
}

/** Confetti bursting out of her: where each piece starts, and how far it flies. */
private val CONFETTI = listOf(
    floatArrayOf(12f, 32f, -18f, -22f), floatArrayOf(92f, 36f, 16f, -26f), floatArrayOf(14f, 76f, -22f, 6f), floatArrayOf(92f, 80f, 20f, 8f),
    floatArrayOf(22f, 14f, -6f, -30f), floatArrayOf(100f, 10f, 10f, -14f), floatArrayOf(4f, 52f, -24f, -10f),
)

/**
 * In a still picture - a widget's, the watch tile's - the piece flying up and right ([STILL_LEFT_OUT])
 * is left out: it ends past the picture's edge, and only a sliver of it showed.
 */
private const val STILL_LEFT_OUT = 5

private fun DrawScope.drawConfetti(t: Float, colour: Color, line: Color, still: Boolean = false) {
    val colours = listOf(PENNY_WARN, line, line, PENNY_WARN, colour, colour, PENNY_WARN)
    val alpha = kf(t, 0.15f, 0f, 0.25f, 1f, 0.7f, 1f, 1f, 0f)
    if (alpha <= 0f) return
    val fly = kf(t, 0.15f, 0f, 0.7f, 1f)
    val fall = kf(t, 0.7f, 0f, 1f, 14f)
    CONFETTI.forEachIndexed { i, c ->
        if (still && i == STILL_LEFT_OUT) return@forEachIndexed
        val x = c[0] + c[2] * fly
        val y = c[1] + c[3] * fly + fall
        rotate(320f * fly, Offset(x + 2.5f, y + 2.5f)) { drawRect(colours[i].copy(alpha = alpha), Offset(x, y), Size(5f, 5f)) }
    }
}

private fun DrawScope.drawZ(t: Float, at: Offset, size: Float, line: Color) {
    val alpha = kf(t, 0f, 0f, 0.3f, 1f, 1f, 0f)
    if (alpha <= 0f) return
    val s = size * kf(t, 0f, 0.6f, 1f, 1.2f)
    val o = Offset(at.x + 10f * t, at.y + kf(t, 0f, 6f, 1f, -14f))
    val c = line.copy(alpha = alpha)
    drawLine(c, o, Offset(o.x + 6f * s, o.y), 2.5f, StrokeCap.Round)
    drawLine(c, Offset(o.x + 6f * s, o.y), Offset(o.x, o.y + 7f * s), 2.5f, StrokeCap.Round)
    drawLine(c, Offset(o.x, o.y + 7f * s), Offset(o.x + 6f * s, o.y + 7f * s), 2.5f, StrokeCap.Round)
}

/** A soft glow from a coloured shape: fading rings around it, so it lights only what's lit. */
private fun DrawScope.glowRect(colour: Color, at: Offset, size: Size, radius: Float, strength: Float) {
    if (strength <= 0.01f) return
    for (k in 1..4) {
        val g = k * 1.6f
        drawRoundRect(colour.copy(alpha = 0.2f / k * strength), Offset(at.x - g, at.y - g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(radius + g))
    }
}

private fun DrawScope.glowCircle(colour: Color, at: Offset, radius: Float, strength: Float) {
    if (strength <= 0.01f) return
    for (k in 1..4) drawCircle(colour.copy(alpha = 0.22f / k * strength), radius + k * 1.8f, at)
}

private fun DrawScope.drawIntoNative(block: (android.graphics.Canvas) -> Unit) {
    drawContext.canvas.nativeCanvas.let(block)
}

private fun lerp(a: Color, b: Color, t: Float): Color =
    if (t <= 0f) a else if (t >= 1f) b else Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t)

/** The colour turned [degrees] round the colour wheel - payday's visor going through the colours. */
private fun hueShift(colour: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(colour.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Penny at one instant - [ms] into her mood, [sinceMoment] into a moment - for frame-by-frame checks. */
@Composable
fun DroidAt(face: DroidFace, size: Dp, colour: Color, ms: Long, moment: DroidMoment = DroidMoment.NONE, sinceMoment: Long = ms, fillOnOpen: Boolean = false, line: Color = Color.White, bulb: Color? = null) {
    Canvas(Modifier.size(size)) {
        val pose = poseAt(face.mood, moment, ms, sinceMoment, ms, fillOnOpen)
        scale(this.size.minDimension / 108f, pivot = Offset.Zero) { drawPose(face, pose, droidColour(face.mood, colour), line, bulb = bulb) }
    }
}

/**
 * How far above her box Penny's moods and moments draw, as a share of her size: the noticed
 * sparks reach highest (the "?", payday's jump and confetti nearly as far). Wherever she sits at
 * the top of something that clips - a sheet, a scrolling list, Home's pages - leave this much
 * room above her. Meet Penny's drop-in starts higher on purpose: she falls in from above.
 * PennyReachTest holds every pose to it.
 */
const val PENNY_REACH = 0.2f

/**
 * Penny as a picture, for a widget: home-screen widgets can't move, so each redraw shows her
 * mood's most telling moment instead - the shiver when tight, the droop when over, the jump and
 * confetti on payday.
 */
fun pennyStill(face: DroidFace, accent: Color, sizePx: Int): android.graphics.Bitmap {
    val ms = when (face.mood) {
        DroidMood.AHEAD -> 0L
        DroidMood.TIGHT -> 600L
        DroidMood.OVER -> 1_200L
        DroidMood.PAYDAY -> 900L
        DroidMood.SYNCING -> 600L
        DroidMood.ASLEEP -> 1_500L
    }
    val image = androidx.compose.ui.graphics.ImageBitmap(sizePx, sizePx)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        androidx.compose.ui.unit.Density(1f),
        androidx.compose.ui.unit.LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(image),
        Size(sizePx.toFloat(), sizePx.toFloat()),
    ) {
        val pose = poseAt(face.mood, DroidMoment.NONE, ms, ms, null, false)
        scale(sizePx / 108f, pivot = Offset.Zero) { drawPose(face, pose, droidColour(face.mood, accent), Color.White, still = true) }
    }
    return image.asAndroidBitmap()
}
