package com.spendroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendroid.BudgetApplication
import com.spendroid.ui.formatMoney
import java.time.format.DateTimeFormatter

class DailyRoundupWorker(
    app: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BudgetApplication

        // Reads whatever DailySyncWorker last stored. Syncing here as well meant a failure to
        // reach the API was swallowed silently, and tied the data refresh to the user's
        // chosen notification time.
        // The shared builder. Assembling this here left out accounts entirely, so the
        // notification counted card spending the home screen excludes and quietly reported
        // a different figure from the one in the app.
        val snapshot = app.repository.budgetSnapshot(java.time.LocalDateTime.now())
            ?: return Result.success()

        val dateFormat = DateTimeFormatter.ofPattern("d MMM")
        val body = buildString {
            append("Spent today ${formatMoney(snapshot.spentToday, "GBP")} · ")
            append("left to spend ${formatMoney(snapshot.availableToSpend, "GBP")}")
            snapshot.nextIncomeDate?.let { append(" until income on ${it.format(dateFormat)}") }
        }

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily budget roundup",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SpenDroid roundup")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()

        if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "daily_roundup"
        private const val NOTIFICATION_ID = 1001
    }
}