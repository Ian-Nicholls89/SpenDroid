package com.spendroid.ui

import com.spendroid.data.toMajor
import java.text.NumberFormat
import java.util.Currency

fun formatMoney(minor: Long, currencyCode: String): String {
    val value = minor.toMajor()
    return try {
        val nf = NumberFormat.getCurrencyInstance()
        nf.currency = Currency.getInstance(currencyCode)
        nf.format(value)
    } catch (e: Exception) {
        "$currencyCode ${value.toPlainString()}"
    }
}
/**
 * A regular payment's amount, written as its parts when it is several on the same day:
 * "2 × £50.00" says both what leaves and why it is that much.
 */
fun recurringAmount(totalMinor: Long, perOccurrence: Int, currency: String, variable: Boolean = false): String =
    if (variable) {
        "about ${formatMoney(totalMinor, currency)}"
    } else if (perOccurrence > 1) {
        "$perOccurrence × ${formatMoney(totalMinor / perOccurrence, currency)}"
    } else {
        formatMoney(totalMinor, currency)
    }

/**
 * Collapses the padding banks put in fixed-width name fields, so "BILLS        NICHO" reads
 * as "BILLS NICHO".
 *
 * Applied at display as well as on the way in, because transactions older than the API's
 * 90-day window never sync again - without this, the accumulated history keeps its padding
 * for good.
 */
fun String.tidyBankName(): String = trim().replace(Regex("\\s+"), " ")

/**
 * A payee's name as shown: the user's own name for it where they've given one, else the bank's,
 * tidied. Every screen, widget, the watch and the computer page show names through this.
 */
fun String.tidyPayee(): String = tidyBankName().let { com.spendroid.data.PayeeNames.nameFor(it) ?: it }
