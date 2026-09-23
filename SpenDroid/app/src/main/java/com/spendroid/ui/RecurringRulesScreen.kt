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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.domain.Cadence
import com.spendroid.domain.Direction
import com.spendroid.domain.RecurringRule
import com.spendroid.ui.formatMoney
import java.time.DayOfWeek
import java.time.LocalDate
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
) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recurring payments", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAddManual) {
                Text("Add manual")
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (income.isNotEmpty() || manualIncome.isNotEmpty()) {
                item { Text("Income", style = MaterialTheme.typography.titleMedium) }
                item {
                    Text(
                        "Mark which income sets your pay cycle. Everything else still adds to " +
                            "the budget - it just does not move the dates.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                items(income, key = { it.key }) { rule ->
                    RuleRow(
                        rule = rule,
                        ignored = ignored.contains(rule.key),
                        isPrimaryIncome = rule.key == primaryIncomeKey,
                        canBePrimary = true,
                        onSetPrimary = {
                            onSetPrimaryIncome(if (rule.key == primaryIncomeKey) null else rule.key)
                        },
                    ) { onToggle(rule.key, it) }
                }
                items(manualIncome, key = { it.id }) { rule ->
                    ManualRuleRow(rule)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            if (fixed.isNotEmpty() || manualFixed.isNotEmpty()) {
                item { Text("Fixed outgoings", style = MaterialTheme.typography.titleMedium) }
                items(fixed, key = { it.key }) { rule ->
                    RuleRow(rule, ignored.contains(rule.key)) { onToggle(rule.key, it) }
                }
                items(manualFixed, key = { it.id }) { rule ->
                    ManualRuleRow(rule)
                }
            }
        }
    }
}

@Composable
private fun ManualRuleRow(
    rule: ManualRecurringRuleEntity,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${rule.payee} (manual)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${describeManualRule(rule)} · ${formatMoney(rule.amountMinor, rule.currency)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Manual entry · always active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
    onSetPrimary: () -> Unit = {},
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canBePrimary) {
                IconButton(onClick = onSetPrimary) {
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
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.payee.ifBlank { "Unknown" }, style = MaterialTheme.typography.titleSmall)
                if (isPrimaryIncome) {
                    Text(
                        "Sets the pay cycle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "${describeRule(rule)} · ${formatMoney(rule.amountMinor, rule.currency)} · " +
                        "${rule.occurrences} seen · ${(rule.score * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (ignored) "Excluded from budget" else "Counted in budget",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = !ignored,
                onCheckedChange = { onToggle(!it) },
            )
        }
    }
}

fun describeRule(rule: RecurringRule): String = when (rule.cadence) {
    Cadence.WEEKLY ->
        "Weekly · ${dayName(rule.anchorDay)}"
    Cadence.FORTNIGHTLY ->
        "Every 2 weeks"
    Cadence.MONTHLY ->
        "Monthly · day ${rule.anchorDay}"
    Cadence.MONTHLY_LAST_DAY ->
        "Monthly · last day"
    Cadence.MONTHLY_LAST_BUSINESS_DAY ->
        "Monthly · last working day"
    Cadence.QUARTERLY ->
        "Quarterly"
    Cadence.ANNUAL ->
        "Yearly"
}

private fun dayName(value: Int): String = try {
    DayOfWeek.of(value).getDisplayName(TextStyle.SHORT, Locale.getDefault())
} catch (e: Exception) {
    ""
}