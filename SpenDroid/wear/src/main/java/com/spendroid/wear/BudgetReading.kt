package com.spendroid.wear

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.google.android.gms.wearable.DataMap

/**
 * The figures the phone sends: its home screen's, formatted there so both apps word them alike.
 *
 * The keys are shared with the phone's WatchSync by value, not by code - the two are separate
 * apps - so a key renamed on one side must be renamed on the other. The whole message is kept,
 * so a screen can read what it needs from [map].
 */
class BudgetReading(val map: DataMap) {

    /** The headline figure, to the pound: "£104". */
    val available: String get() = map.getString("available") ?: "–"
    /** Share of the cycle's budget left, 0 to 1. */
    val budgetLeft: Float get() = map.getFloat("budgetLeft", 1f)
    /** Share of the pay cycle still to run, 0 to 1, or null when the cycle is not known. */
    val cycleLeft: Float? get() = map.getFloat("cycleLeft", -1f).takeIf { it >= 0f }
    /** BudgetPace.Pace on the phone, by name. */
    val pace: String get() = map.getString("pace") ?: "ON_TRACK"

    fun text(key: String): String = map.getString(key).orEmpty()

    /** One card's screen. */
    class Card(private val map: DataMap) {
        fun text(key: String): String = map.getString(key).orEmpty()
        val capShare: Float? get() = map.getFloat("capShare", -1f).takeIf { it >= 0f }
        val statementGone: Float? get() = map.getFloat("statementGone", -1f).takeIf { it >= 0f }
        val over: Boolean get() = map.getBoolean("over", false)
    }

    val cards: List<Card> get() = map.getDataMapArrayList("cards").orEmpty().map { Card(it) }

    data class Upcoming(val name: String, val date: String, val amount: String)

    val upcoming: List<Upcoming>
        get() = map.getDataMapArrayList("upcoming").orEmpty().map {
            Upcoming(it.getString("name").orEmpty(), it.getString("date").orEmpty(), it.getString("amount").orEmpty())
        }

    val week: LongArray get() = map.getLongArray("week") ?: LongArray(0)

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_MAP, Base64.encodeToString(map.toByteArray(), Base64.NO_WRAP))
        }
    }

    companion object {
        const val PATH = "/spendroid/budget"
        const val PREFS = "budget_reading"
        const val KEY_MAP = "map"

        fun from(map: DataMap): BudgetReading? = map.getString("available")?.let { BudgetReading(map) }

        fun load(context: Context): BudgetReading? {
            val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAP, null) ?: return null
            return runCatching { from(DataMap.fromByteArray(Base64.decode(encoded, Base64.NO_WRAP))) }.getOrNull()
        }

        /** A made-up reading, for the complication picker's preview. */
        fun preview(): BudgetReading = BudgetReading(
            DataMap().apply {
                putString("available", "£430")
                putFloat("budgetLeft", 0.52f)
                putFloat("cycleLeft", 0.60f)
                putString("pace", "TIGHT")
            },
        )
    }
}
