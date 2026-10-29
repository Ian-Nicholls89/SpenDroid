package com.spendroid.ui

import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spendroid.data.MoneyWatch
import com.spendroid.data.Privacy
import com.spendroid.domain.PriceChanges
import com.spendroid.ui.theme.Charcoal
import kotlinx.coroutines.launch

/** Settings → Notifications: telling you when a bill goes up, and when an account is heading below zero. */
@Composable
internal fun BillsAndBalancesSection(onChanged: () -> Unit) {
    val context = LocalContext.current
    val watch = remember { MoneyWatch(context) }
    var rises by remember { mutableStateOf(watch.riseAlerts) }
    var threshold by remember { mutableStateOf(watch.threshold) }
    var variable by remember { mutableStateOf(watch.includeVariable) }
    var drops by remember { mutableStateOf(watch.showDrops) }
    var overdraft by remember { mutableStateOf(watch.overdraftAlerts) }
    Panel {
        SectionHeading("Bills & balances")
        SettingSwitch("Tell me when a bill goes up", "A notification, once per rise", rises) { rises = it; watch.riseAlerts = it }
        if (rises) {
            Text("Only if it's up by at least", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PriceChanges.Threshold.entries.forEach { t ->
                    SmallChoice(t.label, t == threshold) { threshold = t; watch.threshold = t }
                }
            }
        }
        SettingSwitch("Include bills that vary", "Energy on a smart meter and the like. Card bills never count.", variable) {
            variable = it; watch.includeVariable = it; onChanged()
        }
        SettingSwitch("Show price drops too", "Quietly, on Regular - never a notification", drops) { drops = it; watch.showDrops = it }
        SettingSwitch("Warn me before an account goes overdrawn", "When the forecast dips below £0 in the next month - once", overdraft) { overdraft = it; watch.overdraftAlerts = it }
    }
}

/**
 * Settings → Privacy & security: the app lock - Android's own fingerprint, face or PIN - how soon it locks
 * again, hiding from recent apps, and what shows outside the app while it's locked.
 */
