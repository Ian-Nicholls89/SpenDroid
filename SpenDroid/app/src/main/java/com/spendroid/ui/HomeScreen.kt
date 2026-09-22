package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spendroid.data.Connection
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.CategoryTotal
import com.spendroid.domain.TrendSummary
import com.spendroid.domain.TrendsEngine
import com.spendroid.ui.theme.HeroGradientEnd
import com.spendroid.ui.theme.HeroGradientStart
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

private val InColor = Color(0xFF2E7D32)
private val OutColor = Color(0xFFC62828)

private val CategoryColors = listOf(
    Color(0xFF43A047),
    Color(0xFF1E88E5),
    Color(0xFFFB8C00),
    Color(0xFFE53935),
    Color(0xFF8E24AA),
    Color(0xFF00ACC1),
    Color(0xFF3949AB),
    Color(0xFFF4511E),
    Color(0xFF00897B),
    Color(0xFF6D4C41),
)

private const val DISPLAY_TRANSACTION_LIMIT = 200

private data class MonthTotals(
    val inSum: Long,
    val outSum: Long,
    val currency: String,
    val avgOut: Long,
    val monthCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: RootUiState,
    onRefresh: () -> Unit,
    onRelink: (Connection) -> Unit,
    onToggleRecurring: () -> Unit,
    onToggleInternal: () -> Unit,
    onLinkBank: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (state.reauthNeeded.isNotEmpty()) {
            ReauthBanner(state.reauthNeeded, onRelink)
            Spacer(Modifier.height(16.dp))
        }
        state.budget?.let { budget ->
            HeroBudgetCard(budget)
            Spacer(Modifier.height(16.dp))
        }

        // Both engines walk the whole transaction history. Keyed on the list so they run when
        // the data changes rather than on every recomposition.
        val breakdown = remember(state.transactions) {
            CategoryEngine.spendingBreakdown(state.transactions)
        }
        if (breakdown.isNotEmpty()) {
            CategoryBreakdownCard(breakdown, state.budget?.variableMonthlyBudget)
            Spacer(Modifier.height(16.dp))
        }

        val trends = remember(state.transactions) { TrendsEngine.analyze(state.transactions) }
        if (trends.months.size >= 2) {
            TrendsCard(trends)
            Spacer(Modifier.height(16.dp))
        }

        MonthlySummary(state.transactions)
        Spacer(Modifier.height(16.dp))
        Text("Accounts", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        state.accounts.forEach { account ->
            AccountCard(account)
            Spacer(Modifier.height(8.dp))
        }
        if (state.accounts.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "No accounts linked yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onLinkBank) {
                        Text("Link a bank")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Transactions", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRefresh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.syncing) "Syncing…" else "Refresh")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.showRecurringOnly,
                onClick = onToggleRecurring,
                label = { Text("Recurring only") },
            )
            FilterChip(
                selected = state.showInternalTransfers,
                onClick = onToggleInternal,
                label = { Text("Internal transfers") },
            )
        }
        Spacer(Modifier.height(8.dp))

        val visible = remember(
            state.transactions,
            state.showRecurringOnly,
            state.showInternalTransfers,
        ) {
            state.transactions
                .filter { tx ->
                    (!state.showRecurringOnly || tx.isRecurring) &&
                        (state.showInternalTransfers || !tx.isInternalTransfer)
                }
                .take(DISPLAY_TRANSACTION_LIMIT)
        }

        if (visible.isEmpty()) {
            Text(
                "No transactions match current filters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        } else {
            if (state.transactions.size > DISPLAY_TRANSACTION_LIMIT) {
                Text(
                    "Showing the latest ${visible.size} of ${state.transactions.size} transactions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            visible.forEachIndexed { index, tx ->
                TransactionRow(tx, isLast = index == visible.lastIndex)
            }
        }
        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReauthBanner(
    connections: List<Connection>,
    onRelink: (Connection) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Bank access expired", style = MaterialTheme.typography.titleMedium)
            Text(
                "Reconnect to keep syncing these accounts:",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            connections.forEach { connection ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        connection.institutionName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onRelink(connection) }) {
                        Text("Reconnect")
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBudgetCard(budget: BudgetSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(HeroGradientStart, HeroGradientEnd)))
                .padding(20.dp),
        ) {
            Column {
                Text(
                    "Budget until next income",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.85f),
                )
                budget.nextIncomeDate?.let { next ->
                    val days = max(0L, ChronoUnit.DAYS.between(LocalDate.now(), next))
                    Text(
                        "Next income ${next.format(dateFormat)} · in $days day${if (days == 1L) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Available to spend",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Text(
                    formatMoney(budget.availableToSpend, "GBP"),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    HeroStat("Spent today", formatMoney(budget.spentToday, "GBP"))
                    HeroStat("This cycle", formatMoney(budget.spentThisCycle, "GBP"))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Income ${formatMoney(budget.averageMonthlyIncome, "GBP")}/mo · " +
                        "Fixed ${formatMoney(budget.fixedMonthlyOutgoings, "GBP")}/mo · " +
                        "Variable ${formatMoney(budget.variableMonthlyBudget, "GBP")}/mo",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
                if (budget.upcomingFixed.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Known deductions to come",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    budget.upcomingFixed.take(5).forEach { payment ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(payment.rule.payee, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                Text(
                                    "due ${payment.dueDate.format(dateFormat)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                )
                            }
                            Text(
                                formatMoney(payment.amountMinor, payment.rule.currency),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                }
                if (budget.incomeRules.isNotEmpty() && budget.fixedRules.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Detected ${budget.incomeRules.size} recurring ${if (budget.incomeRules.size == 1) "income" else "incomes"} " +
                            "and ${budget.fixedRules.size} recurring ${if (budget.fixedRules.size == 1) "payment" else "payments"}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM")

@Composable
private fun CategoryBreakdownCard(
    breakdown: List<CategoryTotal>,
    variableBudget: Long?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Spending by category",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            breakdown.forEachIndexed { index, total ->
                val maxAmount = breakdown.firstOrNull()?.amountMinor ?: 1L
                val fraction = if (maxAmount > 0) total.amountMinor.toFloat() / maxAmount.toFloat() else 0f
                val barColor = CategoryColors[index % CategoryColors.size]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        total.category.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(120.dp),
                    )
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp),
                        color = barColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatMoney(total.amountMinor, total.currency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(80.dp),
                        textAlign = TextAlign.End,
                    )
                    Text(
                        "${total.count}×",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

@Composable
private fun MonthlySummary(transactions: List<TransactionEntity>) {
    val monthKey = remember { "%04d-%02d".format(java.time.LocalDate.now().year, java.time.LocalDate.now().monthValue) }

    // Grouping the full history on every recomposition is wasted work - key it on the data.
    val summary = remember(transactions, monthKey) {
        val thisMonth = transactions.filter { it.bookingDate.startsWith(monthKey) }
        val monthlyOut = transactions
            .filter { it.bookingDate.length >= 7 }
            .groupBy { it.bookingDate.substring(0, 7) }
            .values
            .map { txs -> txs.filter { it.amountMinor < 0 }.sumOf { -it.amountMinor } }
        MonthTotals(
            inSum = thisMonth.filter { it.amountMinor > 0 }.sumOf { it.amountMinor },
            outSum = thisMonth.filter { it.amountMinor < 0 }.sumOf { -it.amountMinor },
            currency = thisMonth.firstOrNull()?.currency ?: "GBP",
            avgOut = if (monthlyOut.isEmpty()) 0L else monthlyOut.sum() / monthlyOut.size,
            monthCount = monthlyOut.size,
        )
    }
    val inSum = summary.inSum
    val outSum = summary.outSum
    val currency = summary.currency
    val avgOut = summary.avgOut

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Spending this month",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatMoney(outSum, currency),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Text("Money in: ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatMoney(inSum, currency),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(16.dp))
                Text("Net: ", style = MaterialTheme.typography.bodyMedium)
                Text(formatMoney(inSum - outSum, currency), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Average monthly spend across ${summary.monthCount} months: ${formatMoney(avgOut, currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountCard(account: AccountEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(account.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    account.institutionName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                account.balanceMinor?.let {
                    Text(formatMoney(it, account.currency), style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "synced " + syncTime(account.lastSynced),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: TransactionEntity, isLast: Boolean = false) {
    val amount = formatMoney(tx.amountMinor, tx.currency)
    val category = CategoryEngine.classify(tx)
    val isInternal = tx.isInternalTransfer
    val isRecurring = tx.isRecurring
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isInternal) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        tx.payee.ifBlank { tx.description?.ifBlank { "Unknown" } ?: "Unknown" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        category.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    if (isInternal) {
                        Text(
                            "↔ Internal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (isRecurring) {
                        Text(
                            "⟳ Recurring",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    listOf(tx.bookingDate.takeIf { it.isNotBlank() }, tx.description?.takeIf { it.isNotBlank() })
                        .filterNotNull()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                amount,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (tx.amountMinor >= 0) InColor else OutColor,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 80.dp),
            )
        }
    }
    if (!isLast) {
        Divider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

private val syncFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())

private fun syncTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(syncFormatter)