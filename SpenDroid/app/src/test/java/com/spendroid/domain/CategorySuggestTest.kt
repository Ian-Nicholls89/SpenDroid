package com.spendroid.domain

import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two categories a swipe offers: right for the best alternative, left for the next.
 * An alternative to what is shown, since what is shown is already the first guess.
 */
class CategorySuggestTest {

    private fun tx(payee: String, amountMinor: Long = -450, override: String? = null, id: String = payee) =
        TransactionEntity(
            accountId = "current",
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

    @Test
    fun `how you filed this payee before comes first`() {
        // Pret reads as Eating out by keyword; this user files it as work lunches.
        val history = CategoryEngine.history(
            listOf(
                tx("PRET A MANGER", override = "WORK_LUNCH", id = "a"),
                tx("PRET A MANGER", override = "WORK_LUNCH", id = "b"),
            ),
        )
        val pret = tx("PRET A MANGER")
        assertEquals(Category.EATING_OUT, CategoryEngine.classify(pret))
        assertEquals(Category.WORK_LUNCH, CategoryEngine.suggest(pret, history = history).first())
    }

    @Test
    fun `what it is already shown as is never offered`() {
        val tesco = tx("TESCO STORES 3241")
        val suggestions = CategoryEngine.suggest(tesco)
        assertFalse(Category.GROCERIES in suggestions)
    }

    @Test
    fun `every matching keyword is a candidate, not just the first`() {
        // "Tesco" hits groceries first, and "petrol" hits transport.
        val fuel = tx("TESCO PETROL")
        assertEquals(Category.GROCERIES, CategoryEngine.classify(fuel))
        assertEquals(Category.TRANSPORT, CategoryEngine.suggest(fuel).first())
    }

    @Test
    fun `a rule of your own counts`() {
        val rules = listOf(CategoryRuleEntity("salt deli", "WORK_LUNCH", 0L))
        val deli = tx("SALT DELI KITCHEN LIMI")
        // The rule already decides what it is shown as, so it is not offered again; the
        // suggestions are still two real alternatives.
        assertEquals(Category.WORK_LUNCH, CategoryEngine.classify(deli, rules))
        val suggestions = CategoryEngine.suggest(deli, rules)
        assertTrue(suggestions.size >= 2)
        assertFalse(Category.WORK_LUNCH in suggestions)
    }

    @Test
    fun `there are always two, so both directions mean something`() {
        assertTrue(CategoryEngine.suggest(tx("ZZQ LTD 000123")).size >= 2)
        assertTrue(CategoryEngine.suggest(tx("A FRIEND", amountMinor = 2000)).size >= 2)
    }

    @Test
    fun `money in is offered as money in, and spending never as salary`() {
        val refund = CategoryEngine.suggest(tx("A FRIEND", amountMinor = 2000))
        assertTrue(refund.all { it in setOf(Category.TRANSFERS, Category.SALARY, Category.SAVINGS, Category.OTHER) })
        assertFalse(Category.SALARY in CategoryEngine.suggest(tx("ZZQ LTD 000123")))
    }
}
