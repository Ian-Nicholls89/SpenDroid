package com.spendroid.work

import com.spendroid.domain.CreditCardEngine
import com.spendroid.ui.formatMoney
import java.time.format.DateTimeFormatter

/**
 * Warnings about spending on a card, on the card's own clock.
 *
 * Card purchases are kept out of the cycle's spending when the bill is what counts - they are
 * next month's bill, and counting both would count the money twice. The cost is that a card
 * could run up unseen until the bill arrived. So each card is watched from statement to
 * statement instead, against a limit the user set or, failing that, its usual bill.
 */
object CardWatch {

    data class Warnings(val messages: List<String>, val keys: Set<String>)

    /** Worth a word approaching the limit, and again on passing it. */
    private val THRESHOLDS = listOf(0.8f, 1.0f)

    private val CLOSE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

    /**
     * What to say about [bills], leaving out anything in [alreadySent].
     *
     * Each warning is said once per statement, keyed "card|what|statementClose". The record
     * returned keeps only keys for statements still open, so a new statement starts afresh.
     */
    fun warnings(bills: List<CreditCardEngine.CardBill>, alreadySent: Set<String>): Warnings {
        val messages = mutableListOf<String>()
        val standing = mutableSetOf<String>()

        for (bill in bills) {
            val cap = bill.capMinor?.takeIf { it > 0L } ?: continue
            val close = bill.statementClose ?: continue
            val spent = bill.unbilledMinor
            val share = spent.toFloat() / cap.toFloat()
            fun key(what: String) = "${bill.cardAccountId}|$what|$close"
            val capText = when (bill.capSource) {
                CreditCardEngine.CapSource.USER -> "your ${money(cap, bill)}"
                else -> "its usual ${money(cap, bill)} bill"
            }

            val threshold = THRESHOLDS.lastOrNull { share >= it }
            if (threshold != null) {
                val k = key(threshold.toString())
                standing += k
                if (k !in alreadySent) {
                    messages += if (threshold >= 1f) {
                        "${bill.cardLabel} is past $capText, at ${money(spent, bill)} since the statement."
                    } else {
                        "${bill.cardLabel} is at ${(share * 100).toInt()}% of $capText, " +
                            "${money(spent, bill)} since the statement."
                    }
                }
            }

            // Only while still under: once past, the warning above already says so.
            val projected = bill.projectedMinor
            if (projected != null && spent < cap && projected > cap) {
                val k = key("pace")
                standing += k
                if (k !in alreadySent) {
                    val by = bill.nextStatementClose?.let { " by the ${it.format(CLOSE_FORMAT)} statement" }.orEmpty()
                    messages += "${bill.cardLabel} is on pace for about ${money(projected, bill)}$by, " +
                        "over $capText."
                }
            }
        }

        val open = bills.mapNotNull { bill -> bill.statementClose?.let { "|$it" to bill.cardAccountId } }
        val stillOpen = alreadySent.filter { k -> open.any { (suffix, card) -> k.startsWith("$card|") && k.endsWith(suffix) } }
        return Warnings(messages, standing + stillOpen)
    }

    private fun money(minor: Long, bill: CreditCardEngine.CardBill) = formatMoney(minor, bill.currency)
}
