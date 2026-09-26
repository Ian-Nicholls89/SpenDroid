package com.spendroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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

    override suspend fun doWork(): Result = daily(DailyRoundupScheduler.Daily.ROUNDUP) { runDay() }

    private suspend fun runDay(): Result {
        val app = applicationContext as BudgetApplication

        // Reads whatever DailySyncWorker last stored. Syncing here as well meant a failure to
        // reach the API was swallowed silently, and tied the data refresh to the user's
        // chosen notification time.
        // The shared builder. Assembling this here left out accounts entirely, so the
        // notification counted card spending the home screen excludes and quietly reported
        // a different figure from the one in the app.
        val snapshot = app.repository.budgetSnapshot()
            ?: return Result.success()

        val dateFormat = DateTimeFormatter.ofPattern("d MMM")
        val body = buildString {
            append("Spent today ${formatMoney(snapshot.spentToday, snapshot.baseCurrency)} · ")
            append("left to spend ${formatMoney(snapshot.availableToSpend, snapshot.baseCurrency)}")
            snapshot.nextIncomeDate?.let { append(" until income on ${it.format(dateFormat)}") }
        }

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        // High importance, so the roundup sits at the top of the shade rather than among
        // everything else. Android never lets an app raise a channel it has already made,
        // so this is a new channel, and the old default-importance one goes.
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily budget roundup",
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(channel)

        // Tapping it opens the app, as tapping its icon would.
        val open = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.let {
                PendingIntent.getActivity(
                    applicationContext,
                    NOTIFICATION_ID,
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SpenDroid roundup")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "daily_roundup_high"
        private const val OLD_CHANNEL_ID = "daily_roundup"
        private const val NOTIFICATION_ID = 1001
    }
}