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
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.Category

private enum class SpendingTab(val label: String) {
    TRANSACTIONS("Transactions"),
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
    onOverrideCategory: (TransactionEntity, Category?) -> Unit,
    onAlwaysCategorise: (TransactionEntity, Category) -> Unit,
    onMarkTransfer: (TransactionEntity, Boolean) -> Unit,
    onSetBudgetGoal: (Category, Long) -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selected) {
            SpendingTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selected == index,
                    onClick = { selected = index },
                    text = { Text(tab.label) },
                )
            }
        }
        when (SpendingTab.entries[selected]) {
            SpendingTab.TRANSACTIONS -> TransactionsScreen(
                state = state,
                onRefresh = onRefresh,
                onToggleRecurring = onToggleRecurring,
                onToggleInternal = onToggleInternal,
                onQueryChange = onQueryChange,
                onOverrideCategory = onOverrideCategory,
                onAlwaysCategorise = onAlwaysCategorise,
                onMarkTransfer = onMarkTransfer,
            )
            SpendingTab.INSIGHTS -> InsightsScreen(
                state = state,
                onSetBudgetGoal = onSetBudgetGoal,
            )
        }
    }
}
