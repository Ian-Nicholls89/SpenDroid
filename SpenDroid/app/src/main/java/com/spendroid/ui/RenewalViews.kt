package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendroid.domain.Renewals
import com.spendroid.ui.theme.Charcoal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val DAY_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/** "in 20 weeks", "in 6 days", "tomorrow": how long until [date]. */
private fun until(date: LocalDate, today: LocalDate): String {
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> "ended"
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        days < 14 -> "in $days days"
        days < 70 -> "in ${days / 7} weeks"
        else -> "in ${java.time.temporal.ChronoUnit.MONTHS.between(today, date)} months"
    }
}

/** What's known about one, under its name: when it ends, and what it cost or how it's paid. */
private fun facts(r: Renewals.Renewal): String = buildString {
    append("ends ${r.ends.format(DAY)}")
    when {
        r.paid == Renewals.Paid.MONTHLY -> append(" · paid monthly")
        r.lastMinor != null -> append(" · about ${roughly(r.lastMinor)} last time")
    }
}

/** £462.18 as "£460": a renewal's price is a rough guide, and said as one. */
private fun roughly(minor: Long): String = formatMoney((minor + 500) / 1000 * 1000, "GBP").removeSuffix(".00")

/**
 * The Renewals tab on Regular (0.7.2): the next one, then the rest - or, before any are added,
 * what they're for and the usual ones to add with a tap.
 */
