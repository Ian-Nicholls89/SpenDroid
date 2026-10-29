package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spendroid.domain.Subscriptions
import com.spendroid.ui.theme.Charcoal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal val SubscriptionPurple = Color(0xFFB79CF5)
private val SubscriptionBg = Color(0xFF2A2440)
private val RenewBlue = Color(0xFF5FB8F5)
private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy")
private val DAY = DateTimeFormatter.ofPattern("d MMM")

/** The purple "subscription" tag on a bill's row. */
@Composable
internal fun SubscriptionTag() {
    Text(
        "subscription",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = SubscriptionPurple,
        modifier = Modifier.padding(top = 3.dp, end = 4.dp).clip(RoundedCornerShape(6.dp)).background(SubscriptionBg).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** All · Subscriptions · Household, above the bills. */
@Composable
internal fun BillFilter(selected: Int, subscriptions: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, leftOut: Int = 0) {
    // Wrapped, not scrolled: four, at a large text size, still all in sight.
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // "Left out" (0.7.3): what was the Ignored tab - bills switched off.
        (listOf("All", "Subscriptions · $subscriptions", "Household") + listOfNotNull("Left out · $leftOut".takeIf { leftOut > 0 || selected == 3 })).forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (on) Color(0xFF111111) else Color(0xFFCFD2D8),
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) MaterialTheme.colorScheme.primary else Charcoal.PanelHigh)
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** "£612 a year on 9 subscriptions · £51 a month". */
@Composable
internal fun SubscriptionSummary(items: List<Subscriptions.Item>, currency: String, modifier: Modifier = Modifier) {
    val yearly = items.sumOf { it.yearlyMinor }
    Text(
        "${formatMoney(yearly, currency).replace(Regex("[.,]00\\b"), "")} a year on ${items.size} subscription${if (items.size == 1) "" else "s"} · " +
            "${formatMoney(yearly / 12, currency)} a month",
        style = MaterialTheme.typography.labelLarge,
        color = Charcoal.Text,
        modifier = modifier,
    )
}

/** A yearly payment coming up, on Upcoming: "Office Suite · £80 · 14 Nov · in 35 days". */
@Composable
internal fun YearlyRow(item: Subscriptions.Item, today: LocalDate) {
    val next = item.next ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(12.dp)).background(Charcoal.Panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtTile(payeeColour(item.rule.payee), size = 38.dp) { Monogram(item.rule.payee.tidyPayee(), 38.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.rule.payee.tidyPayee(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(listOfNotNull("renews", item.paidFrom).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatMoney(kotlin.math.abs(item.rule.amountMinor), item.rule.currency), fontWeight = FontWeight.Black, style = MaterialTheme.typography.bodyMedium)
            val days = ChronoUnit.DAYS.between(today, next)
            Text("${next.format(DAY)} · ${if (days == 0L) "today" else "in $days days"}", style = MaterialTheme.typography.labelSmall, color = RenewBlue, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * In a bill's sheet: what it comes to a year and in all, whether it's a subscription or a
 * household bill, a reminder before it renews, and "I've cancelled it".
 */
@Composable
internal fun SubscriptionFacts(
    item: Subscriptions.Item,
    remind: Boolean,
    cancelled: Boolean,
    onKind: (Subscriptions.Kind) -> Unit,
    onRemind: (Boolean) -> Unit,
    onCancelled: (Boolean) -> Unit,
) {
    val currency = item.rule.currency
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp)).background(Charcoal.Panel).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Fact("A year", formatMoney(item.yearlyMinor, currency).replace(Regex("[.,]00\\b"), ""))
            if (item.paidInAllMinor > 0) Fact("Paid in all", formatMoney(item.paidInAllMinor, currency).replace(Regex("[.,]00\\b"), ""))
            item.since?.let { Fact("Since", it.format(MONTH_YEAR)) }
        }
        item.paidFrom?.let { Text("Paid ${if (it.startsWith("via")) it else "from $it"}", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 6.dp)) }
        Text("What is it?" + if (item.guessed) " - a guess; change it if it's wrong" else "", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Subscriptions.Kind.SUBSCRIPTION to "Subscription", Subscriptions.Kind.HOUSEHOLD to "Household bill").forEach { (kind, label) ->
                val on = item.kind == kind
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = if (on) SubscriptionPurple else Color(0xFFCFD2D8),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) SubscriptionBg else Charcoal.PanelHigh)
                        .clickable { onKind(kind) }.padding(vertical = 9.dp),
                )
            }
        }
        if (item.kind == Subscriptions.Kind.SUBSCRIPTION) {
            Toggle("Remind me 3 days before it renews", "Time to cancel, if you want to.", remind, onRemind)
            Toggle("I've cancelled it", "Switches it off here, and tells you if it charges again.", cancelled, onCancelled)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun Toggle(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Charcoal.PanelHigh)
            .clickable { onChange(!checked) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(note, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
