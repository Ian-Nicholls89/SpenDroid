package com.spendroid.work

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spendroid.BudgetApplication
import com.spendroid.widget.refreshCardWidgets
import com.spendroid.widget.refreshWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads card payments from the notifications of the apps the user picked - Google Wallet and
 * their bank apps - so spending shows the moment it happens rather than at the next bank sync.
 *
 * Every other app's notifications are dropped before anything in them is looked at, and
 * nothing read here leaves the phone.
 */
class SpendListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        // A group's summary repeats its children, and an ongoing one is a status, not news.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val app = applicationContext as? BudgetApplication ?: return
        val source = sbn.packageName
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()
        scope.launch {
            val repo = app.repository
            if (source !in repo.watchedPackages()) return@launch
            val changed = runCatching { repo.onNotification(source, title, text, sbn.postTime) }.getOrDefault(false)
            if (changed) {
                refreshWidgets(applicationContext)
                refreshCardWidgets(applicationContext)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