internal fun LazyListScope.renewalsTab(
    items: List<Renewals.Item>,
    today: LocalDate,
    onOpen: (String) -> Unit,
    onAdd: (String?) -> Unit,
) {
    if (items.isEmpty()) {
        item { RenewalsEmpty(onAdd) }
        return
    }
    item {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text("Things that end and renew - reminded before each", style = MaterialTheme.typography.labelLarge, color = Charcoal.Muted)
            Text(if (items.size == 1) "1 renewal" else "${items.size} renewals", style = MaterialTheme.typography.headlineSmall)
        }
    }
    val next = items.first()
    item { DayHeading("Next") }
    item(key = "renewal|" + next.renewal.id) { RenewalRow(next, today, onOpen, showReminder = true) }
    val later = items.drop(1)
    if (later.isNotEmpty()) {
        item { DayHeading("Later") }
        items(later, key = { "renewal|" + it.renewal.id }) { RenewalRow(it, today, onOpen) }
    }
    item {
        Text(
            "Add a renewal",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Charcoal.Muted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(22.dp)).border(1.dp, Charcoal.Line, RoundedCornerShape(22.dp))
                .clickable { onAdd(null) }.padding(vertical = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun RenewalRow(item: Renewals.Item, today: LocalDate, onOpen: (String) -> Unit, showReminder: Boolean = false) {
    val r = item.renewal
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(12.dp)).background(Charcoal.Panel)
            .clickable { onOpen(r.id) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtTile(payeeColour(r.name), size = 38.dp) { Monogram(r.name, 38.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(facts(r), style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, maxLines = 1)
            if (showReminder && item.status is Renewals.Status.Later) {
                Text("reminder ${r.remindOn.format(DAY)}", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, maxLines = 1)
            }
        }
        val (label, colour) = when (val s = item.status) {
            is Renewals.Status.Ended -> "ENDED" to Charcoal.Warn
            is Renewals.Status.Due -> (if (s.daysLeft < 14) "${s.daysLeft} DAYS" else "${s.daysLeft / 7} WEEKS") to Charcoal.Warn
            is Renewals.Status.Quiet -> (if (r.sorted) "SORTED" else until(r.ends, today).uppercase()) to Charcoal.PanelHigh
            is Renewals.Status.Later -> until(r.ends, today).uppercase() to Charcoal.PanelHigh
        }
        if (item.status !is Renewals.Status.Later || showReminder) {
            Chip(label, colour, textColour = if (colour == Charcoal.Warn) Color(0xFF111111) else Color(0xFFCFD2D8))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenewalsEmpty(onAdd: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Charcoal.Panel).padding(16.dp)) {
        Text("No renewals yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Things that end and renew - insurance, breakdown cover, a TV licence. Add one and I'll remind you before it ends, " +
                "in time to compare prices. Nothing's taken from your budget.",
            style = MaterialTheme.typography.bodySmall,
            color = Charcoal.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text("The usual ones", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Renewals.USUAL.forEach { name -> Pill("+ $name") { onAdd(name) } }
        }
        Text(
            "Add something else",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Charcoal.Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, Charcoal.Line, RoundedCornerShape(22.dp))
                .clickable { onAdd(null) }.padding(vertical = 12.dp),
        )
    }
}

/** A choice to tap, lit when it's the one chosen. */
@Composable
private fun Pill(text: String, chosen: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (chosen) Color.White else Color(0xFFCFD2D8),
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (chosen) Charcoal.Line else Charcoal.PanelHigh)
            .then(if (chosen) Modifier.border(1.dp, Color(0xFF6B707A), RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/**
 * On Upcoming, in a renewal's reminder weeks: when it ends, what it cost, and the two answers -
 * put it off a fortnight, or it's sorted.
 */
@Composable
internal fun RenewingCard(item: Renewals.Item, onSnooze: () -> Unit, onSorted: () -> Unit, onOpen: () -> Unit) {
    val r = item.renewal
    val s = item.status as? Renewals.Status.Due ?: return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(14.dp)).background(Charcoal.Panel)
            .clickable(onClick = onOpen).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtTile(payeeColour(r.name), size = 38.dp) { Monogram(r.name, 38.dp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(facts(r), style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, maxLines = 1)
            }
            Chip(if (s.daysLeft < 14) "${s.daysLeft} DAYS" else "${s.daysLeft / 7} WEEKS", Charcoal.Warn)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            Text(
                "Remind me in 2 weeks",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Charcoal.PanelHigh).clickable(onClick = onSnooze)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Text(
                "Sorted",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111111),
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Charcoal.Good).clickable(onClick = onSorted)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * One renewal's sheet: adding a new one - [existing] null, [name] perhaps chosen already - or one
 * that's there: when to be reminded, what renewed it before, a new end date, or removing it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RenewalSheet(
    existing: Renewals.Renewal?,
    name: String?,
    today: LocalDate,
    onSave: (Renewals.Renewal) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    newId: () -> String,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Charcoal.Panel) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            if (existing == null) {
                AddRenewal(name, today, onSave = { onSave(it.copy(id = newId())) }, onCancel = onDismiss)
            } else {
                RenewalDetails(existing, today, onSave, onRemove)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddRenewal(name: String?, today: LocalDate, onSave: (Renewals.Renewal) -> Unit, onCancel: () -> Unit) {
    var what by remember { mutableStateOf(name.orEmpty()) }
    var ends by remember { mutableStateOf<LocalDate?>(null) }
    var remind by remember { mutableStateOf(Renewals.Remind.SIX_WEEKS) }
    var paid by remember { mutableStateOf(Renewals.Paid.YEARLY) }
    var cost by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    Text("Add a renewal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        "Something that ends and renews - not tied to who it's with, or what it costs.",
        style = MaterialTheme.typography.bodySmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
    )
    OutlinedTextField(what, { what = it }, label = { Text("What it is") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Charcoal.PanelHigh).clickable { picking = true }.padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Cover ends", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
            Text(ends?.format(DAY_YEAR) ?: "Pick the date", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (ends == null) Charcoal.Muted else Charcoal.Text)
        }
        Text(if (ends == null) "Set" else "Change", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
    RemindChoice(remind) { remind = it }
    Text("Paid", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Once a year", paid == Renewals.Paid.YEARLY) { paid = Renewals.Paid.YEARLY }
        Pill("Monthly", paid == Renewals.Paid.MONTHLY) { paid = Renewals.Paid.MONTHLY }
    }
    if (paid == Renewals.Paid.YEARLY) {
        OutlinedTextField(
            cost, { cost = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Last time, roughly (optional)") },
            prefix = { Text("£") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
    val ready = what.isNotBlank() && ends != null
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton("Cancel", null, onCancel, Modifier.weight(1f))
        BigButton(
            "Save", null,
            {
                val end = ends ?: return@BigButton
                onSave(
                    Renewals.Renewal(
                        id = "", name = what.trim(), ends = end, remind = remind, paid = paid,
                        lastMinor = if (paid == Renewals.Paid.YEARLY) cost.toBigDecimalOrNull()?.movePointRight(2)?.toLong() else null,
                    ),
                )
            },
            Modifier.weight(1f),
            colour = if (ready) MaterialTheme.colorScheme.primary else Charcoal.PanelHigh,
            textColour = if (ready) Color(0xFF111111) else Charcoal.Muted,
        )
    }
    if (picking) RenewalDatePicker("Cover ends", ends ?: today.plusMonths(6), earliest = null, onPick = { ends = it; picking = false }, onDismiss = { picking = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemindChoice(remind: Renewals.Remind, onPick: (Renewals.Remind) -> Unit) {
    Text("Remind me", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Renewals.Remind.entries.forEach { r -> Pill(r.label, r == remind) { onPick(r) } }
    }
}

@Composable
private fun RenewalDetails(r: Renewals.Renewal, today: LocalDate, onSave: (Renewals.Renewal) -> Unit, onRemove: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ArtTile(payeeColour(r.name), size = 38.dp) { Monogram(r.name, 38.dp) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(r.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (r.ends.isBefore(today)) "ended ${r.ends.format(DAY_YEAR)}" else "ends ${r.ends.format(DAY_YEAR)} · ${until(r.ends, today)}",
                style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted,
            )
        }
    }
    RemindChoice(r.remind) { onSave(r.copy(remind = it)) }
    if (!r.ends.isBefore(today)) {
        Text("Reminder on ${r.remindOn.format(DAY)}", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 8.dp))
    }
    if (r.renewedBy.isNotEmpty()) {
        SectionHeading("Renewed before", Modifier.padding(top = 18.dp, bottom = 6.dp))
        r.renewedBy.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.payee.tidyPayee(), style = MaterialTheme.typography.bodyMedium)
                    Text(p.date.format(DAY_YEAR), style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                }
                Text(formatMoney(p.amountMinor, "GBP"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }
        Renewals.change(r)?.takeIf { it != 0L }?.let { up ->
            Text(
                if (up > 0) "Up ${formatMoney(up, "GBP")} on the year before - worth comparing." else "Down ${formatMoney(-up, "GBP")} on the year before.",
                style = MaterialTheme.typography.labelSmall, color = if (up > 0) Charcoal.Warn else Charcoal.Muted, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (r.ends.isBefore(today)) "Set the new end date" else "Change the end date",
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { picking = true }.padding(vertical = 10.dp),
        )
        Text(
            "Remove", color = Charcoal.Bad, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRemove).padding(10.dp),
        )
    }
    if (picking) {
        RenewalDatePicker(
            "Cover ends", if (r.ends.isBefore(today)) r.ends.plusYears(1) else r.ends, earliest = null,
            onPick = { onSave(r.copy(ends = it, sorted = false, snoozedUntil = null)); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/** The calendar, opened at [initial]; nothing before [earliest] can be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenewalDatePicker(title: String, initial: LocalDate, earliest: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit, note: String? = null) {
    val utc = java.time.ZoneOffset.UTC
    val floor = earliest?.atStartOfDay()?.toInstant(utc)?.toEpochMilli()
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(utc).toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = floor == null || utcTimeMillis >= floor
        },
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(java.time.Instant.ofEpochMilli(it).atZone(utc).toLocalDate()) } ?: onDismiss()
            }) { Text("Set") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        androidx.compose.material3.DatePicker(
            state = state,
            title = { Text(title, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
    }
}
