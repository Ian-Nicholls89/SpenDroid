package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.7.1: a shop visited often isn't a bill because one amount comes round - a £4.90 coffee
 * among the café's other amounts was "the next big one". A real bill to a payee that's also a
 * shop, and two bills to one payee, still are. Made-up payments.
 */
class ShopNotABillTest {
    private fun tx(payee: String, amountMinor: Long, date: LocalDate, n: Int = 0) = TransactionEntity(
        accountId = "a", transactionId = "$payee-$date-$amountMinor-$n", bookingDate = date.toString(), valueDate = null,
        amountMinor = amountMinor, currency = "GBP", payee = payee, description = null, isPending = false, rawJson = null,
    )

    private val start = LocalDate.of(2026, 1, 1)

    @Test
    fun `a cafe at £4-90 once a month among its other amounts is not a bill`() {
        val coffee = (0 until 6).map { tx("COFFEE SHOP", -490, start.plusMonths(it.toLong()).plusDays(3)) }
        val other = (0 until 180 step 3).map { tx("COFFEE SHOP", -(300L + (it * 37) % 900).let { a -> if (a in 440..549) a + 200 else a }, start.plusDays(it.toLong()), it) }
        val rules = RecurringAnalyzer.analyze(coffee + other)
        assertTrue(rules.joinToString { "${it.payee} ${it.amountMinor}" }, rules.none { it.payee == "COFFEE SHOP" })
    }

    @Test
    fun `a bill still is - on its own, or two to one payee`() {
        val netflix = (0 until 6).map { tx("NETFLIX", -1_099, start.plusMonths(it.toLong()).plusDays(14)) }
        val kids50 = (0 until 6).map { tx("KIDS SAVINGS", -5_000, start.plusMonths(it.toLong()).plusDays(1)) }
        val kids20 = (0 until 6).map { tx("KIDS SAVINGS", -2_000, start.plusMonths(it.toLong()).plusDays(1)) }
        val rules = RecurringAnalyzer.analyze(netflix + kids50 + kids20)
        assertEquals(setOf(-1_099L, -5_000L, -2_000L), rules.map { it.amountMinor }.toSet())
    }
}
