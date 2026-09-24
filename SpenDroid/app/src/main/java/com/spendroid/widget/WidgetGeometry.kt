package com.spendroid.widget

import com.spendroid.domain.CreditCardEngine
import java.time.LocalDate

/**
 * Where things go on the widgets' drawn images, as fractions of the width.
 *
 * Kept apart from the drawing so the arithmetic can be tested without a canvas, and so the
 * images cannot quietly disagree with the numbers printed beside them.
 */
object WidgetGeometry {

    /** Where [date] falls between [start] and [end], clamped to the line. */
    fun fraction(start: LocalDate, end: LocalDate, date: LocalDate): Float {
        val span = (end.toEpochDay() - start.toEpochDay()).toFloat()
        if (span <= 0f) return 1f
        return ((date.toEpochDay() - start.toEpochDay()) / span).coerceIn(0f, 1f)
    }

    /**
     * A card's statement against its limit: what is spent, where it is heading, where an even
     * pace would be by today, and where the limit sits once the projection runs past it.
     */
    data class CardRail(
        val spent: Float,
        val projected: Float,
        val evenPace: Float?,
        /** Null while the limit is the end of the bar; set once the bar has to run past it. */
        val limit: Float?,
    )

    fun cardRail(bill: CreditCardEngine.CardBill): CardRail? {
        val cap = bill.capMinor?.takeIf { it > 0L } ?: return null
        val projected = bill.projectedMinor ?: bill.unbilledMinor
        val scale = maxOf(cap, projected, bill.unbilledMinor).toFloat()

        val evenPace = run {
            val close = bill.statementClose ?: return@run null
            val next = bill.nextStatementClose ?: return@run null
            val elapsed = bill.statementDaysElapsed ?: return@run null
            val days = (next.toEpochDay() - close.toEpochDay()).toFloat()
            if (days <= 0f) null else (cap * (elapsed / days).coerceIn(0f, 1f)) / scale
        }
        return CardRail(
            spent = bill.unbilledMinor / scale,
            projected = projected / scale,
            evenPace = evenPace,
            limit = if (scale > cap) cap / scale else null,
        )
    }
}
