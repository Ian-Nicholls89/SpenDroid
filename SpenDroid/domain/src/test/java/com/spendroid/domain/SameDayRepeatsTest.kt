package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two identical payments on the same day each month - £50 to Beanstalk for each of two
 * children. Grouped by date, the pair read as one £50 payment, so the budget was £50 short.
 */
class SameDayRepeatsTest {

    private var seq = 0
    private fun tx(payee: String, amountMinor: Long, date: LocalDate) = TransactionEntity(
        accountId = "current",
        transactionId = "t${seq++}",
        bookingDate = date.toString(),
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = false,
        rawJson = null,
    )

    private val beanstalk = (6..8).flatMap { m ->
        List(2) { tx("BEANSTALK", -5000, LocalDate.of(2026, m, 19)) }
    }
    private val salary = (6..8).map { m -> tx("ACME LTD SALARY", 250000, LocalDate.of(2026, m, 28)) }

    @Test
    fun `two a day is one rule for both`() {
        val rule = RecurringAnalyzer.analyze(beanstalk).single()
        assertEquals(-10000L, rule.amountMinor)
        assertEquals(2, rule.perOccurrence)
    }

    @Test
    fun `the budget takes both`() {
        val all = beanstalk + salary
        val s = BudgetEngine.snapshot(
            transactions = all,
            rules = RecurringAnalyzer.analyze(all),
            accounts = listOf(AccountEntity("current", "NatWest", "Current", "GBP", 100000, 0L, AccountType.PERSONAL)),
            referenceTime = LocalDateTime.of(2026, 9, 10, 12, 0),
        )
        assertEquals(10000L, s.fixedMonthlyOutgoings)
        assertEquals(10000L, s.upcomingFixed.single { it.rule.payee == "BEANSTALK" }.amountMinor)
    }

    @Test
    fun `one a day stays one`() {
        val single = (6..8).map { m -> tx("NETFLIX", -1099, LocalDate.of(2026, m, 3)) }
        assertEquals(1, RecurringAnalyzer.analyze(single).single().perOccurrence)
    }
}
