package com.spendroid.wear

import android.content.ComponentName
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** Keeps the latest figures from the phone and redraws the complication when they change. */
class BudgetListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        var changed = false
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val item = event.dataItem
            if (item.uri.path == Roundup.PATH) {
                Roundup.from(DataMapItem.fromDataItem(item).dataMap)?.let { Roundup.receive(this, it) }
                return@forEach
            }
            if (item.uri.path != BudgetReading.PATH) return@forEach
            BudgetReading.from(DataMapItem.fromDataItem(item).dataMap)?.let {
                it.save(this)
                changed = true
            }
        }
        if (changed) {
            requestRedraw(this)
            SpenDroidTileService.requestUpdates(this)
            // An open watch app shows the new figures at once.
            sendBroadcast(android.content.Intent(ACTION_UPDATED).setPackage(packageName))
        }
    }

    companion object {
        const val ACTION_UPDATED = "com.spendroid.wear.BUDGET_UPDATED"

        fun requestRedraw(context: android.content.Context) {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, BudgetComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
