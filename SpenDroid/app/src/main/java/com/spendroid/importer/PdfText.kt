package com.spendroid.importer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * A PDF's text, run by run, with where each sits on its page: what PdfStatement reads. Runs are
 * cut where the gap between letters is wider than a space, so columns stay apart.
 */
object PdfText {

    sealed class Outcome {
        class Pages(val pages: List<List<PdfStatement.Item>>) : Outcome()
        /** Locked: [wrong] when a password was given and didn't open it. */
        class NeedsPassword(val wrong: Boolean) : Outcome()
        /** No text on any page: a scan, a picture of the statement. */
        object Scanned : Outcome()
        class Unreadable(val why: String) : Outcome()
    }

    /** "%PDF-" at the start - or, as the format allows, a little after it. */
    fun isPdf(bytes: ByteArray): Boolean = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1).contains("%PDF-")

    fun read(bytes: ByteArray, password: String? = null): Outcome {
        val doc = try {
            if (password == null) PDDocument.load(bytes) else PDDocument.load(bytes, password)
        } catch (e: InvalidPasswordException) {
            return Outcome.NeedsPassword(wrong = password != null)
        } catch (e: Exception) {
            return Outcome.Unreadable(e.message ?: "Couldn't open that PDF.")
        }
        return doc.use { d ->
            val pages = (1..d.numberOfPages).map { n -> Collector().also { it.startPage = n; it.endPage = n; it.getText(d) }.items }
            if (pages.all { it.isEmpty() }) Outcome.Scanned else Outcome.Pages(pages)
        }
    }

    private class Collector : PDFTextStripper() {
        val items = mutableListOf<PdfStatement.Item>()

        init { sortByPosition = true }

        // The size as drawn: yScale includes the page's own scaling (a browser printout draws 16pt at
        // 0.75), fontSizeInPt does not; fall back to it, and the glyph height, if a PDF sets no scale.
        private fun size(p: TextPosition) = (if (p.yScale > 0.5f) p.yScale else maxOf(p.fontSizeInPt, p.heightDir)).coerceAtLeast(4f)

        override fun writeString(text: String, positions: List<TextPosition>) {
            var run = StringBuilder()
            var first: TextPosition? = null
            var last: TextPosition? = null
            fun emit() {
                val f = first ?: return
                val l = last ?: return
                val s = run.toString()
                if (s.isNotBlank()) {
                    items += PdfStatement.Item(s.trimEnd(), f.xDirAdj, f.yDirAdj, l.xDirAdj + l.widthDirAdj - f.xDirAdj, size(f))
                }
                run = StringBuilder(); first = null; last = null
            }
            for (p in positions) {
                // Spaces come from the gaps alone: some PDFs (NatWest's) hide a one-point space on
                // top of each capital letter, which would split "Account" into "A ccount".
                if (p.unicode.isBlank()) continue
                val prev = last
                if (prev != null) {
                    val fs = size(prev)
                    // A letter given no width (NatWest's first letters) touches the next.
                    val gap = if (prev.widthDirAdj <= 0f) 0f else p.xDirAdj - (prev.xDirAdj + prev.widthDirAdj)
                    val sameLine = kotlin.math.abs(p.yDirAdj - prev.yDirAdj) < fs * 0.45f
                    // A wide gap or a new baseline starts a new run. A space is a gap of half the
                    // font's own space or more, as PDFBox judges it.
                    val space = prev.widthOfSpace.takeIf { it > 0f && !it.isNaN() } ?: (fs * 0.25f)
                    if (!sameLine || gap > fs * 0.9f) emit()
                    else if (gap > space * 0.5f) run.append(' ')
                }
                if (first == null) first = p
                run.append(p.unicode)
                last = p
            }
            emit()
        }
    }
}
