package com.budgetapp.data

import java.math.BigDecimal
import java.math.RoundingMode

fun BigDecimal.toMinorLong(): Long =
    setScale(2, RoundingMode.HALF_UP)
        .multiply(BigDecimal(100))
        .setScale(0, RoundingMode.HALF_UP)
        .toLong()

fun Long.toMajor(): BigDecimal = BigDecimal(this).movePointLeft(2)