@Composable
internal fun PrivacySection(onConfirmLock: (onDone: (Boolean) -> Unit) -> Unit, onHideInRecents: (Boolean) -> Unit) {
    val context = LocalContext.current
    val privacy = remember { Privacy(context) }
    var lock by remember { mutableStateOf(privacy.lockEnabled) }
    var after by remember { mutableStateOf(privacy.lockAfter) }
    var hide by remember { mutableStateOf(privacy.hideInRecents) }
    var widgets by remember { mutableStateOf(privacy.stored("widget_figures")) }
    var notifications by remember { mutableStateOf(privacy.stored("notification_figures")) }
    var watch by remember { mutableStateOf(privacy.stored("watch_figures")) }
    var note by remember { mutableStateOf<String?>(null) }
    val canLock = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
    Panel {
        SectionHeading("App lock")
        SettingSwitch("Lock SpenDroid", "Fingerprint, face or your phone's PIN", lock) { on ->
            if (!on) {
                lock = false; privacy.lockEnabled = false; note = null
            } else if (!canLock) {
                note = "Set up a screen lock on your phone first - SpenDroid uses the phone's own."
            } else {
                // Proved once on turning it on, so it can't lock out someone who can't unlock.
                onConfirmLock { ok -> if (ok) { lock = true; privacy.lockEnabled = true; note = null } }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Charcoal.Warn, modifier = Modifier.padding(top = 4.dp)) }
        if (lock) {
            Text("Lock again after", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Privacy.LockAfter.entries.forEach { a -> SmallChoice(a.label, a == after) { after = a; privacy.lockAfter = a } }
            }
        }
        SettingSwitch("Hide it in recent apps", "A blank card in the app switcher, and no screenshots", hide) {
            hide = it; privacy.hideInRecents = it; onHideInRecents(it)
        }
    }
    if (lock) {
        Panel {
            SectionHeading("Outside the app")
            Text(
                "With the lock on, these can hide your figures anywhere outside SpenDroid - all the time, as a widget can't tell when the app locks.",
                style = MaterialTheme.typography.labelSmall,
                color = Charcoal.Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            SettingSwitch("Figures on widgets", "Off: they show £••• and \"Locked\"", widgets) { widgets = it; privacy.widgetFigures = it; refreshAll(context) }
            SettingSwitch("Figures in notifications", "Off: notifications without amounts", notifications) { notifications = it; privacy.notificationFigures = it }
            SettingSwitch("Figures on the watch", "Your watch locks with your wrist", watch) { watch = it; privacy.watchFigures = it; refreshAll(context) }
        }
    }
    EncryptionPanel()
}

/**
 * The database encrypted on the phone: opt-in while it's road-tested. Turning it on or off
 * restarts SpenDroid, and the change is made as it starts - see [com.spendroid.data.DbEncryption].
 */
@Composable
private fun EncryptionPanel() {
    val context = LocalContext.current
    val on = remember { com.spendroid.data.DbEncryption.isOn(context) }
    var note by remember { mutableStateOf(com.spendroid.data.DbEncryption.note(context)) }
    var asking by remember { mutableStateOf<Boolean?>(null) }
    Panel {
        SectionHeading("Data on this phone")
        SettingSwitch(
            "Encrypt it",
            if (on) "On: your history is stored encrypted, with a key only this phone has" else "Your history stored encrypted, with a key only this phone has",
            on,
        ) { asking = it }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Charcoal.Warn, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = { com.spendroid.data.DbEncryption.clearNote(context); note = null }) { Text("OK") }
        }
    }
    asking?.let { turnOn ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(if (turnOn) "Encrypt your data?" else "Stop encrypting?") },
            text = {
                Text(
                    if (turnOn) {
                        "SpenDroid restarts, and writes your history out again encrypted - a few seconds. It checks every " +
                            "row came across before it swaps, and keeps the old copy until the new one has opened.\n\n" +
                            "Export a backup first (Settings → Backups & data), to be safe.\n\n" +
                            "The key stays on this phone. Android's own backup can't bring encrypted data to a new phone - " +
                            "SpenDroid's backup is the way to move it."
                    } else {
                        "SpenDroid restarts, and writes your history out again unencrypted, checking every row came across."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    com.spendroid.data.DbEncryption.request(context, turnOn)
                    restart(context)
                }) { Text(if (turnOn) "Restart and encrypt" else "Restart and decrypt") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Not now") } },
        )
    }
}

/** SpenDroid closed and opened again: the database is only changed while nothing has it open. */
private fun restart(context: android.content.Context) {
    val app = context.applicationContext
    val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
    val pending = android.app.PendingIntent.getActivity(app, 7001, launch, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT)
    app.getSystemService(android.app.AlarmManager::class.java).set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 500, pending)
    kotlin.system.exitProcess(0)
}

/** Widgets and the watch show figures by these settings, so they're redrawn when one changes. */
private fun refreshAll(context: android.content.Context) {
    val app = context.applicationContext
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        runCatching {
            com.spendroid.widget.refreshWidgets(app)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(note, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SmallChoice(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = if (on) Color(0xFF111111) else Color(0xFFCFD2D8),
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) MaterialTheme.colorScheme.primary else Charcoal.PanelHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * What shows while SpenDroid is locked: nothing of the app's - the wordmark and a way in. Asks
 * straight away; "Unlock" asks again if the prompt was dismissed.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onUnlock() }
    val accent = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(Charcoal.Background).clickable(onClick = onUnlock),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark("SpenDroid", accent)
            Text("Locked", style = MaterialTheme.typography.bodyMedium, color = Charcoal.Muted, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Unlock",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
                color = Color(0xFF111111),
                modifier = Modifier.padding(top = 28.dp).clip(RoundedCornerShape(14.dp)).background(accent)
                    .clickable(onClick = onUnlock).padding(horizontal = 36.dp, vertical = 14.dp),
            )
        }
    }
}
