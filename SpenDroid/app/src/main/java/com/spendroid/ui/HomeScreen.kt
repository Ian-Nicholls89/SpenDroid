package com.spendroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendroid.data.Connection
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.BudgetSnapshot
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.CreditCardEngine
import com.spendroid.domain.CategoryTotal
import com.spendroid.domain.TrendSummary
import com.spendroid.domain.TrendsEngine
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
 * The hero's colour, from whether spending is keeping pace with the cycle.
 *
 * Deliberately not from the remaining balance: £200 left is comfortable on day 28 and
 * alarming on day 5, so colouring by the remainder alone would turn the screen red every
 * month as payday approached, and a warning that fires every month is one people learn to
 * ignore. Pace reacts to behaviour instead of to the calendar.
 *
 * Tolerances are fractions of the budget rather than fixed amounts, so the same thresholds
 * suit any income.
 */
private fun heroGradient(budget: BudgetSnapshot): List<Color> {
    val used = budgetUsedFraction(budget)
    val elapsed = cycleElapsedFraction(budget)
    if (used == null || elapsed == null) return listOf(HeroGreenStart, HeroGreenEnd)

    val overspendFraction = used - elapsed
    return when {
        // Nothing left is worth saying loudly whatever the date.
        budget.availableToSpend <= 0L -> listOf(HeroRedStart, HeroRedEnd)
        overspendFraction > 0.15f -> listOf(HeroRedStart, HeroRedEnd)
        overspendFraction > 0.05f -> listOf(HeroAmberStart, HeroAmberEnd)
        else -> listOf(HeroGreenStart, HeroGreenEnd)
    }
}

private val HeroGreenStart = Color(0xFF2E7D32)
private val HeroGreenEnd = Color(0xFF1B5E20)
private val HeroAmberStart = Color(0xFFB26A00)
private val HeroAmberEnd = Color(0xFF7A4600)
private val HeroRedStart = Color(0xFF9B2226)
private val HeroRedEnd = Color(0xFF5D1214)

/**
 * Budget used, drawn as an arc. The track marks how far through the cycle today is, so a gap
 * between the two is the whole signal.
 */
@Composable
private fun SpendingPaceRing(budget: BudgetSnapshot) {
    val used = budgetUsedFraction(budget)
    val elapsed = cycleElapsedFraction(budget)
    if (used == null) return

    val spoken = paceCaption(budget)
        ?: "${(used * 100).toInt()} percent of the budget used"
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(78.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${bill.cardLabel}, estimated bill " +
                    formatMoney(bill.outstandingMinor, bill.currency)
            },
    ) {
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

/**
 * What is held against what is owed.
 *
 * Available-to-spend is a budget: a plan derived from recurring income. This is the other
 * question - what actually exists right now - and the two are worth seeing side by side
 * rather than blended into one number that answers neither cleanly.
 */
@Composable
private fun NetPositionCard(accounts: List<AccountEntity>) {
    val currency = accounts.firstOrNull()?.currency ?: "GBP"
    val held = accounts
        .filter { it.accountType != AccountType.CREDIT_CARD }
        .sumOf { it.balanceMinor ?: 0L }
    // A card balance is negative when money is owed on it; a card in credit owes nothing.
    val owed = accounts
        .filter { it.accountType == AccountType.CREDIT_CARD }
        .sumOf { maxOf(0L, -(it.balanceMinor ?: 0L)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Where you stand",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Held",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(held, currency),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Owed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (owed == 0L) formatMoney(0L, currency) else "−" + formatMoney(owed, currency),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = if (owed > 0L) OutColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Net",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMoney(held - owed, currency),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold,
                        color = if (held - owed < 0L) OutColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * Shown only on the day itself.
 *
 * Exactly when a delayed payment reappears is not worth predicting - it depends on the
 * biller, and it will be somewhere in the couple of days around the closure. Saying so is
 * more useful than a confident date that turns out wrong.
 */
@Composable
private fun BankHolidayBanner(holidayName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Today is $holidayName",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "Banks are closed, so anything due today will most likely leave your " +
                        "account on the next working day. Today's spending figures may look " +
                        "lower than they really are until it catches up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
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
    onSetBudgetGoal: (Category, Long) -> Unit,
    onLinkBank: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
    ) {
        state.bankHolidayToday?.let { name ->
            BankHolidayBanner(name)
            Spacer(Modifier.height(16.dp))
        }
        if (state.reauthNeeded.isNotEmpty()) {
            ReauthBanner(state.reauthNeeded, onRelink)
            Spacer(Modifier.height(16.dp))
        }
        state.budget?.let { budget ->
            HeroBudgetCard(budget)
            Spacer(Modifier.height(16.dp))
        }

        if (state.accounts.isNotEmpty()) {
            NetPositionCard(state.accounts)
            Spacer(Modifier.height(16.dp))
        }

        state.budget?.cardBills.orEmpty()
            .filter { it.outstandingMinor > 0L }
            .forEach { bill ->
                CardBillCard(bill)
                Spacer(Modifier.height(16.dp))
            }

        // Category breakdown and trends live on Spending → Insights. The dashboard reports
        // the state of the cycle; analysing it is a different job and a different screen.

        // The list of accounts belongs to the Accounts tab; this is only the way in on the
        // first run, when there is nothing else on the screen to act on.
        if (state.accounts.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No accounts linked yet.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Link a bank and SpenDroid will work out your pay cycle from what it finds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onLinkBank) { Text("Link a bank") }
                }
            }
            Spacer(Modifier.height(16.dp))
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

        state.linkProgress?.let { progress ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(progress, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

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
                .background(Brush.linearGradient(heroGradient(budget)))
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
                            when (budget.budgetModel) {
                                BudgetModel.ROLLOVER -> "Available to spend, balance carried over"
                                else -> "Available to spend"
                            },
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
                    // Deliberately beside the budget rather than folded into it: the two
                    // answer different questions and the gap between them is the point.
                    if (budget.budgetModel == BudgetModel.SHOW_BOTH) {
                        budget.potBalanceMinor?.let { pot ->
                            HeroStat("In the account", formatMoney(pot, "GBP"))
                        }
                    }
                }
                if (budget.budgetModel == BudgetModel.ROLLOVER) {
                    budget.openingBalanceMinor?.let { opening ->
                        Text(
                            "Includes ${formatMoney(opening, "GBP")} carried in at the start of the cycle",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
                if (budget.designationLost) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The income you picked to set the cycle is no longer being detected — " +
                            "possibly renamed by your bank. Using the largest income instead; " +
                            "pick it again under Recurring rules.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
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
                                Text(payment.rule.payee.tidyPayee(), style = MaterialTheme.typography.bodyMedium, color = Color.White)
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

private fun syncTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()))