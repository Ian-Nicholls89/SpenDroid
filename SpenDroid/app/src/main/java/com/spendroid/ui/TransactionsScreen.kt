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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.CategoryEngine

private const val DISPLAY_TRANSACTION_LIMIT = 200

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    state: RootUiState,
    onRefresh: () -> Unit,
    onToggleRecurring: () -> Unit,
    onToggleInternal: () -> Unit,
) {
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

    PullToRefreshBox(
        isRefreshing = state.syncing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                item { NoMatches(onClearFilters = {
                    if (state.showRecurringOnly) onToggleRecurring()
                    if (!state.showInternalTransfers) onToggleInternal()
                }) }
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
                items(visible, key = { "${it.accountId}|${it.transactionId}" }) { tx ->
                    TransactionRow(tx)
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
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
}

@Composable
private fun NoMatches(onClearFilters: () -> Unit) {
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
            "Nothing matches these filters",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClearFilters) { Text("Clear filters") }
    }
}

@Composable
private fun TransactionRow(tx: TransactionEntity) {
    val category = CategoryEngine.classify(tx)
    val visual = category.visual

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
            Text(
                tx.payee.ifBlank { tx.description?.ifBlank { "Unknown" } ?: "Unknown" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                buildList {
                    add(category.label)
                    tx.bookingDate.takeIf { it.isNotBlank() }?.let(::add)
                    if (tx.isInternalTransfer) add("transfer")
                    if (tx.isRecurring) add("recurring")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(12.dp))
        Text(
            formatMoney(tx.amountMinor, tx.currency),
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
