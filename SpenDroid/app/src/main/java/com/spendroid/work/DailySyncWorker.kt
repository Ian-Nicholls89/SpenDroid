package com.spendroid.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendroid.BudgetApplication
import com.spendroid.widget.refreshCardWidgets
import com.spendroid.widget.refreshWidgets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Pulls every linked account once a day.
 *
 * Transactions older than 90 days exist only in the local database - the API window has
 * moved past them and they can never be fetched again - so a missed sync is not merely
 * stale data, it risks a permanent hole in the history. Failures therefore retry rather
 * than being swallowed.
 *
 * PSD2 allows about four unattended calls per account per day; the three daily syncs leave one
 * for a manual refresh.
 */
class DailySyncWorker(
    app: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result = daily(ownSlot()) { runDay() }

    /** Which of the day's three syncs this is. One booked before there were three is the evening one. */
    private fun ownSlot(): DailyRoundupScheduler.Daily =
        inputData.getString(DailyRoundupScheduler.JOB_KEY)
            ?.let { name -> runCatching { DailyRoundupScheduler.Daily.valueOf(name) }.getOrNull() }
            ?: DailyRoundupScheduler.Daily.SYNC_3

    private suspend fun runDay(): Result {
        val repo = (applicationContext as BudgetApplication).repository

        // Skip anything already pulled recently, so a manual refresh earlier in the day and a
        // retry after a partial failure both avoid spending another call on that account.
        val syncedRecently = repo.accounts()
            .filter { System.currentTimeMillis() - it.lastSynced < MIN_RESYNC_INTERVAL_MS }
            .map { it.id }
            .toSet()

        var failures = 0
        var attempted = 0
        repo.connections.first().forEach { connection ->
            connection.accountIds.forEach { accountId ->
                if (accountId in syncedRecently) return@forEach
                attempted++
                runCatching { repo.importAccount(connection.institutionName, accountId) }
                    .onFailure { failures++ }
            }
        }

        // The figures only move when a sync lands, so this is the moment a widget is stale.
        // Refreshing here beats the 30-minute poll on both freshness and battery.
        if (attempted > 0) {
            refreshWidgets(applicationContext)
            refreshCardWidgets(applicationContext)
        }

        if (failures == 0) return Result.success()
        // Everything failed and there is still time to try again before the next daily run.
        if (failures == attempted && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
        // A partial failure still leaves the successful accounts stored; the accounts that
        // failed are retried on the next run, which the 90-day window still covers.
        return Result.success()
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        // Under the three hours that separate the day's syncs, so one running a little late never
        // makes the next skip; long enough that a manual refresh shortly before spares it.
        private val MIN_RESYNC_INTERVAL_MS = TimeUnit.MINUTES.toMillis(150)
    }
}
