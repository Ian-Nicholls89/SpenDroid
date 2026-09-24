package com.spendroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendroid.BudgetApplication
import com.spendroid.data.Connection
import kotlinx.coroutines.flow.first

class ReauthNotificationWorker(
    app: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result = daily(DailyRoundupScheduler.Daily.REAUTH) { runDay() }

    private suspend fun runDay(): Result {
        val repo = (applicationContext as BudgetApplication).repository
        // Connections saved before the grant date was kept have none; the requisition knows.
        runCatching { repo.backfillConnectionDates() }

        val expiringSoon = repo.connections.first()
            .mapNotNull { conn -> conn.daysUntilExpiry()?.let { conn to it } }
            .filter { (_, daysLeft) -> daysLeft <= WARN_DAYS }

        if (expiringSoon.isNotEmpty()) {
            sendNotifications(expiringSoon)
        }

        return Result.success()
    }

    private fun sendNotifications(connections: List<Pair<Connection, Int>>) {
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reauthorisation reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders to reauthorise bank connections before they expire"
        }
        manager.createNotificationChannel(channel)

        connections.forEachIndexed { index, (conn, daysLeft) ->
            val (title, body) = when {
                // "Expires in -12 days" is not a sentence anyone should get daily.
                daysLeft < 0 -> "${conn.institutionName} has stopped syncing" to
                    "Your ${conn.institutionName} connection has expired. Open the app to reauthorise."
                daysLeft <= 3 -> "Reauthorise ${conn.institutionName} now" to
                    "Your ${conn.institutionName} connection expires in $daysLeft day${if (daysLeft == 1) "" else "s"}. Open the app to reauthorise."
                daysLeft <= 7 -> "${conn.institutionName} expires soon" to
                    "Your ${conn.institutionName} connection expires in $daysLeft days. Reauthorise to keep syncing."
                else -> "${conn.institutionName} reauthorisation needed" to
                    "Your ${conn.institutionName} connection expires in $daysLeft days. Plan to reauthorise soon."
            }

            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(if (daysLeft <= 3) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build()

            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_BASE_ID + index, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "reauth_reminders"
        private const val NOTIFICATION_BASE_ID = 2000
        private const val WARN_DAYS = 14
    }
}