package com.spendroid.data

import com.spendroid.ui.statusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 0.5.9: encrypted automatic backups - the file, the passphrase, and which ones are kept. */
class AutoBackupTest {

    private val json = """{"formatVersion":5,"accounts":[{"id":"a","name":"Everyday £"}]}"""

    @Test
    fun `an encrypted backup opens with its passphrase, and says it's encrypted`() {
        val bytes = BackupCrypto.encrypt(json, "correct horse".toCharArray())
        assertTrue(BackupCrypto.isEncrypted(bytes))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Everyday"))
        assertEquals(json, BackupCrypto.decrypt(bytes, "correct horse".toCharArray()))
    }

    @Test
    fun `a plain backup isn't taken for an encrypted one`() {
        assertFalse(BackupCrypto.isEncrypted(json.toByteArray()))
    }

    @Test(expected = BackupCrypto.WrongPassphrase::class)
    fun `the wrong passphrase doesn't open it`() {
        BackupCrypto.decrypt(BackupCrypto.encrypt(json, "correct horse".toCharArray()), "correct hose".toCharArray())
    }

    @Test(expected = BackupCrypto.WrongPassphrase::class)
    fun `an altered file doesn't open either`() {
        val bytes = BackupCrypto.encrypt(json, "correct horse".toCharArray())
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 1).toByte()
        BackupCrypto.decrypt(bytes, "correct horse".toCharArray())
    }

    @Test
    fun `two backups of the same data differ`() {
        val a = BackupCrypto.encrypt(json, "correct horse".toCharArray())
        val b = BackupCrypto.encrypt(json, "correct horse".toCharArray())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `the newest seven are kept, and nothing else in the folder is touched`() {
        val backups = (1..10).map { "spendroid-backup-2026-10-%02d-0300.spendroid".format(it) }
        val others = listOf("holiday.jpg", "spendroid-backup-2026-01-01.json")
        val gone = AutoBackup.toDelete((others + backups).shuffled())
        assertEquals(backups.take(3).toSet(), gone.toSet())
    }

    @Test
    fun `seven or fewer, none go`() {
        assertTrue(AutoBackup.toDelete((1..7).map { "spendroid-backup-2026-10-0$it-0300.spendroid" }).isEmpty())
    }

    @Test
    fun `names sort as the backups were made`() {
        val zone = ZoneId.systemDefault()
        val early = LocalDate.of(2026, 9, 30).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        val late = LocalDate.of(2026, 10, 1).atTime(3, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("spendroid-backup-2026-09-30-2359.spendroid", AutoBackup.nameFor(early))
        assertTrue(AutoBackup.nameFor(early) < AutoBackup.nameFor(late))
    }

    @Test
    fun `the nightly one starts at the next 3am`() {
        val day = LocalDate.of(2026, 10, 23)
        assertEquals(60L, AutoBackup.minutesUntilThree(day.atTime(2, 0)))
        assertEquals(24 * 60L, AutoBackup.minutesUntilThree(day.atTime(3, 0)))
        assertEquals(15 * 60L, AutoBackup.minutesUntilThree(day.atTime(12, 0)))
    }

    @Test
    fun `the line in Settings`() {
        val today = LocalDate.of(2026, 10, 23)
        val at = today.atTime(3, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("On · the first backup is on its way", statusLine(AutoBackup.Status(null, null, true, null), false, today))
        assertEquals("On · last backup today 03:05 · the last 7 kept", statusLine(AutoBackup.Status(null, at, true, null), false, today))
        assertEquals("On · the backup yesterday 03:05 didn't save", statusLine(AutoBackup.Status(null, at, false, "x"), false, today.plusDays(1)))
        assertEquals("Backing up…", statusLine(AutoBackup.Status(null, at, true, null), true, today))
    }
}
