package com.spendroid.widget

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.spendroid.BudgetApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * What the watch asks of the phone: for now, a new category for a recent payment. It is set as
 * if chosen on the phone, and the widgets and the watch are redrawn so both show it.
 */
class WatchMessageService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != CATEGORY_PATH) return
        val parts = String(event.data, Charsets.UTF_8).split(WatchSync.SEP)
        if (parts.size != 3) return
        val (accountId, transactionId, category) = parts
        val app = applicationContext as? BudgetApplication ?: return
        scope.launch {
            runCatching { app.repository.setCategoryOverride(accountId, transactionId, category) }
            refreshWidgets(applicationContext)
            refreshCardWidgets(applicationContext)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CATEGORY_PATH = "/spendroid/category"
    }
}
