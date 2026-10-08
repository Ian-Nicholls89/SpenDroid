package com.spendroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import com.spendroid.ui.theme.Charcoal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.ManualRecurringRuleEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.spendroid.data.db.RuleOverrideEntity
import com.spendroid.domain.PaymentShift
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.WorkingDayCalendar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.domain.Cadence
import com.spendroid.domain.Direction
import com.spendroid.domain.RecurringRule
import com.spendroid.ui.formatMoney
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun RecurringRulesScreen(
    rules: List<RecurringRule>,
    manualRules: List<ManualRecurringRuleEntity>,
    ignored: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onAddManual: () -> Unit,
    primaryIncomeKey: String? = null,
    onSetPrimaryIncome: (String?) -> Unit = {},
    budget: BudgetSnapshot? = null,
    overrides: Map<String, RuleOverrideEntity> = emptyMap(),
    onSetOverride: (String, Int?, PaymentShift?, Int?) -> Unit = { _, _, _, _ -> },
    holidays: Set<LocalDate> = emptySet(),
    accounts: List<com.spendroid.data.db.AccountEntity> = emptyList(),
    onSetManualPaidFrom: (ManualRecurringRuleEntity, String?) -> Unit = { _, _ -> },
    onDeleteManual: (ManualRecurringRuleEntity) -> Unit = {},
) {
    var editing by remember { mutableStateOf<RecurringRule?>(null) }
    val income = rules.filter { it.direction == Direction.IN }
    val fixed = rules.filter { it.direction == Direction.OUT }
    val manualIncome = manualRules.filter { it.direction == "IN" }
    val manualFixed = manualRules.filter { it.direction == "OUT" }

    if (rules.isEmpty() && manualRules.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "No recurring payments detected yet. Link a bank and sync transactions, or add manual rules.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onAddManual) {
                    Text("Add manual rule")
                }
            }
        }
        return
    }

    editing?.let { rule ->
        RuleOverrideSheet(
            rule = rule,
            override = overrides[rule.key],
            holidays = holidays,
            onDismiss = { editing = null },
            onSave = { anchorDay, shift, decemberDay ->
                onSetOverride(rule.key, anchorDay, shift, decemberDay)
                editing = null
            },
        )
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val ignoredRules = rules.filter { it.key in ignored }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // What the month comes to, worked out from everything listed below it.
            budget?.let { b -> item { EachMonthPanel(b, Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) } }
            item {
                DotTabs(
                    listOf("Upcoming", "Bills", "Income", "Ignored"),
                    tab,
                    { tab = it },
                    Modifier.padding(horizontal = 18.dp),
                )
            }
            when (tab) {
                0 -> {
                    val upcoming = budget?.upcomingFixed.orEmpty()
                    val payday = budget?.nextIncomeDate
                    item {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                            Text(
                                payday?.let { "Before payday · ${it.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))}" } ?: "Before payday",
                                style = MaterialTheme.typography.labelLarge,
                                color = Charcoal.Muted,
                            )
                            Text(
                                "${formatMoney(upcoming.sumOf { it.amountMinor }, budget?.baseCurrency ?: "GBP")} to go out",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                    if (upcoming.isEmpty()) {
                        item { Text("Nothing more due before payday.", color = Charcoal.Muted, modifier = Modifier.padding(horizontal = 18.dp)) }
                    }
                    val today = budget?.asOf ?: LocalDate.now()
                    val (thisWeek, later) = upcoming.partition { !it.dueDate.isAfter(today.plusDays(6)) }
                    listOf("This week" to thisWeek, "Later" to later).forEach { (heading, payments) ->
                        if (payments.isEmpty()) return@forEach
                        item { DayHeading(heading) }
                        items(payments, key = { "${it.rule.key}|${it.dueDate}" }) { p -> UpcomingRow(p, today) }
                    }
                }
                1 -> {
                    item { SectionHeading("Bills", Modifier.padding(horizontal = 18.dp, vertical = 6.dp), trailing = "switch one off to leave it out") }
                    items(fixed.filter { it.key !in ignored }, key = { it.key }) { rule ->
                        RuleRow(
                            rule = rule,
                            ignored = false,
                            overridden = overrides.containsKey(rule.key),
                            onEdit = { editing = rule },
                        ) { onToggle(rule.key, it) }
                    }
                    items(manualFixed, key = { it.id }) { rule -> ManualRuleRow(rule, accounts, onSetManualPaidFrom, onDeleteManual) }
                }
                2 -> {
                    item {
                        Text(
                            "Star the income that sets your pay cycle. Everything else still adds to the budget - " +
                                "it just does not move the dates.",
                            style = MaterialTheme.typography.labelMedium,
                            color = Charcoal.Muted,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        )
                    }
                    items(income.filter { it.key !in ignored }, key = { it.key }) { rule ->
                        RuleRow(
                            rule = rule,
                            ignored = false,
                            isPrimaryIncome = rule.key == primaryIncomeKey,
                            canBePrimary = true,
                            overridden = overrides.containsKey(rule.key),
                            onEdit = { editing = rule },
                            onSetPrimary = { onSetPrimaryIncome(if (rule.key == primaryIncomeKey) null else rule.key) },
                        ) { onToggle(rule.key, it) }
                    }
                    items(manualIncome, key = { it.id }) { rule -> ManualRuleRow(rule, accounts, onSetManualPaidFrom, onDeleteManual) }
                }
                else -> {
                    if (ignoredRules.isEmpty()) {
                        item { Text("Nothing left out. Switch a bill or income off to leave it out of the budget.", color = Charcoal.Muted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
                    }
                    items(ignoredRules, key = { it.key }) { rule ->
                        RuleRow(
                            rule = rule,
                            ignored = true,
                            overridden = overrides.containsKey(rule.key),
                            onEdit = { editing = rule },
                        ) { onToggle(rule.key, it) }
                    }
                }
            }
        }
        androidx.compose.material3.FloatingActionButton(
            onClick = onAddManual,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color(0xFF111111),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "Add a regular payment by hand") }
    }
}

/** A payment still to come, as Sonarr's Upcoming lists an episode: its tile, and its date in the accent. */
@Composable
private fun UpcomingRow(p: com.spendroid.domain.UpcomingPayment, today: LocalDate) {
    val isCard = p.rule.key.startsWith(com.spendroid.domain.CARD_BILL_KEY_PREFIX)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Charcoal.Panel)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtTile(payeeColour(p.rule.payee), size = 44.dp, height = 56.dp) {
            if (isCard) Icon(Icons.Filled.CreditCard, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            else Monogram(p.rule.payee.tidyPayee(), 44.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.rule.payee.tidyPayee(), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                if (isCard) "Card statement · estimated" else describeRule(p.rule),
                style = MaterialTheme.typography.labelSmall,
                color = Charcoal.Muted,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(recurringAmount(p.amountMinor, p.rule.perOccurrence, p.rule.currency, p.rule.isVariable), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
            Text(
                (if (isCard) "~" else "") + shortDay(p.dueDate, today).let { if (it.first().isDigit()) p.dueDate.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) else it },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * A bill added by hand. Tapping it opens where it is paid from - a card's is counted in that
 * card's bill, not again - and a way to delete it, neither of which had a place before.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ManualRuleRow(
    rule: ManualRecurringRuleEntity,
    accounts: List<com.spendroid.data.db.AccountEntity> = emptyList(),
    onSetPaidFrom: (ManualRecurringRuleEntity, String?) -> Unit = { _, _ -> },
    onDelete: (ManualRecurringRuleEntity) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val payer = accounts.firstOrNull { it.id == rule.accountId }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Charcoal.Panel)
            .clickable { open = !open }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtTile(payeeColour(rule.payee), size = 38.dp) { Monogram(rule.payee, 38.dp) }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.payee.tidyPayee(), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "${describeManualRule(rule)} · added by you · " + (payer?.let { "from ${it.label}" } ?: "account not set"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (payer == null && rule.direction == "OUT") Charcoal.Warn else Charcoal.Muted,
                    maxLines = 2,
                )
            }
            Text(formatMoney(rule.amountMinor, rule.currency), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            if (rule.direction == "OUT" && accounts.isNotEmpty()) {
                Text("Paid from", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    accounts.forEach { a ->
                        val chosen = a.id == rule.accountId
                        ColourPill(
                            a.label,
                            if (chosen) colourOf(a, accounts) else Charcoal.PanelHigh,
                            { onSetPaidFrom(rule, if (chosen) null else a.id) },
                        )
                    }
                }
                Text(
                    "One paid on a card is counted in that card's bill, not taken off your budget as well.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Charcoal.Muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            BigButton("Delete this bill", null, { onDelete(rule) }, Modifier.fillMaxWidth(), colour = Charcoal.Bad.copy(alpha = 0.25f), textColour = Charcoal.Bad)
        }
    }
}

fun describeManualRule(rule: ManualRecurringRuleEntity): String = when (rule.cadence) {
    "WEEKLY" -> "Weekly · ${dayName(rule.anchorDay)}"
    "FORTNIGHTLY" -> "Every 2 weeks"
    "MONTHLY" -> "Monthly · day ${rule.anchorDay}"
    "MONTHLY_LAST_DAY" -> "Monthly · last day"
    "MONTHLY_LAST_BUSINESS_DAY" -> "Monthly · last working day"
    "QUARTERLY" -> "Quarterly"
    "ANNUAL" -> "Yearly"
    else -> rule.cadence
}

@Composable
private fun RuleRow(
    rule: RecurringRule,
    ignored: Boolean,
    isPrimaryIncome: Boolean = false,
    canBePrimary: Boolean = false,
    overridden: Boolean = false,
    onEdit: () -> Unit = {},
    onSetPrimary: () -> Unit = {},
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Charcoal.Panel)
            .clickable(onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!canBePrimary) {
            ArtTile(payeeColour(rule.payee), size = 38.dp) { Monogram(rule.payee, 38.dp) }
            Spacer(Modifier.width(10.dp))
        }
        if (canBePrimary) {
            IconButton(onClick = onSetPrimary, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (isPrimaryIncome) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (isPrimaryIncome) {
                        "Sets your pay cycle. Tap to unset."
                    } else {
                        "Use this income to set your pay cycle"
                    },
                    tint = if (isPrimaryIncome) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                rule.payee.tidyPayee().ifBlank { "Unknown" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                color = if (ignored) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                buildList {
                    add(describeRule(rule))
                    if (isPrimaryIncome) add("sets the cycle")
                    if (overridden) add("corrected")
                    if (ignored) add("excluded")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (isPrimaryIncome) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }

        // Confidence as a bar rather than a percentage: the exact figure was never the point,
        // only whether the app has seen enough of something to be trusted about it.
        ConfidenceBar(rule)
        Spacer(Modifier.width(10.dp))

        Text(
            recurringAmount(rule.amountMinor, rule.perOccurrence, rule.currency, rule.isVariable),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = if (rule.direction == Direction.IN) InColor else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(4.dp))
        Switch(checked = !ignored, onCheckedChange = { onToggle(!it) })
    }
}

@Composable
private fun ConfidenceBar(rule: RecurringRule) {
    val fraction = rule.score.coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .semantics {
                    contentDescription =
                        "Seen ${rule.occurrences} times, confidence ${(fraction * 100).toInt()} percent"
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(
                        if (fraction < 0.6f) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        CircleShape,
                    ),
            )
        }
        Text(
            "${rule.occurrences}×",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CycleTimeline(budget: BudgetSnapshot) {
    val today = budget.asOf
    val nextIncome = budget.nextIncomeDate ?: return
    val daysLeft = ChronoUnit.DAYS.between(today, nextIncome).coerceAtLeast(0)
    val dateFormat = DateTimeFormatter.ofPattern("d MMM")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Still to come this cycle", style = MaterialTheme.typography.titleMedium)
            Text(
                "Until ${nextIncome.format(dateFormat)} · $daysLeft day${if (daysLeft == 1L) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (budget.upcomingFixed.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Nothing known is due before then.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                budget.upcomingFixed.forEach { payment ->
                    val forecast = payment.rule.key.startsWith(CARD_BILL_KEY_PREFIX)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (forecast) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                payment.rule.payee.tidyPayee(),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            Text(
                                if (forecast) {
                                    "estimated · ~${payment.dueDate.format(dateFormat)}"
                                } else {
                                    payment.dueDate.format(dateFormat)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "−" + recurringAmount(payment.amountMinor, payment.rule.perOccurrence, payment.rule.currency, payment.rule.isVariable),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    "After everything known lands",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatMoney(budget.availableToSpend, budget.baseCurrency),
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

fun describeRule(rule: RecurringRule): String = when (rule.cadence) {
    Cadence.WEEKLY -> "Weekly · ${dayName(rule.anchorDay)}"
    Cadence.FORTNIGHTLY -> "Every 2 weeks"
    Cadence.MONTHLY -> "Monthly · day ${rule.anchorDay}"
    Cadence.MONTHLY_LAST_DAY -> "Monthly · last day"
    Cadence.MONTHLY_LAST_BUSINESS_DAY -> "Monthly · last working day"
    Cadence.QUARTERLY -> "Quarterly"
    Cadence.ANNUAL -> "Yearly"
}

private fun dayName(value: Int): String = try {
    DayOfWeek.of(value).getDisplayName(TextStyle.SHORT, Locale.getDefault())
} catch (e: Exception) {
    ""
}


/**
 * Corrects a detected rule.
 *
 * Detection infers the day from what it has seen, and a salary paid on the 25th looks like
 * the 24th if the 25th keeps landing on a weekend - the app sees the adjusted dates, not the
 * nominal one. Stating the real day and how it moves fixes it for good.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleOverrideSheet(
    rule: RecurringRule,
    override: RuleOverrideEntity?,
    holidays: Set<LocalDate>,
    onDismiss: () -> Unit,
    onSave: (Int?, PaymentShift?, Int?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var day by remember(rule.key) {
        mutableStateOf(override?.anchorDay?.toString() ?: rule.anchorDay.toString())
    }
    var shift by remember(rule.key) {
        mutableStateOf(PaymentShift.from(override?.shift) ?: PaymentShift.defaultFor(rule.direction))
    }
    var decemberDay by remember(rule.key) {
        mutableStateOf(override?.decemberAnchorDay?.toString() ?: "")
    }

    val calendar = WorkingDayCalendar(holidays)
    val preview = runCatching {
        RecurringAnalyzer.nextOccurrence(
            rule = rule,
            after = LocalDate.now(),
            calendar = calendar,
            shift = shift,
            anchorDayOverride = day.toIntOrNull(),
            decemberAnchorDay = decemberDay.toIntOrNull(),
        )
    }.getOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(rule.payee.tidyPayee(), style = MaterialTheme.typography.titleMedium)
            Text(
                "Detected as ${describeRule(rule)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = day,
                onValueChange = { day = it.filter(Char::isDigit).take(2) },
                label = { Text("Day of the month") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(
                "The date it is really due, before any adjustment for weekends or holidays.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("When that is not a working day", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            PaymentShift.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { shift = option }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = shift == option, onClick = { shift = option })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            option.explanation,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (rule.direction == Direction.IN) {
                Spacer(Modifier.height(16.dp))
                Text("December", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = decemberDay,
                    onValueChange = { decemberDay = it.filter(Char::isDigit).take(2) },
                    label = { Text("Day paid in December") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    "Many employers pay early before Christmas, and plenty do not. Leave this " +
                        "empty and December is treated like any other month.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            preview?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Next expected: ${it.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (holidays.isEmpty()) {
                    Text(
                        "Bank holidays have not been fetched yet, so only weekends are allowed for.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSave(null, null, null) }, modifier = Modifier.weight(1f)) {
                    Text("Use detected")
                }
                Button(
                    onClick = { onSave(day.toIntOrNull(), shift, decemberDay.toIntOrNull()) },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}
