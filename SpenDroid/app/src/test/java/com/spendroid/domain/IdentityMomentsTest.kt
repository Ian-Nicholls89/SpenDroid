package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import com.spendroid.ui.DroidMood
import com.spendroid.ui.droidFaceOf
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.1: the droid's face and read, and the cycle review, agree with the budget's pace. */
class IdentityMomentsTest {

    private val start = LocalDate.of(2026, 10, 1)
    private val end = LocalDate.of(2026, 10, 29)

    private fun snapshot(spent: Long, available: Long, asOf: LocalDate) = BudgetSnapshot(
        averageMonthlyIncome = 234_000, fixedMonthlyOutgoings = 100_000, variableMonthlyBudget = 140_000,
        nextIncomeDate = end.plusDays(1), cycleStart = start, cycleEnd = end,
        spentThisCycle = spent, spentToday = 0, upcomingFixed = emptyList(), availableToSpend = available,
        incomeRules = emptyList(), fixedRules = emptyList(), primaryIncomeRule = null,
        daysUntilNextIncome = java.time.temporal.ChronoUnit.DAYS.between(asOf, end.plusDays(1)).toInt(),
        asOf = asOf,
    )
    private val money = { m: Long -> "£%.2f".format(m / 100.0) }
    private val mid = LocalDate.of(2026, 10, 15) // half way

    @Test
    fun `well under the days - the droid smiles and says how far ahead`() {
        val b = snapshot(spent = 40_000, available = 100_000, asOf = mid)
        assertEquals(DroidMood.AHEAD, droidFaceOf(b).mood)
        assertTrue(TodaysRead.of(b, money), TodaysRead.of(b, money).endsWith("ahead of the days. A normal day keeps you there."))
    }

    @Test
    fun `a little past the days is tight, well past is over`() {
        val tight = snapshot(spent = 80_000, available = 60_000, asOf = mid)
        assertEquals(DroidMood.TIGHT, droidFaceOf(tight).mood)
        assertTrue(TodaysRead.of(tight, money).startsWith("Just behind the days."))
        val over = snapshot(spent = 100_000, available = 40_000, asOf = mid)
        assertEquals(DroidMood.OVER, droidFaceOf(over).mood)
        assertTrue(TodaysRead.of(over, money).startsWith("Over for the days so far."))
    }

    @Test
    fun `payday, syncing and nothing left`() {
        assertEquals(DroidMood.PAYDAY, droidFaceOf(snapshot(0, 140_000, start)).mood)
        assertTrue(TodaysRead.of(snapshot(0, 140_000, start), money).startsWith("Payday!"))
        assertEquals(DroidMood.SYNCING, droidFaceOf(snapshot(40_000, 100_000, mid), syncing = true).mood)
        assertTrue(TodaysRead.of(snapshot(140_000, 0, mid), money).startsWith("Nothing left to spend before payday"))
    }

    @Test
    fun `the visor shows the budget used and the cycle gone`() {
        val face = droidFaceOf(snapshot(spent = 40_000, available = 100_000, asOf = mid))
        assertEquals(40_000f / 140_000f, face.used, 0.02f)
        assertEquals(0.5f, face.elapsed, 0.03f)
    }

    private fun review(left: Long, ahead: Int, days: Int = 28, biggest: Long = 3_000, share: Long = 3_000) = CycleReview(
        start, end, "GBP", left, 80_000,
        daily = List(days) { start.plusDays(it.toLong()) to 2_000L }, dailyShareMinor = share, daysAhead = ahead,
        biggestDay = LocalDate.of(2026, 10, 10) to biggest, mostVisited = null,
    )

    @Test
    fun `the review's headline follows how the cycle went`() {
        assertEquals("A cycle of quiet discipline", review(11_200, 22).headline)
        assertEquals("Made it through, with some to spare", review(11_200, 14).headline)
        assertEquals("Close, but made it", review(500, 5).headline)
        assertEquals("A tight one", review(-2_000, 20).headline)
        // One big day, mentioned when it was more than two and a half days' share.
        assertEquals("Ahead of the days on 22 of 28. One big Saturday, then back on course.", review(11_200, 22, biggest = 11_200).standfirst)
        assertEquals("Ahead of the days on 22 of 28.", review(11_200, 22).standfirst)
    }

    private fun tx(payee: String, date: String, minor: Long = -420, recurring: Boolean = false) = TransactionEntity(
        accountId = "a", transactionId = payee + date + minor, bookingDate = date, valueDate = null, amountMinor = minor,
        currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null, isRecurring = recurring,
    )

    @Test
    fun `most visited is everyday spending, not bills or money in`() {
        val txs = listOf(
            tx("PRET A MANGER", "2026-10-02"), tx("Pret A Manger", "2026-10-05"), tx("PRET A MANGER", "2026-10-09"),
            tx("TESCO", "2026-10-03"), tx("TESCO", "2026-10-04"),
            tx("NETFLIX", "2026-10-06", recurring = true), tx("NETFLIX", "2026-10-07", recurring = true), tx("NETFLIX", "2026-10-08", recurring = true), tx("NETFLIX", "2026-10-11", recurring = true),
            tx("EMPLOYER", "2026-10-01", 234_000), tx("PRET A MANGER", "2026-11-02"),
        )
        assertEquals("PRET A MANGER" to 3, CycleReview.mostVisited(txs, start, end))
        assertNull(CycleReview.mostVisited(listOf(tx("TESCO", "2026-10-03")), start, end))
    }
}
