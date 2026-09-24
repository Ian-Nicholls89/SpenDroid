package com.spendroid.data

import com.spendroid.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of a move between the user's own accounts, and what the user's own say on
 * either half does to the pairing.
 */
class TransferPairingTest {

    private fun tx(
        account: String,
        id: String,
        amountMinor: Long,
        payee: String,
        transfer: Boolean = false,
        overridden: Boolean = false,
    ) = TransactionEntity(
        accountId = account,
        transactionId = id,
        bookingDate = "2026-09-01",
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = false,
        rawJson = null,
        isInternalTransfer = transfer,
        transferOverridden = overridden,
    )

    private fun pairs(vararg txs: TransactionEntity) =
        GoCardlessRepository.detectInternalTransfers(
            GoCardlessRepository.transferCandidates(txs.toList(), excludedAccounts = emptySet()),
        )

    /**
     * The reported bug: a £500 move to savings whose receiving side had been marked as a
     * transfer by hand. Leaving that side out of pairing left the sending side alone, and
     * counted as a £500 monthly commitment.
     */
    @Test
    fun `a half the user marked as a transfer still pairs with its other half`() {
        val out = tx("current", "a", -50000, "MONTHLY SAVINGS")
        val into = tx("savings", "b", 50000, "FROM CURRENT", transfer = true, overridden = true)
        val found = pairs(out, into)
        assertEquals(1, found.size)
        assertTrue(found.single().toList().any { it.transactionId == "a" })
    }

    /** What 2.24 was protecting: a row the user said is not a transfer is never paired. */
    @Test
    fun `a half the user said is not a transfer is left alone`() {
        val out = tx("current", "a", -50000, "MONTHLY SAVINGS")
        val refund = tx("savings", "b", 50000, "FROM CURRENT", transfer = false, overridden = true)
        assertTrue(pairs(out, refund).isEmpty())
    }
}
