package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paying a card produces two rows for one event. Which of them may be hidden depends on how
 * the pairing was established: a settlement whose paying debit was actually found is a
 * duplicate and can go, one arrived at by guesswork might be something else entirely.
 */
class CardSettlementVisibilityTest {

    private val today = LocalDate.of(2026, 9, 28)

    private val card = AccountEntity(
        id = "card",
        institutionName = "NatWest",
        label = "Nectar Credit Card",
        currency = "GBP",
        balanceMinor = -85580,
        lastSynced = 0L,
        accountType = AccountType.CREDIT_CARD,
    )

    private val bank = AccountEntity(
        id = "bank",
        institutionName = "NatWest",
        label = "Personal Account",
        currency = "GBP",
        balanceMinor = 300000,
        lastSynced = 0L,
        accountType = AccountType.PERSONAL,
    )

    private var seq = 0

    private fun tx(account: String, date: String, amountMinor: Long, payee: String) =
        TransactionEntity(
            accountId = account,
            transactionId = "tx-${seq++}",
            bookingDate = date,
            valueDate = null,
            amountMinor = amountMinor,
            currency = "GBP",
            payee = payee,
            description = null,
            isPending = false,
            rawJson = null,
        )

    @Test
    fun `a settlement with its paying debit found is safe to hide`() {
        val settlement = tx("card", "2026-09-15", 95778, "DIRECT DEBIT PAYMENT")
        val payer = tx("bank", "2026-09-15", -95778, "NATWEST BANK PLC")

        val analysis = CreditCardEngine.analyze(listOf(settlement, payer), listOf(card, bank), today)

        assertTrue("card|${settlement.transactionId}" in analysis.confirmedSettlementKeys)
        // And the debit is named as the real outflow, so it is never hidden with it.
        assertTrue("bank|${payer.transactionId}" in analysis.cardPaymentKeys)
    }

    /**
     * With no paying debit anywhere, the credit was identified by guesswork. It is still
     * treated as a bill for the cycle, but it is not evidence enough to remove a row.
     */
    @Test
    fun `a settlement arrived at by guesswork is not`() {
        val settlement = tx("card", "2026-09-15", 95778, "DIRECT DEBIT PAYMENT")

        val analysis = CreditCardEngine.analyze(listOf(settlement), listOf(card), today)

        assertTrue("card|${settlement.transactionId}" in analysis.cardSettlementKeys)
        assertEquals(emptySet<String>(), analysis.confirmedSettlementKeys)
    }

    /** Hiding the credit must not touch what the budget counts. */
    @Test
    fun `the paying debit is still the spending either way`() {
        val transactions = listOf(
            tx("card", "2026-09-15", 95778, "DIRECT DEBIT PAYMENT"),
            tx("bank", "2026-09-15", -95778, "NATWEST BANK PLC"),
            tx("bank", "2026-09-16", -2000, "TESCO STORES"),
        )

        val budget = BudgetEngine.snapshot(
            transactions = transactions,
            rules = RecurringAnalyzer.analyze(transactions),
            accounts = listOf(card, bank),
            referenceTime = today.atStartOfDay(),
        )

        assertEquals(1, budget.confirmedSettlementKeys.size)
        assertEquals(1, budget.cardPayerKeys.size)
        // The two sets never overlap: one is on the card, the other on the account.
        assertTrue((budget.confirmedSettlementKeys intersect budget.cardPayerKeys).isEmpty())
    }
}
