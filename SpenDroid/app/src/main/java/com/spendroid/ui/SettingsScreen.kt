package com.spendroid.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.spendroid.ui.theme.Charcoal
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Switch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.CardTiming
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Three tabs rather than five. Three of the old ones held a single control each, which made
 * the row a way of hiding six settings behind five labels rather than a way of organising
 * them - and five labels sharing a phone's width is what broke "Notifications" into
 * "Notifi / catio / ns" in the first place.
 *
 * Grouped by how often they are touched: things you tune, things you set up once, and things
 * you only read.
 */
/** Settings' sections (0.7.3), grouped as the list shows them; each opens its own page. */
private enum class SettingsPage(val title: String, val group: String?, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    LOOK("Look", "Your budget", Icons.Filled.Palette),
    BUDGET("Budget", "Your budget", Icons.Filled.AccountBalanceWallet),
    NOTIFICATIONS("Notifications", "Your budget", Icons.Filled.Notifications),
    CARD_ALERTS("Card alerts", "Your budget", Icons.Filled.CreditCard),
    BANK("Bank & syncing", "Your data", Icons.Filled.AccountBalance),
    BACKUPS("Backups & data", "Your data", Icons.Filled.CloudUpload),
    PRIVACY("Privacy & security", "Your data", Icons.Filled.Lock),
    WATCH("Watch", "Devices", Icons.Filled.Watch),
    COMPUTER("Computer link", "Devices", Icons.Filled.Computer),
    ABOUT("About", null, Icons.Filled.Info),
}

