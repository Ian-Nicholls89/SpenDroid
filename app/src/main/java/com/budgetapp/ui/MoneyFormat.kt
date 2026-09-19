package com.budgetapp.ui

import com.budgetapp.data.toMajor
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