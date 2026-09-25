package com.spendroid.data

import com.spendroid.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported case: the bank showed the salary as booked, the app still showed it pending,
 * two syncs later. Some banks keep listing a payment as pending after it books.
 */
class PendingTwinTest {

    private fun tx(id: String, date: String, amount: Long = 183689, payee: String = "UKHSA", pending: Boolean) =
        TransactionEntity(
            accountId = "current",
            transactionId = id,
            bookingDate = date,
            valueDate = null,
            amountMinor = amount,
            currency = "GBP",
            payee = payee,
            description = null,
            isPending = pending,
            rawJson = null,
        )

    /** Same id in both lists: the pending copy used to overwrite the booked one every sync. */
    @Test
    fun `a pending copy under the booked id is dropped`() {
        val booked = listOf(tx("salary", "2026-09-25", pending = false))
        val pending = listOf(tx("salary", "2026-09-24", pending = true))
        assertTrue(GoCardlessRepository.stillPendingOnly(booked, pending).isEmpty())
    }

    /** A new id for the booked version: the stale pending one used to sit beside it for good. */
    @Test
    fun `a pending copy under its own id is dropped once its booked twin arrives`() {
        val booked = listOf(tx("b-1", "2026-09-25", pending = false))
        val pending = listOf(tx("p-1", "2026-09-24", pending = true))
        assertTrue(GoCardlessRepository.stillPendingOnly(booked, pending).isEmpty())
    }

    @Test
    fun `genuinely pending payments stay`() {
        val booked = listOf(tx("b-1", "2026-09-25", pending = false))
        val pending = listOf(
            tx("p-2", "2026-09-25", amount = -1486, payee = "PORTON STORES", pending = true),
            // Same payee and amount, but a month on: a different payment.
            tx("p-3", "2026-10-24", pending = true),
        )
        assertEquals(listOf("p-2", "p-3"), GoCardlessRepository.stillPendingOnly(booked, pending).map { it.transactionId })
    }

    /** Two identical coffees, one booked: only one pending copy goes. */
    @Test
    fun `one booked twin clears one pending copy, not every lookalike`() {
        val booked = listOf(tx("b-1", "2026-09-25", amount = -450, payee = "PRET", pending = false))
        val pending = listOf(
            tx("p-1", "2026-09-25", amount = -450, payee = "PRET", pending = true),
            tx("p-2", "2026-09-25", amount = -450, payee = "PRET", pending = true),
        )
        assertEquals(1, GoCardlessRepository.stillPendingOnly(booked, pending).size)
    }
}
