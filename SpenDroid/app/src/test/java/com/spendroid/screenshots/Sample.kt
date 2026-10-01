package com.spendroid.screenshots

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.BudgetEngine
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.ui.RootUiState
import java.time.LocalDate

/** Made-up accounts and three months of made-up spending, for screenshots. Nobody's real figures. */
object Sample {
    val today: LocalDate = LocalDate.of(2026, 10, 1)

    val accounts = listOf(
        AccountEntity("acc-personal", "NatWest", "Personal Account", "GBP", 174_327, System.currentTimeMillis(), AccountType.PERSONAL),
        AccountEntity("acc-joint", "NatWest", "Joint Account", "GBP", 62_200, System.currentTimeMillis(), AccountType.JOINT),
        AccountEntity("acc-nectar", "Sainsbury's Bank", "Nectar Card", "GBP", -13_869, System.currentTimeMillis(), AccountType.CREDIT_CARD,
            statementDayOfMonth = 23, paymentDayOfMonth = 17),
    )

    private var seq = 0
    private fun tx(account: String, date: LocalDate, minor: Long, payee: String, pending: Boolean = false) = TransactionEntity(
        accountId = account, transactionId = "s${seq++}", bookingDate = date.toString(), valueDate = null,
        amountMinor = minor, currency = "GBP", payee = payee, description = null, isPending = pending, rawJson = null,
    )

    val transactions: List<TransactionEntity> = buildList {
        for (m in 6..9) {
            add(tx("acc-personal", LocalDate.of(2026, m, 25), 185_000, "EMPLOYER LTD"))
            add(tx("acc-personal", LocalDate.of(2026, m, 5), -2_499, "PUREGYM"))
            add(tx("acc-personal", LocalDate.of(2026, m, 19), -10_000, "BEANSTALK"))
            add(tx("acc-personal", LocalDate.of(2026, m, 20), -1_800, "VOXI MOBILE"))
            add(tx("acc-joint", LocalDate.of(2026, m, 8), -(8_400L + m * 300), "OCTOPUS ENERGY"))
            add(tx("acc-personal", LocalDate.of(2026, m, 17), -21_208, "SAINSBURYS BANK"))
            add(tx("acc-nectar", LocalDate.of(2026, m, 17), 21_208, "PAYMENT RECEIVED"))
        }
        add(tx("acc-personal", LocalDate.of(2026, 9, 26), -1_486, "PORTON STORES"))
        add(tx("acc-personal", LocalDate.of(2026, 9, 27), -3_520, "WILTSHIRE COUNCIL"))
        add(tx("acc-personal", LocalDate.of(2026, 9, 29), -250, "NAYAX"))
        add(tx("acc-personal", LocalDate.of(2026, 9, 30), -1_280, "TRAINLINE"))
        add(tx("acc-personal", today, -450, "PORTON STORES"))
        add(tx("acc-personal", today, -875, "COSTA COFFEE", pending = true))
        add(tx("acc-nectar", LocalDate.of(2026, 9, 27), -6_480, "LONGLEAT ENTERPRISES"))
        add(tx("acc-nectar", LocalDate.of(2026, 9, 26), -3_565, "SALT DELI KITCHEN"))
        add(tx("acc-nectar", today, -425, "GREGGS", pending = true))
        add(tx("acc-joint", LocalDate.of(2026, 9, 28), -4_505, "EBAY"))
    }.sortedByDescending { it.bookingDate }

    fun state(): RootUiState {
        val rules = RecurringAnalyzer.analyze(transactions)
        val budget = BudgetEngine.snapshot(transactions, rules, accounts, today.atTime(15, 8), budgetModel = BudgetModel.ROLLOVER)
        return RootUiState(
            hasCredentials = true,
            showRecurringOnly = false,
            accounts = accounts,
            transactions = transactions,
            budget = budget,
            rules = rules,
            budgetModel = BudgetModel.ROLLOVER,
            categoryHistory = CategoryEngine.history(transactions),
        )
    }
}
