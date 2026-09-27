package com.spendroid.data

import com.spendroid.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A sync rebuilds every transaction in the 90-day window from the bank's payload, and the
 * upsert replaces the stored row outright. Anything the user decided about a transaction
 * exists nowhere else, so if it is not carried across here it is gone.
 */
class PreserveUserEditsTest {

    private fun tx(
        id: String,
        payee: String = "TESCO STORES",
        transfer: Boolean = false,
        recurring: Boolean = false,
        category: String? = null,
        overridden: Boolean = false,
    ) = TransactionEntity(
        accountId = "acc-1",
        transactionId = id,
        bookingDate = "2026-09-20",
        valueDate = null,
        amountMinor = -2460,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = false,
        rawJson = null,
        isInternalTransfer = transfer,
        isRecurring = recurring,
        categoryOverride = category,
        transferOverridden = overridden,
    )

    @Test
    fun `a hand-set category survives the next sync`() {
        val merged = GoCardlessRepository.preserveUserEdits(
            fetched = listOf(tx("tx-1")),
            existing = listOf(tx("tx-1", category = "WORK_LUNCH", transfer = true, recurring = true)),
        )

        val row = merged.single()
        assertEquals("WORK_LUNCH", row.categoryOverride)
        assertEquals(true, row.isInternalTransfer)
        assertEquals(true, row.isRecurring)
    }

    @Test
    fun `the bank still wins on the facts it owns`() {
        val merged = GoCardlessRepository.preserveUserEdits(
            fetched = listOf(tx("tx-1", payee = "TESCO STORES 3241")),
            existing = listOf(tx("tx-1", payee = "TESCO", category = "GROCERIES")),
        )

        // The payee was corrected by the bank; the category was chosen by the user.
        assertEquals("TESCO STORES 3241", merged.single().payee)
        assertEquals("GROCERIES", merged.single().categoryOverride)
    }

    @Test
    fun `a transaction seen for the first time keeps its defaults`() {
        val merged = GoCardlessRepository.preserveUserEdits(
            fetched = listOf(tx("tx-new")),
            existing = listOf(tx("tx-old", category = "GROCERIES")),
        )

        assertNull(merged.single().categoryOverride)
        assertFalse(merged.single().isInternalTransfer)
    }

    @Test
    fun `nothing stored means nothing to carry`() {
        val fetched = listOf(tx("tx-1"), tx("tx-2"))
        assertEquals(fetched, GoCardlessRepository.preserveUserEdits(fetched, emptyList()))
    }

    /**
     * Detection only ever set the transfer flag and never cleared it, so un-marking a
     * transfer lasted until the next sync put it back. The decision has to be recorded as
     * the user's, not just as a value, or it cannot survive being recomputed.
     */
    @Test
    fun `a transfer the user decided for themselves is marked as theirs`() {
        val merged = GoCardlessRepository.preserveUserEdits(
            fetched = listOf(tx("tx-1")),
            existing = listOf(tx("tx-1", transfer = false, overridden = true)),
        )

        assertEquals(true, merged.single().transferOverridden)
        assertEquals(false, merged.single().isInternalTransfer)
    }

    /** A pending row that books under a new id keeps the category it was given while pending. */
    @Test
    fun `edits follow a pending row to its booked twin`() {
        fun row(id: String, pending: Boolean, date: String, category: String? = null) = com.spendroid.data.db.TransactionEntity(
            accountId = "a", transactionId = id, bookingDate = date, valueDate = null, amountMinor = -450,
            currency = "GBP", payee = "COSTA  COFFEE", description = null, isPending = pending, rawJson = null,
            categoryOverride = category,
        )
        val stored = listOf(row("p1", pending = true, date = "2026-09-25", category = "EATING_OUT"))
        val fetched = listOf(row("b9", pending = false, date = "2026-09-26"))
        val result = GoCardlessRepository.preserveUserEdits(fetched, stored).single()
        org.junit.Assert.assertEquals("b9", result.transactionId)
        org.junit.Assert.assertEquals("EATING_OUT", result.categoryOverride)
    }
}
