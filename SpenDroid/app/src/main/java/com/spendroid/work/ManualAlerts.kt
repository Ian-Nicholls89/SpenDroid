package com.spendroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spendroid.data.ManualReminders
import com.spendroid.data.MoneyWatch
import com.spendroid.data.db.AccountEntity
import com.spendroid.domain.ManualAccounts
import java.time.LocalDate

/**
 * The two reminders for accounts with no bank link, as the user set them: time for a new
 * statement, and time to check the balance. Each said once per time it falls due.
 */
object ManualAlerts {

    private const val CHANNEL = "manual_accounts"

    /** Opens the app on a manual account's import or balance check. */
    const val EXTRA_OPEN_IMPORT = "com.spendroid.OPEN_IMPORT"
    const val EXTRA_OPEN_BALANCE_CHECK = "com.spendroid.OPEN_BALANCE_CHECK"

    fun check(context: Context, accounts: List<AccountEntity>, coverage: Map<String, LocalDate?>, cycleStart: LocalDate?, today: LocalDate = LocalDate.now()) {
        val manual = accounts.filter { it.manual }
        if (manual.isEmpty() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val settings = ManualReminders(context)
        val watch = MoneyWatch(context)
        val told = mutableListOf<String>()

        val stale = manual.filter { ManualAccounts.statementDue(coverage[it.id], settings.statementEveryDays, today) }
        if (stale.isNotEmpty()) {
            // Once per account per period: keyed by the last day covered.
            val key = "manual-statement|" + stale.joinToString(",") { "${it.id}@${coverage[it.id]}" }
            if (!watch.told(key)) {
                val names = stale.joinToString(" and ") { it.label }
                post(
                    context, key.hashCode(), "Time for a new statement",
                    "Download one for $names from your bank's app and share it to SpenDroid. It fills in direct debits and anything a notification missed.",
                    EXTRA_OPEN_IMPORT, stale.first().id,
                )
                told += key
            }
        }

        val unchecked = manual.filter { it.accountType != com.spendroid.data.db.AccountType.CREDIT_CARD }
            .filter { ManualAccounts.balanceDue(ManualAccounts.checkedOn(it), settings.balanceWhen, today, cycleStart) }
        unchecked.firstOrNull()?.let { account ->
            val key = "manual-balance|${account.id}|$today"
            if (!watch.told(key)) {
                post(
                    context, key.hashCode(), "Check ${account.label}'s balance",
                    "Type in what your banking app shows. Anything Penny hasn't seen is counted, so your budget stays right.",
                    EXTRA_OPEN_BALANCE_CHECK, account.id,
                )
                told += key
            }
        }
        watch.tell(told)
    }

    private fun post(context: Context, id: Int, title: String, body: String, extra: String, accountId: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Statements and balance checks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders for accounts with no bank link"
            },
        )
        val tap = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply { putExtra(extra, accountId) }?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.spendroid.R.drawable.ic_stat_droid)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
