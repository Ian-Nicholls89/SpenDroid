package com.spendroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spendroid.data.Connection
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.CreditCardEngine
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

internal val InColor = Color(0xFF2E7D32)
internal val OutColor = Color(0xFFC62828)

/**
 * How much of the variable budget is gone, as a fraction. Null when there is no budget to
 * measure against.
 */
private fun budgetUsedFraction(budget: BudgetSnapshot): Float? {
    if (budget.variableMonthlyBudget <= 0L) return null
    return (budget.spentThisCycle.toFloat() / budget.variableMonthlyBudget.toFloat())
        .coerceIn(0f, 1f)
}

/** How far through the pay cycle today is, as a fraction. */
private fun cycleElapsedFraction(budget: BudgetSnapshot): Float? {
    val end = budget.cycleEnd ?: return null
    val total = ChronoUnit.DAYS.between(budget.cycleStart, end).toFloat()
    if (total <= 0f) return null
    val gone = ChronoUnit.DAYS.between(budget.cycleStart, LocalDate.now()).toFloat()
    return (gone / total).coerceIn(0f, 1f)
}

/**
 * "£742 left" reads very differently with 8 days to go than with 24, so say which it is:
 * spending against time, not just the remaining balance.
 */
private fun paceCaption(budget: BudgetSnapshot): String? {
    val used = budgetUsedFraction(budget) ?: return null
    val elapsed = cycleElapsedFraction(budget) ?: return null
    val expected = (budget.variableMonthlyBudget * elapsed).toLong()
    val difference = expected - budget.spentThisCycle
    val throughCycle = "${(elapsed * 100).toInt()}% through the cycle"
    return when {
        difference > 500L -> "$throughCycle · ahead by ${formatMoney(difference, "GBP")}"
        difference < -500L -> "$throughCycle · over by ${formatMoney(-difference, "GBP")}"
        else -> "$throughCycle · on track"
    }
}

/**
 * Budget used, drawn as an arc. The track marks how far through the cycle today is, so a gap
 * between the two is the whole signal.
 */
@Composable
private fun SpendingPaceRing(budget: BudgetSnapshot) {
    val used = budgetUsedFraction(budget)
    val elapsed = cycleElapsedFraction(budget)
    if (used == null) return

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(78.dp)) {
        Canvas(modifier = Modifier.size(78.dp)) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val offset = Offset(inset, inset)

            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = offset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            elapsed?.let {
                drawArc(
                    color = Color.White.copy(alpha = 0.45f),
                    startAngle = -90f,
                    sweepAngle = 360f * it,
                    useCenter = false,
                    topLeft = offset,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * used,
                useCenter = false,
                topLeft = offset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(used * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                "used",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * The card bill, split into the part already on a closed statement and the part still
 * accruing. Both are owed; only the first has a settled amount, which is why the split is
 * worth showing rather than one total.
 */
@Composable
private fun CardBillCard(bill: CreditCardEngine.CardBill) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CreditCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    bill.cardLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    formatMoney(bill.outstandingMinor, bill.currency),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Billed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(bill.billedMinor, bill.currency),
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Since statement",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(bill.unbilledMinor, bill.currency),
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    )
                }
            }
            if (bill.outstandingMinor > 0L) {
                Spacer(Modifier.height(10.dp))
                val billedShare = bill.billedMinor.toFloat() / bill.outstandingMinor.toFloat()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(billedShare.coerceAtLeast(0.001f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Box(
                        modifier = Modifier
                            .weight((1f - billedShare).coerceAtLeast(0.001f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
            bill.dueDate?.let { due ->
                Spacer(Modifier.height(10.dp))
                Text(
                    // Inferred from payment history, and direct debits shift around weekends
                    // and bank holidays, so the date is approximate on purpose.
                    "Estimated · due ~${due.format(dateFormat)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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
    onSeeAllTransactions: () -> Unit,
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

        state.budget?.cardBills.orEmpty()
            .filter { it.outstandingMinor > 0L }
            .forEach { bill ->
                CardBillCard(bill)
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

        // The list lives on its own destination now; the dashboard keeps a way in.
        TextButton(
            onClick = onSeeAllTransactions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("See all transactions")
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpendingPaceRing(budget)
                    Spacer(Modifier.width(16.dp))
                    Column {
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
                        paceCaption(budget)?.let { caption ->
                            Text(
                                caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
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
                                // A card bill is forecast from the outstanding balance, so it
                                // should not read as a confirmed amount and date.
                                val isForecast = payment.rule.key.startsWith(CARD_BILL_KEY_PREFIX)
                                Text(
                                    if (isForecast) {
                                        "estimated · due ~${payment.dueDate.format(dateFormat)}"
                                    } else {
                                        "due ${payment.dueDate.format(dateFormat)}"
                                    },
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
            // Label above the bar rather than beside it: the old fixed 120.dp column clipped
            // "Bills & Utilities" and "Entertainment" on narrow screens.
            breakdown.forEach { total ->
                val maxAmount = breakdown.firstOrNull()?.amountMinor ?: 1L
                val fraction = if (maxAmount > 0) total.amountMinor.toFloat() / maxAmount.toFloat() else 0f
                val visual = total.category.visual
                Column(modifier = Modifier.padding(vertical = 5.dp)) {
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
                            formatMoney(total.amountMinor, total.currency),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = visual.color,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
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

// Resolved per call, not once into a static: the locale captured at class-init time would
// survive a language change and keep formatting in the old one.
private fun syncTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()))