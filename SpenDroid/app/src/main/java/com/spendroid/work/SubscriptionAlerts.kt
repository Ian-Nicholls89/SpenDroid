package com.spendroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spendroid.data.MoneyWatch
import com.spendroid.data.Privacy
import com.spendroid.data.SubscriptionChoices
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.Cadence
import com.spendroid.domain.Outlook
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.Subscriptions
import com.spendroid.ui.formatMoney
import com.spendroid.ui.tidyPayee
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * In time to act on a subscription: a nudge a few days before a yearly one renews - or any the
 * user asked to be reminded about - and word the day a cancelled one charges again.
 */
object SubscriptionAlerts {

    private const val CHANNEL = "subscriptions"
    private const val ACTION_KEEP = "com.spendroid.SUBSCRIPTION_KEEP"
    private const val EXTRA_ID = "id"
    private const val REMIND_DAYS = 3L
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

    fun check(context: Context, outlook: Outlook, transactions: List<TransactionEntity>, ignored: Set<String>, today: LocalDate = LocalDate.now()) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val choices = SubscriptionChoices(context)
        val watch = MoneyWatch(context)
        val figures = Privacy(context).notificationFigures
        val asked = choices.reminders()
        val cancelled = choices.cancelled()
        val told = mutableListOf<String>()

        // Renewing soon: yearly subscriptions always; any other the user asked about.
        outlook.subscriptions.values.forEach { s ->
            val next = s.next ?: return@forEach
            if (s.lapsed || s.rule.key in ignored || s.rule.key in cancelled) return@forEach
            val yearly = s.kind == Subscriptions.Kind.SUBSCRIPTION && s.rule.cadence == Cadence.ANNUAL
            if (!yearly && s.rule.key !in asked) return@forEach
            val days = ChronoUnit.DAYS.between(today, next)
            if (days !in 0..REMIND_DAYS) return@forEach
            val key = "renew|${s.rule.key}|$next"
            if (watch.told(key)) return@forEach
            val name = s.rule.payee.tidyPayee()
            val amount = formatMoney(abs(s.rule.amountMinor), s.rule.currency)
            val title = when (days) { 0L -> "$name renews today"; 1L -> "$name renews tomorrow"; else -> "$name renews in $days days" }
            val body = (if (figures) "$amount on ${next.format(DAY)}" else "On ${next.format(DAY)}") +
                (s.paidFrom?.let { ", ${if (it.startsWith("via")) it else "from $it"}" } ?: "") + ". Still want it?"
            val id = key.hashCode()
            val keep = PendingIntent.getBroadcast(
                context, id, Intent(context, SubscriptionReceiver::class.java).setAction(ACTION_KEEP).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            post(context, id, title, body, open(context, id, s.rule.key), listOf("Keep it" to keep, "I'll cancel" to open(context, id + 1, s.rule.key)))
            told += key
        }

        // Charged again after the user said it was cancelled.
        cancelled.forEach { (ruleKey, on) ->
            val s = outlook.subscriptions[ruleKey] ?: return@forEach
            val payee = RecurringAnalyzer.normalizedPayee(s.rule.payee)
            transactions.filter { tx ->
                tx.amountMinor < 0 && RecurringAnalyzer.normalizedPayee(tx.payee) == payee &&
                    RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.isAfter(on) == true
            }.forEach { tx ->
                val key = "again|$ruleKey|${tx.accountId}|${tx.transactionId}"
                if (watch.told(key)) return@forEach
                val name = s.rule.payee.tidyPayee()
                val when_ = RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.format(DAY).orEmpty()
                post(
                    context, key.hashCode(), "$name charged again",
                    (if (figures) "${formatMoney(-tx.amountMinor, tx.currency)} on $when_" else "On $when_") + " - you'd said it was cancelled.",
                    open(context, key.hashCode(), ruleKey), emptyList(),
                )
                told += key
            }
        }
        watch.tell(told)
    }

    private fun open(context: Context, requestCode: Int, ruleKey: String): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply { putExtra(MoneyWatchAlerts.EXTRA_OPEN_RULE, ruleKey) }?.let {
            PendingIntent.getActivity(context, requestCode, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun post(context: Context, id: Int, title: String, body: String, tap: PendingIntent?, actions: List<Pair<String, PendingIntent?>>) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Subscriptions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A subscription about to renew, and one charging again after you cancelled it"
            },
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
        actions.forEach { (label, intent) -> if (intent != null) builder.addAction(0, label, intent) }
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
    }

    /** "Keep it": the nudge has done its job. */
    internal fun answer(context: Context, intent: Intent) {
        if (intent.action == ACTION_KEEP) NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_ID, 0))
    }
}

class SubscriptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SubscriptionAlerts.answer(context, intent)
}
