package com.spendroid.domain

import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A spending total going up says nothing about why. These pin the series that answers it:
 * the same months, split by where the money went, ranked by what has actually moved.
 */
class CategoryTrendsTest {

    private var seq = 0

    private fun tx(date: String, amountMinor: Long, payee: String) = TransactionEntity(
        accountId = "bank",
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
     * Work lunches carry no keyword list - the merchants are the same ones as eating out -
     * so they are populated by hand. Passing the rule here exercises that path too.
     */
    private val rules = listOf(CategoryRuleEntity("salt deli", "WORK_LUNCH", 0L))

    /** Work lunches climbing while groceries hold steady. */
    private fun history() = listOf(
        tx("2026-07-04", -9200, "SALT DELI KITCHEN"),
        tx("2026-08-04", -13400, "SALT DELI KITCHEN"),
        tx("2026-09-04", -16800, "SALT DELI KITCHEN"),
        tx("2026-07-10", -26800, "TESCO STORES"),
        tx("2026-08-10", -25400, "TESCO STORES"),
        tx("2026-09-10", -29100, "TESCO STORES"),
    )

    @Test
    fun `each category gets one figure per month, oldest first`() {
        val trends = TrendsEngine.categoryTrends(history(), rules)
        val lunches = trends.first { it.category == Category.WORK_LUNCH }

        assertEquals(listOf(9200L, 13400L, 16800L), lunches.monthlyMinor)
        assertEquals(9200L, lunches.firstMinor)
        assertEquals(16800L, lunches.lastMinor)
    }

    /** A big category that never moves is not news; a smaller one that doubles is. */
    @Test
    fun `the biggest mover is ranked first, not the biggest category`() {
        val trends = TrendsEngine.categoryTrends(history(), rules)

        assertEquals(Category.WORK_LUNCH, trends.first().category)
        assertTrue(
            "groceries are larger but steadier",
            trends.first { it.category == Category.GROCERIES }.lastMinor > trends.first().lastMinor,
        )
    }

    @Test
    fun `the change is reported as a fraction of where it started`() {
        val lunches = TrendsEngine.categoryTrends(history(), rules)
            .first { it.category == Category.WORK_LUNCH }

        assertEquals(0.826f, lunches.change!!, 0.01f)
    }

    /** A month with nothing spent is a zero, so every series lines up on the same months. */
    @Test
    fun `a gap in a category is a zero rather than a missing point`() {
        val sparse = history() + tx("2026-07-20", -4500, "WILTON RESTAURANT")
        val eatingOut = TrendsEngine.categoryTrends(sparse, rules)
            .first { it.category == Category.EATING_OUT }

        assertEquals(3, eatingOut.monthlyMinor.size)
        assertEquals(listOf(4500L, 0L, 0L), eatingOut.monthlyMinor)
    }

    /** Starting from nothing has no percentage, and claiming one would be an infinity. */
    @Test
    fun `a category that started at zero reports no change`() {
        val appearing = listOf(
            tx("2026-07-10", -26800, "TESCO STORES"),
            tx("2026-08-10", -25400, "TESCO STORES"),
            tx("2026-09-04", -16800, "SALT DELI KITCHEN"),
        )
        val lunches = TrendsEngine.categoryTrends(appearing, rules)
            .first { it.category == Category.WORK_LUNCH }

        assertNull(lunches.change)
    }

    @Test
    fun `a trifling category is left out rather than charted`() {
        val withNoise = history() + tx("2026-09-11", -120, "PARKING")
        val trends = TrendsEngine.categoryTrends(withNoise, rules)

        assertTrue(trends.none { it.category == Category.TRANSPORT })
    }

    @Test
    fun `one month of history is not a trend`() {
        val single = listOf(tx("2026-09-10", -26800, "TESCO STORES"))
        assertEquals(emptyList<CategoryTrend>(), TrendsEngine.categoryTrends(single, rules))
    }
}
