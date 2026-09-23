package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confidence score used to collapse to 0.4 for everything, because the scaling bound to
 * one term instead of the sum. These pin it to something that actually varies.
 */
class RecurringScoreTest {

    private fun tx(payee: String, amountMinor: Long, date: LocalDate) = TransactionEntity(
        accountId = "a",
        transactionId = "$payee-$date",
        bookingDate = date.toString(),
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = null,
        isPending = false,
        rawJson = null,
    )

    private fun monthly(payee: String, months: Int, day: Int = 15) =
        (0 until months).map { tx(payee, -1299, LocalDate.of(2026, 1, day).plusMonths(it.toLong())) }

    @Test
    fun `more history means more confidence`() {
        val few = RecurringAnalyzer.analyze(monthly("SHORT", 3)).first().score
        val many = RecurringAnalyzer.analyze(monthly("LONG", 12)).first().score
        assertTrue("12 months should beat 3: $many vs $few", many > few)
    }

    @Test
    fun `the score is a real proportion, not a constant`() {
        val score = RecurringAnalyzer.analyze(monthly("REGULAR", 12)).first().score
        assertTrue("expected a high score for a year of regular payments, got $score", score > 0.9f)
        assertTrue("a proportion cannot exceed 1: $score", score <= 1.0f)
    }

    @Test
    fun `a sparse history scores below a complete one`() {
        val sparse = RecurringAnalyzer.analyze(monthly("SPARSE", 2)).first().score
        assertTrue("a two-month history should not read as certain: $sparse", sparse < 0.8f)
    }
}
