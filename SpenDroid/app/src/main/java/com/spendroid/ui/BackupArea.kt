package com.spendroid.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.spendroid.widget.refreshWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [RootViewModel]'s backups: exported, restored - an encrypted one with its passphrase - and the data cleared.
 */
internal class BackupArea(host: AreaHost,
    /** The bank link's keys and banks on screen, forgotten with the data. */
    private val forgetLink: () -> Unit,) : ViewModelArea(host) {

    /** Writes a full backup to a location the user picked through the system file picker. */
    fun exportTo(destination: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(exportStatus = ExportStatus.Working) }
            val status = runCatching {
                com.spendroid.data.Choices.awaitLoaded()
                val json = repo.exportJson(com.spendroid.data.BackupPrefs.export(getApplication()))
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openOutputStream(destination)
                        ?.use { out -> out.write(json.toByteArray()) }
                        ?: error("Could not open the selected file for writing")
                }
                json.length
            }.fold(
                onSuccess = { ExportStatus.Done("Backup saved (${it / 1024} KB)") },
                onFailure = { ExportStatus.Failed(it.message ?: it.javaClass.simpleName) },
            )
            _state.update { it.copy(exportStatus = status) }
        }
    }

    /** Merges a backup file back into the database, then reloads what is on screen. */
    /** A backup restored - an encrypted one (the automatic backups) needs its [passphrase]. */
    fun importFrom(source: Uri, passphrase: CharArray? = null) {
        viewModelScope.launch {
            _state.update { it.copy(importStatus = ImportStatus.Working) }
            val status = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openInputStream(source)
                        ?.use { input -> input.readBytes() }
                        ?: error("Could not open the selected file")
                }
                val json = if (com.spendroid.data.BackupCrypto.isEncrypted(bytes)) {
                    if (passphrase == null) {
                        _state.update { it.copy(importStatus = ImportStatus.NeedsPassphrase(source)) }
                        return@launch
                    }
                    try {
                        withContext(Dispatchers.Default) { com.spendroid.data.BackupCrypto.decrypt(bytes, passphrase) }
                    } catch (e: com.spendroid.data.BackupCrypto.WrongPassphrase) {
                        _state.update { it.copy(importStatus = ImportStatus.NeedsPassphrase(source, wrong = true)) }
                        return@launch
                    } finally {
                        passphrase.fill(' ')
                    }
                } else {
                    bytes.decodeToString()
                }
                repo.importJson(json).also { restored ->
                    com.spendroid.data.BackupPrefs.restore(getApplication(), restored.preferences)
                    com.spendroid.data.PayeeNames.load(getApplication())
                    // The icon's colour, if it follows the app's: switched as it was.
                    if (LauncherIcon.follows(getApplication())) {
                        val accent = com.spendroid.ui.theme.Accent.entries.firstOrNull { it.name == repo.accent.first() }
                        accent?.let { LauncherIcon.apply(getApplication(), it) }
                    }
                }
            }.fold(
                onSuccess = { restored ->
                    if (restored.isEmpty) {
                        ImportStatus.Failed("That backup contained no accounts or transactions.")
                    } else {
                        ImportStatus.Done(
                            "Restored ${restored.transactions.size} transactions " +
                                "across ${restored.accounts.size} accounts" +
                                if (restored.hasBank) ", and your bank link." else ".",
                        )
                    }
                },
                onFailure = { ImportStatus.Failed(it.message ?: it.javaClass.simpleName) },
            )
            // The bank link came with it: linked again as it was, and its banks listed.
            if (status is ImportStatus.Done) refreshConfig()
            // Restored during setup: the data's all here, so a restart opens on Home, not the welcome
            // screen again - while setup carries on to what this phone still needs.
            if (status is ImportStatus.Done && !_state.value.setupDone) setupPrefs().edit().putBoolean("done", true).apply()
            _state.update { it.copy(importStatus = status) }
            if (status is ImportStatus.Done) loadLocal()
        }
    }

    /** The passphrase asked for wasn't given: nothing restored. */
    fun cancelRestore() {
        _state.update { it.copy(importStatus = ImportStatus.Idle) }
    }

    fun clearData() {
        viewModelScope.launch {
            runCatching { repo.clearAllData() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            // What's kept outside the database about the data just deleted - and setup, so a
            // fresh start starts at the beginning even after a restart. Device settings (the lock,
            // the icon, the watch) and remembered file layouts stay.
            val app = getApplication<Application>()
            com.spendroid.data.Stores.clearedWithData.forEach { com.spendroid.data.Stores.prefs(app, it).edit().clear().commit() }
            com.spendroid.data.PayeeNames.load(app)
            _state.update { RootUiState(connections = emptyList(), manualRules = emptyList(), showRecurringOnly = true, showInternalTransfers = false, versionName = "", versionCode = 0) }
            forgetLink()
            // Otherwise the widgets go on showing the figures that were just deleted.
            refreshWidgets(getApplication())
        }
    }
}
