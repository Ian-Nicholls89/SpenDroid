package com.spendroid.ui

import android.content.Context
import com.spendroid.domain.Cadence
import com.spendroid.domain.Direction
import com.spendroid.domain.RecurringRule

/**
 * What Penny has noticed for herself, said once on Home in place of her line on the day: a new
 * regular bill she's found, or a card's last four digits she's learnt from a payment. Each is
 * said the first time it's there and then known; what was there when this began is known
 * already, so she doesn't announce everything at once.
 */
object PennyNews {

    data class News(val key: String, val line: String, val ruleKey: String? = null)

    private const val PREFS = "penny_news"
    private const val KNOWN = "known"
    private const val STARTED = "started"

    /** The next thing to say, if there's one she hasn't said. */
    fun next(context: Context, state: RootUiState): News? {
        // Only once the figures are in, or everything would look new while they load.
        if (state.budget == null || state.rules.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = of(state)
        if (!prefs.getBoolean(STARTED, false)) {
            prefs.edit().putStringSet(KNOWN, all.map { it.key }.toSet()).putBoolean(STARTED, true).apply()
            return null
        }
        val known = prefs.getStringSet(KNOWN, emptySet()).orEmpty()
        return all.firstOrNull { it.key !in known }
    }

    /** Said, or not worth saying - typed in by the user, say. */
    fun know(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KNOWN, prefs.getStringSet(KNOWN, emptySet()).orEmpty() + key).apply()
    }

    fun digitsKey(accountId: String, digits: String) = "digits:$accountId:$digits"

    /** Everything there is to say about: new bills first, then cards. */
    fun of(state: RootUiState): List<News> {
        val bills = state.rules
            .filter { it.direction == Direction.OUT && !it.internalTransfer && !it.isManual && it.key !in state.ignoredRules }
            .map { rule -> News("bill:${rule.payee.lowercase().filter { it.isLetterOrDigit() }}", billLine(rule), rule.key) }
            .distinctBy { it.key }
        val cards = state.accounts.mapNotNull { a ->
            val digits = a.cardLastFour ?: return@mapNotNull null
            News(digitsKey(a.id, digits), "I've learnt your ${a.label.trim()} ends $digits, so I can tell its payments apart now.")
        }
        return bills + cards
    }

    private fun billLine(rule: RecurringRule): String {
        val money = formatMoney(kotlin.math.abs(rule.amountMinor), rule.currency)
        val payee = rule.payee.tidyPayee().ifBlank { rule.payee }
        val often = when (rule.cadence) {
            Cadence.WEEKLY -> "every week"
            Cadence.FORTNIGHTLY -> "every two weeks"
            Cadence.MONTHLY -> "around the ${rule.anchorDay}${suffix(rule.anchorDay)} each month"
            Cadence.MONTHLY_LAST_DAY, Cadence.MONTHLY_LAST_BUSINESS_DAY -> "at the end of each month"
            Cadence.QUARTERLY -> "every three months"
            Cadence.ANNUAL -> "once a year"
        }
        return "I've spotted a new regular bill: $payee, $money $often. I'll plan for it from now on."
    }

    private fun suffix(day: Int): String = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
}
