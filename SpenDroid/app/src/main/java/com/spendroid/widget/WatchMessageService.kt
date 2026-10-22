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
        // The watch app crashed: kept with the phone's, to send from Settings.
        if (event.path == CRASH_PATH) {
            val body = String(event.data, Charsets.UTF_8)
            com.spendroid.data.CrashLog.record(applicationContext, "watch " + body.substringBefore('\n'), body.substringAfter('\n'))
            return
        }
        if (event.path != CATEGORY_PATH) return
        val parts = String(event.data, Charsets.UTF_8).split(WatchSync.SEP)
        if (parts.size != 3) return
        val (accountId, transactionId, category) = parts
        val app = applicationContext as? BudgetApplication ?: return
        scope.launch {
            runCatching { app.repository.setCategoryOverride(accountId, transactionId, category) }
            refreshWidgets(applicationContext)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CATEGORY_PATH = "/spendroid/category"
        const val CRASH_PATH = "/spendroid/crash"
    }
}
