package com.spendroid.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.NotificationSampleEntity
import com.spendroid.domain.NotificationSpend
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** An app from the launcher, for picking bank apps. Its icon is loaded only when it's shown. */
internal data class LauncherApp(val packageName: String, val label: String)

/**
 * Every app with a launcher icon, by name. Slow - a phone has a hundred or more - so it's read
 * off the main thread, and without the icons: loading every one made the tab hang on opening.
 */
internal fun launcherApps(context: Context): List<LauncherApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { LauncherApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/**
 * Settings → Card alerts: reading card payments from Google Wallet and bank-app notifications,
 * set up in four steps - access, the bank apps, which accounts each speaks for, and which
 * accounts' cards are in Google Wallet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardAlertsSection(
    accounts: List<AccountEntity>,
    sources: List<NotificationSpend.Source>,
    readingOn: Boolean,
    samples: List<NotificationSampleEntity>,
    onSaveSources: (List<NotificationSpend.Source>) -> Unit,
    onReadingOn: (Boolean) -> Unit,
    onUpdateAccount: (AccountEntity) -> Unit,
    onClear: () -> Unit,
    learned: List<NotificationSpend.Learned> = emptyList(),
    onTeach: (Long, Boolean) -> Unit = { _, _ -> },
    onForgetLearned: () -> Unit = {},
) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(hasAccess(context)) }
    // Checked again on coming back from the system's settings page.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { access = hasAccess(context) }
    }
    val apps by androidx.compose.runtime.produceState(emptyList<LauncherApp>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { launcherApps(context) }
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    var showSamples by rememberSaveable { mutableStateOf(false) }

    Column {
        Text("Card alerts", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Counts a card payment the moment Google Wallet or your bank app announces it, instead of at the " +
                "next bank sync. The bank's own record takes over when it arrives. Only the apps you pick are " +
                "read, and nothing leaves your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 1. Access
        Spacer(Modifier.height(16.dp))
        Text(if (access) "1. Notification access ✓" else "1. Allow notification access", style = MaterialTheme.typography.labelLarge)
        if (!access) {
            Text(
                "Android keeps this setting locked for apps not from the Play Store. First open App info → ⋮ → " +
                    "Allow restricted settings, then turn on SpenDroid under Notification access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("App info") }
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Notification access") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Count spending from notifications", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = readingOn, onCheckedChange = onReadingOn)
            }
        }

        // 2 + 3. Bank apps and their accounts
        Spacer(Modifier.height(16.dp))
        Text("2. Your bank apps, and their accounts", style = MaterialTheme.typography.labelLarge)
        Text(
            "Tick the accounts each app covers. With more than one, the card digits in a notification decide; " +
                "where there are none, the starred account is used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sources.forEach { source ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(source.packageName)
                Spacer(Modifier.width(8.dp))
                Text(source.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSaveSources(sources - source) }) { Text("Remove") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { account ->
                    val on = account.id in source.accountIds
                    val isDefault = source.defaultAccountId == account.id
                    FilterChip(
                        selected = on,
                        onClick = {
                            val ids = if (on) source.accountIds - account.id else source.accountIds + account.id
                            onSaveSources(
                                sources.map {
                                    if (it != source) it else it.copy(
                                        accountIds = ids,
                                        defaultAccountId = it.defaultAccountId?.takeIf { d -> d in ids } ?: ids.firstOrNull(),
                                    )
                                },
                            )
                        },
                        label = { Text((if (isDefault && source.accountIds.size > 1) "★ " else "") + account.label, maxLines = 1) },
                    )
                }
            }
            if (source.accountIds.size > 1) {
                Text(
                    "Default: tap to change",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val ids = source.accountIds
                        val next = ids[(ids.indexOf(source.defaultAccountId) + 1).mod(ids.size)]
                        onSaveSources(sources.map { if (it != source) it else it.copy(defaultAccountId = next) })
                    }.padding(vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { picking = true }) { Text("Add a bank app") }

        // 4. Google Wallet
        Spacer(Modifier.height(16.dp))
        Text("3. Cards in Google Wallet", style = MaterialTheme.typography.labelLarge)
        Text(
            "Turn on each account whose card is in Google Wallet. The last four digits tell cards apart; " +
                "leave them blank and SpenDroid will ask once and remember.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        accounts.forEach { account ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text(account.label, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                if (account.walletLinked) {
                    var digits by remember(account.id, account.cardLastFour) { mutableStateOf(account.cardLastFour.orEmpty()) }
                    OutlinedTextField(
                        value = digits,
                        onValueChange = { v ->
                            digits = v.filter(Char::isDigit).take(4)
                            if (digits.length == 4 || digits.isEmpty()) {
                                onUpdateAccount(account.copy(cardLastFour = digits.ifEmpty { null }))
                            }
                        },
                        placeholder = { Text("1234") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(88.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Switch(
                    checked = account.walletLinked,
                    onCheckedChange = { onUpdateAccount(account.copy(walletLinked = it)) },
                )
            }
        }

        // The log
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Notifications seen (last 30 days): ${samples.size}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showSamples = true }, enabled = samples.isNotEmpty()) { Text("View") }
            TextButton(onClick = onClear, enabled = samples.isNotEmpty()) { Text("Clear") }
        }
        Text(
            "Tap one to teach SpenDroid: count notifications worded like it, or ignore them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (learned.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Wordings learned: ${learned.count { it.count }} counted, ${learned.count { !it.count }} ignored",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onForgetLearned) { Text("Forget all") }
            }
        }
    }

    if (picking) {
        AppPicker(
            apps = apps.filter { app -> sources.none { it.packageName == app.packageName } && app.packageName != NotificationSpend.GOOGLE_WALLET },
            onPick = { app ->
                picking = false
                onSaveSources(sources + NotificationSpend.Source(app.packageName, app.label, emptyList()))
            },
            onDismiss = { picking = false },
        )
    }
    if (showSamples) {
        SamplesDialog(
            samples,
            labels = apps.associate { it.packageName to it.label },
            learned = learned,
            onTeach = onTeach,
            onDismiss = { showSamples = false },
        )
    }
}

private fun hasAccess(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    // Loaded as it comes into view, off the main thread.
    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, packageName) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(64, 64).asImageBitmap() }.getOrNull()
        }
    }
    val shown = bitmap
    if (shown != null) Image(shown, contentDescription = null, modifier = Modifier.size(28.dp))
    else Spacer(Modifier.size(28.dp))
}

@Composable
private fun AppPicker(apps: List<LauncherApp>, onPick: (LauncherApp) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a bank app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(apps.filter { it.label.contains(query.trim(), ignoreCase = true) }, key = { it.packageName }) { app ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 8.dp),
                        ) {
                            AppIcon(app.packageName)
                            Spacer(Modifier.width(10.dp))
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val SAMPLE_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm")

@Composable
private fun SamplesDialog(
    samples: List<NotificationSampleEntity>,
    labels: Map<String, String>,
    learned: List<NotificationSpend.Learned>,
    onTeach: (Long, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var teaching by remember { mutableStateOf<NotificationSampleEntity?>(null) }
    teaching?.let { sample ->
        val reads = remember(sample, learned) { NotificationSpend.parse(sample.source, sample.title, sample.text, learned) }
        AlertDialog(
            onDismissRequest = { teaching = null },
            title = { Text("Teach SpenDroid") },
            text = {
                Column {
                    sample.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    sample.text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        reads?.let { "Reads now as: ${formatMoney(it.amountMinor, "GBP")} · ${it.merchant}" }
                            ?: "Not read as a payment now.",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Counting learns this wording - any amount, any payee - and counts this one too. " +
                            "Ignoring stops ones like it for this payee, whatever the amount, from counting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onTeach(sample.id, true); teaching = null }) { Text("Count ones like this") }
            },
            dismissButton = {
                TextButton(onClick = { onTeach(sample.id, false); teaching = null }) { Text("Ignore ones like this") }
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notifications seen") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                items(samples, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().clickable { teaching = s }.padding(vertical = 6.dp)) {
                        Text(
                            (labels[s.source] ?: if (s.source == NotificationSpend.GOOGLE_WALLET) "Google Wallet" else s.source) +
                                " · " + Instant.ofEpochMilli(s.postedAt).atZone(ZoneId.systemDefault()).format(SAMPLE_TIME) +
                                " · " + (
                                    s.outcome?.let { o -> runCatching { NotificationSpend.Outcome.valueOf(o).label }.getOrNull() }
                                        ?: if (s.parsed) "read as a payment" else "not a payment"
                                    ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        s.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        s.text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
