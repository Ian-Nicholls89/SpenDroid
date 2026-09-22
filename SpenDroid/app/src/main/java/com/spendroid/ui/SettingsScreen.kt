package com.spendroid.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private enum class SettingsTab(val label: String) {
    CREDENTIALS("Credentials"),
    UPDATES("Updates"),
    NOTIFICATIONS("Notifications"),
    DATA("Data"),
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
    onExport: (Uri) -> Unit,
    onClearData: () -> Unit,
    onCheckUpdate: () -> Unit,
    secretId: String,
    secretKey: String,
    notificationTime: String,
    onSaveNotificationTime: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var secretIdField by rememberSaveable(secretId) { mutableStateOf(secretId) }
    var secretKeyField by rememberSaveable(secretKey) { mutableStateOf(secretKey) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var selectedTime by rememberSaveable { mutableStateOf(parseNotificationTime(notificationTime).format(timeFormatter)) }

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            SettingsTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(tab.label) },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            when (SettingsTab.entries[selectedTab]) {
                SettingsTab.CREDENTIALS -> CredentialsSection(
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

                SettingsTab.UPDATES -> UpdatesSection(
                    state = state,
                    versionName = state.versionName,
                    versionCode = state.versionCode,
                    onCheckUpdate = onCheckUpdate,
                )

                SettingsTab.NOTIFICATIONS -> NotificationsSection(
                    selectedTime = selectedTime,
                    onPickTime = { showTimePicker = true },
                )

                SettingsTab.DATA -> DataSection(
                    state = state,
                    onExport = onExport,
                    onClearData = onClearData,
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all data?") },
            text = {
                Text(
                    "This removes all linked accounts, transactions, and manual rules. " +
                        "You'll need to enter your GoCardless credentials and re-link your banks.",
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

@Composable
private fun UpdatesSection(
    state: RootUiState,
    versionName: String,
    versionCode: Int,
    onCheckUpdate: () -> Unit,
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
        is UpdateCheckStatus.Idle -> {}
    }

    Spacer(Modifier.height(24.dp))
    Divider(color = MaterialTheme.colorScheme.outlineVariant)
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
        "A personal budgeting app with GoCardless integration",
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
private fun DataSection(
    state: RootUiState,
    onExport: (Uri) -> Unit,
    onClearData: () -> Unit,
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
        "Removes all linked accounts, transactions, and settings. You'll need to re-link your banks.",
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