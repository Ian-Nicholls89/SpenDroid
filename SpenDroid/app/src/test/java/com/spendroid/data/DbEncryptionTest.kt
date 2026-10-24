package com.spendroid.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 0.5.8: what can be checked off the phone - the encryption itself needs SQLCipher's native code,
 * so the conversion is road-tested on the phone, behind its switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DbEncryptionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `off and not asked for, the database opens plain as ever`() {
        assertNull(DbEncryption.prepare(context))
        assertFalse(DbEncryption.isOn(context))
    }

    @Test
    fun `encrypted on another phone, its key not here - set aside, started empty, and said`() {
        val db = context.getDatabasePath(DbEncryption.NAME).apply { parentFile?.mkdirs(); writeText("encrypted elsewhere") }
        context.getSharedPreferences("db_encryption", Context.MODE_PRIVATE).edit().putBoolean("on", true).commit()

        assertNull(DbEncryption.prepare(context))

        assertFalse(DbEncryption.isOn(context))
        assertFalse(db.exists())
        assertTrue(db.parentFile!!.listFiles()!!.any { it.name.startsWith("${DbEncryption.NAME}.unreadable-") })
        assertTrue(DbEncryption.note(context)!!.contains("Restore from a SpenDroid backup"))
    }

    @Test
    fun `the database being converted is opened allowed to create the new one beside it`() {
        // 0.5.8 on the phone: ATTACH couldn't create budget.db.converting - "unable to open database" (14).
        val create = net.zetetic.database.sqlcipher.SQLiteDatabase.CREATE_IF_NECESSARY
        assertTrue(DbEncryption.SOURCE_FLAGS and create == create)
    }
}
