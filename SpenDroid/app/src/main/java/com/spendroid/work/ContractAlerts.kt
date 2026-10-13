package com.spendroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spendroid.data.MoneyWatch
import com.spendroid.data.Privacy
import com.spendroid.domain.Contracts
import com.spendroid.domain.Outlook
import com.spendroid.ui.formatMoney
import com.spendroid.ui.tidyPayee
import java.time.format.DateTimeFormatter

/**
 * A contract ending: told six weeks, two weeks and the day before. Once ended, if the bill went up,
 * told once - after that it's kept in view inside the app, not by more notifications.
 */
object ContractAlerts {

    private const val CHANNEL = "contracts"
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

    /** Days before the end to remind, furthest first, and how each is said. */
    private val REMINDERS = listOf(42L to "in 6 weeks", 14L to "in 2 weeks", 1L to "tomorrow")

    fun check(context: Context, outlook: Outlook) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val watch = MoneyWatch(context)
        val figures = Privacy(context).notificationFigures
        val told = mutableListOf<String>()
        outlook.contracts.values.forEach { e ->
            val name = e.payee.tidyPayee()
            val money = { m: Long -> formatMoney(m, e.currency) }
            when (val s = e.status) {
                is Contracts.Status.EndingSoon -> {
                    // The nearest reminder due and not yet given - one a day at most, never a backlog.
                    val due = REMINDERS.lastOrNull { (days, _) -> s.daysLeft <= days } ?: return@forEach
                    val key = "contract|${e.key}|${s.end}|${due.first}"
                    if (watch.told(key)) return@forEach
                    val title = if (s.daysLeft == 0L) "$name contract ends today" else "$name contract ends ${due.second}"
                    val body = (if (figures) "${money(e.amountMinor)} a month until ${s.end.format(DAY)}. " else "On ${s.end.format(DAY)}. ") +
                        "A good time to look for a better deal - or ask them for one."
                    post(context, key.hashCode(), title, body, e.key)
                    told += key
                    // Earlier reminders missed - the phone was off - aren't sent late.
                    REMINDERS.filter { it.first > due.first }.forEach { told += "contract|${e.key}|${s.end}|${it.first}" }
                }
                is Contracts.Status.EndedUp -> {
                    val key = "contract-up|${e.key}|${s.end}"
                    if (watch.told(key)) return@forEach
                    val title = "$name went up now it's out of contract"
                    val body = if (figures) {
                        "${money(s.beforeMinor)} → ${money(s.afterMinor)} since your contract ended on ${s.end.format(DAY)} - " +
                            "${money(s.upMinor * 12)} a year more."
                    } else {
                        "Since your contract ended on ${s.end.format(DAY)}."
                    }
                    post(context, key.hashCode(), title, body, e.key)
                    told += key
                }
                else -> Unit
            }
        }
        watch.tell(told)
    }

    private fun post(context: Context, id: Int, title: String, body: String, contractKey: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Contracts ending", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A contract coming to its end, and a bill that went up after"
            },
        )
        val tap = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { putExtra(EXTRA_OPEN_CONTRACT, contractKey) }
            ?.let { PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(id, n)
        }
    }

    const val EXTRA_OPEN_CONTRACT = "com.spendroid.OPEN_CONTRACT"
}
