package com.spendroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.6.0: everything stored outside the database is on one list, and the app follows it. */
class StoresTest {

    private val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `delete all data clears what it cleared before`() {
        assertEquals(
            setOf(
                "first_run", "moments", "contracts", "subscriptions", "money_watch", "manual_reminders", "payee_names",
                "penny_ask", "penny_bills", "penny_news", "penny_voice",
            ),
            Stores.clearedWithData.toSet(),
        )
    }

    @Test
    fun `a backup carries the user's data, and the settings that mean the same on another phone`() {
        // All of it, but whether setup's finished: that's the phone's (0.6.3.2).
        assertEquals(Stores.clearedWithData.toSet() - Stores.FIRST_RUN, Stores.clearedWithData.toSet() intersect Stores.inBackup.toSet())
        assertEquals(
            setOf("privacy", "launcher_icon", "watch_chime", "web_import"),
            Stores.inBackup.toSet() - Stores.clearedWithData.toSet(),
        )
    }

    @Test
    fun `no store is named twice`() {
        assertEquals(Stores.ALL.size, Stores.ALL.map { it.name }.toSet().size)
    }

    @Test
    fun `every preferences file opened is on the list`() {
        assertTrue("sources not found from ${File(".").absolutePath}", sources.isNotEmpty())
        val literal = Regex("""getSharedPreferences\(\s*"([^"]+)"""")
        val ownName = Regex("""const val PREFS = "([^"]+)"""")
        val strays = sources.flatMap { file ->
            val text = file.readText()
            (literal.findAll(text) + ownName.findAll(text)).map { "${file.name}: ${it.groupValues[1]}" }.toList()
        }
        assertEquals("Name these in Stores instead", emptyList<String>(), strays)
    }

    @Test
    fun `Android's backup rules leave out what the list says, and nothing else`() {
        val res = File("src/main/res/xml")
        val extraction = File(res, "data_extraction_rules.xml").readText()
        val legacy = File(res, "backup_rules.xml").readText()
        val excluded = Regex("""domain="sharedpref" path="([^"]+)\.xml"""")
        val cloud = extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        for ((name, rules) in listOf("cloud backup" to cloud, "device transfer" to transfer, "backup_rules" to legacy)) {
            assertEquals(name, Stores.phoneOnly.toSet(), excluded.findAll(rules).map { it.groupValues[1] }.toSet())
            assertTrue("$name keeps the GoCardless settings out", rules.contains("""domain="file" path="${Stores.SETTINGS_FILE}""""))
        }
    }
}
