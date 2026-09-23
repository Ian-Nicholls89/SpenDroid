package com.spendroid.domain

import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryEngineTest {

    private fun tx(payee: String, amountMinor: Long = -1200, override: String? = null) =
        TransactionEntity(
            accountId = "a",
            transactionId = payee,
            bookingDate = "2026-09-22",
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
    fun `a card payment is not entertainment`() {
        // "barclay" used to sit in the ENTERTAINMENT keyword list, so every Barclaycard
        // payment was filed as a night out.
        assertEquals(Category.OTHER, CategoryEngine.classify(tx("BARCLAYCARD VISA PAYMENT")))
        assertEquals(Category.OTHER, CategoryEngine.classify(tx("BARCLAYS BANK PLC")))
    }

    @Test
    fun `the bank is not a bus but the bikes still are`() {
        assertEquals(Category.OTHER, CategoryEngine.classify(tx("SANTANDER UK PLC")))
        assertEquals(Category.TRANSPORT, CategoryEngine.classify(tx("SANTANDER CYCLES LONDON")))
    }

    @Test
    fun `eating out is separated from entertainment`() {
        assertEquals(Category.EATING_OUT, CategoryEngine.classify(tx("PRET A MANGER 421")))
        assertEquals(Category.EATING_OUT, CategoryEngine.classify(tx("DELIVEROO")))
        assertEquals(Category.EATING_OUT, CategoryEngine.classify(tx("THE RED LION PUB")))
        assertEquals(Category.ENTERTAINMENT, CategoryEngine.classify(tx("NETFLIX.COM")))
        assertEquals(Category.ENTERTAINMENT, CategoryEngine.classify(tx("ODEON CINEMAS")))
    }

    @Test
    fun `donations are recognised`() {
        assertEquals(Category.CHARITY, CategoryEngine.classify(tx("JUSTGIVING DONATION")))
        assertEquals(Category.CHARITY, CategoryEngine.classify(tx("OXFAM SHOP")))
        assertEquals(Category.CHARITY, CategoryEngine.classify(tx("RNLI LIFEBOATS")))
    }

    @Test
    fun `groceries still win over eating out for a supermarket`() {
        assertEquals(Category.GROCERIES, CategoryEngine.classify(tx("TESCO STORES 3241")))
        assertEquals(Category.GROCERIES, CategoryEngine.classify(tx("SAINSBURYS S/MKT")))
    }

    @Test
    fun `work lunches are never guessed, only assigned`() {
        // No keyword list: a sandwich shop looks identical to dinner out.
        assertEquals(Category.EATING_OUT, CategoryEngine.classify(tx("PRET A MANGER 421")))

        val rule = listOf(CategoryRuleEntity("pret a manger", Category.WORK_LUNCH.name))
        assertEquals(Category.WORK_LUNCH, CategoryEngine.classify(tx("PRET A MANGER 421"), rule))
    }

    @Test
    fun `a user rule beats the built-in list and an override beats everything`() {
        val rule = listOf(CategoryRuleEntity("tesco", Category.EATING_OUT.name))
        assertEquals(Category.EATING_OUT, CategoryEngine.classify(tx("TESCO STORES"), rule))
        assertEquals(
            Category.CHARITY,
            CategoryEngine.classify(tx("TESCO STORES", override = Category.CHARITY.name), rule),
        )
    }

    @Test
    fun `the longer of two overlapping rules wins`() {
        val rules = listOf(
            CategoryRuleEntity("pret", Category.EATING_OUT.name),
            CategoryRuleEntity("pret a manger victoria", Category.WORK_LUNCH.name),
        )
        assertEquals(
            Category.WORK_LUNCH,
            CategoryEngine.classify(tx("PRET A MANGER VICTORIA"), rules),
        )
    }
}
