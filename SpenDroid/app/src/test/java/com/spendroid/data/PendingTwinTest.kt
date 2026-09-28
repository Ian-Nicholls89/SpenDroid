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

    /** The user's Nectar card (28 Sep 2026): pending "GREGGS 4168", booked "GREGGS PLC AMESBURY". */
    @Test
    fun `a pending payment booked under a fuller name is dropped`() {
        val booked = listOf(tx("b", "2026-09-28", amount = -425, payee = "GREGGS PLC AMESBURY", pending = false))
        val pending = listOf(tx("p", "2026-09-28", amount = -425, payee = "GREGGS 4168", pending = true))
        assertTrue(GoCardlessRepository.stillPendingOnly(booked, pending).isEmpty())
    }

    /** Names that share nothing, but only one pairing is possible: still the same payment. */
    @Test
    fun `the only possible pairing is taken`() {
        val booked = listOf(tx("b", "2026-09-28", amount = -6480, payee = "LONGLEAT ENTERPRISES", pending = false))
        val pending = listOf(tx("p", "2026-09-25", amount = -6480, payee = "LEP WARMINSTER", pending = true))
        assertTrue(GoCardlessRepository.stillPendingOnly(booked, pending).isEmpty())
    }

    /** Two coffees of the same price, differently named, and one booked: which is which is not
     *  known, so neither pending one is dropped by guesswork. */
    @Test
    fun `an ambiguous pairing is left alone`() {
        val booked = listOf(tx("b", "2026-09-28", amount = -320, payee = "PRET", pending = false))
        val pending = listOf(
            tx("p1", "2026-09-28", amount = -320, payee = "COSTA", pending = true),
            tx("p2", "2026-09-28", amount = -320, payee = "NERO", pending = true),
        )
        assertEquals(2, GoCardlessRepository.stillPendingOnly(booked, pending).size)
    }
}