/** A section in the list: its icon, its name, how it's set now, and the way in. */
@Composable
private fun SettingsRow(page: SettingsPage, status: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(page.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(page.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(status, style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
        }
        Text("›", color = Charcoal.Faint, style = MaterialTheme.typography.titleLarge)
    }
}

/** "last night", "today at 03:12", "on Tue 27 Oct". */
private fun backedUp(at: Long): String {
    val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    val day = time.toLocalDate()
    val today = java.time.LocalDate.now()
    return when {
        day == today && time.hour < 6 -> "last night"
        day == today -> "today at ${time.format(timeFormatter)}"
        day == today.minusDays(1) -> "yesterday"
        else -> "on ${day.format(DateTimeFormatter.ofPattern("EEE d MMM"))}"
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun parseNotificationTime(value: String): LocalTime =
    runCatching { LocalTime.parse(value, timeFormatter) }
        .recoverCatching { LocalTime.parse(value) }
        .getOrDefault(LocalTime.of(21, 0))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: RootUiState,
    onSave: (String, String) -> Unit,
    onSetBudgetModel: (BudgetModel) -> Unit,
    onSetCardTiming: (CardTiming) -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onClearData: () -> Unit,
    onUnlockBackup: (Uri, CharArray) -> Unit = { _, _ -> },
    onCancelRestore: () -> Unit = {},
    onCheckUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onInstallDownloaded: () -> Unit = {},
    secretId: String,
    secretKey: String,
    notificationTime: String,
    onSaveNotificationTime: (String) -> Unit,
    cardAlerts: @Composable () -> Unit = {},
    /** How card alerts are set, for its line in the list. */
    cardAlertsStatus: String = "Payments from bank apps and Wallet",
    look: Look = Look(),
    onSetAccent: (com.spendroid.ui.theme.Accent) -> Unit = {},
    onSetAccountColours: (Boolean) -> Unit = {},
    onSetIconFollows: (Boolean) -> Unit = {},
    /** Proves the user can unlock before the app lock is turned on. */
    onConfirmLock: ((Boolean) -> Unit) -> Unit = { it(true) },
    onHideInRecents: (Boolean) -> Unit = {},
    onReload: () -> Unit = {},
) {
    // 0.7.3: one list of sections, each opening its own page - in place of seven tabs.
    var openPage by rememberSaveable { mutableStateOf<String?>(null) }
    val page = openPage?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
    androidx.activity.compose.BackHandler(enabled = page != null) { openPage = null }
    var secretIdField by rememberSaveable(secretId) { mutableStateOf(secretId) }
    var secretKeyField by rememberSaveable(secretKey) { mutableStateOf(secretKey) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var selectedTime by rememberSaveable { mutableStateOf(parseNotificationTime(notificationTime).format(timeFormatter)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (page == null) {
            val context = LocalContext.current
            fun status(p: SettingsPage): String = when (p) {
                SettingsPage.LOOK -> "${look.accent.label} · " + if (look.accountColours) "accounts in their own colours" else "one colour throughout"
                SettingsPage.BUDGET -> "${state.budgetModel.label} · cards: ${state.cardTiming.label.lowercase()}"
                SettingsPage.NOTIFICATIONS -> {
                    // Only what's actually on.
                    val watch = com.spendroid.data.MoneyWatch(context)
                    listOfNotNull("Roundup at $selectedTime", "bills going up".takeIf { watch.riseAlerts }, "overdraft".takeIf { watch.overdraftAlerts }).joinToString(" · ")
                }
                SettingsPage.CARD_ALERTS -> cardAlertsStatus
                SettingsPage.BANK -> when (val n = state.connections.size) {
                    0 -> if (secretId.isBlank()) "No bank linked" else "GoCardless · no bank linked yet"
                    1 -> "GoCardless · 1 bank link"
                    else -> "GoCardless · $n bank links"
                }
                SettingsPage.BACKUPS -> {
                    val backup = com.spendroid.data.AutoBackup.status(context)
                    when {
                        !com.spendroid.data.AutoBackup.isOn(context) -> "Automatic backup off · export, restore, clear"
                        !backup.ok -> "The last automatic backup failed"
                        backup.lastAt != null -> "Backed up " + backedUp(backup.lastAt)
                        else -> "Automatic backup on"
                    }
                }
                SettingsPage.PRIVACY -> {
                    val lock = if (com.spendroid.data.Privacy(context).lockEnabled) "Locked" else "No app lock"
                    lock + if (com.spendroid.data.DbEncryption.isOn(context)) " · encrypted" else ""
                }
                SettingsPage.WATCH -> "Install, chime and what it shows"
                SettingsPage.COMPUTER -> if (com.spendroid.web.WebAccess.running.value != null) "On" else "Off"
                SettingsPage.ABOUT -> state.versionName.takeIf { it.isNotBlank() }?.let { "Version $it · updates" } ?: "Updates"
            }
            SettingsPage.entries.groupBy { it.group }.forEach { (group, pages) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    group?.let { SectionHeading(it, Modifier.padding(horizontal = 6.dp)) }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Charcoal.Panel)) {
                        pages.forEachIndexed { i, p ->
                            if (i > 0) androidx.compose.material3.HorizontalDivider(color = Charcoal.Line)
                            SettingsRow(p, status(p)) { openPage = p.name }
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openPage = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
                }
                Text(page.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            when (page) {
                SettingsPage.LOOK -> LookSection(look, onSetAccent, onSetAccountColours, onSetIconFollows)
                SettingsPage.BUDGET -> {
                    Panel { BudgetSection(current = state.budgetModel, onSelect = onSetBudgetModel) }
                    Panel { CardTimingSection(current = state.cardTiming, onSelect = onSetCardTiming) }
                }
                SettingsPage.NOTIFICATIONS -> {
                    Panel { NotificationsSection(selectedTime = selectedTime, onPickTime = { showTimePicker = true }) }
                    BillsAndBalancesSection(onChanged = onReload)
                }
                SettingsPage.CARD_ALERTS -> Panel { cardAlerts() }
                SettingsPage.BANK -> {
                    SyncingSection(state)
                    Panel {
                        CredentialsSection(
                            secretId = secretIdField,
                            onSecretIdChange = {
                                secretIdField = it
                                saved = false
                            },
                            secretKey = secretKeyField,
                            onSecretKeyChange = {
                                secretKeyField = it
                                saved = false
                            },
                            saved = saved,
                            onSave = {
                                onSave(secretIdField.trim(), secretKeyField.trim())
                                saved = true
                            },
                        )
                    }
                }
                SettingsPage.BACKUPS -> Panel {
                    DataSection(
                        state = state,
                        onExport = onExport,
                        onImport = onImport,
                        onClearData = onClearData,
                        onUnlockBackup = onUnlockBackup,
                        onCancelRestore = onCancelRestore,
                    )
                }
                SettingsPage.PRIVACY -> PrivacySection(onConfirmLock, onHideInRecents)
                SettingsPage.WATCH -> Panel { WatchSection() }
                SettingsPage.COMPUTER -> WebSection()
                SettingsPage.ABOUT -> {
                    Panel {
                        UpdatesSection(
                            state = state,
                            versionName = state.versionName,
                            versionCode = state.versionCode,
                            onCheckUpdate = onCheckUpdate,
                            onOpenInstallSettings = onOpenInstallSettings,
                            onInstallDownloaded = onInstallDownloaded,
                        )
                    }
                    Panel { CrashSection() }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all data?") },
            text = {
                Text(
                    "This removes your accounts, transactions, bills, answers to Penny and bank link from this phone. " +
                        "Your backups aren't touched: the welcome screen can restore one, bank link and all if it's an " +
                        "automatic backup. Without one, it can't be undone.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        onClearData()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Clear all data")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialValue = parseNotificationTime(selectedTime),
            onConfirm = { time ->
                selectedTime = time.format(timeFormatter)
                onSaveNotificationTime(selectedTime)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

/**
 * The Look card: the app's colour, from five, whether the launcher icon follows it, and whether
 * each account wears its own colour. Applied at once, so the choice can be judged on the screen
 * it was made on.
 */
@Composable
private fun LookSection(
    look: Look,
    onSetAccent: (com.spendroid.ui.theme.Accent) -> Unit,
    onSetAccountColours: (Boolean) -> Unit,
    onSetIconFollows: (Boolean) -> Unit = {},
) {
    Panel {
        SectionHeading("Look", trailing = "colours")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("App colour", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            com.spendroid.ui.theme.Accent.entries.forEach { accent ->
                val chosen = accent == look.accent
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(accent.colour)
                        .then(if (chosen) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                        .clickable { onSetAccent(accent) }
                        .semantics { contentDescription = accent.label + if (chosen) ", chosen" else "" },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val context = androidx.compose.ui.platform.LocalContext.current
        var follows by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(LauncherIcon.follows(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("App icon in this colour", style = MaterialTheme.typography.bodyLarge)
                Text("Some launchers redraw the home-screen icon when it changes", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
            }
            Switch(checked = follows, onCheckedChange = { follows = it; onSetIconFollows(it) })
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Colour each account", style = MaterialTheme.typography.bodyLarge)
                Text("Its pages, bars and tiles in its own colour", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
            }
            Switch(checked = look.accountColours, onCheckedChange = onSetAccountColours)
        }
    }
}

/**
 * How the bank's allowance stands, account by account, as Unraid shows a disk: a bar with its
 * word inside - full while a refresh is there, half on the last one, filling towards the reset
 * once spent. No count of calls: once a reset has passed nobody can say how many came back.
 */
@Composable
private fun SyncingSection(state: RootUiState) {
    val now = System.currentTimeMillis()
    Panel {
        SectionHeading("Syncing", trailing = "GoCardless")
        state.nextSyncAt?.let {
            Text("Next scheduled sync ${com.spendroid.data.SyncAllowance.resetLabel(it, now)}", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
        }
        state.accounts.forEach { account ->
            val allowance = state.syncAllowances[account.id]
            val status = com.spendroid.data.SyncAllowance.status(allowance, now)
            val (fraction, colour) = when (status) {
                com.spendroid.data.SyncAllowance.Status.AVAILABLE -> 1f to Charcoal.Good
                com.spendroid.data.SyncAllowance.Status.LAST_ONE -> 0.5f to Charcoal.Warn
                com.spendroid.data.SyncAllowance.Status.UNAVAILABLE ->
                    (allowance?.resetAt?.let { 1f - (it - now).toFloat() / (24 * 60 * 60 * 1000f) } ?: 0f) to Charcoal.Bad
            }
            Spacer(Modifier.height(10.dp))
            Text(account.label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            LabelledBar(fraction, com.spendroid.data.SyncAllowance.summary(allowance, now), colour, height = 18.dp)
        }
    }
}

/** Separates two sections sharing a tab, so they do not read as one long form. */
@Composable
private fun SectionBreak() {
    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun CredentialsSection(
    secretId: String,
    onSecretIdChange: (String) -> Unit,
    secretKey: String,
    onSecretKeyChange: (String) -> Unit,
    saved: Boolean,
    onSave: () -> Unit,
) {
    Text("GoCardless API credentials", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Get these from https://ob.nordigen.com/ → Developer → User secrets",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = secretId,
        onValueChange = onSecretIdChange,
        label = { Text("secret_id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = secretKey,
        onValueChange = onSecretKeyChange,
        label = { Text("secret_key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onSave,
        enabled = secretId.isNotBlank() && secretKey.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (saved) "Saved" else "Save credentials")
    }
}

/**
 * Crashes kept on the phone - its own and the watch's - and the way to send them: shared as text
 * however the user likes, by email or a GitHub issue. Nothing is sent by itself.
 */
@Composable
private fun CrashSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var crashes by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.spendroid.data.CrashLog.all(context)) }
    Text("Crash reports", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    val latest = crashes.firstOrNull()?.let {
        java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    }
    Text(
        if (crashes.isEmpty()) "No crashes recorded. If SpenDroid or the watch app ever closes unexpectedly, what happened is kept here for you to send."
        else "${crashes.size} crash${if (crashes.size == 1) "" else "es"} recorded, the latest $latest. Sending shares a text report - " +
            "it's worth a glance first, as an error can name a payee.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (crashes.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "SpenDroid crash report")
                    .putExtra(android.content.Intent.EXTRA_TEXT, com.spendroid.data.CrashLog.report(context))
                context.startActivity(android.content.Intent.createChooser(send, "Send crash report"))
            }, modifier = Modifier.weight(1f)) { Text("Send report") }
            androidx.compose.material3.OutlinedButton(onClick = {
                com.spendroid.data.CrashLog.clear(context)
                crashes = emptyList()
            }, modifier = Modifier.weight(1f)) { Text("Clear") }
        }
    }
}

@Composable
private fun UpdatesSection(
    state: RootUiState,
    versionName: String,
    versionCode: Int,
    onCheckUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onInstallDownloaded: () -> Unit = {},
) {
    Text("App updates", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "SpenDroid checks for updates automatically. Tap below to check manually.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    val isChecking = state.updateCheckStatus is UpdateCheckStatus.Checking
    Button(
        onClick = onCheckUpdate,
        enabled = !isChecking,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isChecking) "Checking…" else "Check now")
        }
    }
    // Downloaded, and its install prompt missed or cancelled: installed from here, not downloaded again.
    val context = androidx.compose.ui.platform.LocalContext.current
    val waiting = androidx.compose.runtime.remember(state.updateCheckStatus) { com.spendroid.data.ApkInstaller.downloaded(context) }
    waiting?.let { update ->
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onInstallDownloaded, modifier = Modifier.fillMaxWidth()) {
            Text("Install SpenDroid ${update.versionName} (downloaded)")
        }
    }
    when (state.updateCheckStatus) {
        is UpdateCheckStatus.Checking -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "Checking for updates…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is UpdateCheckStatus.Success -> {
            Spacer(Modifier.height(8.dp))
            Text(
                state.updateCheckStatus.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is UpdateCheckStatus.Error -> {
            Spacer(Modifier.height(8.dp))
            Text(
                state.updateCheckStatus.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is UpdateCheckStatus.Downloading -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "Downloading update… ${state.updateCheckStatus.percent}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { state.updateCheckStatus.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is UpdateCheckStatus.Installing -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "Downloaded and verified. Android will ask you to confirm the install.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is UpdateCheckStatus.NeedsInstallPermission -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "SpenDroid needs your permission to install app updates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onOpenInstallSettings) { Text("Open settings") }
        }
        is UpdateCheckStatus.Idle -> {}
    }

    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(16.dp))
    Text("About", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "SpenDroid v$versionName (build $versionCode)",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Penny is a SpenDroid, a budgeting assistant. She works out what's left to spend until payday, " +
            "after your bills and card statements.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "She keeps an eye on your spending through your banking apps' and Google Wallet's notifications, " +
            "or statements you import, and can link straight to your bank through open banking (GoCardless). " +
            "She learns your spending habits and asks when she isn't sure, to help you get a better handle " +
            "on your day-to-day spending.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "She is your personal and private assistant: no account, no company cloud.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NotificationsSection(
    selectedTime: String,
    onPickTime: () -> Unit,
) {
    Text("Daily notifications", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Set the time for daily budget summary notifications.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = selectedTime,
            onValueChange = {},
            label = { Text("Notification time") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            readOnly = true,
        )
        Button(
            onClick = onPickTime,
            modifier = Modifier.width(96.dp),
        ) {
            Text("Change")
        }
    }
}

@Composable
private fun BudgetSection(
    current: BudgetModel,
    onSelect: (BudgetModel) -> Unit,
) {
    Text("How the budget is worked out", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "There is no single right answer, and it can reasonably change month to month.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    BudgetModel.entries.forEach { model ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(model) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = current == model, onClick = { onSelect(model) })
            Spacer(Modifier.width(8.dp))
            Column {
                Text(model.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    model.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "Carrying the balance over and showing the balance both use the account your main " +
            "income is paid into. Star which income that is on Regular - tap Income in Each month.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CardTimingSection(
    current: CardTiming,
    onSelect: (CardTiming) -> Unit,
) {
    Text("When card spending counts", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Either way it counts once - at the till, or when the bill leaves your account.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    CardTiming.entries.forEach { timing ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(timing) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = current == timing, onClick = { onSelect(timing) })
            Spacer(Modifier.width(8.dp))
            Column {
                Text(timing.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    timing.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DataSection(
    state: RootUiState,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onClearData: () -> Unit,
    onUnlockBackup: (Uri, CharArray) -> Unit,
    onCancelRestore: () -> Unit,
) {
    Text("Data management", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(onExport) }

    OutlinedButton(
        onClick = {
            val stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            exportPicker.launch("spendroid-backup-$stamp.json")
        },
        enabled = state.exportStatus !is ExportStatus.Working,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.exportStatus is ExportStatus.Working) "Exporting…" else "Export backup")
    }
    Text(
        "Transactions older than 90 days can't be re-downloaded from your bank, so this file " +
            "is the only copy. Keep it somewhere safe.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (val status = state.exportStatus) {
        is ExportStatus.Done -> Text(
            status.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is ExportStatus.Failed -> Text(
            "Export failed: ${status.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        else -> {}
    }

    Spacer(Modifier.height(16.dp))

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

    OutlinedButton(
        onClick = { importPicker.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
        enabled = state.importStatus !is ImportStatus.Working,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.importStatus is ImportStatus.Working) "Restoring…" else "Restore from backup")
    }
    Text(
        "Merges a backup into what's already here. Existing transactions are replaced by their " +
            "backed-up copy; anything not in the file is left alone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (val status = state.importStatus) {
        is ImportStatus.Done -> Text(
            status.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is ImportStatus.Failed -> Text(
            "Restore failed: ${status.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is ImportStatus.NeedsPassphrase -> BackupPassphraseDialog(
            wrong = status.wrong,
            onUnlock = { onUnlockBackup(status.source, it) },
            onCancel = onCancelRestore,
        )
        else -> {}
    }

    Spacer(Modifier.height(16.dp))
    AutoBackupSettings()

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onClearData,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Clear all data")
    }
    Text(
        "Removes your data and bank link from this phone; your backups stay where they are. Restore one from the " +
            "welcome screen - an automatic backup brings the bank link back too.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TimePickerDialog(
    initialValue: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var time by rememberSaveable { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select notification time") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = time.hour.toString().padStart(2, '0'),
                        onValueChange = { hourStr ->
                            val hour = hourStr.takeLast(2).toIntOrNull()?.coerceIn(0, 23) ?: time.hour
                            time = time.withHour(hour)
                        },
                        label = { Text("Hour") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = time.minute.toString().padStart(2, '0'),
                        onValueChange = { minuteStr ->
                            val minute = minuteStr.takeLast(2).toIntOrNull()?.coerceIn(0, 59) ?: time.minute
                            time = time.withMinute(minute)
                        },
                        label = { Text("Minute") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(time) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}