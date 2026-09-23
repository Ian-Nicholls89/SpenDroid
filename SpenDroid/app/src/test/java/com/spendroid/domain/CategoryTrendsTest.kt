package com.spendroid.domain

import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A spending total going up says nothing about why. These pin the series that answers it:
 * the same stretch of time, split by where the money went.
 *
 * The periods are rolling windows rather than calendar months, and that is the point. With
 * ninety days of history the oldest month is a stub of a few days and the current one is
 * however far through it happens to be, so a category that never moved could be reported as
 * having risen sixfold purely from the lengths of the buckets.
 */
class CategoryTrendsTest {

    /** Three whole 30-day windows: 26 Jun-25 Jul, 26 Jul-24 Aug, 25 Aug-23 Sep. */
    private val today = LocalDate.of(2026, 9, 23)

    /**
     * Work lunches carry no keyword list - the merchants are the same ones as eating out -
     * so they are populated by hand. Passing the rule here exercises that path too.
     */
    private val rules = listOf(CategoryRuleEntity("salt deli", "WORK_LUNCH", 0L))

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

    private fun trends(transactions: List<TransactionEntity>) =
        TrendsEngine.categoryTrends(transactions, rules, today = today)

    /** Work lunches climbing steadily while groceries hold roughly level. */
    private fun history() = listOf(
        tx("2026-06-26", -26800, "TESCO STORES"),
        tx("2026-08-04", -25400, "TESCO STORES"),
        tx("2026-09-10", -29100, "TESCO STORES"),
        tx("2026-07-04", -9200, "SALT DELI KITCHEN"),
        tx("2026-08-04", -13400, "SALT DELI KITCHEN"),
        tx("2026-09-04", -16800, "SALT DELI KITCHEN"),
    )

    @Test
    fun `each category gets one figure per window, oldest first`() {
        val lunches = trends(history()).first { it.category == Category.WORK_LUNCH }

        assertEquals(listOf(9200L, 13400L, 16800L), lunches.seriesMinor)
        assertEquals(30, lunches.windowDays)
        assertEquals(60, lunches.spanDays)
    }

    /**
     * The bug this replaced. Spending exactly £100 every thirty days must read as flat -
     * under calendar months the same money landed in a five-day June and a full August and
     * appeared to have collapsed.
     */
    @Test
    fun `a category spending the same every period reports no change`() {
        // One per window: 26 Jun lands in the first, 26 Jul the second, 26 Aug the third.
        val steady = listOf(
            tx("2026-06-26", -10000, "TESCO STORES"),
            tx("2026-07-26", -10000, "TESCO STORES"),
            tx("2026-08-26", -10000, "TESCO STORES"),
        )

        val groceries = trends(steady).first { it.category == Category.GROCERIES }

        assertEquals(listOf(10000L, 10000L, 10000L), groceries.seriesMinor)
        assertEquals(0f, groceries.change!!, 0.001f)
    }

    /** A big category that never moves is not news; a smaller one that doubles is. */
    @Test
    fun `the biggest mover is ranked first, not the biggest category`() {
        val ranked = trends(history())

        assertEquals(Category.WORK_LUNCH, ranked.first().category)
        assertTrue(
            "groceries are larger but steadier",
            ranked.first { it.category == Category.GROCERIES }.lastMinor > ranked.first().lastMinor,
        )
    }

    @Test
    fun `the change is reported as a fraction of where it started`() {
        val lunches = trends(history()).first { it.category == Category.WORK_LUNCH }
        assertEquals(0.826f, lunches.change!!, 0.01f)
    }

    /** A window with nothing spent is a zero, so every series lines up on the same periods. */
    @Test
    fun `a window with nothing spent is a zero rather than a missing point`() {
        val eatingOut = trends(history() + tx("2026-07-20", -4500, "WILTON RESTAURANT"))
            .first { it.category == Category.EATING_OUT }

        assertEquals(listOf(4500L, 0L, 0L), eatingOut.seriesMinor)
    }

    /** Starting from nothing has no percentage, and claiming one would be an infinity. */
    @Test
    fun `a category that started at zero reports no change`() {
        val appearing = listOf(
            tx("2026-06-26", -26800, "TESCO STORES"),
            tx("2026-08-10", -25400, "TESCO STORES"),
            tx("2026-09-04", -16800, "SALT DELI KITCHEN"),
        )

        assertNull(trends(appearing).first { it.category == Category.WORK_LUNCH }.change)
    }

    @Test
    fun `a trifling category is left out rather than charted`() {
        assertTrue(
            trends(history() + tx("2026-09-11", -120, "PARKING"))
                .none { it.category == Category.TRANSPORT },
        )
    }

    /**
     * Half a window is worse than none: it invites a comparison between periods of
     * different lengths, which is exactly what went wrong before.
     */
    @Test
    fun `less than two whole windows is not a trend`() {
        val fortyDays = listOf(
            tx("2026-08-14", -26800, "TESCO STORES"),
            tx("2026-09-10", -29100, "TESCO STORES"),
        )

        assertEquals(emptyList<CategoryTrend>(), trends(fortyDays))
    }

    /** Ninety days of history is exactly three windows, and no more are invented. */
    @Test
    fun `only whole windows the data covers are used`() {
        val ninetyDays = (0..89).map { day ->
            tx(today.minusDays(day.toLong()).toString(), -1000, "TESCO STORES")
        }

        val groceries = trends(ninetyDays).first { it.category == Category.GROCERIES }

        assertEquals(3, groceries.seriesMinor.size)
        // Thirty days of £10 in every window, so all three are identical.
        assertEquals(listOf(30000L, 30000L, 30000L), groceries.seriesMinor)
    }
}
