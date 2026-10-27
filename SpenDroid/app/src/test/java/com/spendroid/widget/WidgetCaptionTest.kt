package com.spendroid.widget

import com.spendroid.domain.BudgetPace
import com.spendroid.domain.PeriodRows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.7.0: the widget's captions say over and quick as such - "Ahead of the days" is Home's word
 * for under pace, so it's never said here for the opposite. Made-up figures.
 */
class WidgetCaptionTest {
    private fun row(pace: BudgetPace.Pace) =
        PeriodRows.Row("a", "Everyday", PeriodRows.Kind.CYCLE, 30_000, 40_000, PeriodRows.Against.USUAL, 0.75f, 0.5f, 14, 28, 0L, null, pace, "GBP")

    @Test
    fun `over and quick, in words that can't be read as good`() {
        val over = ThisPeriodWidget.caption(row(BudgetPace.Pace.OVER))
        val tight = ThisPeriodWidget.caption(row(BudgetPace.Pace.TIGHT))
        assertTrue(over, over.startsWith("▲ Over for the days so far"))
        assertTrue(tight, tight.startsWith("◆ A little quick for the days"))
        listOf(over, tight).forEach { assertFalse(it, "Ahead" in it) }
    }
}
