package com.spendroid.data

import android.content.Context
import androidx.core.content.edit
import com.spendroid.domain.PriceChanges
import java.time.LocalDate

/**
 * What the user wants to hear about bills' prices and the forecast, and what they've already
 * heard: each price change and each overdraft warning is said once, and a decision on a change -
 * expected, a one-off, or "remind me" - sticks to it.
 */
class MoneyWatch(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("money_watch", Context.MODE_PRIVATE)

    var riseAlerts: Boolean
        get() = prefs.getBoolean("rise_alerts", true)
        set(v) = prefs.edit { putBoolean("rise_alerts", v) }

    var threshold: PriceChanges.Threshold
        get() = PriceChanges.Threshold.from(prefs.getString("threshold", null))
        set(v) = prefs.edit { putString("threshold", v.name) }

    /** Bills whose amount varies anyway - metered energy - count as rises too. */
    var includeVariable: Boolean
        get() = prefs.getBoolean("include_variable", false)
        set(v) = prefs.edit { putBoolean("include_variable", v) }

    /** Price drops shown on Regular as well as rises. Never a notification. */
    var showDrops: Boolean
        get() = prefs.getBoolean("show_drops", false)
        set(v) = prefs.edit { putBoolean("show_drops", v) }

    var overdraftAlerts: Boolean
        get() = prefs.getBoolean("overdraft_alerts", true)
        set(v) = prefs.edit { putBoolean("overdraft_alerts", v) }

    enum class Decision { EXPECTED, ONE_OFF, REMIND }

    fun decision(changeKey: String): Decision? =
        prefs.getString("decision:$changeKey", null)?.substringBefore('@')?.let { n -> Decision.entries.firstOrNull { it.name == n } }

    fun decide(changeKey: String, decision: Decision, today: LocalDate = LocalDate.now()) = prefs.edit {
        putString("decision:$changeKey", if (decision == Decision.REMIND) "${decision.name}@${today.plusDays(7)}" else decision.name)
    }

    /** Changes the user asked to be reminded about, now due. */
    fun remindersDue(today: LocalDate): List<String> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith("decision:")) return@mapNotNull null
        val s = v as? String ?: return@mapNotNull null
        if (!s.startsWith("REMIND@")) return@mapNotNull null
        val on = runCatching { LocalDate.parse(s.substringAfter('@')) }.getOrNull() ?: return@mapNotNull null
        if (on.isAfter(today)) null else k.removePrefix("decision:")
    }

    /** A reminder given: it becomes "expected" - one nudge, not a nag. */
    fun reminded(changeKey: String) = decide(changeKey, Decision.EXPECTED)

    fun told(key: String): Boolean = key in prefs.getStringSet("told", emptySet()).orEmpty()

    fun tell(keys: Collection<String>) {
        if (keys.isEmpty()) return
        // Only the last few hundred: old ones can't come round again.
        val all = (prefs.getStringSet("told", emptySet()).orEmpty() + keys).toList().takeLast(400).toSet()
        prefs.edit { putStringSet("told", all) }
    }
}
