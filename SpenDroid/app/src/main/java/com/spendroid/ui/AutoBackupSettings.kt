package com.spendroid.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.spendroid.data.AutoBackup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings → Data's automatic backup: a folder chosen (Google Drive's, if it's installed, is one
 * of the places the picker offers), a passphrase set, and a backup every night from then on.
 */
@Composable
internal fun AutoBackupSettings() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(AutoBackup.status(context)) }
    var on by remember { mutableStateOf(AutoBackup.isOn(context)) }
    // Re-read whenever a backup starts or finishes, so the line under it is current.
    val running by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(AutoBackup.NOW_WORK) }
        .collectAsState(initial = emptyList())
    val backingUp = running.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    LaunchedEffect(running) { status = AutoBackup.status(context) }

    var folder by remember { mutableStateOf<Uri?>(null) }
    var stopping by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        folder = picked
    }

    Text("Automatic backup", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    if (!on) {
        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Back up every night to a folder…")
        }
        Text(
            "Choose a folder - Google Drive appears in the list if it's installed - and a passphrase. " +
                "Every night an encrypted backup is saved there; the last ${AutoBackup.KEEP} are kept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(statusLine(status, backingUp), style = MaterialTheme.typography.bodyMedium)
        status.note?.takeIf { !status.ok }?.let {
            Text("Last try: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row {
            OutlinedButton(onClick = { AutoBackup.backUpNow(context) }, enabled = !backingUp) { Text("Back up now") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { folderPicker.launch(null) }) { Text("Change") }
            TextButton(onClick = { stopping = true }) { Text("Stop") }
        }
        Text(
            "Restoring one asks for the passphrase. Without it, they can't be opened - by anyone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    folder?.let { chosen ->
        NewPassphraseDialog(
            onSet = { passphrase ->
                runCatching { AutoBackup.turnOn(context, chosen, passphrase) }
                    .onFailure { e -> AutoBackup.record(context, ok = false, note = e.message ?: "Couldn't use that folder") }
                passphrase.fill(' ')
                folder = null
                on = AutoBackup.isOn(context)
                status = AutoBackup.status(context)
            },
            onCancel = { folder = null },
        )
    }
    if (stopping) {
        AlertDialog(
            onDismissRequest = { stopping = false },
            title = { Text("Stop backing up every night?") },
            text = { Text("The backups already in the folder stay there, and still open with your passphrase.") },
            confirmButton = {
                TextButton(onClick = {
                    AutoBackup.turnOff(context)
                    on = false
                    status = AutoBackup.status(context)
                    stopping = false
                }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { stopping = false }) { Text("Keep backing up") } },
        )
    }
}

internal fun statusLine(status: AutoBackup.Status, backingUp: Boolean, today: LocalDate = LocalDate.now()): String {
    if (backingUp) return "Backing up…"
    val at = status.lastAt ?: return "On · the first backup is on its way"
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val day = when (time.toLocalDate()) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> time.format(DateTimeFormatter.ofPattern("d MMM"))
    }
    val whenText = "$day ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    return if (status.ok) "On · last backup $whenText · the last ${AutoBackup.KEEP} kept" else "On · the backup $whenText didn't save"
}

/** A new passphrase, typed twice; at least [MIN] characters. */
@Composable
private fun NewPassphraseDialog(onSet: (CharArray) -> Unit, onCancel: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val problem = when {
        first.length < MIN -> "At least $MIN characters"
        second.isNotEmpty() && second != first -> "The two don't match"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("A passphrase for the backups") },
        text = {
            Column {
                Text(
                    "You'll need it to restore - on this phone or a new one. Write it down somewhere safe: " +
                        "forgotten, the backups can't be opened.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(first, { first = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(second, { second = it }, label = { Text("Again") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(first.toCharArray()) }, enabled = problem == null && second == first) { Text("Start backing up") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** An encrypted backup being restored: its passphrase asked for. */
@Composable
internal fun BackupPassphraseDialog(wrong: Boolean, onUnlock: (CharArray) -> Unit, onCancel: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("This backup is encrypted") },
        text = {
            Column {
                Text("Type the passphrase you chose for the automatic backups.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(typed, { typed = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (wrong) Text("That passphrase doesn't open this backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onUnlock(typed.toCharArray()) }, enabled = typed.isNotEmpty()) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private const val MIN = 8
