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
        description: String? = null,
    ) = TransactionEntity(
        accountId = account,
        transactionId = id,
        bookingDate = "2026-09-01",
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = description,
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

    /**
     * The same money, two ways out: £500 to savings and £500 to the joint account on the same
     * day, and one £500 arriving in savings. The joint account is not a pairing candidate, so
     * both debits could claim the one credit.
     */
    private val toJoint = tx("current", "j", -50000, "JOINT ACCOUNT BILLS", description = "JOINT BILLS")
    private val toSavings = tx("current", "s", -50000, "MONTHLY SAVINGS", description = "SAVINGS TOP UP")
    private val intoSavings = tx("savings", "in", 50000, "FROM CURRENT", description = "SAVINGS TOP UP")

    /**
     * The reported flip. Pairing used to walk the accounts in the order they last synced and
     * take the first debit with a single candidate - so the joint payment, listed first, took
     * the savings credit, and the real transfer was left counted as a £500 commitment. Which
     * one won changed with the sync order, from one refresh to the next.
     */
    @Test
    fun `a credit two debits could claim goes to the one its reference names`() {
        val found = pairs(toJoint, toSavings, intoSavings)
        assertEquals(setOf("s", "in"), found.single().toList().map { it.transactionId }.toSet())
    }

    @Test
    fun `the answer does not depend on the order the accounts arrive in`() {
        val forwards = pairs(toJoint, toSavings, intoSavings).map { p -> p.toList().map { it.transactionId }.toSet() }
        val backwards = pairs(intoSavings, toSavings, toJoint).map { p -> p.toList().map { it.transactionId }.toSet() }
        assertEquals(forwards, backwards)
    }

    /** With nothing to tell them apart, guessing would hide real spending, so neither pairs. */
    @Test
    fun `two equal claims with nothing to separate them pair neither`() {
        val a = tx("current", "a", -50000, "PAYMENT")
        val b = tx("current", "b", -50000, "PAYMENT")
        val c = tx("savings", "c", 50000, "RECEIVED")
        assertTrue(pairs(a, b, c).isEmpty())
    }

    /**
     * Marking a regular payment once covers every occurrence, including next month's, which
     * does not exist yet when the choice is made. A single row the user said is not a
     * transfer still keeps their word.
     */
    @Test
    fun `a marked regular payment covers every occurrence but a row the user excepted`() {
        val group = com.spendroid.domain.RecurringAnalyzer.groupKey(toSavings)
        val july = toSavings.copy(transactionId = "jul", bookingDate = "2026-07-01")
        val august = toSavings.copy(transactionId = "aug", bookingDate = "2026-08-01")
        val excepted = toSavings.copy(transactionId = "x", bookingDate = "2026-06-01", transferOverridden = true)

        val covered = GoCardlessRepository.inTransferGroups(listOf(july, august, excepted, toJoint), setOf(group))
        assertEquals(setOf("jul", "aug"), covered.map { it.transactionId }.toSet())
    }
}
