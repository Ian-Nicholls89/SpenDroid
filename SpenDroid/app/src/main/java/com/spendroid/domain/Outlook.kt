package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate

/**
 * What's ahead, worked out once for everything that shows it - Home, the widgets, the alerts:
 * the budget account's balance to payday, any account that would dip below zero in the next
 * month, and each bill's latest change in price.
 */
data class Outlook(
    /** The account the budget is paid from, to the day after payday. */
    val ahead: Forecast.Result?,
    val payday: LocalDate?,
    val warnings: List<Forecast.Warning>,
    val priceChanges: Map<String, PriceChanges.Change>,
    /** Every regular payment out, as a subscription or a household bill, by rule key. */
    val subscriptions: Map<String, Subscriptions.Item> = emptyMap(),
    /** Bills with a contract end date, where each stands, by [Contracts.keyFor] its payee. */
    val contracts: Map<String, Contracts.Entry> = emptyMap(),
) {
    companion object {
        val EMPTY = Outlook(null, null, emptyList(), emptyMap())

        fun of(
            snapshot: BudgetSnapshot?,
            accounts: List<AccountEntity>,
            rules: List<RecurringRule>,
            ignored: Set<String>,
            transactions: List<TransactionEntity>,
            today: LocalDate,
            includeVariable: Boolean,
            calendar: WorkingDayCalendar = WorkingDayCalendar(),
            overrides: Map<String, com.spendroid.data.db.RuleOverrideEntity> = emptyMap(),
            subscriptionKinds: Map<String, Subscriptions.Kind> = emptyMap(),
            contractEnds: Map<String, LocalDate> = emptyMap(),
            acceptedContracts: Set<String> = emptySet(),
        ): Outlook {
            val active = rules.filter { it.key !in ignored }
            val payday = snapshot?.nextIncomeDate
            val pot = accounts.firstOrNull { it.id == snapshot?.potAccountId }
                ?: Forecast.forecastable(accounts).firstOrNull { it.accountType == com.spendroid.data.db.AccountType.PERSONAL }
            val ahead = pot?.let {
                Forecast.forAccount(it, accounts, active, ignored, snapshot, transactions, today, (payday ?: today.plusDays(30)).plusDays(1), calendar, overrides)
            }
            return Outlook(
                ahead = ahead,
                payday = payday,
                warnings = Forecast.warnings(accounts, active, ignored, snapshot, transactions, today, today.plusDays(WARN_DAYS), calendar, overrides),
                priceChanges = PriceChanges.all(active, transactions, today, includeVariable),
                // Switched-off ones too: a cancelled subscription is switched off, and still watched.
                subscriptions = Subscriptions.all(rules, transactions, accounts, subscriptionKinds, today),
                contracts = Contracts.all(rules, transactions, contractEnds, acceptedContracts, today),
            )
        }

        /** How far ahead a dip below zero is worth warning of: a month, so there's time to act. */
        const val WARN_DAYS = 31L
    }
}
