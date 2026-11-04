package com.spendroid.wear

import com.spendroid.wear.BudgetArc.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The segmented ring as Home's bar (watch 0.1.16): the account's spending, the cards', what's left. */
class BudgetArcTest {

    private fun kinds(account: Float, cards: Float) =
        BudgetArc.segments(account, cards).map { it.kind to "%.2f".format(it.weight) }

    @Test
    fun `the ring fills with what is spent, the account's then the cards'`() {
        assertEquals(
            listOf(Kind.ACCOUNT to "0.30", Kind.CARDS to "0.18", Kind.LEFT to "0.52"),
            kinds(0.30f, 0.18f),
        )
    }

    @Test
    fun `with no cards it is the account and what is left`() {
        assertEquals(listOf(Kind.ACCOUNT to "0.40", Kind.LEFT to "0.60"), kinds(0.40f, 0f))
    }

    @Test
    fun `spending past the month fills the ring and no more`() {
        assertEquals(listOf(Kind.ACCOUNT to "0.70", Kind.CARDS to "0.30"), kinds(0.70f, 0.50f))
        assertEquals(listOf(Kind.ACCOUNT to "1.00"), kinds(1.2f, 0.1f))
    }

    @Test
    fun `nothing spent is all left`() {
        assertEquals(listOf(Kind.LEFT to "1.00"), kinds(0f, 0f))
    }

    /** A face that paints by position must always get three, what's left third - its green. */
    @Test
    fun `the face always gets account, cards and left, in that order`() {
        val noCards = BudgetArc.forFace(BudgetArc.segments(0.40f, 0f))
        assertEquals(listOf(Kind.ACCOUNT, Kind.CARDS, Kind.LEFT), noCards.map { it.kind })
        assertEquals(0.60f, noCards.last().weight, 0.0001f)
        assertTrue(noCards[1].weight < 0.01f)
        val untouched = BudgetArc.forFace(BudgetArc.segments(0f, 0f))
        assertEquals(listOf(Kind.ACCOUNT, Kind.CARDS, Kind.LEFT), untouched.map { it.kind })
        assertTrue(untouched.all { it.weight > 0f })
    }
}
