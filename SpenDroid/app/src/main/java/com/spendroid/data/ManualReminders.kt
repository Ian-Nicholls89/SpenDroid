package com.spendroid.data

import android.content.Context
import androidx.core.content.edit
import com.spendroid.domain.ManualAccounts

/**
 * The two small habits that keep an account with no bank link right, as the user chose them:
 * how often to import a statement, and when to check the balance against the bank's.
 */
class ManualReminders(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(com.spendroid.data.Stores.MANUAL_REMINDERS, Context.MODE_PRIVATE)

    /** Days between statements; 0 for never. A week by default. */
    var statementEveryDays: Int
        get() = prefs.getInt("statement_every_days", 7)
        set(v) = prefs.edit { putInt("statement_every_days", v) }

    var balanceWhen: ManualAccounts.BalanceWhen
        get() = ManualAccounts.BalanceWhen.entries.firstOrNull { it.name == prefs.getString("balance_when", null) }
            ?: ManualAccounts.BalanceWhen.SUNDAY
        set(v) = prefs.edit { putString("balance_when", v.name) }
}
