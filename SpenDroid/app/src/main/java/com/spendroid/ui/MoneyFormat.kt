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
 * Collapses the padding banks put in fixed-width name fields, so "BILLS        NICHO" reads
 * as "BILLS NICHO".
 *
 * Applied at display as well as on the way in, because transactions older than the API's
 * 90-day window never sync again - without this, the accumulated history keeps its padding
 * for good.
 */
fun String.tidyPayee(): String = trim().replace(Regex("\\s+"), " ")
