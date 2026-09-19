package com.budgetapp.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.budgetapp.BudgetApplication
import com.budgetapp.data.Connection
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class ReauthNotificationWorker(
    app: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BudgetApplication
        val connections = app.repository.connections.first()

        val now = System.currentTimeMillis()
        val warningThresholdMs = 14L * 24 * 60 * 60 * 1000 // 14 days
        val urgentThresholdMs = 3L * 24 * 60 * 60 * 1000 // 3 days

        val expiringSoon = connections.filter { conn ->
            val expiryMs = conn.createdAt + 90L * 24 * 60 * 60 * 1000
            val daysLeft = ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate(),
                Instant.ofEpochMilli(expiryMs).atZone(ZoneId.systemDefault()).toLocalDate(),
            )
            daysLeft <= 14
        }

        if (expiringSoon.isNotEmpty()) {
            sendNotifications(expiringSoon, now)
        }

        return Result.success()
    }

    private fun sendNotifications(connections: List<Connection>, now: Long) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reauthorisation reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders to reauthorise bank connections before they expire"
        }
        manager.createNotificationChannel(channel)

        connections.forEachIndexed { index, conn ->
            val expiryMs = conn.createdAt + 90L * 24 * 60 * 60 * 1000
            val daysLeft = ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate(),
                Instant.ofEpochMilli(expiryMs).atZone(ZoneId.systemDefault()).toLocalDate(),
            )

            val (title, body) = when {
                daysLeft <= 3 -> "Reauthorise ${conn.institutionName} now" to
                    "Your ${conn.institutionName} connection expires in $daysLeft day(s). Open the app to reauthorise."
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
    }
}