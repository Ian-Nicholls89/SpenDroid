package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refund arrives on a card as a positive amount, exactly like a bill payment does. Reading
 * one as the other is expensive: it corrupts the statement cycle, moves what counts as
 * settled, and puts a refund in the list as though it were money earned.
 */
class CardRefundTest {

    private val today = LocalDate.of(2026, 9, 28)

    private val card = AccountEntity(
        id = "card",
        institutionName = "Tesco Bank",
        label = "Tesco Credit Card",
        currency = "GBP",
        balanceMinor = -5000,
        lastSynced = 0L,
        accountType = AccountType.CREDIT_CARD,
    )

    private val bank = AccountEntity(
        id = "bank",
        institutionName = "NatWest",
        label = "Everyday",
        currency = "GBP",
        balanceMinor = 200000,
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

    /**
     * The reported hole. With no account linked, the old rule took the largest credit each
     * month - and a returned purchase is easily the largest credit in a quiet month.
     */
    @Test
    fun `a refund is not mistaken for a bill payment`() {
        val transactions = listOf(
            tx("card", "2026-09-02", -8000, "CURRYS PC WORLD"),
            tx("card", "2026-09-12", 8000, "CURRYS PC WORLD REFUND"),
            tx("card", "2026-09-20", -5000, "TESCO STORES"),
        )

        val analysis = CreditCardEngine.analyze(transactions, listOf(card), today)

        // Nothing here settles a bill, so nothing may be named as settling one.
        assertTrue(analysis.cardSettlementKeys.isEmpty())
    }

    /** The other leg on a real account is what makes a credit a payment. */
    @Test
    fun `a credit matching a debit on a real account is a payment`() {
        val transactions = listOf(
            tx("card", "2026-09-02", -8000, "CURRYS PC WORLD"),
            tx("card", "2026-09-12", 8000, "CURRYS PC WORLD REFUND"),
            tx("card", "2026-09-15", 12000, "PAYMENT RECEIVED"),
            tx("bank", "2026-09-15", -12000, "TESCO BANK CREDIT CARD"),
        )

        val analysis = CreditCardEngine.analyze(transactions, listOf(card, bank), today)
        val settled = transactions.filter { "card|${it.transactionId}" in analysis.cardSettlementKeys }

        assertEquals(listOf(12000L), settled.map { it.amountMinor })
    }

    /**
     * The paying account no longer has to be linked by hand. Requiring the link sent an
     * unlinked card straight to guessing, which is where refunds got caught.
     */
    @Test
    fun `the paying account is found without being linked`() {
        val transactions = listOf(
            tx("card", "2026-09-15", 12000, "PAYMENT RECEIVED"),
            tx("bank", "2026-09-14", -12000, "TESCO BANK CREDIT CARD"),
        )

        val analysis = CreditCardEngine.analyze(transactions, listOf(card, bank), today)

        assertTrue(analysis.cardPaymentKeys.contains("bank|${transactions[1].transactionId}"))
        assertTrue(analysis.cardSettlementKeys.contains("card|${transactions[0].transactionId}"))
    }

    /** A refund is not earnings, and it is not a bill either. */
    @Test
    fun `a refund on a card is categorised by who sent it, not as salary`() {
        val refund = tx("card", "2026-09-12", 8000, "AMAZON REFUND")

        assertEquals(Category.SALARY, CategoryEngine.classify(refund))
        assertEquals(
            Category.SHOPPING,
            CategoryEngine.classify(
                refund,
                emptyList(),
                emptySet(),
                creditCardAccountIds = setOf("card"),
            ),
        )
    }

    /** Salary into a current account is still salary. */
    @Test
    fun `income into a real account is untouched`() {
        val salary = tx("bank", "2026-09-25", 250000, "UKHSA SALARY")
        assertEquals(
            Category.SALARY,
            CategoryEngine.classify(salary, emptyList(), emptySet(), setOf("card")),
        )
    }

    /**
     * With no paying debit anywhere and nothing said by the user, a credit is simply
     * unexplained. It used to be guessed into a bill on the strength of being the largest
     * that month; now it is passed over, which is the honest answer.
     */
    @Test
    fun `an unexplained credit is passed over rather than guessed`() {
        val transactions = listOf(
            tx("card", "2026-09-02", -8000, "CURRYS PC WORLD"),
            tx("card", "2026-09-12", 8000, "CURRYS PC WORLD REFUND"),
            tx("card", "2026-09-15", 12000, "PAYMENT RECEIVED"),
        )

        val analysis = CreditCardEngine.analyze(transactions, listOf(card), today)

        assertTrue(analysis.cardSettlementKeys.isEmpty())
    }

    /** Saying so is enough, and is as good as finding the other leg. */
    @Test
    fun `a credit the user declares a bill payment counts as one`() {
        val declared = tx("card", "2026-09-15", 12000, "PAYMENT RECEIVED")
            .copy(isCardPayment = true)
        val transactions = listOf(
            tx("card", "2026-09-02", -8000, "CURRYS PC WORLD"),
            tx("card", "2026-09-12", 8000, "CURRYS PC WORLD REFUND"),
            declared,
        )

        val analysis = CreditCardEngine.analyze(transactions, listOf(card), today)

        assertEquals(setOf("card|${declared.transactionId}"), analysis.cardSettlementKeys)
        assertEquals(setOf("card|${declared.transactionId}"), analysis.confirmedSettlementKeys)
    }

    /**
     * Marking one payment has to settle the rest, or the same answer is wanted every month.
     * A bill is named by the bank, so its wording repeats; the declaration travels with it.
     */
    @Test
    fun `declaring one payment identifies every later one worded the same`() {
        val transactions = listOf(
            tx("card", "2026-07-15", 40000, "DIRECT DEBIT PAYMENT").copy(isCardPayment = true),
            tx("card", "2026-08-15", 52000, "DIRECT DEBIT PAYMENT"),
            tx("card", "2026-09-15", 31000, "DIRECT DEBIT PAYMENT"),
        )

        val analysis = CreditCardEngine.analyze(transactions, listOf(card), today)

        assertEquals(3, analysis.cardSettlementKeys.size)
    }

    /** But not a refund, which carries the merchant's name rather than the bank's. */
    @Test
    fun `a differently worded credit is not swept up by the declaration`() {
        val transactions = listOf(
            tx("card", "2026-08-15", 52000, "DIRECT DEBIT PAYMENT").copy(isCardPayment = true),
            tx("card", "2026-09-02", -8000, "CURRYS PC WORLD"),
            tx("card", "2026-09-12", 8000, "CURRYS PC WORLD REFUND"),
        )

        val analysis = CreditCardEngine.analyze(transactions, listOf(card), today)

        assertEquals(1, analysis.cardSettlementKeys.size)
        assertTrue(analysis.cardSettlementKeys.single().endsWith(transactions[0].transactionId))
    }
}
