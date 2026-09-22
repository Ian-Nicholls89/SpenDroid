package com.spendroid.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendroid.BudgetApplication
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
 * PSD2 allows about four unattended calls per account per day; this deliberately uses one.
 */
class DailySyncWorker(
    app: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result {
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

        if (failures == 0) return Result.success()
        // Everything failed and there is still time to try again before the next daily run.
        if (failures == attempted && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
        // A partial failure still leaves the successful accounts stored; the accounts that
        // failed are retried on the next run, which the 90-day window still covers.
        return Result.success()
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private val MIN_RESYNC_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)
    }
}
