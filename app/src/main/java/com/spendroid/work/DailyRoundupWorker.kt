package com.spendroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendroid.BudgetApplication
import com.spendroid.domain.BudgetEngine
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.ui.formatMoney
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

class DailyRoundupWorker(
    app: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BudgetApplication

        app.repository.connections.first().forEach { connection ->
            connection.accountIds.forEach { accountId ->
                runCatching { app.repository.importAccount(connection.institutionName, accountId) }
            }
        }

        val transactions = app.repository.transactions()
        if (transactions.isEmpty()) return Result.success()

        val rules = RecurringAnalyzer.analyze(transactions)
        val ignored = app.repository.ignoredRules.first()
        val notificationTime = java.time.LocalDateTime.now()
        val snapshot = BudgetEngine.snapshot(transactions, rules.filter { it.key !in ignored }, referenceTime = notificationTime)

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

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "daily_roundup"
        private const val NOTIFICATION_ID = 1001
    }
}