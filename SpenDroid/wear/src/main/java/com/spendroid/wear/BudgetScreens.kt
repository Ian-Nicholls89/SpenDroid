package com.spendroid.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin

private val Neutral = listOf(Color(0xFF1E2A42), Color(0xFF101828))
private val OverBg = listOf(Color(0xFF9B2226), Color(0xFF5D1214))
private val TightBg = listOf(Color(0xFFB26A00), Color(0xFF7A4600))
private val OnTrackBg = listOf(Color(0xFF2E7D32), Color(0xFF1B5E20))
private val Green = Color(0xFFC4E84A)
private val Red = Color(0xFFFF5C3C)
private val Blue = Color(0xFFBED8FF)
private val Soft = Color(0xFFCDD7E8)
private val PendingText = Color(0xFFFFD696)

/** The screens, in order: the budget, today, the week, each card, what is to come, the phone. */
private sealed interface Page {
    data object Hero : Page
    data object Today : Page
    data object Week : Page
    data class Card(val card: BudgetReading.Card) : Page
    data object Upcoming : Page
    data object Phone : Page
}

@Composable
fun BudgetScreens(reading: BudgetReading?, onOpenPhone: () -> Unit) {
    MaterialTheme {
        if (reading == null || reading.map.getString("availableFull") == null) {
            Screen(Neutral) {
                Text(
                    "Open SpenDroid on your phone and pull to refresh, and the figures will arrive here.",
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                )
            }
            return@MaterialTheme
        }
        val pages = buildList {
            add(Page.Hero)
            add(Page.Today)
            add(Page.Week)
            reading.cards.forEach { add(Page.Card(it)) }
            add(Page.Upcoming)
            add(Page.Phone)
        }
        val state = rememberPagerState(pageCount = { pages.size })
        VerticalPager(state = state, modifier = Modifier.fillMaxSize()) { index ->
            when (val page = pages[index]) {
                Page.Hero -> Hero(reading)
                Page.Today -> Today(reading)
                Page.Week -> Week(reading)
                is Page.Card -> CardScreen(page.card)
                Page.Upcoming -> Upcoming(reading)
                Page.Phone -> Screen(Neutral) {
                    Button(
                        onClick = onOpenPhone,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.White, contentColor = Neutral[0]),
                        modifier = Modifier.fillMaxWidth(0.7f),
                        shape = RoundedCornerShape(50),
                    ) { Text("Open on phone", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun Screen(background: List<Color>, ring: (DrawScope.() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(background)),
        contentAlignment = Alignment.Center,
    ) {
        ring?.let { Canvas(modifier = Modifier.fillMaxSize()) { it() } }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    }
}

private const val ARC_START = 125f
private const val ARC_SWEEP = 290f

private fun DrawScope.arc(from: Float, sweep: Float, color: Color, width: Float) {
    if (sweep <= 0f) return
    val inset = width / 2 + 6.dp.toPx()
    drawArc(
        color = color,
        startAngle = from,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(size.width - 2 * inset, size.height - 2 * inset),
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

private fun paceBackground(pace: String) = when (pace) {
    "OVER" -> OverBg
    "TIGHT" -> TightBg
    else -> OnTrackBg
}

@Composable
private fun Hero(r: BudgetReading) {
    val segments = BudgetArc.drawOrder(BudgetArc.segments(r.budgetLeft, r.cycleLeft))
    Screen(
        paceBackground(r.pace),
        ring = {
            val w = 9.dp.toPx()
            var at = ARC_START
            segments.forEach { s ->
                val sweep = ARC_SWEEP * s.weight
                val colour = when (s.kind) {
                    BudgetArc.Kind.SPENT -> Blue
                    BudgetArc.Kind.BEHIND -> Red
                    BudgetArc.Kind.LEFT -> Green
                }
                arc(at + 1f, sweep - 2f, colour, w)
                at += sweep
            }
        },
    ) {
        Text("Available to spend", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
        Text(r.text("availableFull"), fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        // The phone widget's words and shapes, so the colour is never the only thing saying it.
        val badge = when (r.pace) { "OVER" -> "▲ Over"; "TIGHT" -> "◆ Tight"; else -> "● On track" }
        Text(
            badge,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = paceBackground(r.pace)[0],
            modifier = Modifier.background(Color.White, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(r.text("daysLine"), fontSize = 13.sp)
        Text(r.text("incomeLine"), fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
private fun Line(label: String, value: String, note: String = "") {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Soft, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    if (note.isNotEmpty()) Text(note, fontSize = 10.sp, color = PendingText, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun Today(r: BudgetReading) {
    Screen(Neutral) {
        Text("Today", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Soft)
        Spacer(Modifier.height(6.dp))
        Line("From accounts", r.text("todayAccounts"))
        Line("On credit cards", r.text("todayCards"), r.text("todayCardsPending"))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Soft.copy(alpha = 0.4f)))
        Spacer(Modifier.height(5.dp))
        Line("This cycle", r.text("thisCycle"))
        r.text("inAccount").takeIf { it.isNotEmpty() }?.let { Line("In the account", it) }
        Text("Card spending counts when the bill is paid", fontSize = 10.sp, color = Soft, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Week(r: BudgetReading) {
    val values = r.week
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val end = runCatching { LocalDate.parse(r.text("weekEnds")) }.getOrNull()
    Screen(Neutral) {
        Text("Last 7 days", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Soft)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(70.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            values.forEach { v ->
                Box(
                    Modifier.width(12.dp)
                        .fillMaxHeight((v.toFloat() / max).coerceAtLeast(0.04f))
                        .background(Blue, RoundedCornerShape(3.dp)),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            values.indices.forEach { i ->
                val day = end?.minusDays((values.size - 1 - i).toLong())?.dayOfWeek?.name?.take(1).orEmpty()
                Text(day, fontSize = 10.sp, color = Soft, modifier = Modifier.width(12.dp), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(r.text("updated"), fontSize = 11.sp, color = Soft)
    }
}

@Composable
private fun CardScreen(c: BudgetReading.Card) {
    val tone = if (c.over) Red else Green
    Screen(
        if (c.over) OverBg else OnTrackBg,
        ring = {
            val w = 9.dp.toPx()
            arc(ARC_START, ARC_SWEEP, Color.White.copy(alpha = 0.18f), w)
            c.capShare?.let { arc(ARC_START, ARC_SWEEP * it, tone, w) }
            c.statementGone?.let { gone ->
                val angle = Math.toRadians((ARC_START + ARC_SWEEP * gone).toDouble())
                val radius = size.width / 2 - w / 2 - 6.dp.toPx()
                val centre = Offset(size.width / 2 + radius * cos(angle).toFloat(), size.height / 2 + radius * sin(angle).toFloat())
                drawCircle(Color.Black, radius = w * 0.8f, center = centre)
                drawCircle(Color.White, radius = w * 0.62f, center = centre)
            }
        },
    ) {
        Text(c.text("name"), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Since statement", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
        Text(c.text("since"), fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        c.text("pending").takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 10.sp, color = PendingText) }
        c.text("pace").takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tone) }
        c.text("cap").takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f)) }
        Text(c.text("billed"), fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun Upcoming(r: BudgetReading) {
    Screen(Neutral) {
        Text("To come before payday", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Soft)
        Spacer(Modifier.height(6.dp))
        val items = r.upcoming
        if (items.isEmpty()) Text("Nothing else before payday", fontSize = 12.sp, color = Soft)
        items.take(6).forEach { u ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(u.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(u.date, fontSize = 9.sp, color = Soft)
                }
                Text(u.amount, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (items.size > 6) Text("and ${items.size - 6} more on your phone", fontSize = 9.sp, color = Soft)
    }
}
