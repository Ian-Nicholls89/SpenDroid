package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spendroid.domain.Contracts
import com.spendroid.ui.theme.Charcoal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val LONG = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val SHORT = DateTimeFormatter.ofPattern("d MMM yyyy")
private val DAY = DateTimeFormatter.ofPattern("d MMM")
private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy")

/** On a bill's row: grey while it runs, amber in its last six weeks, red once out of contract and dearer. */
@Composable
internal fun ContractTag(status: Contracts.Status, currency: String) {
    val (text, bg, fg) = when (status) {
        is Contracts.Status.Running -> Triple("contract ends ${status.end.format(SHORT)}", Charcoal.PanelHigh, Color(0xFFCFD2D8))
        is Contracts.Status.EndingSoon -> Triple(
            when (status.daysLeft) { 0L -> "contract ends today"; 1L -> "contract ends tomorrow"; else -> "contract ends in ${status.daysLeft} days" },
            Color(0xFF3A2F18), Charcoal.Warn,
        )
        is Contracts.Status.EndedUp -> Triple("out of contract · up ${formatMoney(status.upMinor, currency)}/month", Color(0xFF3A1F1F), Charcoal.Bad)
        is Contracts.Status.Ended -> Triple("out of contract since ${status.end.format(DAY)}", Charcoal.PanelHigh, Charcoal.Muted)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = fg,
        // Wrapped rather than lose the part that matters - the date, or how much it went up.
        maxLines = 3,
        modifier = Modifier.padding(top = 3.dp, end = 4.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * In a bill's sheet: when its contract ends - the one thing asked - with 12, 18 and 24 months from
 * when payments started as one-tap guesses, and once it's dearer out of contract, "I'm happy with
 * this price".
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ContractSection(
    end: LocalDate?,
    status: Contracts.Status?,
    started: LocalDate?,
    guesses: List<Pair<Int, LocalDate>>,
    onSet: (LocalDate?) -> Unit,
    onAccept: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp)).background(Charcoal.Panel).padding(12.dp)) {
        Text("Contract ends", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Charcoal.PanelHigh)
                .clickable { picking = true }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(end?.format(LONG) ?: "Not set", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = if (end == null) Charcoal.Muted else Charcoal.Text)
            Text(if (end == null) "Set" else "Change", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            if (end != null) {
                Spacer(Modifier.width(14.dp))
                Text("Clear", color = Charcoal.Muted, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { onSet(null) })
            }
        }
        if (end == null) {
            val today = LocalDate.now()
            if (guesses.isNotEmpty()) {
                started?.let {
                    Text("Paying since ${it.format(MONTH_YEAR)}. A contract of", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
            // Wrapped, not scrolled sideways: every choice in sight, "Already out of contract" too.
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                guesses.forEach { (months, date) ->
                    Text(
                        "$months months · " + (if (date.isAfter(today)) date.format(DAY) else "ended ${date.format(DAY)}"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFCFD2D8),
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Charcoal.PanelHigh).clickable { onSet(date) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                // Out of contract already, date unknown: ended yesterday - out of contract now, not
                // "ends today" with its reminder - so a rise from here is caught.
                Text(
                    "Already out of contract",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFCFD2D8),
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Charcoal.PanelHigh).clickable { onSet(today.minusDays(1)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                "Already ended? Pick the date it ended if you know it - SpenDroid will see whether the bill went up then.",
                style = MaterialTheme.typography.labelSmall,
                color = Charcoal.Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            when (status) {
                null, is Contracts.Status.Running, is Contracts.Status.EndingSoon -> "Reminders 6 weeks, 2 weeks and the day before it ends."
                is Contracts.Status.EndedUp -> "Ended - and up ${formatMoney(status.upMinor, "GBP")} a month since. Set the new end date if you've signed up again or switched."
                is Contracts.Status.Ended -> "Out of contract since ${status.end.format(DAY)}. You'll hear if the bill goes up."
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (status is Contracts.Status.EndedUp) Charcoal.Bad else Charcoal.Muted,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (status is Contracts.Status.EndedUp) {
            Text(
                "I'm happy with this price",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Charcoal.PanelHigh).clickable(onClick = onAccept)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
    if (picking) ContractDatePicker(end ?: guesses.lastOrNull()?.second ?: LocalDate.now().plusYears(1), onPick = { picking = false; onSet(it) }, onDismiss = { picking = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContractDatePicker(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state, title = { Text("Contract ends", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
}

/** Home: a bill that went up once out of contract, kept in view until it's sorted. */
@Composable
internal fun OutOfContractCard(entry: Contracts.Entry, onSetEnd: (LocalDate) -> Unit, onAccept: () -> Unit, modifier: Modifier = Modifier) {
    val s = entry.status as? Contracts.Status.EndedUp ?: return
    var picking by remember { mutableStateOf(false) }
    val money = { m: Long -> formatMoney(m, entry.currency) }
    Panel(modifier, colour = Color(0xFF2B1F21)) {
        SectionHeading("Out of contract", colour = Charcoal.Bad)
        Text("${entry.payee.tidyPayee()} is up ${money(s.upMinor)} a month", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 6.dp))
        Text(
            "${money(s.beforeMinor)} → ${money(s.afterMinor)} since your contract ended on ${s.end.format(DAY)}. ${money(s.upMinor * 12)} a year more while you stay on it.",
            style = MaterialTheme.typography.bodySmall,
            color = Charcoal.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Set a new end date",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111111),
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary).clickable { picking = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
            Text(
                "I'm happy with this price",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Charcoal.PanelHigh).clickable(onClick = onAccept)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
    if (picking) ContractDatePicker(LocalDate.now().plusYears(1), onPick = { picking = false; onSetEnd(it) }, onDismiss = { picking = false })
}

/** On Upcoming: a contract in its last six weeks. */
@Composable
internal fun ContractEndingRow(entry: Contracts.Entry) {
    val s = entry.status as? Contracts.Status.EndingSoon ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(12.dp)).background(Charcoal.Panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtTile(payeeColour(entry.payee), size = 38.dp) { Monogram(entry.payee.tidyPayee(), 38.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.payee.tidyPayee(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("${formatMoney(entry.amountMinor, entry.currency)} a month until then", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(s.end.format(DAY), fontWeight = FontWeight.Black, style = MaterialTheme.typography.bodyMedium)
            Text(if (s.daysLeft == 0L) "today" else "in ${s.daysLeft} days", style = MaterialTheme.typography.labelSmall, color = Charcoal.Warn, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.height(0.dp))
}
