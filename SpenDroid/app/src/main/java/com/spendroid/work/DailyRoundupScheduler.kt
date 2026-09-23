package com.spendroid.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spendroid.BudgetApplication
import com.spendroid.data.GoCardlessRepository
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object DailyRoundupScheduler {

    private const val UNIQUE_NAME = "daily_roundup"
    private const val SYNC_UNIQUE_NAME = "daily_sync"
    private const val ALERTS_UNIQUE_NAME = "daily_alerts"
    private const val REAUTH_UNIQUE_NAME = "reauth_reminders"
    private const val UPDATE_CHECK_UNIQUE_NAME = "update_checker"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun schedule(context: Context) {
        val app = context.applicationContext as BudgetApplication
        val repo: GoCardlessRepository = app.repository

        scope.launch {
            // Get notification time from repository (with default fallback)
            val notificationTime = repo.notificationTime.first() ?: "21:00"
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            val notificationTimeLocal = runCatching { LocalTime.parse(notificationTime, formatter) }
                .getOrDefault(LocalTime.of(21, 0))

            // Sync an hour ahead of the roundup so the notification reports fresh figures.
            // Requires network: without it an offline run burns the day's sync, and anything
            // that ages past the API's 90-day window cannot be fetched again.
            val syncRequest = PeriodicWorkRequestBuilder<DailySyncWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(
                    initialDelayMillis(notificationTimeLocal.minusHours(1)),
                    TimeUnit.MILLISECONDS,
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(SYNC_UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, syncRequest)

            val roundupRequest = PeriodicWorkRequestBuilder<DailyRoundupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelayMillis(notificationTimeLocal), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, roundupRequest)

            val alertsRequest = PeriodicWorkRequestBuilder<AlertsWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelayMillis(notificationTimeLocal), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(ALERTS_UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, alertsRequest)

            val reauthRequest = PeriodicWorkRequestBuilder<ReauthNotificationWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelayMillis(notificationTimeLocal), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(REAUTH_UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, reauthRequest)

            // Check for updates weekly. Without the network constraint this fires while
            // offline and burns a retry; the running version is read from BuildConfig inside
            // the worker, so nothing version-specific is baked in at schedule time.
            val updateRequest = PeriodicWorkRequestBuilder<UpdateCheckerWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(initialDelayMillis(notificationTimeLocal), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UPDATE_CHECK_UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, updateRequest)
        }
    }

    private fun initialDelayMillis(notificationTime: LocalTime): Long {
        val now = java.time.LocalDateTime.now()
        var next = java.time.LocalDateTime.of(now.toLocalDate(), notificationTime)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}