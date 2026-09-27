package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A direct debit due today is still to come until the bank shows it. */
class DueTodayTest {

    private var seq = 0
    private fun tx(date: String, amountMinor: Long, payee: String) = TransactionEntity(
        accountId = "current",
        transactionId = "t${seq++}",
        bookingDate = date,
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = false,
        rawJson = null,
    )

    private val current = AccountEntity(
        id = "current", institutionName = "NatWest", label = "Current", currency = "GBP",
        balanceMinor = 100000, lastSynced = 0L, accountType = AccountType.PERSONAL,
    )

    private val history = listOf("2026-06-25", "2026-07-24", "2026-08-25", "2026-09-25")
        .map { tx(it, 250000, "ACME SALARY") } +
        listOf("2026-07-14", "2026-08-14", "2026-09-14").map { tx(it, -3500, "OCTOPUS ENERGY") }

    private fun upcoming(today: LocalDate, extra: List<TransactionEntity> = emptyList()) =
        (history + extra).let { all ->
            BudgetEngine.snapshot(
                transactions = all,
                rules = RecurringAnalyzer.analyze(all),
                accounts = listOf(current),
                referenceTime = today.atTime(9, 0),
            ).upcomingFixed.filter { it.rule.payee == "OCTOPUS ENERGY" }
        }

    @Test
    fun `due today and not yet taken, it is still to come`() {
        val due = upcoming(LocalDate.of(2026, 10, 14)).single()
        assertEquals(LocalDate.of(2026, 10, 14), due.dueDate)
    }

    @Test
    fun `once it shows, it is not`() {
        val taken = tx("2026-10-14", -3500, "OCTOPUS ENERGY")
        assertTrue(upcoming(LocalDate.of(2026, 10, 14), listOf(taken)).none { it.dueDate == LocalDate.of(2026, 10, 14) })
    }
}
