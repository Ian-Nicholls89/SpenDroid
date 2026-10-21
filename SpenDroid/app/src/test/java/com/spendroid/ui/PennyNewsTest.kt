package com.spendroid.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spendroid.domain.Cadence
import com.spendroid.domain.Direction
import com.spendroid.domain.RecurringRule
import com.spendroid.screenshots.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.5: what Penny says she's noticed, and when she asks again after "Ask me later". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PennyNewsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun bill(payee: String) = RecurringRule(
        key = "OUT|GBP|11|${payee.lowercase()}", payee = payee, direction = Direction.OUT, amountMinor = -1_099, currency = "GBP",
        cadence = Cadence.MONTHLY, anchorDay = 14, lastOccurrence = java.time.LocalDate.of(2026, 10, 14), occurrences = 3, score = 1f,
    )

    @Test
    fun `a new bill is asked about once, and what was planned for already isn't`() {
        val state = Sample.state()
        assertEquals("nothing asked about on first sight", emptyList<RecurringRule>(), PennyBills.toAsk(context, state))
        val more = state.copy(rules = state.rules + bill("StreamCo"))
        val ask = PennyBills.toAsk(context, more)
        assertEquals(listOf("StreamCo"), ask.map { it.payee })
        assertEquals("around the 14th each month", PennyBills.often(ask.single()))
        PennyBills.answer(context, ask.single())
        assertEquals(emptyList<RecurringRule>(), PennyBills.toAsk(context, more))
        // A price change is the same bill, not a new one.
        assertEquals(emptyList<RecurringRule>(), PennyBills.toAsk(context, state.copy(rules = state.rules + bill("StreamCo").copy(key = "OUT|GBP|12|streamco"))))
    }

    @Test
    fun `a quarterly bill seen only twice is asked about even at the start`() {
        val state = Sample.state()
        val carPark = bill("City Car Park").copy(cadence = Cadence.QUARTERLY, occurrences = 2, amountMinor = -230)
        assertEquals(listOf("City Car Park"), PennyBills.toAsk(context, state.copy(rules = state.rules + carPark)).map { it.payee })
    }

    @Test
    fun `digits learnt from a payment are said, digits typed in aren't`() {
        val state = Sample.state()
        assertNull(PennyNews.next(context, state))
        val card = state.accounts.first()
        val learnt = state.copy(accounts = state.accounts.map { if (it.id == card.id) it.copy(cardLastFour = "0042") else it })
        assertTrue(PennyNews.next(context, learnt)!!.line.contains("ends 0042"))
        val typed = learnt.accounts.map { if (it.id == card.id) it.copy(cardLastFour = "7777") else it }
        PennyNews.know(context, PennyNews.digitsKey(card.id, "7777"))
        PennyNews.know(context, PennyNews.digitsKey(card.id, "0042"))
        assertNull(PennyNews.next(context, learnt.copy(accounts = typed)))
    }

    @Test
    fun `ask me later waits six hours`() {
        val now = 1_000_000_000L
        assertTrue(PennyAsk.due(context, now))
        PennyAsk.later(context, now)
        assertFalse(PennyAsk.due(context, now + 5 * 3_600_000L))
        assertTrue(PennyAsk.due(context, now + 6 * 3_600_000L))
    }
}
