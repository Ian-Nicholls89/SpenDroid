package com.spendroid.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendroid.BudgetApplication
import com.spendroid.widget.WidgetRefresh
import com.spendroid.widget.refreshWidgets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Hourly, redraws the widgets - which sends the watch the same - when the clock has moved what
 * they show. Nothing is asked of the bank.
 *
 * Everything new - a sync, a notification spend, an edit in the app - redraws them as it lands,
 * so what is left to the clock is the day: the days left, the daily amount and the pace move on
 * when the budget's day does, and "08:02" becomes "4 Nov" when the date does. It used to redraw
 * every hour from 8 to 8, reading every transaction and working the budget out each time, for
 * figures that had not moved since the last; now each hour asks only which day it is (0.8.6).
 */
class WatchUpdateWorker(app: Context, parameters: WorkerParameters) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result {
        hour(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "watch_hourly"

        /** One hour's check: redraws if the day has moved on, and says whether it did. */
        internal suspend fun hour(context: Context): Boolean {
            val repo = (context.applicationContext as? BudgetApplication)?.repository ?: return false
            val budgetDay = runCatching { repo.budgetTime().toLocalDate() }.getOrNull() ?: return false
            if (!needsRedraw(WidgetRefresh.drawnAt(context), WidgetRefresh.drawnBudgetDay(context), budgetDay)) return false
            refreshWidgets(context)
            return true
        }

        /**
         * Whether the widgets show a day other than now's: never drawn, drawn on another date
         * (the times they show read "d MMM" once it is not today), or the budget's day has moved
         * since - at its first sync, or when the wait for one gives up (BudgetDay). A budget
         * drawn as none ([drawnBudgetDay] null) has no day to move; the date still does.
         */
        internal fun needsRedraw(
            drawnAt: Long?,
            drawnBudgetDay: LocalDate?,
            budgetDay: LocalDate,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Boolean {
            if (drawnAt == null) return true
            if (Instant.ofEpochMilli(drawnAt).atZone(zone).toLocalDate() != today) return true
            return drawnBudgetDay != null && drawnBudgetDay != budgetDay
        }
    }
}
