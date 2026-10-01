@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spendroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.Category

/**
 * The tabs along the top, nzb360's way. The first four are views of the one list - all of it,
 * what is still pending, the regular payments, the transfers - and the last is what it adds up to.
 */
private enum class SpendingTab(val label: String, val recurring: Boolean = false, val transfers: Boolean = false, val pending: Boolean = false) {
    ALL("All"),
    PENDING("Pending", pending = true),
    BILLS("Bills", recurring = true),
    TRANSFERS("Transfers", transfers = true),
    INSIGHTS("Insights"),
}

/**
 * Everything about money already spent: the list of it, and what it adds up to.
 *
 * Both were reachable before - one on its own destination, one buried down the dashboard.
 * Putting them together keeps the bottom bar at five destinations, which is as many as
 * Material allows before the labels start to crowd.
 */
@Composable
fun SpendingScreen(
    state: RootUiState,
    onRefresh: () -> Unit,
    onToggleRecurring: () -> Unit,
    onToggleInternal: () -> Unit,
    onQueryChange: (String) -> Unit,
    onAccountFilter: (String?) -> Unit,
    onOverrideCategory: (TransactionEntity, Category?) -> Unit,
    onAlwaysCategorise: (TransactionEntity, Category) -> Unit,
    onMarkTransfer: (TransactionEntity, Boolean) -> Unit,
    onMarkTransferGroup: (TransactionEntity, Boolean) -> Unit = { _, _ -> },
    onRestoreCategory: (TransactionEntity, String?) -> Unit = { _, _ -> },
    onMarkCardPayment: (TransactionEntity, Boolean) -> Unit,
    onCategoryFilter: (Category?) -> Unit,
    onSetBudgetGoal: (Category, Long) -> Unit,
    /** Questions about payments read from notifications, shown above the list. */
    prompts: @Composable () -> Unit = {},
    onTreatAsBill: ((TransactionEntity) -> Unit)? = null,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tab = SpendingTab.entries[selected.coerceIn(0, SpendingTab.entries.lastIndex)]
    // The list's two switches follow the tab; they are toggles, so flip only what differs.
    LaunchedEffect(tab, state.showRecurringOnly, state.showInternalTransfers) {
        if (tab == SpendingTab.INSIGHTS) return@LaunchedEffect
        if (state.showRecurringOnly != tab.recurring) onToggleRecurring()
        if (state.showInternalTransfers != tab.transfers) onToggleInternal()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DotTabs(
            tabs = SpendingTab.entries.map { it.label },
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        )
        when (tab) {
            SpendingTab.INSIGHTS -> Unit
            else -> Column {
                prompts()
                TransactionsScreen(
                state = state,
                onRefresh = onRefresh,
                onToggleRecurring = onToggleRecurring,
                onToggleInternal = onToggleInternal,
                onQueryChange = onQueryChange,
                onAccountFilter = onAccountFilter,
                onOverrideCategory = onOverrideCategory,
                onAlwaysCategorise = onAlwaysCategorise,
                onMarkTransfer = onMarkTransfer,
                onMarkTransferGroup = onMarkTransferGroup,
                onRestoreCategory = onRestoreCategory,
                onMarkCardPayment = onMarkCardPayment,
                onCategoryFilter = onCategoryFilter,
                onTreatAsBill = onTreatAsBill,
                pendingOnly = tab.pending,
                )
            }
        }
        when (tab) {
            SpendingTab.INSIGHTS -> InsightsScreen(
                state = state,
                onSetBudgetGoal = onSetBudgetGoal,
            )
            else -> Unit
        }
    }
}
