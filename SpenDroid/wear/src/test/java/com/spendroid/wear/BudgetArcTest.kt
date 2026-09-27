package com.spendroid.wear

import com.spendroid.wear.BudgetArc.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetArcTest {

    private fun kinds(left: Float, cycle: Float?) =
        BudgetArc.segments(left, cycle).map { it.kind to "%.2f".format(it.weight) }

    /** The mock-up's example: 52% of the budget left with 60% of the cycle to go. */
    @Test
    fun `behind pace shows the gap in amber`() {
        assertEquals(
            listOf(Kind.LEFT to "0.52", Kind.BEHIND to "0.08", Kind.SPENT to "0.40"),
            kinds(0.52f, 0.60f),
        )
    }

    @Test
    fun `ahead of pace has no band`() {
        assertEquals(listOf(Kind.LEFT to "0.70", Kind.SPENT to "0.30"), kinds(0.70f, 0.50f))
    }

    @Test
    fun `an unknown cycle shows the budget alone`() {
        assertEquals(listOf(Kind.LEFT to "0.40", Kind.SPENT to "0.60"), kinds(0.40f, null))
    }

    @Test
    fun `nothing left is the gap and the spent part`() {
        assertEquals(listOf(Kind.BEHIND to "0.30", Kind.SPENT to "0.70"), kinds(0f, 0.30f))
        assertEquals(listOf(Kind.SPENT to "1.00"), kinds(0f, 0f))
    }
}
