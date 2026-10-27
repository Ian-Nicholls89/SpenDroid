package com.spendroid.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.spendroid.ui.PennySleep
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Redraws the widgets at 11pm and 6am, so Penny falls asleep and wakes on them (0.7.0) - from what
 * the phone already has: nothing is fetched from the bank. Then books the next.
 */
class PennySleepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { com.spendroid.widget.refreshWidgets(applicationContext) }
        schedule(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "penny_sleep"

        /** The next redraw, at her next change - asked again at each start, which keeps the one booked. */
        fun schedule(context: Context, now: LocalDateTime = LocalDateTime.now()) {
            val delay = PennySleep.untilNextChange(now).toMillis() + 30_000L
            val request = OneTimeWorkRequestBuilder<PennySleepWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
