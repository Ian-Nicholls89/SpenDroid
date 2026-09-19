package com.budgetapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
// import androidx.compose.ui.text.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.budgetapp.data.Connection
import com.budgetapp.data.db.AccountEntity
import com.budgetapp.data.db.TransactionEntity
import com.budgetapp.domain.BudgetSnapshot
import com.budgetapp.domain.Category
import com.budgetapp.domain.CategoryEngine
import com.budgetapp.domain.CategoryTotal
import com.budgetapp.domain.TrendSummary
import com.budgetapp.domain.TrendsEngine
import com.budgetapp.data.remote.InstitutionDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

private val InColor = Color(0xFF2E7D32)
private val OutColor = Color(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: RootUiState,
    onRefresh: () -> Unit,
    onOpenRules: () -> Unit,
    onRelink: (Connection) -> Unit,
    onLink: (InstitutionDto) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccounts: () -> Unit,
    onToggleRecurring: () -> Unit,
    onToggleInternal: () -> Unit,
) {
    var showLinkDialog by remember { mutableStateOf(false) }

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budget") },
                navigationIcon = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
            )
        },
    ) { padding ->
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(if (state.syncing) "Syncing…" else "Refresh") },
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = "") },
                onClick = { showMenu = false; onRefresh() },
            )
            DropdownMenuItem(
                text = { Text("Accounts") },
                leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = "") },
                onClick = { showMenu = false; onOpenAccounts() },
            )
            DropdownMenuItem(
                text = { Text("Rules") },
                leadingIcon = { Icon(Icons.Default.List, contentDescription = "") },
                onClick = { showMenu = false; onOpenRules() },
            )
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = "") },
                onClick = { showMenu = false; onOpenSettings() },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (state.reauthNeeded.isNotEmpty()) {
                ReauthBanner(state.reauthNeeded, onRelink)
                Spacer(Modifier.height(16.dp))
            }
            state.budget?.let { budget ->
                BudgetCard(budget)
                Spacer(Modifier.height(16.dp))
            }

            val breakdown = CategoryEngine.spendingBreakdown(state.transactions)
            if (breakdown.isNotEmpty()) {
                CategoryBreakdownCard(breakdown, state.budget?.variableMonthlyBudget)
                Spacer(Modifier.height(16.dp))
            }

            val trends = TrendsEngine.analyze(state.transactions)
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
                        Button(onClick = { showLinkDialog = true }) {
                            Text("Link a bank")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val filteredTx = state.transactions.filter { tx ->
                (!state.showRecurringOnly || tx.isRecurring) &&
                (state.showInternalTransfers || !tx.isInternalTransfer)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Transactions", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = onToggleRecurring,
                    modifier = Modifier.wrapContentSize(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.showRecurringOnly) "Recurring only" else "All transactions",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                TextButton(
                    onClick = onToggleInternal,
                    modifier = Modifier.wrapContentSize(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.showInternalTransfers) "Show internal" else "Hide internal",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (filteredTx.isEmpty()) {
                Text(
                    "No transactions match current filters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            } else {
                filteredTx.forEachIndexed { index, tx ->
                    TransactionRow(tx, isLast = index == filteredTx.lastIndex)
                }
            }
            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showLinkDialog) {
        LinkBankDialog(
            state = state,
            onDismiss = { showLinkDialog = false },
            onLink = { institution ->
                showLinkDialog = false
                onLink(institution)
            },
        )
    }
}

@Composable
private fun LinkBankDialog(
    state: RootUiState,
    onDismiss: () -> Unit,
    onLink: (InstitutionDto) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link a bank") },
        text = {
            Column {
                Text(
                    "Search for your bank. If you see multiple entries, pick the personal account one.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search banks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val filtered = state.institutions.filter { inst ->
                    query.isBlank() || inst.name.lowercase().contains(query.lowercase())
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                ) {
                    items(filtered, key = { it.id }) { institution ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(institution.name, style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(onClick = { onLink(institution) }) {
                                Text("Link")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
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
            breakdown.forEach { total ->
                val maxAmount = breakdown.firstOrNull()?.amountMinor ?: 1L
                val fraction = if (maxAmount > 0) total.amountMinor.toFloat() / maxAmount.toFloat() else 0f
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
                        color = OutColor,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatMoney(total.amountMinor, "GBP"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(80.dp),
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
                Text(
                    "Total variable: ${formatMoney(totalSpent, "GBP")} of ${formatMoney(variableBudget, "GBP")} budget",
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
                            formatMoney(summary.income, "GBP"),
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
                            formatMoney(summary.spending, "GBP"),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(70.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Column {
                Text(
                    "Avg savings rate: ${(trends.avgSavingsRate * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (trends.avgSavingsRate >= 0) InColor else OutColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Avg income: ${formatMoney(trends.avgIncome, "GBP")}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Avg spend: ${formatMoney(trends.avgSpending, "GBP")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun BudgetCard(budget: BudgetSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Budget until next income",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            budget.nextIncomeDate?.let { next ->
                val days = max(0L, ChronoUnit.DAYS.between(LocalDate.now(), next))
                Text(
                    "Next income ${next.format(dateFormat)} (in $days day${if (days == 1L) "" else "s"})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Left to spend",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatMoney(budget.availableToSpend, "GBP"),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    "Spent this cycle ${formatMoney(budget.spentThisCycle, "GBP")}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Today ${formatMoney(budget.spentToday, "GBP")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OutColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Income ${formatMoney(budget.averageMonthlyIncome, "GBP")}/mo · " +
                    "Fixed ${formatMoney(budget.fixedMonthlyOutgoings, "GBP")}/mo · " +
                    "Variable budget ${formatMoney(budget.variableMonthlyBudget, "GBP")}/mo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (budget.upcomingFixed.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Known deductions still to come",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                budget.upcomingFixed.forEach { payment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(payment.rule.payee, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "due ${payment.dueDate.format(dateFormat)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            formatMoney(payment.amountMinor, payment.rule.currency),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM")

@Composable
private fun MonthlySummary(transactions: List<TransactionEntity>) {
    val monthKey = remember { "%04d-%02d".format(java.time.LocalDate.now().year, java.time.LocalDate.now().monthValue) }

    val thisMonth = transactions.filter { it.bookingDate.startsWith(monthKey) }
    val inSum = thisMonth.filter { it.amountMinor > 0 }.sumOf { it.amountMinor }
    val outSum = thisMonth.filter { it.amountMinor < 0 }.sumOf { -it.amountMinor }

    val monthlyOut = transactions
        .filter { it.bookingDate.length >= 7 }
        .groupBy { it.bookingDate.substring(0, 7) }
        .values
        .map { txs -> txs.filter { it.amountMinor < 0 }.sumOf { -it.amountMinor } }

    val avgOut = if (monthlyOut.isEmpty()) 0L else monthlyOut.sum() / monthlyOut.size

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Spending this month",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatMoney(outSum, "GBP"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Text("Money in: ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatMoney(inSum, "GBP"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(16.dp))
                Text("Net: ", style = MaterialTheme.typography.bodyMedium)
                Text(formatMoney(inSum - outSum, "GBP"), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Average monthly spend across ${monthlyOut.size} months: ${formatMoney(avgOut, "GBP")}",
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
        androidx.compose.material3.Divider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

private val syncFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())

private fun syncTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(syncFormatter)