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
        AppScreen.Rules -> budget?.let { TabVoice.regular(it, state.outlook, money, state.renewals) }.orEmpty()
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

/**
 * What needs the user, wherever it's said (0.7.2) - a renewal, a bank link lapsing, a contract
 * ending, a statement to import: Penny says it on Home too, first. Two at most.
 */
internal fun needsYouLines(state: RootUiState): List<TabVoice.Line> =
    (tabLines(state, AppScreen.Rules) + tabLines(state, AppScreen.Accounts))
        .filter { it.needsYou }
        .sortedByDescending { it.rank }
        .take(2)

/** The words on a line's link. */
internal fun linkLabel(target: TabVoice.Target): String = when (target) {
    is TabVoice.Target.Relink -> "Renew"
    is TabVoice.Target.Import -> "Import"
    else -> "See it"
}

/** What opens a renewal on Regular: through the same way in as a bill's key. */
internal const val RENEWAL_LINK = "renewal:"
