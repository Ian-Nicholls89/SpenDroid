package com.spendroid.importer

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 5.4: the app's PDF reader against the browser page's, on the same made-up statements (every
 * layout it was built on: card sub-totals, current account balances, CR credits, an unfamiliar
 * layout, an interest table to ignore, amounts last, Barclays' interim balances, TSB's payment
 * types, Monzo's signed amounts - with cover pages, terms and giro slips around them).
 * expected.json is penny-pdf.js's own output on these files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfStatementTest {

    @Before
    fun setUp() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    private fun bytes(name: String) = javaClass.classLoader!!.getResourceAsStream("pdf/$name")!!.readBytes()
    private val expected = JSONObject(String(javaClass.classLoader!!.getResourceAsStream("pdf/expected.json")!!.readBytes()))
    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun read(name: String): PdfStatement.Result {
        val out = PdfText.read(bytes(name))
        assertTrue("$name: $out", out is PdfText.Outcome.Pages)
        return PdfStatement.read((out as PdfText.Outcome.Pages).pages, java.time.LocalDate.of(2026, 10, 20))
    }

    @Test
    fun `every sample reads exactly as the browser page reads it`() {
        for (name in expected.keys()) {
            val want = expected.getJSONObject(name)
            val got = read(name)
            assertEquals("$name kind", want.getString("kind"), got.kind.name.lowercase())
            val wantTx = want.getJSONArray("transactions")
            val gotTx = got.transactions.map { listOf(it.date.toString(), it.postDate?.toString() ?: "", it.pence.toString(), norm(it.description)) }
            val wantList = (0 until wantTx.length()).map { i -> wantTx.getJSONArray(i).let { a -> listOf(a.getString(0), a.getString(1), a.getLong(2).toString(), norm(a.getString(3))) } }
            assertEquals("$name transactions", wantList, gotTx)
            val wantChecks = want.getJSONArray("checks").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { c -> c.getString(0) to c.getBoolean(1) } } }
            val gotChecks = got.checks.map { (if (it.what.startsWith("Balance after")) "Balance after" else it.what) to it.ok }
            assertEquals("$name checks", wantChecks, gotChecks)
        }
    }

    @Test
    fun `the anonymised layout keeps the shape and hides the content`() {
        val text = PdfStatement.anonymise(read("a-card-subtotals.pdf"))
        assertTrue(text, text.startsWith("Verdict: everything adds up"))
        assertTrue(text, "GROCER" !in text && "85.45" !in text)
        assertTrue(text, "Sub-Total" in text)
    }

    @Test
    fun `a PDF's rows go through the import as an OFX's do, dated when the bank posted them`() {
        for (name in expected.keys()) {
            val r = read(name)
            val parsed = StatementFile.fromPdf(r)
            val rows = StatementFile.read(parsed, StatementFile.Layout(emptyList(), emptyList()))
            assertEquals(name, r.transactions.size, rows.count { it.ok })
            assertEquals(name, r.transactions.map { it.postDate ?: it.date }, rows.map { it.date })
            assertEquals(name, r.transactions.sumOf { it.pence }, rows.sumOf { it.amount!! })
            if (r.closing != null) assertEquals(name, r.closing, StatementFile.closingBalance(parsed, StatementFile.Layout(emptyList(), emptyList()))?.second)
        }
    }

    @Test
    fun `a locked PDF asks for its password, and a wrong one says so`() {
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument().apply {
            addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
            protect(com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "1234", com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()))
        }
        val bytes = java.io.ByteArrayOutputStream().also { doc.save(it); doc.close() }.toByteArray()
        assertTrue(PdfText.isPdf(bytes))
        assertTrue((PdfText.read(bytes) as PdfText.Outcome.NeedsPassword).wrong.not())
        assertTrue((PdfText.read(bytes, "0000") as PdfText.Outcome.NeedsPassword).wrong)
        // Opened, a page with no words on it reads as a scan.
        assertTrue(PdfText.read(bytes, "1234") is PdfText.Outcome.Scanned)
        assertTrue(!PdfText.isPdf("Date,Amount\n".toByteArray()))
    }
}
