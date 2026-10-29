package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The user's lunch (28 Sep 2026): £35.65 on the Nectar card, pending. Card spending is counted
 * at the bill, so the budget leaves it out - and "spent today" read £0.00, and the card
 * "£0.00 since statement". Both now show it, marked as pending, without it reaching the budget.
 */
class TodayOnCardsTest {

    private var seq = 0
    private fun tx(account: String, date: String, amount: Long, payee: String, pending: Boolean = false) =
        TransactionEntity(
            accountId = account, transactionId = "t${seq++}", bookingDate = date, valueDate = null, amountMinor = amount,
            currency = "GBP", payee = payee, description = null, isPending = pending, rawJson = null,
        )

    private val current = AccountEntity(
        id = "current", institutionName = "first direct", label = "Personal", currency = "GBP",
        balanceMinor = 100_000, lastSynced = 0, accountType = AccountType.PERSONAL,
    )
    private val nectar = AccountEntity(
        id = "nectar", institutionName = "NatWest", label = "Nectar", currency = "GBP",
        balanceMinor = -129_420, lastSynced = 0, accountType = AccountType.CREDIT_CARD, statementDayOfMonth = 23, paymentDayOfMonth = 17,
    )

    private val history = listOf("2026-07-25", "2026-08-25", "2026-09-25").map { tx("current", it, 250_000, "ACME") } +
        listOf(
            tx("nectar", "2026-09-10", -129_420, "BIG SHOP"),
            tx("current", "2026-09-28", -250, "LEISUREMAT", pending = true),
            tx("nectar", "2026-09-28", -3_565, "SALT DELI KITCHEN LIMI", pending = true),
            tx("nectar", "2026-09-28", -425, "GREGGS 4168", pending = true),
        )

    private fun snapshot() = BudgetEngine.snapshot(
        transactions = history,
        rules = RecurringAnalyzer.analyze(history),
        accounts = listOf(current, nectar),
        referenceTime = LocalDate.of(2026, 9, 28).atTime(14, 0),
    )

    @Test
    fun `today is split into accounts and cards, with the pending part`() {
        val s = snapshot()
        assertEquals(250L, s.spentTodayFromAccountsMinor)
        assertEquals(3_990L, s.spentTodayOnCardsMinor)
        assertEquals(3_990L, s.spentTodayOnCardsPendingMinor)
        assertEquals(4_240L, s.spentTodayPendingMinor)
        // The budget's own figure still counts the card at the bill.
        assertEquals(250L, s.spentToday)
    }

    @Test
    fun `pending card charges are on the card since the statement`() {
        val bill = snapshot().cardBills.single()
        assertEquals(3_990L, bill.unbilledMinor)
        assertEquals(3_990L, bill.pendingMinor)
    }
}
