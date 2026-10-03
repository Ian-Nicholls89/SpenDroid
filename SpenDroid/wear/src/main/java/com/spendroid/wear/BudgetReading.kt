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

    /** The phone's accent, for the glow and the curved title. Orange until a phone says otherwise. */
    val accent: Int get() = map.getInt("accent", 0xFFF5A623.toInt())
    /** Share of the budget used, 0 to 1, for the bar; null when unknown. */
    val used: Float? get() = map.getFloat("used", -1f).takeIf { it >= 0f }
    /** Share of the cycle gone, 0 to 1, for the bar's tick; null when unknown. */
    val elapsed: Float? get() = map.getFloat("elapsed", -1f).takeIf { it >= 0f }

    /** One card's screen. */
    class Card(private val map: DataMap) {
        fun text(key: String): String = map.getString(key).orEmpty()
        /** The account's own colour on the phone. */
        val colour: Int get() = map.getInt("colour", 0xFF9B5DE5.toInt())
        /** The card's regular payments still to come this statement, as a share of the bar. */
        val toComeShare: Float get() = map.getFloat("toComeShare", 0f)
        val capShare: Float? get() = map.getFloat("capShare", -1f).takeIf { it >= 0f }
        val statementGone: Float? get() = map.getFloat("statementGone", -1f).takeIf { it >= 0f }
        val over: Boolean get() = map.getBoolean("over", false)
    }

    val cards: List<Card> get() = map.getDataMapArrayList("cards").orEmpty().map { Card(it) }

    data class Upcoming(
        val name: String,
        val date: String,
        val amount: String,
        /** To the pound, for a poster. */
        val short: String = amount,
        val card: Boolean = false,
        val colour: Int = 0xFF5A5FD8.toInt(),
    )

    val upcoming: List<Upcoming>
        get() = map.getDataMapArrayList("upcoming").orEmpty().map {
            Upcoming(
                it.getString("name").orEmpty(),
                it.getString("date").orEmpty(),
                it.getString("amount").orEmpty(),
                it.getString("short") ?: it.getString("amount").orEmpty(),
                it.getBoolean("card"),
                it.getInt("colour", 0xFF5A5FD8.toInt()),
            )
        }

    val week: LongArray get() = map.getLongArray("week") ?: LongArray(0)

    /** A recent payment, recategorisable from the wrist. */
    data class Recent(val id: String, val payee: String, val amount: String, val category: String, val colour: Int, val pending: Boolean, val day: String = "")

    val recent: List<Recent>
        get() = map.getDataMapArrayList("recent").orEmpty().map {
            Recent(
                it.getString("id").orEmpty(),
                it.getString("payee").orEmpty(),
                it.getString("amount").orEmpty(),
                it.getString("category").orEmpty(),
                it.getInt("colour"),
                it.getBoolean("pending"),
                it.getString("day").orEmpty(),
            )
        }

    /** A category a payment can be put in: its name for the phone, its label and colour to show. */
    data class Option(val name: String, val label: String, val colour: Int)

    val categoryOptions: List<Option>
        get() = map.getDataMapArrayList("categoryOptions").orEmpty().map {
            Option(it.getString("name").orEmpty(), it.getString("label").orEmpty(), it.getInt("colour"))
        }

    /** This cycle's spending in one category, against the user's limit where there is one. */
    data class CategorySpend(
        val label: String,
        val spent: String,
        val limit: String,
        val share: Float?,
        val over: Boolean,
        val colour: Int,
        val minor: Long,
    )

    val categories: List<CategorySpend>
        get() = map.getDataMapArrayList("categories").orEmpty().map {
            CategorySpend(
                it.getString("label").orEmpty(),
                it.getString("spent").orEmpty(),
                it.getString("limit").orEmpty(),
                it.getFloat("share", -1f).takeIf { s -> s >= 0f },
                it.getBoolean("over"),
                it.getInt("colour"),
                it.getLong("minor"),
            )
        }

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
