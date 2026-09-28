package com.spendroid.widget

import android.content.Context
import androidx.core.content.edit

/** Each "This period" widget's own choices: which accounts, in what order, and which way the lines run. */
internal object ThisPeriodPrefs {

    private const val PREFS = "this_period_widget"
    const val MAX_ACCOUNTS = 3

    data class Choice(val accountIds: List<String>, val drain: Boolean)

    fun load(context: Context, widgetId: Int): Choice? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getString("ids_$widgetId", null) ?: return null
        return Choice(ids.split(',').filter { it.isNotBlank() }, prefs.getBoolean("drain_$widgetId", false))
    }

    fun save(context: Context, widgetId: Int, choice: Choice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("ids_$widgetId", choice.accountIds.take(MAX_ACCOUNTS).joinToString(","))
            putBoolean("drain_$widgetId", choice.drain)
        }
    }

    fun forget(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove("ids_$widgetId")
            remove("drain_$widgetId")
        }
    }
}
