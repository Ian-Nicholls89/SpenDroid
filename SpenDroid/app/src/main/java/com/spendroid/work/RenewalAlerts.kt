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
import com.spendroid.data.RenewalStore
import com.spendroid.domain.Renewals
import com.spendroid.ui.formatMoney
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * A renewal coming up (0.7.2): told once, when its reminder is due - "Remind me in 2 weeks" puts
 * it off and it's told again then; "Sorted" and that's all until the next end date.
 */
object RenewalAlerts {

    private const val CHANNEL = "renewals"
    private const val ACTION_SNOOZE = "com.spendroid.RENEWAL_SNOOZE"
    private const val ACTION_SORTED = "com.spendroid.RENEWAL_SORTED"
    private const val EXTRA_ID = "renewal_id"
    private const val EXTRA_NOTIFICATION = "notification_id"
    const val EXTRA_OPEN_RENEWAL = "com.spendroid.OPEN_RENEWAL"
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

    fun check(context: Context, renewals: List<Renewals.Item>) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val watch = MoneyWatch(context)
        val figures = Privacy(context).notificationFigures
        val told = mutableListOf<String>()
        renewals.forEach { item ->
            val s = item.status as? Renewals.Status.Due ?: return@forEach
            val r = item.renewal
            // Once for each end date - and once more after each "Remind me in 2 weeks".
            val key = "renewal|${r.id}|${r.ends}|${r.snoozedUntil ?: ""}"
            if (watch.told(key)) return@forEach
            val title = "${r.name} ends on ${r.ends.format(DAY)}"
            val when_ = when {
                s.daysLeft == 0L -> "Ends today"
                s.daysLeft == 1L -> "Ends tomorrow"
                s.daysLeft < 14 -> "${s.daysLeft} days to go"
                else -> "${words(s.daysLeft / 7)} weeks to go"
            }
            val body = "$when_ - " + (if (Renewals.compares(r)) "time to compare quotes." else "worth a look before it renews.") +
                (r.lastMinor?.takeIf { figures }?.let { " Last time about ${formatMoney((it + 500) / 1000 * 1000, "GBP").removeSuffix(".00")}." } ?: "")
            post(context, key.hashCode(), title, body, r.id)
            told += key
        }
        watch.tell(told)
    }

    /** "Six", not "6", at the start of a sentence. */
    private fun words(n: Long): String = listOf("No", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine").getOrNull(n.toInt()) ?: n.toString()

    private fun post(context: Context, id: Int, title: String, body: String, renewalId: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Renewals", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Insurance, cover or a licence coming up for renewal"
            },
        )
        val tap = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { putExtra(EXTRA_OPEN_RENEWAL, renewalId) }
            ?.let { PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        fun answer(action: String, code: Int) = PendingIntent.getBroadcast(
            context, code,
            Intent(context, RenewalReceiver::class.java).setAction(action).putExtra(EXTRA_ID, renewalId).putExtra(EXTRA_NOTIFICATION, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.spendroid.R.drawable.ic_stat_droid)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .addAction(0, "Remind me in 2 weeks", answer(ACTION_SNOOZE, id + 1))
            .addAction(0, "Sorted", answer(ACTION_SORTED, id + 2))
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    /** A button on the notification: saved, then the notification goes. */
    internal suspend fun answer(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        com.spendroid.data.Choices.awaitLoaded()
        val store = RenewalStore(context)
        when (intent.action) {
            ACTION_SNOOZE -> store.snooze(id)
            ACTION_SORTED -> store.sorted(id)
            else -> return
        }
        com.spendroid.data.Choices.flush()
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
    }
}

class RenewalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Kept going until the answer is saved: a receiver alone is let go as soon as it returns.
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                RenewalAlerts.answer(context.applicationContext, intent)
            } finally {
                pending.finish()
            }
        }
    }
}
