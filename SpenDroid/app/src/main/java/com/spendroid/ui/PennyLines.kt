package com.spendroid.ui

import android.content.Context
import com.spendroid.data.db.AccountType
import com.spendroid.domain.PennyVoice
import com.spendroid.domain.PeriodRows
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.TrendsEngine
import java.time.LocalDateTime

/** Penny's lines for Home, from everything the app knows: see [PennyVoice]. */
internal fun pennyLines(state: RootUiState, now: LocalDateTime = LocalDateTime.now()): List<PennyVoice.Line> {
    val budget = state.budget ?: return emptyList()
    // The real dates the main income landed on, where each cycle began - as Insights has them.
    val cycleStarts = budget.primaryIncomeRule?.let { rule ->
        state.transactions.filter { RecurringAnalyzer.matches(rule, it) }
            .mapNotNull { RecurringAnalyzer.parseBookingDate(it.bookingDate) }.distinct().sorted()
    }.orEmpty()
    val trends = TrendsEngine.categoryTrends(
        transactions = state.transactions,
        userRules = state.categoryRules,
        cardPaymentKeys = budget.cardPaymentKeys,
        creditCardAccountIds = budget.creditCardAccountIds,
        cycleStarts = cycleStarts,
        currentCycleEnd = budget.cycleEnd,
        today = budget.asOf,
    )
    val cards = state.accounts.filter { it.accountType == AccountType.CREDIT_CARD }.let { cards ->
        PeriodRows.rows(cards.map { it.id }, budget, state.accounts, state.transactions).map { row ->
            PennyVoice.Card(cards.first { it.id == row.accountId }.label.trim(), row)
        }
    }
    return PennyVoice.lines(budget, now, { formatMoney(it, budget.baseCurrency) }, trends, cards)
}

/** Each time Home comes to the front: which of her top lines leads, so she doesn't repeat herself. */
internal object PennyVisits {
    private const val PREFS = com.spendroid.data.Stores.PENNY_VOICE
    private const val VISIT = "visit"

    fun next(context: Context): Int {
        val prefs = com.spendroid.data.Stores.prefs(context, PREFS)
        val n = prefs.getInt(VISIT, 0) + 1
        prefs.edit().putInt(VISIT, n).apply()
        return n
    }
}
