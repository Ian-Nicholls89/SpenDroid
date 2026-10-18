package com.spendroid.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.basicCurvedText
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.Typography

/*
 * The phone's nzb360 look, on a round screen: a glow at the top in the accent or the account's
 * colour, the screen's name curved along the top edge in spaced capitals, bars with their figure
 * inside, chips, and poster tiles.
 */

internal val Charcoal = Color(0xFF23252B)
internal val Track2 = Color(0xFF33363E)
internal val Good = Color(0xFF43B05C)
internal val Warn = Color(0xFFF5A623)
internal val Bad = Color(0xFFE5534B)
internal val Soft = Color(0xFF9AA0AA)
internal val PendingBlue = Color(0xFF29B6F6)

internal fun pace(pace: String): Color = when (pace) {
    "OVER" -> Bad
    "TIGHT" -> Warn
    else -> Good
}

internal fun paceWord(pace: String): String = when (pace) {
    "OVER" -> "OVER PACE"
    "TIGHT" -> "TIGHT"
    else -> "ON PACE"
}

/** A screen in the new look: black, a glow at the top in [colour], its name curved over it. */
@Composable
internal fun NzbScreen(title: String, colour: Color, glow: Float = 0.5f, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The glow: the colour washing down from the top edge into the black.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to colour.copy(alpha = glow), 0.5f to Color.Transparent)))
        CurvedTitle(title, colour)
        content()
    }
}

/** The screen's name along the top edge, in lowercase as the phone's wordmark: curved. */
@Composable
internal fun CurvedTitle(text: String, colour: Color) {
    // Inset below the clock the watch draws along the top edge, which it would otherwise sit under.
    CurvedLayout(Modifier.fillMaxSize().padding(26.dp), anchor = 270f) {
        curvedRow {
            basicCurvedText(
                text.lowercase(),
                style = CurvedTextStyle(color = colour, fontSize = 14.sp, fontWeight = FontWeight.Black),
            )
        }
    }
}

/** Space Grotesk, the phone's figures' face. */
internal val Grotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
    Font(R.font.space_grotesk_bold, FontWeight.Black),
)

/** The watch's type: the figures and titles in Grotesk, the rest as the system's. */
internal fun groteskTypography(): Typography = Typography().let { t ->
    t.copy(
        numeralExtraLarge = t.numeralExtraLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        numeralLarge = t.numeralLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        numeralMedium = t.numeralMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        numeralSmall = t.numeralSmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = Grotesk),
        titleMedium = t.titleMedium.copy(fontFamily = Grotesk),
        titleSmall = t.titleSmall.copy(fontFamily = Grotesk),
    )
}

/**
 * A visor: the droid's bar, as on the phone - softly squared, its figure on the right, and a
 * white tick standing proud of it where an even pace would be.
 */
@Composable
internal fun WatchBar(fraction: Float, label: String, colour: Color, modifier: Modifier = Modifier, tick: Float? = null, height: Dp = 18.dp, extra: Float = 0f) {
    val shape = RoundedCornerShape(height * 0.3f)
    val over = if (height >= 12.dp) 3.dp else 2.dp
    BoxWithConstraints(modifier.fillMaxWidth().height(height + over * 2)) {
        val full = maxWidth
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(height).clip(shape).background(Track2)) {
            if (extra > 0f) {
                Box(Modifier.fillMaxHeight().width(full * (fraction + extra).coerceIn(0f, 1f)).clip(shape).background(colour.copy(alpha = 0.35f)))
            }
            Box(Modifier.fillMaxHeight().width(full * fraction.coerceIn(0f, 1f)).clip(shape).background(colour))
            if (label.isNotEmpty()) {
                Text(
                    label,
                    fontFamily = Grotesk,
                    fontWeight = FontWeight.Bold,
                    fontSize = (height.value * 0.5f).sp,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = maxOf(6.dp, height * 0.25f)),
                )
            }
        }
        tick?.let { t ->
            Box(Modifier.offset(x = (maxWidth * t.coerceIn(0f, 1f) - 1.5.dp).coerceIn(0.dp, maxWidth - 3.dp)).width(3.dp).fillMaxHeight().clip(RoundedCornerShape(1.5.dp)).background(Color.White))
        }
    }
}

/**
 * The visor bent round the edge of the screen: the budget used in [colour] from the lower left,
 * over the top, and a white tick where the days have reached.
 */
@Composable
internal fun VisorArc(used: Float?, elapsed: Float?, colour: Color, modifier: Modifier = Modifier, width: Dp = 14.dp) {
    Canvas(modifier.fillMaxSize()) {
        val w = width.toPx()
        val inset = w / 2 + 3.dp.toPx()
        val topLeft = Offset(inset, inset)
        val box = Size(size.width - 2 * inset, size.height - 2 * inset)
        // Compose measures from three o'clock; twelve is 270, and the visor runs 130 either side.
        val start = 140f
        val sweep = 260f
        drawArc(Track2, start, sweep, false, topLeft, box, style = Stroke(w, cap = StrokeCap.Butt))
        used?.let { drawArc(colour, start, sweep * it.coerceIn(0f, 1f), false, topLeft, box, style = Stroke(w, cap = StrokeCap.Butt)) }
        elapsed?.let {
            val a = Math.toRadians((start + sweep * it.coerceIn(0f, 1f)).toDouble())
            val r = box.width / 2
            val c = Offset(size.width / 2, size.height / 2)
            val over = 3.dp.toPx()
            fun at(d: Float) = Offset(c.x + (d * kotlin.math.cos(a)).toFloat(), c.y + (d * kotlin.math.sin(a)).toFloat())
            drawLine(Color.White, at(r - w / 2 - over), at(r + w / 2 + over), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** A small solid chip: "ON PACE", "BELOW USUAL". */
@Composable
internal fun WatchChip(text: String, colour: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = Color(0xFF111111),
        maxLines = 1,
        modifier = Modifier.background(colour, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** A bill as a poster: a tile in its colour with the date on a chip, the name and figure beneath. */
@Composable
internal fun WatchPoster(name: String, amount: String, date: String, colour: Color, glyph: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(listOf(colour, colour.copy(red = colour.red * 0.4f, green = colour.green * 0.4f, blue = colour.blue * 0.4f))))
                .padding(4.dp),
        ) {
            Text(
                date,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
            )
            Text(glyph, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.height(2.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(amount, style = MaterialTheme.typography.labelSmall, color = Soft, maxLines = 1)
    }
}

/** A rounded square of colour with a letter: a category or payee's tile. */
@Composable
internal fun WatchTile(letter: String, colour: Color, size: Dp = 26.dp) {
    Box(Modifier.width(size).height(size).clip(RoundedCornerShape(7.dp)).background(colour), contentAlignment = Alignment.Center) {
        Text(letter, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = Color.White)
    }
}

internal fun initial(text: String): String = text.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "£"
