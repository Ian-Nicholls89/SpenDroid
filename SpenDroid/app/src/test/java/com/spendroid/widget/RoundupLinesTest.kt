package com.spendroid.widget

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.BudgetEngine
import com.spendroid.domain.RecurringAnalyzer
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The roundup as the watch shows it under its headline. */
class RoundupLinesTest {

    private var seq = 0
    private fun tx(payee: String, amountMinor: Long, date: LocalDate) = TransactionEntity(
        accountId = "bank",
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

    private val today = LocalDate.of(2026, 9, 30)
    private val all = (6..9).map { m -> tx("ACME SALARY", 250000, LocalDate.of(2026, m, 25)) } +
        (6..9).map { m -> tx("NETFLIX", -1099, LocalDate.of(2026, m, 1)) }

    private val snapshot = BudgetEngine.snapshot(
        all,
        RecurringAnalyzer.analyze(all),
        listOf(AccountEntity("bank", "NatWest", "Current", "GBP", 100000, 0L, AccountType.PERSONAL)),
        referenceTime = today.atTime(21, 0),
    )

    @Test
    fun `spent today, then income, then tomorrow's bills`() {
        val lines = WatchSync.roundupLines(snapshot, 0, today)
        assertTrue(lines.first().startsWith("Spent today "))
        assertTrue(lines[1], lines[1].startsWith("Income "))
        assertTrue(lines.any { it.startsWith("Tomorrow: NETFLIX") })
    }

    @Test
    fun `questions waiting are mentioned, in the singular when one`() {
        assertEquals("1 transaction to check on your phone", WatchSync.roundupLines(snapshot, 1, today).last())
        assertEquals("3 transactions to check on your phone", WatchSync.roundupLines(snapshot, 3, today).last())
        assertTrue(WatchSync.roundupLines(snapshot, 0, today).none { it.contains("to check") })
    }
}
