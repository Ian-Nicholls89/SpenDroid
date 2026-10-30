package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The day the pay is due, before it's in (0.8.0's bug check): the old cycle runs on, and what's
 * spent that day is its spending - it used to fall between the two cycles. Made-up figures.
 */
class LatePayDayTest {

    private var seq = 0
    private fun tx(account: String, date: String, amountMinor: Long, payee: String) = TransactionEntity(
        accountId = account, transactionId = "tx-${seq++}", bookingDate = date, valueDate = null, amountMinor = amountMinor,
        currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
    )

    @Test
    fun `spending on payday, before the pay is in, counts today`() {
        val accounts = listOf(AccountEntity("current", "NatWest", "Current", "GBP", 120000, 0L, AccountType.PERSONAL))
        // Paid on the 1st; 1 Nov 2026 is a Sunday, so the pay is due on Friday the 30th.
        val transactions = listOf("2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01").map { tx("current", it, 200000, "ACME LTD SALARY") } +
            tx("current", "2026-10-30", -1250, "CORNER SHOP")
        val s = BudgetEngine.snapshot(
            transactions = transactions, rules = RecurringAnalyzer.analyze(transactions), accounts = accounts,
            referenceTime = LocalDateTime.of(2026, 10, 30, 12, 0),
        )
        assertEquals(1250L, s.spentToday)
        assertEquals(1250L, s.spentTodayFromAccountsMinor)
        assertEquals(1250L, s.spentThisCycle)
    }
}
