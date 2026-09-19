package com.budgetapp.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.budgetapp.data.db.ManualRecurringRuleEntity
import com.budgetapp.domain.Cadence
import com.budgetapp.domain.Direction
import com.budgetapp.domain.RecurringRule
import com.budgetapp.ui.formatMoney
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringRulesScreen(
    rules: List<RecurringRule>,
    manualRules: List<ManualRecurringRuleEntity>,
    ignored: Set<String>,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onAddManual: () -> Unit,
) {
    val income = rules.filter { it.direction == Direction.IN }
    val fixed = rules.filter { it.direction == Direction.OUT }
    val manualIncome = manualRules.filter { it.direction == "IN" }
    val manualFixed = manualRules.filter { it.direction == "OUT" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recurring payments") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(onClick = onAddManual) {
                        Text("Add manual")
                    }
                },
            )
        },
    ) { padding ->
        if (rules.isEmpty() && manualRules.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No recurring payments detected yet. Link a bank and sync transactions, or add manually.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (income.isNotEmpty() || manualIncome.isNotEmpty()) {
                item { Text("Income", style = MaterialTheme.typography.titleMedium) }
                items(income, key = { it.key }) { rule ->
                    RuleRow(rule, ignored.contains(rule.key)) { onToggle(rule.key, it) }
                }
                items(manualIncome, key = { it.id }) { rule ->
                    ManualRuleRow(rule) { /* no toggle for manual - always active */ }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            if (fixed.isNotEmpty() || manualFixed.isNotEmpty()) {
                item { Text("Fixed outgoings", style = MaterialTheme.typography.titleMedium) }
                items(fixed, key = { it.key }) { rule ->
                    RuleRow(rule, ignored.contains(rule.key)) { onToggle(rule.key, it) }
                }
                items(manualFixed, key = { it.id }) { rule ->
                    ManualRuleRow(rule) { /* no toggle for manual - always active */ }
                }
            }
        }
    }
}

@Composable
private fun ManualRuleRow(
    rule: ManualRecurringRuleEntity,
    onDelete: () -> Unit,
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
            // Could add delete button here
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
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.payee.ifBlank { "Unknown" }, style = MaterialTheme.typography.titleSmall)
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