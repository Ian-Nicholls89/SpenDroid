package com.spendroid.ui

import com.spendroid.domain.CreditCardEngine
import java.time.format.DateTimeFormatter

/** One line on how a card's current statement is going, and whether it is a warning. */
data class CardPaceLine(val text: String, val over: Boolean)

private val CLOSE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * "On pace for about £640 by 10 Oct · your limit £500", shared by the home screen and the
 * widget so the two cannot word the same card differently. Null when there is nothing to
 * measure against yet.
 */
fun cardPaceLine(bill: CreditCardEngine.CardBill): CardPaceLine? {
    val cap = bill.capMinor?.takeIf { it > 0L }
    val projected = bill.projectedMinor
    if (cap == null && projected == null) return null

    val parts = buildList {
        projected?.let { p ->
            val by = bill.nextStatementClose?.let { " by ${it.format(CLOSE_FORMAT)}" }.orEmpty()
            add("On pace for about ${formatMoney(p, bill.currency)}$by")
        }
        cap?.let { c ->
            val whose = if (bill.capSource == CreditCardEngine.CapSource.USER) "your limit" else "usual bill"
            add("$whose ${formatMoney(c, bill.currency)}")
        }
    }
    val over = cap != null && (bill.unbilledMinor >= cap || (projected ?: 0L) > cap)
    return CardPaceLine(parts.joinToString(" · ").replaceFirstChar { it.uppercase() }, over)
}
