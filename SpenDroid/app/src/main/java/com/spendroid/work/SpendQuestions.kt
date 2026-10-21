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
    /** On the app's own intent: open Penny's questions straight away. */
    const val EXTRA_ASK = "com.spendroid.ASK_PENNY"
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
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.putExtra(EXTRA_ASK, true)?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.spendroid.R.drawable.ic_stat_droid)
            .setContentTitle(questionTitle(spend.merchant))
            .setContentText("$what · which account? It's counted already.")
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

    private const val ACTION_REPEAT = "com.spendroid.SPEND_REPEAT"
    private const val EXTRA_TWIN = "twin"
    private const val EXTRA_ONCE = "once"
    private const val REPEAT_CHANNEL = "spend_repeats"

    private fun repeatId(spendId: String) = ("repeat|$spendId").hashCode()

    /**
     * "Penny has a question about your recent spending": two payments alike on one day - once,
     * or twice? Answered from the notification, or later from the card at the top of Home.
     */
    fun askRepeat(context: Context, repeat: com.spendroid.domain.NotificationSpend.Repeat) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(REPEAT_CHANNEL, "Penny's questions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Two payments alike on one day: one purchase, or two?"
            },
        )
        val spend = repeat.spend
        val id = repeatId(spend.id)
        val shop = shopName(spend.merchant) ?: spend.merchant
        val what = if (Privacy(context).notificationFigures) {
            "Did you pay ${formatMoney(-spend.amountMinor, spend.currency)} at $shop once or twice today?"
        } else {
            "Did you pay at $shop once or twice today?"
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.putExtra(EXTRA_ASK, true)?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        fun answer(once: Boolean) = PendingIntent.getBroadcast(
            context,
            id * 31 + if (once) 1 else 2,
            Intent(context, SpendAnswerReceiver::class.java)
                .setAction(ACTION_REPEAT)
                .putExtra(EXTRA_SPEND, spend.id)
                .putExtra(EXTRA_TWIN, repeat.twin.id)
                .putExtra(EXTRA_ONCE, once),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, REPEAT_CHANNEL)
            .setSmallIcon(com.spendroid.R.drawable.ic_stat_droid)
            .setContentTitle(questionTitle(spend.merchant))
            .setContentText(what)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$what Two payments alike were seen. Both are counted until you say, and you can change your answer later from the payment.",
                ),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            // Short, as notification buttons are: they answer the question right above them.
            .addAction(0, "Once", answer(once = true))
            .addAction(0, "Twice", answer(once = false))
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /**
     * "Penny has a question about your spend at Tesco" - the shop's tidy name where there is a
     * clean one, else "your recent spending" rather than a bank's raw string.
     */
    internal fun questionTitle(merchant: String): String =
        shopName(merchant)?.let { "Penny has a question about your spend at $it" } ?: "Penny has a question about your recent spending"

    /** A shop's name fit for a sentence, or null: no letters, or too long to read at a glance. */
    internal fun shopName(merchant: String): String? =
        com.spendroid.data.PayeeNames.nameFor(merchant.trim())?.trim()?.takeIf { it.length in 2..24 }
            ?: com.spendroid.domain.ShopName.clean(merchant)

    /** Takes Penny's question away once answered in the app. */
    fun repeatAnswered(context: Context, spendId: String) {
        NotificationManagerCompat.from(context).cancel(repeatId(spendId))
    }

    /** Takes the question away once answered in the app, so it isn't answered twice. */
    fun answered(context: Context, spendId: String) {
        NotificationManagerCompat.from(context).cancel(spendId.hashCode())
    }

    internal fun answer(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult) {
        if (intent.action == ACTION_REPEAT) return answerRepeat(context, intent, pending)
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

private fun answerRepeat(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult) {
    val spendId = intent.getStringExtra("spend")
    val twinId = intent.getStringExtra("twin")
    val app = context.applicationContext as? BudgetApplication
    if (spendId == null || twinId == null || app == null) {
        pending.finish()
        return
    }
    SpendQuestions.repeatAnswered(context, spendId)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            app.repository.answerRepeat(spendId, twinId, once = intent.getBooleanExtra("once", false))
            refreshWidgets(context)
            refreshCardWidgets(context)
        } finally {
            pending.finish()
        }
    }
}

class SpendAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SpendQuestions.answer(context, intent, goAsync())
}
