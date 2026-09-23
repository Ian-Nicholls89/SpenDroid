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
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.toRecurringRule
import com.spendroid.ui.formatMoney
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.flow.first

/**
 * Notices the handful of things worth interrupting someone for.
 *
 * The daily roundup answers "how am I doing"; this answers "something needs your attention".
 * Both read local data only - the sync worker has already run by the time these fire.
 */
class AlertsWorker(
    app: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as BudgetApplication).repository
        val transactions = repo.transactions()
        if (transactions.isEmpty()) return Result.success()

        val ignored = repo.ignoredRules.first()
        val manual = repo.manualRules.first().mapNotNull { it.toRecurringRule() }
        val rules = (RecurringAnalyzer.analyze(transactions) + manual)
            .filter { it.key !in ignored }
        val snapshot = BudgetEngine.snapshot(transactions, rules, repo.accounts())

        val alerts = buildList {
            billsDueTomorrow(snapshot)?.let(::add)
            unusuallyLargeTransaction(transactions)?.let(::add)
        }
        if (alerts.isEmpty()) return Result.success()

        notify(alerts)
        return Result.success()
    }

    private fun billsDueTomorrow(snapshot: com.spendroid.domain.BudgetSnapshot): String? {
        val tomorrow = LocalDate.now().plusDays(1)
        val due = snapshot.upcomingFixed.filter { it.dueDate == tomorrow }
        if (due.isEmpty()) return null

        val total = due.sumOf { it.amountMinor }
        val names = due.joinToString(", ") { it.rule.payee }
        val estimated = due.any { it.rule.key.startsWith(CARD_BILL_KEY_PREFIX) }
        val prefix = if (estimated) "About " else ""
        return "$prefix${formatMoney(total, "GBP")} leaves tomorrow ($names). " +
            "That leaves ${formatMoney(snapshot.availableToSpend, "GBP")} to spend."
    }

    /**
     * A debit far above the usual for its size, judged against the median rather than the
     * mean so a single outlier does not raise the bar that catches the next one.
     */
    private fun unusuallyLargeTransaction(
        transactions: List<com.spendroid.data.db.TransactionEntity>,
    ): String? {
        val yesterday = LocalDate.now().minusDays(1)
        val debits = transactions.filter { !it.isPending && !it.isInternalTransfer && it.amountMinor < 0 }
        if (debits.size < MIN_HISTORY_FOR_COMPARISON) return null

        val amounts = debits.map { abs(it.amountMinor) }.sorted()
        val median = amounts[amounts.size / 2]
        if (median <= 0L) return null

        val recent = debits.firstOrNull { tx ->
            RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.isAfter(yesterday.minusDays(1)) == true &&
                abs(tx.amountMinor) > median * LARGE_MULTIPLE
        } ?: return null

        val multiple = abs(recent.amountMinor) / median
        return "${formatMoney(recent.amountMinor, recent.currency)} at ${recent.payee} — " +
            "about ${multiple}× your usual transaction."
    }

    private fun notify(alerts: List<String>) {
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Bills falling due and unusually large transactions"
            },
        )

        val body = alerts.joinToString("\n\n")
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (alerts.size == 1) "SpenDroid" else "SpenDroid · ${alerts.size} things")
            .setContentText(alerts.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "spendroid_alerts"
        private const val NOTIFICATION_ID = 4001
        private const val MIN_HISTORY_FOR_COMPARISON = 20
        private const val LARGE_MULTIPLE = 5
    }
}
