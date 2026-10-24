package com.spendroid.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.6.0.2: the settings store in a backup - every setting placed, and put back as it was. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SettingsBackupTest {

    @Test
    fun `every setting is either in a backup, the bank link, or about today`() {
        val keys = Class.forName("com.spendroid.data.SecretsStore\$Keys").declaredFields
            .filter { androidx.datastore.preferences.core.Preferences.Key::class.java.isAssignableFrom(it.type) }
            .map { it.isAccessible = true; (it.get(null) as androidx.datastore.preferences.core.Preferences.Key<*>).name }
            .toSet()
        val placed = SecretsStore.IN_BACKUP + SecretsStore.BANK + SecretsStore.NOT_CARRIED
        assertEquals("Place these in SecretsStore's backup lists", emptySet<String>(), keys - placed)
        assertEquals("No longer settings", emptySet<String>(), placed - keys)
        assertTrue((SecretsStore.IN_BACKUP intersect SecretsStore.BANK).isEmpty())
    }

    @Test
    fun `settings come back as they were, sets are added to, and the bank link only from an encrypted backup`() = runBlocking {
        val store = SecretsStore(ApplicationProvider.getApplicationContext<Context>())
        store.saveAccent("BLUE")
        store.saveAccountColours(true)
        store.saveNotificationTime("19:30")
        store.setSpendPlace("porton stores", "acc-1")
        store.saveNotifiedDuplicates(setOf("dup-1"))
        store.addIgnoredRules(setOf("OUT|GBP|15|netflix"))

        val connection = Connection("NATIONWIDE", "Made-up Bank", "req-1", listOf("acc-1"), createdAt = 1_760_000_000_000)
        store.saveConnections(listOf(connection))

        // A plain file never has the bank link; an encrypted backup does.
        assertFalse(SecretsStore.BANK.any { store.exportStore().has(it) })
        assertTrue(store.exportStore(includeBank = true).has("connections"))
        val backup = store.exportStore(includeBank = true)

        // A different state, then the backup put back.
        store.saveAccent("GREEN")
        store.saveAccountColours(false)
        store.saveNotificationTime("21:00")
        store.setSpendPlace("porton stores", null)
        store.saveNotifiedDuplicates(setOf("dup-2"))
        store.saveConnections(emptyList())
        store.restoreStore(backup)

        assertEquals("BLUE", store.accent.first())
        assertTrue(store.accountColours.first())
        assertEquals("19:30", store.notificationTime.first())
        assertEquals("acc-1", store.spendPlaces.first()["porton stores"])
        assertEquals(setOf("dup-1", "dup-2"), store.notifiedDuplicates.first())
        assertEquals(setOf("OUT|GBP|15|netflix"), store.ignoredRules.first())
        assertEquals(listOf(connection), store.connections.first())
    }
}
