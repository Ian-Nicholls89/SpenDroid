package com.spendroid.importer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's reader against the sample statements in test/resources/statements. The computer
 * page's reader is held to the same expected.json by test/web/import-parity.mjs, so a file reads
 * the same in either place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StatementFileTest {

    private fun text(name: String) = javaClass.classLoader!!.getResource("statements/$name")!!.readText()
    private val expected = JSONObject(text("expected.json"))

    @Test
    fun `every sample reads as expected`() {
        for (name in expected.keys()) {
            val want = expected.getJSONObject(name)
            val parsed = StatementFile.parse(text(name))
            val layout = if (parsed.kind == StatementFile.Kind.CSV) StatementFile.guess(parsed, null, accountIsCard = false) else StatementFile.Layout(emptyList(), emptyList())
            val rows = StatementFile.read(parsed, layout)
            val got = rows.filter { it.ok }.map { listOf(it.date.toString(), it.amount, it.payee) }
            val wantRows = (0 until want.getJSONArray("rows").length()).map { i ->
                val r = want.getJSONArray("rows").getJSONArray(i)
                listOf(r.getString(0), r.getLong(1), r.getString(2))
            }
            assertEquals(name, wantRows, got)
            assertEquals("$name recognised", want.getString("known"), layout.known)
            assertEquals("$name left out", want.optInt("left", 0), rows.count { it.left })
            assertEquals("$name unreadable", 0, rows.count { !it.ok && !it.left })
        }
    }

    @Test
    fun `a card file with spending as positive is read the right way round`() {
        val parsed = StatementFile.parse("Date,Description,Amount\n01/02/2025,SHOP,12.50\n02/02/2025,CAFE,3.00\n03/02/2025,PAYMENT,-100.00\n")
        val layout = StatementFile.guess(parsed, null, accountIsCard = true)
        assertTrue(layout.flip)
        assertEquals(listOf(-1250L, -300L, 10000L), StatementFile.read(parsed, layout).map { it.amount })
    }

    @Test
    fun `a layout matched before is used again`() {
        val parsed = StatementFile.parse(text("unknown.csv"))
        val mine = StatementFile.guess(parsed, null, false).with(1, StatementFile.PAYEE)
        val saved = JSONObject().put(StatementFile.signature(parsed.heads), StatementFile.remembered(parsed, mine))
        val again = StatementFile.guess(parsed, saved, false)
        assertEquals("a layout you matched before", again.known)
        assertEquals("VIS 4471", StatementFile.read(parsed, again).first().payee)
    }

    @Test
    fun `choosing a column for a role takes it from any other`() {
        val layout = StatementFile.Layout(listOf("date", "payee", "payee2"), listOf(true, true, false)).with(2, StatementFile.PAYEE)
        assertEquals(listOf("date", "", "payee"), layout.roles)
    }

    @Test
    fun `a byte-order mark at the start doesn't hide the first heading`() {
        val parsed = StatementFile.parse("\uFEFF" + text("firstdirect.csv"))
        assertEquals("first direct", StatementFile.guess(parsed, null, false).known)
    }

    @Test
    fun `amounts and dates read in every common form`() {
        assertEquals(-123456L, StatementFile.toMinor("-£1,234.56"))
        assertEquals(-1200L, StatementFile.toMinor("(12.00)"))
        assertEquals(-1200L, StatementFile.toMinor("12.00 DR"))
        assertEquals(1200L, StatementFile.toMinor("12.00 CR"))
        assertEquals(null, StatementFile.toMinor("n/a"))
        assertEquals("2025-03-04", StatementFile.parseDate("04/03/2025", true).toString())
        assertEquals("2025-04-03", StatementFile.parseDate("04/03/2025", false).toString())
        assertEquals("2025-02-05", StatementFile.parseDate("05 Feb 2025", true).toString())
        assertEquals(null, StatementFile.parseDate("31/02/2025", true))
    }
}
