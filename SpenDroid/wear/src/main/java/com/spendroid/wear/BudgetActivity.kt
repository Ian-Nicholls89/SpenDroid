package com.spendroid.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
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

    private val updated = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reading.value = BudgetReading.load(context)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reading.value = BudgetReading.load(this)
        setContent { BudgetScreens(reading.value, onOpenPhone = ::openOnPhone) }
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

    /** Opens SpenDroid on the phone, through the link the phone app answers to. */
    private fun openOnPhone() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spendroid://open"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching { RemoteActivityHelper(this).startRemoteActivity(intent) }
            .onSuccess { Toast.makeText(this, "Opening on your phone", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Couldn't reach your phone", Toast.LENGTH_SHORT).show() }
    }
}
