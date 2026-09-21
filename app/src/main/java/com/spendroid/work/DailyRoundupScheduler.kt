package com.spendroid.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spendroid.BudgetApplication
import com.spendroid.data.GoCardlessRepository
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object DailyRoundupScheduler {

    private const val UNIQUE_NAME = "daily_roundup"
    private const val REAUTH_UNIQUE_NAME = "reauth_reminders"
    private const val UPDATE_CHECK_UNIQUE_NAME = "update_checker"

    fun schedule(context: Context, versionCode: Int) {
        val app = context.applicationContext as BudgetApplication
        val repo: GoCardlessRepository = app.repository
        
        // Get notification time from repository (with default fallback)
        val notificationTime = repo.notificationTime.first() ?: "21:00"
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val notificationTimeLocal = LocalTime.parse(notificationTime, formatter)

        val inputData = Data.Builder().putInt(UpdateCheckerWorker.VERSION_CODE_KEY, versionCode).build()

        val roundupRequest = PeriodicWorkRequestBuilder<DailyRoundupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMillis(notificationTimeLocal), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, roundupRequest)

        val reauthRequest = PeriodicWorkRequestBuilder<ReauthNotificationWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMillis(notificationTimeLocal), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(REAUTH_UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, reauthRequest)

        // Check for updates weekly
        val updateRequest = PeriodicWorkRequestBuilder<UpdateCheckerWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMillis(notificationTimeLocal), TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UPDATE_CHECK_UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, updateRequest)
    }

    private fun initialDelayMillis(notificationTime: LocalTime): Long {
        val now = java.time.LocalDateTime.now()
        var next = java.time.LocalDateTime.of(now.toLocalDate(), notificationTime)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}