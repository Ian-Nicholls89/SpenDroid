package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A pending transaction is money already committed: the card has been presented and the
 * funds are held. It was shown in the list looking like any other row while counting
 * towards nothing, so the figures lagged reality by however long the bank took to book it -
 * which is precisely the window in which knowing would have been useful.
 */
class PendingSpendingTest {

    private val now = LocalDateTime.of(2026, 9, 20, 12, 0)

    private val account = AccountEntity(
        id = "bank",
        institutionName = "NatWest",
        label = "Personal",
        currency = "GBP",
        balanceMinor = 150000,
        lastSynced = 0L,
        accountType = AccountType.PERSONAL,
    )

    private var seq = 0

    private fun tx(date: String, amountMinor: Long, payee: String, pending: Boolean = false) =
        TransactionEntity(
            accountId = "bank",
            transactionId = "tx-${seq++}",
            bookingDate = date,
            valueDate = null,
            amountMinor = amountMinor,
            currency = "GBP",
            payee = payee,
            description = null,
            isPending = pending,
            rawJson = null,
        )

    private fun spentWith(vararg transactions: TransactionEntity): Long {
        val all = listOf(
            tx("2026-08-25", 250000, "UKHSA"),
            tx("2026-09-25", 250000, "UKHSA"),
        ) + transactions
        return BudgetEngine.snapshot(
            transactions = all,
            rules = RecurringAnalyzer.analyze(all),
            accounts = listOf(account),
            referenceTime = now,
        ).spentThisCycle
    }

    @Test
    fun `a pending debit counts towards spending`() {
        val booked = spentWith(tx("2026-09-18", -5000, "TESCO STORES"))
        val pending = spentWith(tx("2026-09-18", -5000, "TESCO STORES", pending = true))

        assertEquals(5000L, booked)
        assertEquals(booked, pending)
    }

    @Test
    fun `booked and pending add up together rather than one hiding the other`() {
        val both = spentWith(
            tx("2026-09-17", -5000, "TESCO STORES"),
            tx("2026-09-18", -2000, "WILTON RESTAURANT", pending = true),
        )

        assertEquals(7000L, both)
    }
}
