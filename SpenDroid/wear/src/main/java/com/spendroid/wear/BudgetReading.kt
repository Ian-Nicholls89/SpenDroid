package com.spendroid.wear

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.wearable.DataMap

/**
 * The figures the phone sends: the same ones its home-screen widget shows.
 *
 * The keys are shared with the phone's WatchSync by value, not by code - the two are separate
 * apps - so a key renamed on one side must be renamed on the other.
 */
data class BudgetReading(
    /** The headline figure, already formatted on the phone: "£430". */
    val available: String,
    /** Share of the cycle's budget left, 0 to 1. */
    val budgetLeft: Float,
    /** Share of the pay cycle still to run, 0 to 1, or null when the cycle is not known. */
    val cycleLeft: Float?,
    /** BudgetPace.Pace on the phone, by name. */
    val pace: String,
    /** When the phone worked it out, epoch millis. */
    val updatedAt: Long,
) {
    companion object {
        const val PATH = "/spendroid/budget"
        const val KEY_AVAILABLE = "available"
        const val KEY_BUDGET_LEFT = "budgetLeft"
        const val KEY_CYCLE_LEFT = "cycleLeft"
        const val KEY_PACE = "pace"
        const val KEY_UPDATED_AT = "updatedAt"

        private const val PREFS = "budget_reading"

        fun from(map: DataMap): BudgetReading? {
            val available = map.getString(KEY_AVAILABLE) ?: return null
            return BudgetReading(
                available = available,
                budgetLeft = map.getFloat(KEY_BUDGET_LEFT, 1f),
                cycleLeft = map.getFloat(KEY_CYCLE_LEFT, -1f).takeIf { it >= 0f },
                pace = map.getString(KEY_PACE) ?: "ON_TRACK",
                updatedAt = map.getLong(KEY_UPDATED_AT, 0L),
            )
        }

        fun load(context: Context): BudgetReading? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val available = prefs.getString(KEY_AVAILABLE, null) ?: return null
            return BudgetReading(
                available = available,
                budgetLeft = prefs.getFloat(KEY_BUDGET_LEFT, 1f),
                cycleLeft = prefs.getFloat(KEY_CYCLE_LEFT, -1f).takeIf { it >= 0f },
                pace = prefs.getString(KEY_PACE, null) ?: "ON_TRACK",
                updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_AVAILABLE, available)
            putFloat(KEY_BUDGET_LEFT, budgetLeft)
            putFloat(KEY_CYCLE_LEFT, cycleLeft ?: -1f)
            putString(KEY_PACE, pace)
            putLong(KEY_UPDATED_AT, updatedAt)
        }
    }
}
