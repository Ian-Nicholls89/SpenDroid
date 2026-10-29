package com.spendroid.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Backups made by themselves, every night, to a folder the user picked - Google Drive's, through
 * Android's own picker, so there's no Google sign-in here - encrypted with a passphrase the user
 * chose ([BackupCrypto]), as they leave the phone. The last [KEEP] are kept; older ones go.
 *
 * The passphrase is kept on the phone, sealed by its keystore, so the nightly one needs nothing
 * from the user; to restore elsewhere they type it. Forgotten, those backups can't be opened.
 */
object AutoBackup {

    const val KEEP = 7
    private const val PREFS = com.spendroid.data.Stores.AUTO_BACKUP
    private const val FOLDER = "folder"
    private const val PASSPHRASE = "passphrase"
    private const val LAST_AT = "lastAt"
    private const val LAST_OK = "lastOk"
    private const val LAST_NOTE = "lastNote"
    private const val WORK = "auto_backup"
    const val NOW_WORK = "auto_backup_now"
    const val PREFIX = "spendroid-backup-"
    const val SUFFIX = ".spendroid"
    private const val ACCESS = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    data class Status(val folder: Uri?, val lastAt: Long?, val ok: Boolean, val note: String?)

    fun status(context: Context): Status = prefs(context).let { p ->
        Status(
            p.getString(FOLDER, null)?.let(Uri::parse),
            p.getLong(LAST_AT, 0L).takeIf { it > 0L },
            p.getBoolean(LAST_OK, true),
            p.getString(LAST_NOTE, null),
        )
    }

    fun isOn(context: Context): Boolean = prefs(context).contains(FOLDER) && prefs(context).contains(PASSPHRASE)

    /** Turned on: the folder kept (and permission to it held), the passphrase sealed, one made now. */
    fun turnOn(context: Context, folder: Uri, passphrase: CharArray) {
        context.contentResolver.takePersistableUriPermission(folder, ACCESS)
        // Moved from another folder: that one's permission isn't needed any more.
        status(context).folder?.takeIf { it != folder }?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, ACCESS) }
        }
        prefs(context).edit(commit = true) {
            putString(FOLDER, folder.toString())
            putString(PASSPHRASE, SecretCipher.seal(String(passphrase)))
            remove(LAST_NOTE)
        }
        schedule(context)
        backUpNow(context)
    }

    fun turnOff(context: Context) {
        status(context).folder?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, ACCESS)
            }
        }
        prefs(context).edit(commit = true) { clear() }
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Every night from about 3am, while on - asked for again at each start, which keeps it as it is. */
    fun schedule(context: Context) {
        if (!isOn(context)) return
        val nightly = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            // A periodic job's first run is straight away otherwise - a second backup beside "now"'s.
            .setInitialDelay(minutesUntilThree(java.time.LocalDateTime.now()), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, nightly)
    }

    internal fun minutesUntilThree(now: java.time.LocalDateTime): Long {
        val three = now.toLocalDate().atTime(3, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
        return java.time.Duration.between(now, three).toMinutes()
    }

    fun backUpNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    internal fun passphrase(context: Context): CharArray? =
        prefs(context).getString(PASSPHRASE, null)?.let { SecretCipher.open(it) }?.toCharArray()

    internal fun record(context: Context, ok: Boolean, note: String?, at: Long = System.currentTimeMillis(), skipped: Boolean = false) {
        prefs(context).edit(commit = true) {
            // Skipped as there was nothing to back up: the last real backup's time stays.
            if (skipped) {
                putBoolean(LAST_OK, true)
                putString(LAST_NOTE, "Nothing to back up yet - the backups in the folder were left as they are.")
                return@edit
            }
            putLong(LAST_AT, at)
            putBoolean(LAST_OK, ok)
            if (note == null) remove(LAST_NOTE) else putString(LAST_NOTE, note)
        }
    }

    /** Whether a backup has anything in it: an account, or a transaction. */
    fun hasData(json: String): Boolean = runCatching {
        val root = org.json.JSONObject(json)
        root.optInt("accountCount") > 0 || root.optInt("transactionCount") > 0
    }.getOrDefault(true)

    /** What went wrong, as a sentence the user can act on - the folder's errors aren't. */
    fun explain(error: Throwable): String = when (error) {
        is SecurityException, is java.io.FileNotFoundException, is IllegalArgumentException ->
            "The backup folder can't be reached - it may have been moved or deleted. Choose it again in Settings → Backups & data."
        else -> (error.message ?: error.javaClass.simpleName).trimEnd('.') + "."
    }

    /** A backup's name: sorted by name, they're sorted by when. */
    fun nameFor(at: Long): String =
        PREFIX + Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + SUFFIX

    /** Of the backups in the folder, those past the newest [KEEP]: the ones to delete. */
    fun toDelete(names: List<String>): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }.sortedDescending().drop(KEEP)

    /** Writes [bytes] to the folder as a new backup, then lets the oldest go. */
    internal fun write(context: Context, folder: Uri, name: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val file = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: error("The folder wouldn't take a new file")
        resolver.openOutputStream(file, "w")?.use { it.write(bytes) } ?: error("Couldn't write to the folder")
        // Only once the new one is in: the oldest past the last seven go.
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val present = resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }.orEmpty()
        val old = toDelete(present.map { it.second }).toSet()
        present.filter { it.second in old }.forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(folder, id)) }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
