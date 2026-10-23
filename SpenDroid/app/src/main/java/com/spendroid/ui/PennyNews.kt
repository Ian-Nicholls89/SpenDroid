package com.spendroid.ui

import android.content.Context
import com.spendroid.domain.Cadence
import com.spendroid.domain.Direction
import com.spendroid.domain.RecurringRule

/**
 * What Penny has noticed for herself, said once on Home in place of her line on the day: a
 * card's last four digits she's learnt from a payment. (A new regular bill she asks about
 * instead - see [PennyBills].) Each is
 * said the first time it's there and then known; what was there when this began is known
 * already, so she doesn't announce everything at once.
 */
object PennyNews {

    data class News(val key: String, val line: String, val ruleKey: String? = null)

    private const val PREFS = com.spendroid.data.Stores.PENNY_NEWS
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

    /** Everything there is to say about: the cards whose digits she knows. */
    fun of(state: RootUiState): List<News> = state.accounts.mapNotNull { a ->
        val digits = a.cardLastFour ?: return@mapNotNull null
        News(digitsKey(a.id, digits), "I've learnt your ${a.label.trim()} ends $digits, so I can tell its payments apart now.")
    }
}

/**
 * Bills Penny has found and asks about before planning for them: a car park visited now and then
 * can look regular. Until answered a new one is planned for, the cautious way round. What was
 * already planned for when this began is taken as said - except a quarterly or yearly "bill"
 * seen only twice, the likeliest to be a coincidence, which she asks about too.
 */
object PennyBills {
    private const val PREFS = com.spendroid.data.Stores.PENNY_BILLS
    private const val CONFIRMED = "confirmed"
    private const val NOT_BILLS = "notBills"
    private const val STARTED = "started"

    /** Changes as answers are given, so what's asked is worked out again. */
    val answered = androidx.compose.runtime.mutableIntStateOf(0)

    fun id(rule: RecurringRule): String = rule.payee.lowercase().filter { it.isLetterOrDigit() }

    private fun candidates(state: RootUiState): List<RecurringRule> {
        // Paying off a card isn't a bill to ask about: the card's own bill is worked out apart.
        val cardPayments = state.budget?.cardPaymentKeys.orEmpty()
        val payingCards = state.transactions
            .filter { it.isCardPayment || it.isInternalTransfer || "${it.accountId}|${it.transactionId}" in cardPayments }
        return state.rules
            .filter { it.direction == Direction.OUT && !it.internalTransfer && !it.isManual && it.key !in state.ignoredRules }
            .filter { !it.key.startsWith(com.spendroid.domain.CARD_BILL_KEY_PREFIX) }
            .filter { rule -> payingCards.none { com.spendroid.domain.RecurringAnalyzer.matches(rule, it) } }
            .distinctBy { id(it) }
    }

    private fun weak(rule: RecurringRule) = (rule.cadence == Cadence.QUARTERLY || rule.cadence == Cadence.ANNUAL) && rule.occurrences <= 2

    /** The bills to ask about now. */
    fun toAsk(context: Context, state: RootUiState): List<RecurringRule> {
        if (state.budget == null || state.rules.isEmpty()) return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = candidates(state)
        if (!prefs.getBoolean(STARTED, false)) {
            prefs.edit().putStringSet(CONFIRMED, all.filterNot(::weak).map(::id).toSet()).putBoolean(STARTED, true).apply()
        }
        val confirmed = prefs.getStringSet(CONFIRMED, emptySet()).orEmpty()
        return all.filter { id(it) !in confirmed }
    }

    /**
     * Answered, either way: not asked about again. A "just spending" is kept by payee too, as a
     * rule's key carries its amount: the car park at a new price is a new rule, and still not a bill.
     */
    fun answer(context: Context, rule: RecurringRule, isBill: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notBills = prefs.getStringSet(NOT_BILLS, emptySet()).orEmpty()
        prefs.edit()
            .putStringSet(CONFIRMED, prefs.getStringSet(CONFIRMED, emptySet()).orEmpty() + id(rule))
            .putStringSet(NOT_BILLS, if (isBill) notBills - id(rule) else notBills + id(rule))
            .apply()
        answered.intValue++
    }

    /** Rules for a payee said to be just spending that aren't ignored yet - a new amount, say. */
    fun stillToIgnore(context: Context, state: RootUiState): List<String> {
        val notBills = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(NOT_BILLS, emptySet()).orEmpty()
        if (notBills.isEmpty()) return emptyList()
        return state.rules.filter { !it.isManual && it.direction == Direction.OUT && it.key !in state.ignoredRules && id(it) in notBills }.map { it.key }
    }

    /** How often, said plainly: "around the 14th each month". */
    fun often(rule: RecurringRule): String = when (rule.cadence) {
        Cadence.WEEKLY -> "every week"
        Cadence.FORTNIGHTLY -> "every two weeks"
        Cadence.MONTHLY -> "around the ${rule.anchorDay}${suffix(rule.anchorDay)} each month"
        Cadence.MONTHLY_LAST_DAY, Cadence.MONTHLY_LAST_BUSINESS_DAY -> "at the end of each month"
        Cadence.QUARTERLY -> "about every three months"
        Cadence.ANNUAL -> "about once a year"
    }

    private fun suffix(day: Int): String = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
}
