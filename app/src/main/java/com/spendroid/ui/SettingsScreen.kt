package com.spendroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentSecretId: String,
    currentSecretKey: String,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
    onClearData: () -> Unit,
    onCheckUpdate: () -> Unit,
    notificationTime: String,
    onSaveNotificationTime: (String) -> Unit,
) {
    var secretId by remember { mutableStateOf(currentSecretId) }
    var secretKey by remember { mutableStateOf(currentSecretKey) }
    var showClearDialog by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    var selectedTime by remember { mutableStateOf(LocalTime.parse(notificationTime)) }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    androidx.compose.material3.TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(16.dp),
        ) {
            Text("GoCardless API credentials", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Get these from https://ob.nordigen.com/ → Developer → User secrets",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedTextField(
                value = secretId,
                onValueChange = {
                    secretId = it
                    saved = false
                },
                label = { Text("secret_id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = secretKey,
                onValueChange = {
                    secretKey = it
                    saved = false
                },
                label = { Text("secret_key") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onSave(secretId.trim(), secretKey.trim())
                    saved = true
                },
                enabled = secretId.isNotBlank() && secretKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (saved) "Saved" else "Save credentials")
            }

            Spacer(Modifier.height(24.dp))
            androidx.compose.material3.Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text("App updates", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "SpenDroid checks for updates automatically. Tap below to check manually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        checkingUpdate = true
                        updateResult = null
                        onCheckUpdate()
                    },
                    enabled = !checkingUpdate,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (checkingUpdate) "Checking…" else "Check now")
                }
            }
            updateResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(24.dp))
            androidx.compose.material3.Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

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
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = selectedTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    onValueChange = { /* handled by time picker */ },
                    label = { Text("Notification time") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    readOnly = true,
                )
                Button(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.width(48.dp),
                ) {
                    Text("Change")
                }
            }

            Spacer(Modifier.height(24.dp))
            androidx.compose.material3.Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Text("Data management", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showClearDialog = true },
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
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select notification time") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = selectedTime.hour.toString().padStart(2, '0'),
                            onValueChange = { hourStr ->
                                val hour = hourStr.toIntOrNull()?.coerceIn(0, 23) ?: selectedTime.hour
                                selectedTime = selectedTime.withHour(hour)
                            },
                            label = { Text("Hour") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .width(80.dp),
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = selectedTime.minute.toString().padStart(2, '0'),
                            onValueChange = { minuteStr ->
                                val minute = minuteStr.toIntOrNull()?.coerceIn(0, 59) ?: selectedTime.minute
                                selectedTime = selectedTime.withMinute(minute)
                            },
                            label = { Text("Minute") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .width(80.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newTime = selectedTime.format(DateTimeFormatter.ofPattern("HH:mm"))
                    onSaveNotificationTime(newTime)
                    showTimePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
