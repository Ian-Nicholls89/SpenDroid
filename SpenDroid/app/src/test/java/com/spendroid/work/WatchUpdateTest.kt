package com.spendroid.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spendroid.BudgetApplication
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.DuplicateCheck
import com.spendroid.widget.WidgetRefresh
import com.spendroid.widget.refreshWidgets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * 0.8.6: the hourly redraw happens only when the clock has moved what the widgets and the watch
 * show - the date, or the budget's day - not every hour regardless. Made-up names and figures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchUpdateTest {

    private val app get() = ApplicationProvider.getApplicationContext<BudgetApplication>()
    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 11, 4)
    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `never drawn, it draws`() {
        assertTrue(WatchUpdateWorker.needsRedraw(null, null, today, today, zone))
    }

    @Test
    fun `drawn today on today's budget, the hour does nothing`() {
        assertFalse(WatchUpdateWorker.needsRedraw(at(today, 9), today, today, today, zone))
        assertFalse(WatchUpdateWorker.needsRedraw(at(today, 0), today, today, today, zone))
    }

    @Test
    fun `the first hour of a new date redraws, though the budget is still on yesterday`() {
        val yesterday = today.minusDays(1)
        // Drawn at 10pm; past midnight the budget waits for the first sync, still on yesterday.
        assertTrue(WatchUpdateWorker.needsRedraw(at(yesterday, 22), yesterday, yesterday, today, zone))
        // Once redrawn after midnight, it waits again.
        assertFalse(WatchUpdateWorker.needsRedraw(at(today, 0), yesterday, yesterday, today, zone))
    }

    @Test
    fun `the budget's day moving on with no sync redraws`() {
        // The first sync never came; four hours after it was due the day starts regardless.
        assertTrue(WatchUpdateWorker.needsRedraw(at(today, 6), today.minusDays(1), today, today, zone))
    }

    @Test
    fun `with no budget drawn, only the date matters`() {
        assertFalse(WatchUpdateWorker.needsRedraw(at(today, 9), null, today, today, zone))
        assertTrue(WatchUpdateWorker.needsRedraw(at(today.minusDays(1), 9), null, today, today, zone))
    }

    @Test
    fun `a redraw notes the budget's day, and the next hour then leaves the widgets alone`() = runBlocking {
        app.getSharedPreferences(com.spendroid.data.Stores.FIRST_RUN, Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
        com.spendroid.data.Choices.awaitLoaded()
        val repo = app.repository
        val account = repo.addManualAccount("Everyday", "Made-up Bank", AccountType.PERSONAL, 120_000)
        val now = LocalDate.now()
        repo.addImported(
            listOf(
                TransactionEntity(
                    accountId = account.id, transactionId = DuplicateCheck.IMPORTED_PREFIX + "pay", bookingDate = now.withDayOfMonth(1).toString(),
                    valueDate = null, amountMinor = 200_000, currency = "GBP", payee = "MADE-UP EMPLOYER LTD", description = null, isPending = false, rawJson = null,
                ),
            ),
            closing = now to 120_000L,
        )

        // Never drawn: the first hour draws.
        assertTrue(WatchUpdateWorker.hour(app))
        val drawnAt = WidgetRefresh.drawnAt(app)
        assertEquals(repo.budgetTime().toLocalDate(), WidgetRefresh.drawnBudgetDay(app))
        // The next finds nothing moved, and draws nothing: the widgets' time stays as it was.
        assertFalse(WatchUpdateWorker.hour(app))
        assertEquals(drawnAt, WidgetRefresh.drawnAt(app))

        // Drawn on another day (as last night's draw would be): the hour redraws.
        WidgetRefresh.drawn(app, System.currentTimeMillis() - 86_400_000L, now.minusDays(1))
        assertTrue(WatchUpdateWorker.hour(app))
        assertEquals(repo.budgetTime().toLocalDate(), WidgetRefresh.drawnBudgetDay(app))

        // Any other redraw - a sync, the app - notes the day too.
        refreshWidgets(app)
        assertFalse(WatchUpdateWorker.hour(app))
    }
}
