package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spendroid.data.MoneyWatch
import com.spendroid.domain.PriceChanges
import com.spendroid.ui.theme.Charcoal
import java.time.format.DateTimeFormatter

private val MONTH = DateTimeFormatter.ofPattern("MMM")
private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM")
private val DAY = DateTimeFormatter.ofPattern("d MMM")

/** "▲ £2.70 in Oct" under a bill on Regular - amber up, green down. */
@Composable
internal fun PriceChip(change: PriceChanges.Change, currency: String) {
    val (bg, fg) = if (change.up) Color(0xFF3A2F18) to Charcoal.Warn else Color(0xFF1F3A2A) to Charcoal.In
    Text(
        "${if (change.up) "▲" else "▼"} ${formatMoney(kotlin.math.abs(change.deltaMinor), currency)} in ${change.on.format(MONTH)}",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = fg,
        modifier = Modifier.padding(top = 3.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** At the head of the bills: how many went up in the last year, and what that adds up to. */
@Composable
internal fun PriceSummary(changes: Collection<PriceChanges.Change>, currency: String, modifier: Modifier = Modifier) {
    val ups = changes.filter { it.up }
    if (ups.isEmpty()) return
    val perYear = ups.sumOf { it.perYearMinor }
    Text(
        "${ups.size} bill${if (ups.size == 1) "" else "s"} went up in the last year · ${formatMoney(perYear, currency)} a year more",
        style = MaterialTheme.typography.labelMedium,
        color = Charcoal.Warn,
        modifier = modifier,
    )
}

/**
 * A bill's story, in its sheet: every payment as a bar, the new amount in amber, what changed
 * and what it adds up to - then what the user makes of it.
 */
@Composable
internal fun PriceHistory(change: PriceChanges.Change, currency: String) {
    val context = LocalContext.current
    val watch = remember { MoneyWatch(context) }
    var decision by remember(change.key) { mutableStateOf(watch.decision(change.key)) }
    val money = { m: Long -> formatMoney(m, currency) }
    val shown = change.history.takeLast(12)
    val top = shown.maxOf { it.amountMinor }.coerceAtLeast(1L)
    val low = (shown.minOf { it.amountMinor } * 0.7).toLong()

    Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp)).background(Charcoal.Panel).padding(12.dp)) {
        SectionHeading("What it's cost")
        Row(Modifier.fillMaxWidth().height(110.dp).padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            shown.forEachIndexed { i, p ->
                val changed = i > 0 && p.amountMinor != shown[i - 1].amountMinor
                val fraction = ((p.amountMinor - low).toFloat() / (top - low).coerceAtLeast(1L)).coerceIn(0.08f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight(fraction).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                        .background(if (p.date == change.on) Charcoal.Warn else if (changed) Charcoal.Warn.copy(alpha = 0.5f) else Color(0xFF3A3D45)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { p ->
                Text(p.date.format(MONTH), style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val pct = String.format(java.util.Locale.UK, "%+.0f%%", change.percent)
        Text(
            "${if (change.up) "Up" else "Down"} from ${money(change.beforeMinor)} to ${money(change.afterMinor)} on ${change.on.format(DAY)} ($pct). " +
                "It had been ${money(change.beforeMinor)} since ${change.since.format(MONTH_YEAR)}. " +
                "That's ${money(kotlin.math.abs(change.perYearMinor))} a year ${if (change.up) "more" else "less"}.",
            style = MaterialTheme.typography.bodySmall,
            color = Charcoal.Text,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (change.up) {
            Text("Is this right?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            listOf(
                Triple(MoneyWatch.Decision.EXPECTED, "That's expected", "The new amount becomes the usual one. Nothing more said."),
                Triple(MoneyWatch.Decision.REMIND, "Remind me to look for a better deal", "A nudge in a week, in case you want to shop around or query it."),
                Triple(MoneyWatch.Decision.ONE_OFF, "It was a one-off", "Not counted as a rise; no more said about it."),
            ).forEach { (d, title, note) ->
                val picked = decision == d
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (picked) Color(0xFF1F3A2A) else Charcoal.PanelHigh)
                        .clickable { watch.decide(change.key, d); decision = d }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text((if (picked) "✓ " else "") + title, style = MaterialTheme.typography.titleSmall)
                    Text(note, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                }
            }
        }
    }
}
