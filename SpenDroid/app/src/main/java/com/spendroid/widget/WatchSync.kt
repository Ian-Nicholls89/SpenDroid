package com.spendroid.widget

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.spendroid.BudgetApplication
import com.spendroid.domain.BudgetPace
import com.spendroid.ui.formatMoney
import kotlinx.coroutines.tasks.await

/**
 * Sends the watch the same figures the home-screen widget shows, whenever the widget redraws.
 *
 * The keys are shared with the watch app's BudgetReading by value - the two are separate apps -
 * so a key renamed here must be renamed there. With no watch, or no Play services, this does
 * nothing: the data layer keeps the item until a watch connects, and failing is not an error.
 */
internal object WatchSync {

    private const val PATH = "/spendroid/budget"

    suspend fun push(context: Context) {
        runCatching {
            val app = context.applicationContext as? BudgetApplication ?: return
            val snapshot = app.repository.budgetSnapshot() ?: return
            val elapsed = BudgetPace.elapsedFraction(snapshot)
            val request = PutDataMapRequest.create(PATH).apply {
                dataMap.putString("available", poundsOnly(snapshot.availableToSpend, snapshot.baseCurrency))
                dataMap.putFloat("budgetLeft", BudgetPace.remainingFraction(snapshot))
                dataMap.putFloat("cycleLeft", elapsed?.let { 1f - it } ?: -1f)
                dataMap.putString("pace", BudgetPace.of(snapshot).name)
                dataMap.putLong("updatedAt", System.currentTimeMillis())
            }
            // Urgent, so the watch hears now rather than whenever the phone next batches.
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()).await()
        }
    }

    /** "£430": a complication has room for a few characters, not pence. As the widget does it. */
    private fun poundsOnly(minor: Long, currency: String): String =
        formatMoney((minor / 100L) * 100L, currency).replace(Regex("[.,]00\\b"), "")
}
