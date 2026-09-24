package com.spendroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendroid.BudgetApplication
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.ui.formatMoney
import java.time.LocalDate
import kotlin.math.abs
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
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

    override suspend fun doWork(): Result = daily(DailyRoundupScheduler.Daily.ALERTS) { runDay() }

    private suspend fun runDay(): Result {
        val repo = (applicationContext as BudgetApplication).repository
        val transactions = repo.transactions()
        if (transactions.isEmpty()) return Result.success()

        val snapshot = repo.budgetSnapshot() ?: return Result.success()

        val goals = goalWarnings(repo, snapshot, transactions)

        val large = unusuallyLargeTransaction(
            transactions,
            snapshot,
            repo.notifiedLargeTransactions.first(),
        )

        val cards = CardWatch.warnings(snapshot.cardBills, repo.notifiedCardWarnings.first())

        val alerts = buildList {
            billsDueTomorrow(snapshot)?.let(::add)
            large.message?.let(::add)
            addAll(goals.messages)
            addAll(cards.messages)
        }
        if (alerts.isEmpty()) return Result.success()

        // Recorded before the notification goes out, so a failure to post cannot turn into
        // the same warning arriving every day for the rest of the cycle.
        if (goals.messages.isNotEmpty()) repo.saveNotifiedGoalWarnings(goals.keys)
        if (large.message != null) repo.saveNotifiedLargeTransactions(large.keys)
        if (cards.messages.isNotEmpty()) repo.saveNotifiedCardWarnings(cards.keys)

        notify(alerts)
        return Result.success()
    }

    private data class GoalWarnings(val messages: List<String>, val keys: Set<String>)

    /**
     * Caps that have just been passed, or are close enough to be worth saying so.
     *
     * A cap once passed stays passed, so each warning is remembered against the cycle it
     * belongs to and said once. The record is rewritten rather than added to, so keys from
     * finished cycles drop out on their own.
     */
    private suspend fun goalWarnings(
        repo: com.spendroid.data.GoCardlessRepository,
        snapshot: com.spendroid.domain.BudgetSnapshot,
        transactions: List<TransactionEntity>,
    ): GoalWarnings {
        val goals = repo.budgetGoals.first().filter { it.limitMinor > 0L }
        if (goals.isEmpty()) return GoalWarnings(emptyList(), emptySet())

        val thisCycle = transactions.filter { tx ->
            val date = RecurringAnalyzer.parseBookingDate(tx.bookingDate) ?: return@filter false
            tx.amountMinor < 0L && !date.isBefore(snapshot.cycleStart)
        }
        val spentByCategory = CategoryEngine
            .spendingBreakdown(
                thisCycle,
                repo.categoryRules.first(),
                snapshot.cardPaymentKeys,
                snapshot.creditCardAccountIds,
            )
            .associate { it.category to it.amountMinor }

        val alreadySent = repo.notifiedGoalWarnings.first()
        val cycle = snapshot.cycleStart.toString()
        val messages = mutableListOf<String>()
        val keys = mutableSetOf<String>()

        for (goal in goals) {
            val category = runCatching { Category.valueOf(goal.category) }.getOrNull() ?: continue
            val spent = spentByCategory[category] ?: 0L
            val share = spent.toFloat() / goal.limitMinor.toFloat()
            val threshold = THRESHOLDS.lastOrNull { share >= it } ?: continue

            val key = "${category.name}|${threshold}|${cycle}"
            keys.add(key)
            if (key in alreadySent) continue

            val remaining = (goal.limitMinor - spent).coerceAtLeast(0L)
            val cap = formatMoney(goal.limitMinor, snapshot.baseCurrency)
            val left = formatMoney(remaining, snapshot.baseCurrency)
            val runway = snapshot.daysUntilNextIncome
                ?.let { days -> " with " + days + " day" + (if (days == 1) "" else "s") + " to go" }
                .orEmpty()

            messages += if (share >= 1f) {
                "${category.label} is over its $cap, at ${formatMoney(spent, snapshot.baseCurrency)}."
            } else {
                "${category.label} is at ${(share * 100).toInt()}% of its $cap, $left still to go$runway."
            }
        }
        // Everything still standing is carried forward; anything no longer true can be said
        // again if it comes back.
        return GoalWarnings(messages, keys + alreadySent.filter { it.endsWith("|" + cycle) })
    }

    private fun billsDueTomorrow(snapshot: com.spendroid.domain.BudgetSnapshot): String? {
        val tomorrow = LocalDate.now().plusDays(1)
        val due = snapshot.upcomingFixed.filter { it.dueDate == tomorrow }
        if (due.isEmpty()) return null

        val total = due.sumOf { it.amountMinor }
        val names = due.joinToString(", ") { it.rule.payee }
        val estimated = due.any { it.rule.key.startsWith(CARD_BILL_KEY_PREFIX) }
        val prefix = if (estimated) "About " else ""
        return "$prefix${formatMoney(total, snapshot.baseCurrency)} leaves tomorrow ($names). " +
            "That leaves ${formatMoney(snapshot.availableToSpend, snapshot.baseCurrency)} to spend."
    }

    private data class LargeTransaction(val message: String?, val keys: Set<String>)

    /**
     * A debit far above the usual for its size, judged against the median rather than the
     * mean so a single outlier does not raise the bar that catches the next one.
     *
     * The window covers yesterday as well as today, because a row can book after the evening
     * run - which meant one purchase was announced on both nights. What has been said is
     * remembered for as long as it is in the window. A card bill payment is large by nature
     * and expected, so it is not news.
     */
    private fun unusuallyLargeTransaction(
        transactions: List<TransactionEntity>,
        snapshot: com.spendroid.domain.BudgetSnapshot,
        alreadySent: Set<String>,
    ): LargeTransaction {
        val none = LargeTransaction(null, alreadySent)
        val debits = transactions.filter {
            !it.isInternalTransfer && it.amountMinor < 0 &&
                "${it.accountId}|${it.transactionId}" !in snapshot.cardPaymentKeys
        }
        if (debits.size < MIN_HISTORY_FOR_COMPARISON) return none

        val amounts = debits.map { abs(it.amountMinor) }.sorted()
        val median = amounts[amounts.size / 2]
        if (median <= 0L) return none

        val since = LocalDate.now().minusDays(1)
        val inWindow = debits.filter { tx ->
            RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { !it.isBefore(since) } == true &&
                abs(tx.amountMinor) > median * LARGE_MULTIPLE
        }
        val keys = inWindow.mapTo(mutableSetOf()) { "${it.accountId}|${it.transactionId}" }
        val recent = inWindow.firstOrNull { "${it.accountId}|${it.transactionId}" !in alreadySent }
            ?: return none

        val multiple = abs(recent.amountMinor) / median
        return LargeTransaction(
            "${formatMoney(recent.amountMinor, recent.currency)} at ${recent.payee} — " +
                "about ${multiple}× your usual transaction.",
            keys,
        )
    }

    private fun notify(alerts: List<String>) {
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Bills falling due, unusually large transactions, and spending limits"
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

        /** Worth a word approaching a cap, and again on passing it. Not more often. */
        private val THRESHOLDS = listOf(0.8f, 1.0f)
    }
}
