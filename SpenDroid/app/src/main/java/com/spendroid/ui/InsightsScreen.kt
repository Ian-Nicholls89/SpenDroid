package com.spendroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.CategoryTotal
import com.spendroid.domain.TrendSummary
import com.spendroid.domain.TrendsEngine

/**
 * Where spending is analysed, as opposed to the dashboard, which says where the cycle
 * stands. Both engines walk the whole history, so both are keyed on the data rather than
 * recomputed on every recomposition.
 */
@Composable
fun InsightsScreen(
    state: RootUiState,
    onSetBudgetGoal: (Category, Long) -> Unit,
) {
    val cardPaymentKeys = state.budget?.cardPaymentKeys.orEmpty()
    val cardAccountIds = state.budget?.creditCardAccountIds.orEmpty()
    val breakdown = remember(state.transactions, state.categoryRules, cardPaymentKeys) {
        CategoryEngine.spendingBreakdown(
            state.transactions,
            state.categoryRules,
            cardPaymentKeys,
            cardAccountIds,
        )
    }
    val trends = remember(state.transactions) { TrendsEngine.analyze(state.transactions) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
    ) {
        if (breakdown.isEmpty() && trends.months.size < 2) {
            Text(
                "Once a few weeks of transactions have synced, spending by category and " +
                    "month-by-month trends will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (breakdown.isNotEmpty()) {
            CategoryBreakdownCard(
                breakdown = breakdown,
                variableBudget = state.budget?.variableMonthlyBudget,
                goals = state.budgetGoals,
                onSetGoal = onSetBudgetGoal,
            )
            Spacer(Modifier.height(16.dp))
        }
        if (trends.months.size >= 2) {
            TrendsCard(trends)
        }
    }
}

@Composable
private fun BudgetGoalDialog(
    category: Category,
    current: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(current?.let { (it / 100).toString() } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monthly cap for ${category.label}") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit) },
                    label = { Text("Amount") },
                    prefix = { Text("£") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Leave empty to remove the cap.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm((text.toLongOrNull() ?: 0L) * 100) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CategoryBreakdownCard(
    breakdown: List<CategoryTotal>,
    variableBudget: Long?,
    goals: List<BudgetGoalEntity>,
    onSetGoal: (Category, Long) -> Unit,
) {
    var editing by remember { mutableStateOf<Category?>(null) }
    val goalFor = remember(goals) { goals.associateBy { it.category } }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Spending by category",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // Label above the bar rather than beside it: the old fixed 120.dp column clipped
            // "Bills & Utilities" and "Entertainment" on narrow screens.
            breakdown.forEach { total ->
                val maxAmount = breakdown.firstOrNull()?.amountMinor ?: 1L
                val fraction = if (maxAmount > 0) total.amountMinor.toFloat() / maxAmount.toFloat() else 0f
                val visual = total.category.visual
                val goal = goalFor[total.category.name]?.limitMinor
                val exceededBy = goal?.let { (total.amountMinor - it).takeIf { over -> over > 0L } }
                val overBudget = exceededBy != null
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = total.category }
                        .padding(vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            visual.icon,
                            contentDescription = null,
                            tint = visual.color,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            total.category.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${total.count}×",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (goal != null) {
                                formatMoney(total.amountMinor, total.currency) + " / " +
                                    formatMoney(goal, total.currency)
                            } else {
                                formatMoney(total.amountMinor, total.currency)
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = if (overBudget) OutColor else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        // Against the cap when one is set, otherwise against the largest
                        // category - the bar answers a different question in each case.
                        progress = {
                            if (goal != null && goal > 0L) {
                                (total.amountMinor.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
                            } else {
                                fraction
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = if (overBudget) OutColor else visual.color,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    exceededBy?.let { over ->
                        Text(
                            "${formatMoney(over, total.currency)} over",
                            style = MaterialTheme.typography.labelSmall,
                            color = OutColor,
                        )
                    }
                }
            }
            editing?.let { category ->
                BudgetGoalDialog(
                    category = category,
                    current = goalFor[category.name]?.limitMinor,
                    onDismiss = { editing = null },
                    onConfirm = { limit ->
                        onSetGoal(category, limit)
                        editing = null
                    },
                )
            }
            if (variableBudget != null && variableBudget > 0) {
                Spacer(Modifier.height(8.dp))
                val totalSpent = breakdown.sumOf { it.amountMinor }
                val remaining = (variableBudget - totalSpent).coerceAtLeast(0)
                val currency = breakdown.firstOrNull()?.currency ?: "GBP"
                Text(
                    "Total variable: ${formatMoney(totalSpent, currency)} of ${formatMoney(variableBudget, currency)} budget",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrendsCard(trends: TrendSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Spending trends",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Last ${trends.months.size} months",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val maxIncome = trends.months.maxOfOrNull { it.income } ?: 1L
            val maxSpending = trends.months.maxOfOrNull { it.spending } ?: 1L
            val maxValue = maxOf(maxIncome, maxSpending)

            trends.months.forEach { summary ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        summary.month.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Income",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(48.dp),
                        )
                        LinearProgressIndicator(
                            progress = { if (maxValue > 0) summary.income.toFloat() / maxValue.toFloat() else 0f },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp),
                            color = InColor,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            formatMoney(summary.income, summary.currency),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(70.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Spend",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(48.dp),
                        )
                        LinearProgressIndicator(
                            progress = { if (maxValue > 0) summary.spending.toFloat() / maxValue.toFloat() else 0f },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp),
                            color = OutColor,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            formatMoney(summary.spending, summary.currency),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(70.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val trendCurrency = trends.months.maxByOrNull { it.spending }?.currency ?: "GBP"
            Column {
                Text(
                    "Avg savings rate: ${(trends.avgSavingsRate * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (trends.avgSavingsRate >= 0) InColor else OutColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Avg income: ${formatMoney(trends.avgIncome, trendCurrency)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Avg spend: ${formatMoney(trends.avgSpending, trendCurrency)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
