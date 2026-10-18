package com.spendroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spendroid.BudgetApplication
import com.spendroid.data.Privacy
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.ui.formatMoney
import com.spendroid.widget.refreshCardWidgets
import com.spendroid.widget.refreshWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "£12.40 at Co-op · which account?" - asked straight away, with a button for each account,
 * when a banking app that covers several doesn't say. Missed, the question waits at the top of
 * Home and Spending instead.
 */
object SpendQuestions {

    private const val CHANNEL = "spend_questions"
    private const val ACTION_ANSWER = "com.spendroid.SPEND_ANSWER"
    private const val EXTRA_SPEND = "spend"
    private const val EXTRA_ACCOUNT = "account"
    private const val EXTRA_NOTIFICATION = "notification"

    /** Android shows at most three buttons; with more accounts, a tap opens the app to choose. */
    private const val MAX_ACTIONS = 3

    fun ask(context: Context, spend: SeenSpendEntity, accounts: List<AccountEntity>) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Which account?", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A payment your banking app didn't say the account for"
            },
        )
        val id = spend.id.hashCode()
        val what = if (Privacy(context).notificationFigures) {
            "${formatMoney(-spend.amountMinor, spend.currency)} at ${spend.merchant}"
        } else {
            "A payment at ${spend.merchant}"
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.spendroid.R.drawable.ic_stat_droid)
            .setContentTitle("$what · which account?")
            .setContentText("It's counted already. Tap the account it came from.")
            .setContentIntent(open)
            .setAutoCancel(true)
        if (accounts.size <= MAX_ACTIONS) {
            accounts.forEachIndexed { i, account ->
                val answer = PendingIntent.getBroadcast(
                    context,
                    id * 31 + i,
                    Intent(context, SpendAnswerReceiver::class.java)
                        .setAction(ACTION_ANSWER)
                        .putExtra(EXTRA_SPEND, spend.id)
                        .putExtra(EXTRA_ACCOUNT, account.id)
                        .putExtra(EXTRA_NOTIFICATION, id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(0, account.label, answer)
            }
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /** Takes the question away once answered in the app, so it isn't answered twice. */
    fun answered(context: Context, spendId: String) {
        NotificationManagerCompat.from(context).cancel(spendId.hashCode())
    }

    internal fun answer(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult) {
        val spendId = intent.getStringExtra(EXTRA_SPEND)
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT)
        val app = context.applicationContext as? BudgetApplication
        if (spendId == null || accountId == null || app == null) {
            pending.finish()
            return
        }
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, spendId.hashCode()))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.repository.assignSeenSpend(spendId, accountId)
                refreshWidgets(context)
                refreshCardWidgets(context)
            } finally {
                pending.finish()
            }
        }
    }
}

class SpendAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SpendQuestions.answer(context, intent, goAsync())
}
