package com.spendroid.ui

import com.spendroid.AppScreen
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.TabVoice

/**
 * What Penny says on [screen] (0.7.0), from what's on the phone: Spending, Accounts and Regular
 * have her [TabVoice] lines; Home has her own ([pennyLines]); Settings, none.
 */
internal fun tabLines(state: RootUiState, screen: AppScreen): List<TabVoice.Line> {
    val budget = state.budget
    val money = { minor: Long -> formatMoney(minor, budget?.baseCurrency ?: "GBP") }
    return when (screen) {
        AppScreen.Spending -> budget?.let { b ->
            TabVoice.spending(b, state.transactions, money) { tx ->
                CategoryEngine.classify(tx, state.categoryRules, b.cardPaymentKeys, b.creditCardAccountIds)
            }
        }.orEmpty()
        AppScreen.Rules -> budget?.let { TabVoice.regular(it, state.outlook, money) }.orEmpty()
        AppScreen.Accounts -> TabVoice.accounts(budget, state.accounts, state.connections, state.manualCoverage, budget?.asOf ?: java.time.LocalDate.now(), money)
        else -> emptyList()
    }
}

/** Whether Penny is talking on [screen]: if so, she isn't in the banner too. */
internal fun pennyTalks(state: RootUiState, screen: AppScreen): Boolean = when (screen) {
    AppScreen.Home -> state.budget != null
    AppScreen.Settings -> false
    else -> tabLines(state, screen).isNotEmpty()
}
