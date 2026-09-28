package com.spendroid.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendroid.widget.refreshCardWidgets
import com.spendroid.widget.refreshWidgets
import java.time.LocalTime

/**
 * Hourly through the day, redraws the widgets from what the phone already holds - which sends
 * the watch the same. Nothing is asked of the bank: the days left, the daily amount and the
 * pace move on with the clock even when nothing new has been spent, and the watch keeps up.
 */
class WatchUpdateWorker(app: Context, parameters: WorkerParameters) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result {
        if (LocalTime.now().hour !in FIRST_HOUR..LAST_HOUR) return Result.success()
        refreshWidgets(applicationContext)
        refreshCardWidgets(applicationContext)
        return Result.success()
    }

    companion object {
        /** 08:00 to 20:59: the day, as the syncs keep it. */
        private const val FIRST_HOUR = 8
        private const val LAST_HOUR = 20
        const val UNIQUE_NAME = "watch_hourly"
    }
}
