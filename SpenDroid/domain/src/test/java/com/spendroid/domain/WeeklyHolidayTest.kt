package com.spendroid.domain

import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** One bank holiday moving a weekly payment a day no longer stops it being recognised. */
class WeeklyHolidayTest {

    @Test
    fun `a weekly payment moved by a holiday is still weekly`() {
        val mondays = (0L until 10L).map { LocalDate.of(2026, 3, 2).plusWeeks(it) }
            .map { if (it == LocalDate.of(2026, 4, 6)) it.plusDays(1) else it } // Easter Monday
        val txs = mondays.mapIndexed { i, d ->
            TransactionEntity(
                accountId = "a", transactionId = "w$i", bookingDate = d.toString(), valueDate = null,
                amountMinor = -2000, currency = "GBP", payee = "CLEANER", description = null,
                isPending = false, rawJson = null,
            )
        }
        val rule = RecurringAnalyzer.analyze(txs).single()
        assertEquals(Cadence.WEEKLY, rule.cadence)
        assertEquals(1, rule.anchorDay) // Monday
    }
}
