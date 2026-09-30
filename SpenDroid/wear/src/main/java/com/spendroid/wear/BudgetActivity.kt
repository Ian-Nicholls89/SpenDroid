package com.spendroid.wear

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper

/**
 * What a tap on the complication opens: the phone's home screen, a screen at a time, turned
 * with the crown. Read-only - the figures are the phone's, sent whenever its widget redraws.
 */
class BudgetActivity : ComponentActivity() {

    private val reading = mutableStateOf<BudgetReading?>(null)

    /** The roundup to show first, when a tap on its icon opened the app. */
    private val roundup = mutableStateOf<Roundup?>(null)

    private val updated = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reading.value = BudgetReading.load(context)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reading.value = BudgetReading.load(this)
        showRoundupIf(intent)
        setContent {
            BudgetScreens(
                reading.value,
                onOpenPhone = ::openOnPhone,
                roundup = roundup.value,
                onRoundupShown = { roundup.value = null },
            )
        }
        // The roundup's icon on the watch face is a notification, which needs asking for once.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showRoundupIf(intent)
    }

    /** Opened from the roundup's icon: show it, and take the icon off the watch face. */
    private fun showRoundupIf(intent: Intent?) {
        if (intent?.getBooleanExtra(Roundup.EXTRA_OPEN, false) != true) return
        intent.removeExtra(Roundup.EXTRA_OPEN)
        roundup.value = Roundup.load(this)
        Roundup.markRead(this)
    }

    override fun onStart() {
        super.onStart()
        reading.value = BudgetReading.load(this)
        ContextCompat.registerReceiver(
            this,
            updated,
            IntentFilter(BudgetListenerService.ACTION_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(updated)
        super.onStop()
    }

    /**
     * Opens SpenDroid on the phone, through the link the phone app answers to. True when the request
     * went, so the standard "Open on phone" animation plays; a failure says so instead.
     */
    private fun openOnPhone(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spendroid://open"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return runCatching { RemoteActivityHelper(this).startRemoteActivity(intent) }
            .onFailure { Toast.makeText(this, "Couldn't reach your phone", Toast.LENGTH_SHORT).show() }
            .isSuccess
    }
}
