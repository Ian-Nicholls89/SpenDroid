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
import com.spendroid.domain.Outlook
import com.spendroid.domain.PriceChanges
import com.spendroid.ui.formatMoney
import com.spendroid.ui.tidyPayee
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Bills that went up, reminders the user asked for, and accounts heading below zero - each said
 * once, as its own notification, with what to do about it to hand.
 */
object MoneyWatchAlerts {

    const val EXTRA_OPEN_RULE = "com.spendroid.OPEN_RULE"
    const val EXTRA_OPEN_FORECAST = "com.spendroid.OPEN_FORECAST"
    private const val CHANNEL = "bills_and_balances"
    private const val ACTION_EXPECTED = "com.spendroid.PRICE_EXPECTED"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_ID = "id"
    private val SHORT = DateTimeFormatter.ofPattern("d MMM")
    private val MONTH = DateTimeFormatter.ofPattern("MMMM")

    /** A rise is news for ten days after the payment that showed it. */
    private const val FRESH_DAYS = 10L
    private const val MAX_RISES = 3

    fun check(context: Context, outlook: Outlook, today: LocalDate = LocalDate.now()) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val watch = MoneyWatch(context)
        val figures = Privacy(context).notificationFigures
        val told = mutableListOf<String>()

        if (watch.riseAlerts) {
            outlook.priceChanges.values
                .filter { it.up && watch.threshold.passes(it) && ChronoUnit.DAYS.between(it.on, today) in 0..FRESH_DAYS }
                .filter { !watch.told(it.key) && watch.decision(it.key) == null }
                .take(MAX_RISES)
                .forEach { c ->
                    rise(context, c, figures)
                    told += c.key
                }
        }
        // Reminders asked for from a bill's sheet: one nudge each.
        watch.remindersDue(today).forEach { key ->
            val change = outlook.priceChanges.values.firstOrNull { it.key == key }
            if (change != null) reminder(context, change, figures)
            watch.reminded(key)
        }
        if (watch.overdraftAlerts) {
            outlook.warnings.forEach { w ->
                val key = "od|${w.account.id}|${w.on}"
                if (watch.told(key)) return@forEach
                val money = { m: Long -> if (figures) formatMoney(m, w.account.currency) else Privacy.HIDDEN }
                val title = if (figures) "${w.account.label.trim()} could go ${money(w.shortMinor)} overdrawn on ${w.on.format(SHORT)}"
                else "${w.account.label.trim()} could go overdrawn on ${w.on.format(SHORT)}"
                val body = buildString {
                    w.cause?.let { append("${it.label.tidyPayee()} ${money(-it.amountMinor)} on ${it.date.format(SHORT)}") }
                    w.nextIn?.let { append(", and ${it.label.tidyPayee()} doesn't come in until ${it.date.format(SHORT)}") }
                    if (figures) append(". Moving ${money(w.coverMinor)} in before ${w.on.format(SHORT)} covers it.") else append(".")
                }
                post(context, key.hashCode(), title, body, open(context, key.hashCode(), EXTRA_OPEN_FORECAST, w.account.id), emptyList())
                told += key
            }
        }
        watch.tell(told)
    }

    private fun rise(context: Context, c: PriceChanges.Change, figures: Boolean) {
        val money = { m: Long -> formatMoney(m, "GBP") }
        val name = c.payee.tidyPayee()
        val pct = String.format(java.util.Locale.UK, "%+.0f%%", c.percent)
        val title = if (figures) "$name up ${money(c.deltaMinor)}" else "$name has gone up"
        val body = if (figures) {
            "$name took ${money(c.afterMinor)} on ${c.on.format(SHORT)} - it had been ${money(c.beforeMinor)} since ${c.since.format(MONTH)} ($pct). " +
                "That's ${money(c.perYearMinor)} more a year."
        } else {
            "A regular payment came out higher than usual. Open SpenDroid to see it."
        }
        val id = c.key.hashCode()
        val expected = PendingIntent.getBroadcast(
            context, id,
            Intent(context, MoneyWatchReceiver::class.java).setAction(ACTION_EXPECTED).putExtra(EXTRA_KEY, c.key).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(context, id, title, body, open(context, id, EXTRA_OPEN_RULE, c.ruleKey), listOf("That's expected" to expected))
    }

    private fun reminder(context: Context, c: PriceChanges.Change, figures: Boolean) {
        val name = c.payee.tidyPayee()
        val title = "Looking for a better deal on $name?"
        val body = if (figures) {
            "It went up ${formatMoney(c.deltaMinor, "GBP")} on ${c.on.format(SHORT)} - ${formatMoney(c.perYearMinor, "GBP")} a year. You asked to be reminded."
        } else {
            "It went up on ${c.on.format(SHORT)}. You asked to be reminded."
        }
        val id = ("remind|" + c.key).hashCode()
        post(context, id, title, body, open(context, id, EXTRA_OPEN_RULE, c.ruleKey), emptyList())
    }

    private fun open(context: Context, requestCode: Int, extra: String, value: String): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply { putExtra(extra, value) }?.let {
            PendingIntent.getActivity(context, requestCode, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun post(context: Context, id: Int, title: String, body: String, tap: PendingIntent?, actions: List<Pair<String, PendingIntent>>) {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Bills and balances", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A bill that went up, and an account heading below zero"
            },
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
        actions.forEach { (label, intent) -> builder.addAction(0, label, intent) }
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
    }

    /** "That's expected", from the notification. */
    internal fun answer(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPECTED) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        MoneyWatch(context).decide(key, MoneyWatch.Decision.EXPECTED)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_ID, 0))
    }
}

class MoneyWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = MoneyWatchAlerts.answer(context, intent)
}
