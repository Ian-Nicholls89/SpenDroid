package com.spendroid.domain

import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A card bill is the one real cash outflow in the card model, so it has to be visible as
 * itself. Left to the keyword list it is filed under whoever issued the card.
 */
class CardBillCategoryTest {

    private fun tx(
        payee: String,
        amountMinor: Long = -12000,
        id: String = "tx-1",
        override: String? = null,
    ) = TransactionEntity(
        accountId = "bank",
        transactionId = id,
        bookingDate = "2026-09-20",
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = false,
        rawJson = null,
        categoryOverride = override,
    )

    /** The bug this category fixes: a card named after a supermarket is not a shop. */
    @Test
    fun `a card issued by a supermarket is not groceries`() {
        assertEquals(Category.CARD_BILL, CategoryEngine.classify(tx("TESCO BANK CREDIT CARD")))
        assertEquals(Category.GROCERIES, CategoryEngine.classify(tx("TESCO STORES 3241")))
    }

    @Test
    fun `the usual ways a bank writes a card bill are recognised`() {
        listOf(
            "BARCLAYCARD",
            "CREDIT CARD PAYMENT",
            "AMEX PAYMENT",
            "NATWEST CARD PAYMENT",
        ).forEach { payee ->
            assertEquals(payee, Category.CARD_BILL, CategoryEngine.classify(tx(payee)))
        }
    }

    /**
     * The point of taking the keys from CreditCardEngine: a payment it matched to a card is
     * a card bill whatever the bank called it, and banks call it anything.
     */
    @Test
    fun `a payment matched to a card wins over whatever the bank called it`() {
        val payment = tx("TRANSFER TO 4929", id = "tx-9")
        val keys = setOf("bank|tx-9")

        // Left to keywords it reads as a plain transfer, which is what it looks like.
        assertEquals(Category.TRANSFERS, CategoryEngine.classify(payment))
        assertEquals(Category.CARD_BILL, CategoryEngine.classify(payment, emptyList(), keys))
    }

    /** A correction made by hand still wins, as it does for every other category. */
    @Test
    fun `a hand-set category beats the structural match`() {
        val payment = tx("TRANSFER TO 4929", id = "tx-9", override = "TRANSFERS")
        assertEquals(
            Category.TRANSFERS,
            CategoryEngine.classify(payment, emptyList(), setOf("bank|tx-9")),
        )
    }

    @Test
    fun `a user rule still beats the keyword list`() {
        val rules = listOf(CategoryRuleEntity("barclaycard", "BILLS", 0L))
        assertEquals(Category.BILLS, CategoryEngine.classify(tx("BARCLAYCARD"), rules))
    }

    @Test
    fun `the breakdown reports card bills as their own line`() {
        val transactions = listOf(
            tx("TESCO STORES 3241", amountMinor = -8765, id = "a"),
            tx("TRANSFER TO 4929", amountMinor = -10946, id = "b"),
        )
        val totals = CategoryEngine.spendingBreakdown(
            transactions,
            emptyList(),
            cardPaymentKeys = setOf("bank|b"),
        )

        assertEquals(10946L, totals.first { it.category == Category.CARD_BILL }.amountMinor)
        assertEquals(8765L, totals.first { it.category == Category.GROCERIES }.amountMinor)
    }
}
