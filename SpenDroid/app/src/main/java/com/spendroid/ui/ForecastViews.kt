package com.spendroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.AccountEntity
import com.spendroid.domain.Forecast
import com.spendroid.domain.toRecurringRule
import com.spendroid.ui.theme.Charcoal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val SHORT = DateTimeFormatter.ofPattern("d MMM")
private val LONG = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * A forecast as a line: the balance day by day, a dot on each day something known lands - amber
 * for money out, green for money in - and a dashed £0 line when it gets near. With [onSelect],
 * a touch or a drag picks a day.
 */
@Composable
internal fun ForecastChart(
    result: Forecast.Result,
    colour: Color,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    selected: LocalDate? = null,
    onSelect: ((LocalDate) -> Unit)? = null,
) {
    val days = result.days
    if (days.size < 2) return
    val low = days.minOf { it.balanceMinor }
    val max = days.maxOf { it.balanceMinor }
    // The £0 line shows once the line comes within a quarter of its own range of it.
    val showZero = low < (max - low) / 4
    val lo = if (showZero) minOf(low, 0L) else low
    val span = (max - lo).coerceAtLeast(1L).toFloat()
    val pick = { x: Float, width: Float ->
        val i = ((x / width) * (days.size - 1)).toInt().coerceIn(0, days.size - 1)
        onSelect?.invoke(days[i].date)
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (onSelect != null) {
                    Modifier
                        .pointerInput(days) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
                        .pointerInput(days) { detectDragGestures { change, _ -> pick(change.position.x, size.width.toFloat()) } }
                } else Modifier,
            ),
    ) {
        val pad = 8.dp.toPx()
        val w = size.width
        val h = size.height - pad * 2
        fun x(i: Int) = w * i / (days.size - 1)
        fun y(v: Long) = pad + h * (1f - (v - lo) / span)
        if (showZero) {
            drawLine(Charcoal.Bad.copy(alpha = 0.7f), Offset(0f, y(0)), Offset(w, y(0)), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        val line = Path().apply {
            days.forEachIndexed { i, d -> if (i == 0) moveTo(x(i), y(d.balanceMinor)) else lineTo(x(i), y(d.balanceMinor)) }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(w, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(colour.copy(alpha = 0.32f), Color.Transparent)))
        drawPath(line, colour, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Below zero, the line goes red.
        days.forEachIndexed { i, d ->
            if (i > 0 && d.balanceMinor < 0) {
                drawLine(Charcoal.Bad, Offset(x(i - 1), y(days[i - 1].balanceMinor)), Offset(x(i), y(d.balanceMinor)), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        days.forEachIndexed { i, d ->
            if (d.events.isEmpty()) return@forEachIndexed
            val net = d.events.sumOf { it.amountMinor }
            drawCircle(if (net >= 0) Charcoal.In else Charcoal.Warn, radius = 4.5.dp.toPx(), center = Offset(x(i), y(d.balanceMinor)))
        }
        drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(0f, y(days[0].balanceMinor)))
        selected?.let { s ->
            val i = days.indexOfFirst { it.date == s }
            if (i >= 0) {
                drawLine(Color.White.copy(alpha = 0.6f), Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 1.dp.toPx())
                drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(x(i), y(days[i].balanceMinor)))
            }
        }
    }
}

/** Home, under the budget: the budget account's balance to payday, and its lowest point. */
@Composable
internal fun AheadPanel(result: Forecast.Result, payday: LocalDate?, currency: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Panel(modifier, onClick = onOpen) {
        SectionHeading("Ahead", trailing = if (payday != null) "to payday" else "next 30 days")
        Spacer(Modifier.height(10.dp))
        ForecastChart(result, accent)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Today", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.weight(1f))
            payday?.let { Text("Pay ${it.format(SHORT)}", style = MaterialTheme.typography.labelSmall, color = Charcoal.In) }
        }
        val low = result.lowestBefore(payday)
        val money = formatMoney(low.balanceMinor, currency)
        val (bg, fg) = if (low.balanceMinor < 0) Color(0xFF3A1F1F) to Charcoal.Bad else Color(0xFF3A2F18) to Charcoal.Warn
        Text(
            "Lowest: $money on ${low.date.format(SHORT)}" + if (payday != null && low.date == payday.minusDays(1)) ", the day before payday" else "",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** An account that would go below zero: which, when, by how much, why, and what would cover it. */
@Composable
internal fun HeadsUpCard(w: Forecast.Warning, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val money = { m: Long -> formatMoney(m, w.account.currency) }
    Panel(modifier, colour = Color(0xFF2B1F21), onClick = onOpen) {
        SectionHeading("Heads up", colour = Charcoal.Bad)
        Text(
            "${w.account.label.trim()} could go ${money(w.shortMinor)} overdrawn on ${w.on.format(SHORT)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            buildString {
                w.cause?.let { append("${it.label.tidyPayee()} ${money(-it.amountMinor)} on ${it.date.format(SHORT)}") }
                w.nextIn?.let { append(", and ${it.label.tidyPayee()} doesn't come in until ${it.date.format(SHORT)}") }
                append(". Moving ${money(w.coverMinor)} in before ${w.on.format(SHORT)} covers it.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = Charcoal.Muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private enum class Horizon(val label: String) { PAYDAY("To payday"), WEEKS6("6 weeks"), MONTHS3("3 months") }

/**
 * An account's forecast, full screen: to payday, six weeks or three months; drag along the line
 * for any day; what moves it; and what's coming, day by day.
 */
@Composable
fun ForecastScreen(state: RootUiState, accountId: String?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val accent = MaterialTheme.colorScheme.primary
    val accounts = Forecast.forecastable(state.accounts)
    var chosen by remember { mutableStateOf(accounts.firstOrNull { it.id == accountId }?.id ?: state.budget?.potAccountId ?: accounts.firstOrNull()?.id) }
    var horizon by remember { mutableStateOf(Horizon.WEEKS6) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val account = accounts.firstOrNull { it.id == chosen }
    val today = LocalDate.now()
    val payday = state.budget?.nextIncomeDate
    val until = when (horizon) {
        Horizon.PAYDAY -> (payday ?: today.plusDays(30)).plusDays(1)
        Horizon.WEEKS6 -> today.plusWeeks(6)
        Horizon.MONTHS3 -> today.plusMonths(3)
    }
    val rules = remember(state.rules, state.manualRules) { state.rules + state.manualRules.mapNotNull { it.toRecurringRule() } }
    val result = remember(account, until, rules, state.ignoredRules, state.budget, state.transactions) {
        account?.let { Forecast.forAccount(it, state.accounts, rules.filter { r -> r.key !in state.ignoredRules }, state.ignoredRules, state.budget, state.transactions, today, until, com.spendroid.domain.WorkingDayCalendar(state.bankHolidays), state.ruleOverrides) }
    }
    val colour = account?.let { colourOf(it, state.accounts) } ?: accent

    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Charcoal.Text) {
        Box(Modifier.fillMaxSize().background(Charcoal.Background)) {
            HeaderGlow(colour, height = 260.dp, strength = 0.36f)
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Row(Modifier.padding(start = 6.dp, top = 6.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                    Wordmark("Forecast", colour)
                }
                if (accounts.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { a ->
                            Choice(a.label.trim(), a.id == chosen) { chosen = a.id; selected = null }
                        }
                    }
                }
                if (account == null || result == null) {
                    Text("No balance to forecast from yet - sync first.", color = Charcoal.Muted, modifier = Modifier.padding(20.dp))
                    return@Column
                }
                val money = { m: Long -> formatMoney(m, account.currency) }
                Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Panel {
                        Text(account.label.trim().uppercase(), style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(money(result.startMinor), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                            Text(" now", style = MaterialTheme.typography.labelLarge, color = Charcoal.Muted, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Horizon.entries.forEach { h -> Choice(h.label, h == horizon) { horizon = h; selected = null } }
                        }
                        Spacer(Modifier.height(12.dp))
                        ForecastChart(result, colour, height = 190.dp, selected = selected, onSelect = { selected = it })
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text("Today", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.weight(1f))
                            Text(until.format(SHORT), style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                        }
                        val day = result.days.firstOrNull { it.date == selected }
                        Text(
                            if (day != null) {
                                "${day.date.format(LONG)} · ${money(day.balanceMinor)}" +
                                    day.events.joinToString("") { "\n${it.label.tidyPayee()} ${if (it.amountMinor > 0) "+" else ""}${money(it.amountMinor)}" }
                            } else {
                                val low = result.lowest
                                "Lowest ${money(low.balanceMinor)} on ${low.date.format(LONG)}. Touch the line for any day."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (day == null && result.lowest.balanceMinor < 0) Charcoal.Bad else Charcoal.Text,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Charcoal.PanelHigh).padding(10.dp),
                        )
                    }
                    Panel {
                        SectionHeading("What moves it")
                        val ins = result.events.filter { it.amountMinor > 0 }.sumOf { it.amountMinor }
                        val outs = -result.events.filter { it.amountMinor < 0 }.sumOf { it.amountMinor }
                        MoveLine("In - pay and transfers", "+${money(ins)}", Charcoal.In)
                        MoveLine("Bills and card bills", "-${money(outs)}", Charcoal.Text)
                        MoveLine("Usual day-to-day spending", "-${money(result.dailySpendMinor)} a day", Charcoal.Text)
                        Text(
                            "Day-to-day spending is this account's last 90 days, regular payments aside.",
                            style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (result.events.isNotEmpty()) {
                        Panel {
                            SectionHeading("Coming")
                            result.events.take(30).forEach { e ->
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(e.date.format(SHORT), style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted, modifier = Modifier.width(56.dp))
                                    Text(e.label.tidyPayee(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                                    Text(
                                        (if (e.amountMinor > 0) "+" else "") + money(e.amountMinor),
                                        fontWeight = FontWeight.Black,
                                        color = if (e.amountMinor > 0) Charcoal.In else Charcoal.Text,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = if (on) Color(0xFF111111) else Color(0xFFCFD2D8),
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) MaterialTheme.colorScheme.primary else Charcoal.PanelHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun MoveLine(label: String, value: String, colour: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Black, color = colour)
    }
}
