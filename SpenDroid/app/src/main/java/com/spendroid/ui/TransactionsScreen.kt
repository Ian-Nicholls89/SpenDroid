package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DISPLAY_TRANSACTION_LIMIT = 200

/** Minimum comfortable touch target; rows and controls should not fall below it. */
private val MinTouchTarget = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    state: RootUiState,
    onRefresh: () -> Unit,
    onToggleRecurring: () -> Unit,
    onToggleInternal: () -> Unit,
    onQueryChange: (String) -> Unit,
    onAccountFilter: (String?) -> Unit,
    onOverrideCategory: (TransactionEntity, Category?) -> Unit,
    onAlwaysCategorise: (TransactionEntity, Category) -> Unit,
    onMarkTransfer: (TransactionEntity, Boolean) -> Unit,
    onMarkCardPayment: (TransactionEntity, Boolean) -> Unit,
) {
    var selected by remember { mutableStateOf<TransactionEntity?>(null) }

    val accountNames = remember(state.accounts) { state.accounts.associate { it.id to it.label } }

    val visible = remember(
        state.transactions,
        state.showRecurringOnly,
        state.showInternalTransfers,
        state.transactionQuery,
        state.accountFilter,
        state.budget?.confirmedSettlementKeys,
    ) {
        val query = state.transactionQuery.tidyPayee().lowercase()
        val settlements = state.budget?.confirmedSettlementKeys.orEmpty()
        val payers = state.budget?.cardPayerKeys.orEmpty()
        state.transactions
            .filter { tx ->
                val id = "${tx.accountId}|${tx.transactionId}"
                // Paying a card posts a credit on the card and a debit on the account that
                // paid. They are one event, and the debit is the half that matters: it is
                // the money actually leaving. The credit only restates it, in green, in a
                // list of spending - so it is dropped unless you are looking at that card's
                // own transactions, where its ledger has to balance.
                val duplicateHalf = id in settlements &&
                    state.accountFilter != tx.accountId &&
                    !state.showInternalTransfers
                // Pairing may also have flagged the paying debit as a transfer. By shape it
                // is one; by consequence it is spending, so it stays visible.
                val realOutflow = id in payers

                !duplicateHalf &&
                    (state.accountFilter == null || tx.accountId == state.accountFilter) &&
                    (!state.showRecurringOnly || tx.isRecurring) &&
                    (state.showInternalTransfers || !tx.isInternalTransfer || realOutflow) &&
                    (
                        query.isEmpty() ||
                            tx.payee.tidyPayee().lowercase().contains(query) ||
                            tx.description?.tidyPayee()?.lowercase()?.contains(query) == true
                        )
            }
            .take(DISPLAY_TRANSACTION_LIMIT)
    }

    // Grouped by month so a long history can be read rather than merely scrolled.
    val months = remember(visible, state.categoryRules) {
        visible
            .groupBy { it.bookingDate.take(7) }
            .toList()
            .sortedByDescending { it.first }
    }

    PullToRefreshBox(
        isRefreshing = state.syncing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = state.transactionQuery,
                    onValueChange = onQueryChange,
                    label = { Text("Search payee or reference") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.transactionQuery.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.accounts.size > 1) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.accountFilter == null,
                            onClick = { onAccountFilter(null) },
                            label = { Text("All accounts") },
                        )
                        state.accounts.forEach { account ->
                            FilterChip(
                                selected = state.accountFilter == account.id,
                                onClick = {
                                    onAccountFilter(
                                        if (state.accountFilter == account.id) null else account.id,
                                    )
                                },
                                label = { Text(account.label, maxLines = 1) },
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
            }

            if (visible.isEmpty()) {
                item {
                    NoMatches(
                        hasQuery = state.transactionQuery.isNotEmpty(),
                        onClearFilters = {
                            onQueryChange("")
                            onAccountFilter(null)
                            if (state.showRecurringOnly) onToggleRecurring()
                            if (!state.showInternalTransfers) onToggleInternal()
                        },
                    )
                }
            } else {
                if (state.transactions.size > DISPLAY_TRANSACTION_LIMIT) {
                    item {
                        Text(
                            "Showing the latest ${visible.size} of ${state.transactions.size} transactions.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                months.forEach { (monthKey, rows) ->
                    item(key = "header-$monthKey") {
                        MonthHeader(monthKey, rows)
                    }
                    items(rows, key = { "${it.accountId}|${it.transactionId}" }) { tx ->
                        TransactionRow(
                            tx = tx,
                            userRules = state.categoryRules,
                            // Redundant once the list is filtered to a single account.
                            accountName = if (state.accountFilter == null) {
                                accountNames[tx.accountId]
                            } else {
                                null
                            },
                            cardPaymentKeys = state.budget?.cardPaymentKeys.orEmpty(),
                            creditCardAccountIds =
                                state.budget?.creditCardAccountIds.orEmpty(),
                            onClick = { selected = tx },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 64.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }

            state.error?.let { error ->
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    selected?.let { tx ->
        TransactionDetailSheet(
            transaction = tx,
            userRules = state.categoryRules,
            cardPaymentKeys = state.budget?.cardPaymentKeys.orEmpty(),
            creditCardAccountIds = state.budget?.creditCardAccountIds.orEmpty(),
            onDismiss = { selected = null },
            onOverrideCategory = { t, c -> onOverrideCategory(t, c); selected = null },
            onAlwaysCategorise = { t, c -> onAlwaysCategorise(t, c); selected = null },
            onMarkTransfer = { t, v -> onMarkTransfer(t, v); selected = null },
            onMarkCardPayment = { t, v -> onMarkCardPayment(t, v); selected = null },
        )
    }
}

@Composable
private fun MonthHeader(monthKey: String, rows: List<TransactionEntity>) {
    val label = remember(monthKey) {
        runCatching {
            YearMonth.parse(monthKey).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        }.getOrDefault(monthKey)
    }
    val spent = rows.filter { it.amountMinor < 0 }.sumOf { -it.amountMinor }
    val currency = rows.firstOrNull()?.currency ?: "GBP"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatMoney(spent, currency),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoMatches(hasQuery: Boolean, onClearFilters: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.FilterAltOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasQuery) "Nothing matches that search" else "Nothing matches these filters",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClearFilters) { Text("Clear filters") }
    }
}

@Composable
private fun TransactionRow(
    tx: TransactionEntity,
    userRules: List<CategoryRuleEntity>,
    accountName: String? = null,
    cardPaymentKeys: Set<String> = emptySet(),
    creditCardAccountIds: Set<String> = emptySet(),
    onClick: () -> Unit,
) {
    val category = CategoryEngine.classify(tx, userRules, cardPaymentKeys, creditCardAccountIds)
    val visual = category.visual
    val amount = formatMoney(tx.amountMinor, tx.currency)
    val name = tx.payee.tidyPayee().ifBlank { tx.description?.tidyPayee()?.ifBlank { "Unknown" } ?: "Unknown" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            // One announcement for the row rather than four disconnected fragments.
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, $category.label, ${tx.bookingDate}, $amount"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(visual.color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                visual.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                buildList {
                    add(category.label)
                    accountName?.let(::add)
                    tx.bookingDate.takeIf { it.isNotBlank() }?.let(::add)
                    if (tx.isInternalTransfer) add("transfer")
                    if (tx.isRecurring) add("recurring")
                    if (tx.categoryOverride != null) add("edited")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(12.dp))
        Text(
            amount,
            // Colour marks the exception, not the rule: an ordinary debit is the most common
            // thing on this screen and does not need the loudest colour on the palette.
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = if (tx.amountMinor >= 0) InColor else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 84.dp),
        )
    }
}
