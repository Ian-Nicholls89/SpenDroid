package com.spendroid.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.6.4: payee names, contract dates and subscription choices go in the backup and come back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupPrefsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `what the user said comes back exactly from a backup`() {
        PayeeNames.load(context)
        PayeeNames.set("VIS 4471 SQ *KTH LDN", "Coffee by the station")
        ContractEnds(context).set("o2", java.time.LocalDate.of(2027, 3, 1))
        ContractEnds(context).accept("o2", java.time.LocalDate.of(2026, 3, 1))
        SubscriptionChoices(context).setKind("OUT|GBP|11|streamco", com.spendroid.domain.Subscriptions.Kind.entries.first())
        SubscriptionChoices(context).setRemind("OUT|GBP|11|streamco", true)
        SubscriptionChoices(context).setCancelled("OUT|GBP|9|gym", true, java.time.LocalDate.of(2026, 9, 1))
        val before = BackupPrefs.FILES.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all }

        // Through the backup file itself, as a restore on a new phone would read it.
        val json = BackupExporter.toJson(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptySet(), preferences = BackupPrefs.export(context))
        BackupPrefs.FILES.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        BackupPrefs.restore(context, BackupImporter.parse(json).preferences)
        PayeeNames.load(context)

        assertEquals(before, BackupPrefs.FILES.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all })
        assertEquals("Coffee by the station", PayeeNames.nameFor("VIS 4471 SQ *KTH LDN"))
    }
}
