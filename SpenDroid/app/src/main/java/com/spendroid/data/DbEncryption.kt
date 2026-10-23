package com.spendroid.data

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * The database encrypted on the phone - when the user turns it on in Settings → Privacy - with a
 * key the phone's keystore holds, so there's nothing to type. Off by default while it's road-tested.
 *
 * Turning it on or off asks for a restart, and the change is made as the app next starts, before
 * the database opens: the whole database written out, encrypted (or plain), beside the old one;
 * opened again with its key and checked - every table's rows counted against the old, the
 * version the same - and only then swapped in. The old one is kept until the new one has opened
 * as the app ran; anything going wrong leaves the old one in use, and says so.
 *
 * The keystore's key never leaves this phone. An encrypted database restored to another phone -
 * Android's own backup - can't be opened there: it's set aside, the app starts empty, and says to
 * restore from SpenDroid's own backup instead.
 */
object DbEncryption {

    const val NAME = "budget.db"
    private const val PREFS = com.spendroid.data.Stores.DB_ENCRYPTION
    private const val ON = "on"
    private const val WANTED = "wanted"
    private const val KEY = "key"
    private const val NOTE = "note"

    /** Whether the database is encrypted now. */
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(ON, false)

    /** Something to tell the user about the last change: it couldn't be made, or the database was set aside. */
    fun note(context: Context): String? = prefs(context).getString(NOTE, null)

    fun clearNote(context: Context) = prefs(context).edit().remove(NOTE).apply()

    /** Asked for: made as the app next starts. */
    fun request(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(WANTED, on).remove(NOTE).commit()
    }

    /**
     * At start-up, before the database opens: makes a change asked for, and gives the passphrase
     * to open the database with - null for a plain one.
     */
    fun prepare(context: Context): ByteArray? {
        val prefs = prefs(context)
        if (prefs.contains(WANTED)) {
            val want = prefs.getBoolean(WANTED, false)
            prefs.edit().remove(WANTED).commit()
            if (want != isOn(context)) {
                runCatching { convert(context, encrypt = want) }.onFailure { error ->
                    // Whatever was half-written goes; the database in use is untouched.
                    context.getDatabasePath(NAME).parentFile?.listFiles()?.filter { it.name.startsWith("$NAME.converting") }?.forEach { it.delete() }
                    CrashLog.record(context, "phone · database ${if (want) "encryption" else "decryption"} didn't complete", CrashLog.stackOf(error))
                    prefs.edit().putString(
                        NOTE,
                        "Couldn't ${if (want) "encrypt" else "decrypt"} the data - it's as it was, and nothing is lost. " +
                            "A crash report has what went wrong.",
                    ).commit()
                }
            }
        }
        if (!isOn(context)) return null
        val passphrase = passphrase(context)
        if (passphrase == null) {
            // Encrypted, but the key isn't here: restored from another phone. Set aside, start empty.
            val db = context.getDatabasePath(NAME)
            if (db.exists()) db.renameTo(File(db.parentFile, "$NAME.unreadable-${System.currentTimeMillis()}"))
            sidecars(db).forEach { it.delete() }
            prefs.edit().putBoolean(ON, false).remove(KEY).putString(
                NOTE,
                "The data here was encrypted on another phone, and can't be opened on this one. " +
                    "Restore from a SpenDroid backup: Settings → Data → Restore.",
            ).commit()
            return null
        }
        return passphrase
    }

    /** Once the app has opened the database as it is now: the copy kept from before a change can go. */
    fun opened(context: Context) {
        context.getDatabasePath(NAME).parentFile?.listFiles()?.filter { it.name.startsWith("$NAME.before-") }?.forEach { it.delete() }
    }

    // ---- the change ---------------------------------------------------------------------------

    private fun convert(context: Context, encrypt: Boolean) {
        System.loadLibrary("sqlcipher")
        val db = context.getDatabasePath(NAME)
        val passphrase = if (encrypt) newPassphrase(context) else passphrase(context) ?: error("The key for the encrypted data is missing")
        if (!db.exists()) {
            // Nothing yet: the database is made as it should be when it's first opened.
            prefs(context).edit().putBoolean(ON, encrypt).apply { if (!encrypt) remove(KEY) }.commit()
            return
        }
        val target = File(db.parentFile, "$NAME.converting").apply { delete() }
        sidecars(target).forEach { it.delete() }
        val fromKey = if (encrypt) ByteArray(0) else passphrase
        val toKey = if (encrypt) String(passphrase, Charsets.UTF_8) else ""

        val source = open(db, fromKey)
        val counts: Map<String, Long>
        val version: Int
        try {
            // Everything in the write-ahead log into the file first, so it's all exported.
            source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            counts = tableCounts(source)
            version = source.version
            // Both written in: the app's own path and a hex key, neither with a quote in it.
            source.execSQL("ATTACH DATABASE '${target.path}' AS target KEY '$toKey'")
            source.rawQuery("SELECT sqlcipher_export('target')", null).use { it.moveToFirst() }
            // The export doesn't carry the version, which Room reads to know what it's opening.
            source.execSQL("PRAGMA target.user_version = $version")
            source.execSQL("DETACH DATABASE target")
        } finally {
            source.close()
        }

        // Checked before anything is swapped: every table, every row, the same version.
        val check = open(target, if (encrypt) passphrase else ByteArray(0))
        try {
            check(check.version == version) { "Version ${check.version} after, $version before" }
            val after = tableCounts(check)
            check(after == counts) { "Rows differ after: $after, before: $counts" }
        } finally {
            check.close()
        }

        val kept = File(db.parentFile, "$NAME.before-${if (encrypt) "encryption" else "decryption"}").apply { delete() }
        check(db.renameTo(kept)) { "Couldn't move the old database aside" }
        sidecars(db).forEach { it.delete() }
        if (!target.renameTo(db)) {
            kept.renameTo(db)
            error("Couldn't put the new database in place")
        }
        prefs(context).edit().putBoolean(ON, encrypt).apply { if (!encrypt) remove(KEY) }.commit()
    }

    private fun open(file: File, passphrase: ByteArray) =
        net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            file.path, passphrase, null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null,
        )

    private fun tableCounts(db: net.zetetic.database.sqlcipher.SQLiteDatabase): Map<String, Long> {
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { t -> db.rawQuery("SELECT COUNT(*) FROM `$t`", null).use { it.moveToFirst(); it.getLong(0) } }
    }

    private fun sidecars(db: File) = listOf(File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"))

    // ---- the key ------------------------------------------------------------------------------

    /** A new random passphrase, sealed by the keystore and kept. Hex, so it's the same as text and as bytes. */
    private fun newPassphrase(context: Context): ByteArray {
        val hex = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY, SecretCipher.seal(hex)).commit()
        return hex.toByteArray(Charsets.UTF_8)
    }

    private fun passphrase(context: Context): ByteArray? =
        prefs(context).getString(KEY, null)?.let { SecretCipher.open(it) }?.toByteArray(Charsets.UTF_8)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
