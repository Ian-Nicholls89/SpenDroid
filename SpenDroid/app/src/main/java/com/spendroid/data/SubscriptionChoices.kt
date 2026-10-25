package com.spendroid.data

import android.content.Context
import androidx.core.content.edit
import com.spendroid.domain.Subscriptions
import java.time.LocalDate

/**
 * What the user has said about subscriptions: which payments are subscriptions and which are
 * household bills, which they want reminding about before they renew, and which they've
 * cancelled - and since when, so a charge after that can be told.
 */
class SubscriptionChoices(context: Context) {

    private val prefs = Stores.prefs(context, Stores.SUBSCRIPTIONS)

    fun kinds(): Map<String, Subscriptions.Kind> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith("kind:")) return@mapNotNull null
        Subscriptions.Kind.entries.firstOrNull { it.name == v }?.let { k.removePrefix("kind:") to it }
    }.toMap()

    fun setKind(ruleKey: String, kind: Subscriptions.Kind) = prefs.edit { putString("kind:$ruleKey", kind.name) }

    fun remind(ruleKey: String): Boolean = prefs.getBoolean("remind:$ruleKey", false)

    fun setRemind(ruleKey: String, on: Boolean) = prefs.edit { putBoolean("remind:$ruleKey", on) }

    fun reminders(): Set<String> = prefs.all.filter { (k, v) -> k.startsWith("remind:") && v == true }.keys.map { it.removePrefix("remind:") }.toSet()

    /** Cancelled, by rule key: the day the user said so. */
    fun cancelled(): Map<String, LocalDate> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith("cancelled:")) return@mapNotNull null
        runCatching { LocalDate.parse(v as String) }.getOrNull()?.let { k.removePrefix("cancelled:") to it }
    }.toMap()

    fun setCancelled(ruleKey: String, on: Boolean, today: LocalDate = LocalDate.now()) = prefs.edit {
        if (on) putString("cancelled:$ruleKey", today.toString()) else remove("cancelled:$ruleKey")
    }
}
