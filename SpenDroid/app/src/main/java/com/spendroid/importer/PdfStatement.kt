package com.spendroid.importer

import java.time.LocalDate
import kotlin.math.abs

/**
 * Penny's PDF statement reader: given each page's words and where they sit, the transactions on
 * it, what every line was taken as, and whether the statement's own totals agree. Pure logic, the
 * same as the browser test page's (proposals/pdf-reader/src/penny-pdf.js) - PdfStatementTest holds
 * the two to the same made-up statements. Built and proved on the user's own First Direct and card
 * statements and on real statements councils publish (NatWest, Lloyds, HSBC, Barclays, Unity
 * Trust, CAF, Lloyds commercial cards).
 */
object PdfStatement {

    /** A run of text on a page: [y] is the baseline, measured down from the top. */
    data class Item(val str: String, val x: Float, val y: Float, val w: Float, val h: Float)

    enum class Kind { CARD, ACCOUNT }

    data class Period(val start: LocalDate?, val end: LocalDate)

    data class Transaction(
        val date: LocalDate,
        val postDate: LocalDate?,
        val description: String,
        val reference: String?,
        /** Spending below zero, money in above - as the app keeps it. */
        val pence: Long,
        val page: Int,
        val balanceOk: Boolean? = null,
    )

    data class Check(val what: String, val ok: Boolean, val printed: Long? = null, val counted: Long? = null, val page: Int? = null)

    /** One line as Penny saw it, for the anonymised layout. */
    data class Seen(val text: String, val kind: String)

    data class Result(
        val kind: Kind,
        val period: Period?,
        val transactions: List<Transaction>,
        val checks: List<Check>,
        val notes: List<String>,
        val report: List<List<Seen>>,
        /** The statement's closing balance as printed: owed for a card, held for an account. */
        val closing: Long?,
    ) {
        val allChecksOk: Boolean get() = checks.isNotEmpty() && checks.all { it.ok }
        val recognised: Boolean get() = report.any { page -> page.any { it.kind == "header" } }
    }

    // ---- patterns -----------------------------------------------------------------------------

    private val MONTHS = mapOf("jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12)
    private const val MONTH_RE = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private const val MONTH_NC = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private val I = RegexOption.IGNORE_CASE

    // A date at the very start of a line: "27 MAR", "27 Mar 26", "27 March 2026", "27/03/2026", "27-03-26", "27/03".
    private val LEAD_DATE = Regex(
        "^(?:(\\d{1,2})[ \\-]?$MONTH_RE\\.?(?:[ \\-,]+(\\d{4}|\\d{2})(?!\\d)(?!\\s*[ \\-]?$MONTH_NC))?(?![a-z])|(\\d{1,2})[/\\-.](\\d{1,2})(?:[/\\-.](\\d{4}|\\d{2}))?)(?=\\s|$)",
        I,
    )

    // A sum of money: "1,234.56", "£1,234.56", "-£3.20", "(626.04)", "626.04 -", "12.50CR", "12.50 DR".
    private val AMOUNT = Regex("^(\\()?([-+])?\\s?£?\\s?(\\d{1,3}(?:,\\d{3})*|\\d+)\\.(\\d{2})\\)?(?:\\s?(CR|DR|C|D|-|\\+))?$", I)
    private val TRAILING_AMOUNT = Regex("(?:^|\\s)((?:\\()?[-+]?\\s?£?\\s?(?:\\d{1,3}(?:,\\d{3})*|\\d+)\\.\\d{2}\\)?(?:\\s?(?:CR|DR|C|D|-|\\+))?)$", I)
    private val WHOLE = Regex("^(\\()?([-+])?\\s?£?\\s?(\\d{1,3}(?:,\\d{3})+|\\d{1,7})\\)?(?:\\s?(CR|DR|-))?$", I)

    private class Pattern(val kind: String, val re: Regex)

    // Lines that are the statement checking itself, never transactions.
    private val CHECK_LINES = listOf(
        Pattern("opening", Regex("balance (brought|carried) forward from|balance brought|brought forward|balance from previous statement|previous (statement )?balance|opening balance|start balance", I)),
        Pattern("closing", Regex("balance carried|carried forward|closing balance|new balance|statement balance|end balance|current balance", I)),
        Pattern("subtotal", Regex("sub[- ]?total", I)),
        Pattern("total", Regex("^\\s*total\\b", I)),
    )

    // "£ Paid out", "Balance(£)", "(GBP) Amount", "Amount GBP": the currency before or after.
    private fun heading(words: String) = Regex("^(?:\\(?(?:£|GBP)\\)?\\s*)?(?:$words)(?:\\s*\\(?(?:£|GBP)\\)?)?$", I)
    private val HEADER_AMOUNT_WORDS = listOf(
        Pattern("out", heading("paid out|money out|out|debits?|withdrawals?|withdrawn|payments? out|payments|spent")),
        Pattern("in", heading("paid in|money in|in|credits?|deposits?|receipts?|payments? in")),
        Pattern("balance", heading("(?:total |running |available |account )?balance")),
        Pattern("amount", heading("amount|value|sum|how much")),
    )
    private const val CODES = "\\){3,4}|VIS|DD|BP|SO|STO|CR|DR|TFR|CHQ|ATM|CPT|FPI|FPO|BGC|POS|DEB|DEP|CC|FEE|INT|OBP|PAY|BAC|COR|CSH|MPI|MPO|SBT|TLR|CHG|ADV"

    // A bank's code for the kind of payment, at the start of its details: dropped from the description.
    private val TYPE_CODE = Regex("^($CODES)\\s+")
    private val TYPE_CELL = Regex("^($CODES)$")

    // Totals in a summary box worth checking against: "Payments In £1,234.56", "Payments Out ...".
    private val SUMMARY_TOTALS = listOf(
        Pattern("totalIn", Regex("(?:payments|money|paid|total) in\\b|total deposits|deposits\\b", I)),
        Pattern("totalOut", Regex("(?:payments|money|paid|total) out\\b|withdrawn\\b|total withdrawals|withdrawals\\b", I)),
    )
    private val HEADER_DATE = Regex("\\b(date|when)\\b", I)
    private val HEADER_DESC = Regex("\\b(description|details|transaction|what|narrative|merchant|payee)\\b", I)
    private val FOOTER = Regex("^(page \\d+( of \\d+)?|continued( overleaf| on next page)?|\\d+ of \\d+)$", I)
    private val CARD_HINTS = Regex("credit limit|card ending|available credit|credit card statement", I)
    private val SCREEN_READER = Regex("^(column|[a-z ]+(\\(£\\))?\\s*blank)$", I)
    private val HEADING_WORD = Regex("^(date|description|details|type|money (in|out)|paid (in|out)|balance|amount)(\\s*\\(£\\))?$", I)
    private val ANY_HEADING = Regex("(paid out|paid in|money out|money in|withdrawn|withdrawals?|deposits?|debits?|credits?|(?:total |running )?balance|amount)(\\s*\\(?(?:£|GBP)\\)?)?", I)

    // ---- money --------------------------------------------------------------------------------

    /** How the statement marked a figure: a minus before or after, brackets, CR or DR - or nothing. */
    internal enum class Mark { NONE, MINUS, TRAIL_MINUS, CR, DR, PLUS }

    internal data class Amount(val pence: Long, val mark: Mark)

    internal fun parseAmount(text: String, whole: Boolean = false): Amount? {
        val t = text.trim()
        var groups: List<String>? = AMOUNT.find(t)?.groupValues
        if (groups == null && whole) {
            // A statement in whole pounds - "2,000", "15" - as mock statements are; never a real bank's.
            WHOLE.find(t)?.groupValues?.let { w -> groups = listOf(w[0], w[1], w[2], w[3], "00", w[4]) }
        }
        val m = groups ?: return null
        val pence = m[3].replace(",", "").toLong() * 100 + m[4].toLong()
        val marker = m[5].uppercase()
        val mark = when {
            marker == "-" -> Mark.TRAIL_MINUS // "626.04 -": a card's credit
            m[1].isNotEmpty() || m[2] == "-" -> Mark.MINUS
            marker == "CR" || marker == "C" -> Mark.CR
            marker == "DR" || marker == "D" -> Mark.DR
            m[2] == "+" || marker == "+" -> Mark.PLUS
            else -> Mark.NONE
        }
        return Amount(pence, mark)
    }

    /** A balance as printed: a card's is what's owed (a minus or CR below zero); an account's is what's in it (a minus, DR or D). */
    private fun signedForCheck(a: Amount, card: Boolean): Long {
        val below = if (card) setOf(Mark.MINUS, Mark.TRAIL_MINUS, Mark.CR) else setOf(Mark.MINUS, Mark.TRAIL_MINUS, Mark.DR)
        return if (a.mark in below) -a.pence else a.pence
    }

    // ---- lines --------------------------------------------------------------------------------

    internal class Cell(var text: String, val x: Float, var w: Float) {
        val right: Float get() = x + w
    }

    internal class Line(val y: Float, val h: Float, var cells: List<Cell>) {
        var text: String = cells.joinToString("  ") { it.text }.trim()
        fun reset(kept: List<Cell>) { cells = kept; text = kept.joinToString("  ") { it.text }.trim() }
    }

    /** Words to lines: items on the same baseline, left to right, merged into cells where they touch. */
    internal fun toLines(items: List<Item>): List<Line> {
        val sorted = items.filter { it.str.isNotBlank() }.map { it.copy(h = maxOf(it.h, 4f)) }.sortedWith(compareBy({ it.y }, { it.x }))
        val groups = mutableListOf<Triple<Float, Float, MutableList<Item>>>()
        for (it in sorted) {
            val g = groups.lastOrNull()
            if (g != null && abs(it.y - g.first) <= maxOf(2f, g.second * 0.45f)) g.third += it else groups += Triple(it.y, it.h, mutableListOf(it))
        }
        return groups.map { (y, h, list) ->
            val cells = mutableListOf<Cell>()
            for (it in list.sortedBy { it.x }) {
                val cell = cells.lastOrNull()
                val gap = if (cell != null) it.x - (cell.x + cell.w) else Float.MAX_VALUE
                if (cell != null && gap < h * 0.9f) {
                    val space = gap > h * 0.12f && !cell.text.endsWith(" ") && !it.str.startsWith(" ")
                    cell.text += (if (space) " " else "") + it.str
                    cell.w = it.x + it.w - cell.x
                } else {
                    cells += Cell(it.str, it.x, it.w)
                }
            }
            cells.forEach { c ->
                // Lloyds writes "01 Feb 24." and "186.57."; its screen-reader labels - "Column",
                // "Money In (£)blank." - are in the text though nothing shows; and print-room marks
                // ("%%eyeball2") can run on to a date.
                var t = c.text.replace(Regex("\\s+"), " ").trim().removeSuffix(".").trim()
                t = t.replaceFirst(Regex("%%[a-z]+?\\d(?=\\d{2}[-/.]\\d{2}[-/.]\\d{2,4})", I), "").replace(Regex("%%[a-z]+\\d*", I), "").trim()
                c.text = if (SCREEN_READER.matches(t)) "" else t
            }
            Line(y, h, cells.filter { it.text.isNotEmpty() })
        }
    }

    // ---- dates --------------------------------------------------------------------------------

    private class D(val d: Int, val m: Int, var y: Int?)

    private fun monthOf(name: String): Int = MONTHS[name.lowercase().take(3)] ?: 0

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /** The period the statement covers, for the year of dates printed without one. */
    internal fun findPeriod(allText: String): Period? {
        Regex("(\\d{1,2}) $MONTH_RE(?:,? (\\d{4}))?\\s*(?:-|–|to)\\s*(\\d{1,2}) $MONTH_RE,? (\\d{4})", I).find(allText)?.let { m ->
            val g = m.groupValues
            val endY = g[6].toInt()
            val end = date(endY, monthOf(g[5]), g[4].toInt())
            val startY = if (g[3].isNotEmpty()) g[3].toInt() else if (monthOf(g[2]) > monthOf(g[5])) endY - 1 else endY
            if (end != null) return Period(date(startY, monthOf(g[2]), g[1].toInt()), end)
        }
        Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})\\s*(?:-|–|to)\\s*(\\d{1,2})/(\\d{1,2})/(\\d{4})").find(allText)?.let { m ->
            val g = m.groupValues
            val end = date(g[6].toInt(), g[5].toInt(), g[4].toInt())
            if (end != null) return Period(date(g[3].toInt(), g[2].toInt(), g[1].toInt()), end)
        }
        // Else the latest full date written on it: a statement date.
        val latest = Regex("(\\d{1,2})(?:st|nd|rd|th)? $MONTH_RE,? (\\d{4})", I).findAll(allText)
            .mapNotNull { date(it.groupValues[3].toInt(), monthOf(it.groupValues[2]), it.groupValues[1].toInt()) }.maxOrNull()
        if (latest != null) return Period(null, latest)
        // "Issue date: 15 MAR 13", "Statement date 01/02/26": a two-digit year where it's labelled.
        Regex("(?:statement|issue|print(?:ed)?)\\s+date:?\\s*(\\d{1,2})[ \\-]$MONTH_RE[ \\-](\\d{2})\\b", I).find(allText)?.let { m ->
            date(2000 + m.groupValues[3].toInt(), monthOf(m.groupValues[2]), m.groupValues[1].toInt())?.let { return Period(null, it) }
        }
        Regex("(?:statement|issue|print(?:ed)?)\\s+date:?\\s*(\\d{1,2})/(\\d{1,2})/(\\d{2,4})\\b", I).find(allText)?.let { m ->
            val y = m.groupValues[3].let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            date(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { return Period(null, it) }
        }
        return null
    }

    private class Lead(val dates: List<D>, val rest: String)

    /** The dates at the start of a line, and the text left after them. */
    private fun leadingDates(text: String): Lead {
        val dates = mutableListOf<D>()
        var rest = text
        repeat(2) {
            val m = LEAD_DATE.find(rest) ?: return Lead(dates, rest)
            val g = m.groupValues
            if (g[1].isNotEmpty()) {
                dates += D(g[1].toInt(), monthOf(g[2]), g[3].takeIf { it.isNotEmpty() }?.toInt())
            } else {
                val d = g[4].toInt(); val mo = g[5].toInt()
                if (mo !in 1..12 || d !in 1..31) return Lead(dates, rest)
                dates += D(d, mo, g[6].takeIf { it.isNotEmpty() }?.toInt())
            }
            rest = rest.substring(m.value.length).trim()
        }
        return Lead(dates, rest)
    }

    private fun resolve(d: D, period: Period?, today: LocalDate): LocalDate? {
        var y = d.y
        if (y != null && y < 100) y += 2000
        if (y == null) {
            val end = period?.end
            y = end?.year ?: today.year
            // "27 DEC" on a statement ending in January is last year's.
            val guess = date(y, d.m, d.d)
            if (end != null && guess != null && guess.isAfter(end.plusDays(31))) y -= 1
        }
        return date(y, d.m, d.d)
    }

    // ---- headings -----------------------------------------------------------------------------

    internal class Column(val kind: String, val x: Float, val right: Float)

    private class Header(val columns: List<Column>, val descX: Float?)

    private class Group(var x: Float, var right: Float, val parts: MutableList<Pair<Cell, Int>>)

    /** Headings stacked over two or three lines - "Money" over "out", "Trans" over "Date" - as one. */
    private fun stacked(line: Line, above: Line?, below: Line?): List<Cell> {
        fun words(l: Line?): List<Cell> = if (l != null && abs(l.y - line.y) < line.h * 1.9f && !LEAD_DATE.containsMatchIn(l.text) &&
            l.cells.all { c -> c.text.length <= 20 && c.text.none(Char::isDigit) }
        ) l.cells else emptyList()
        val parts = words(above).map { it to 0 } + line.cells.map { it to 1 } + words(below).map { it to 2 }
        val groups = mutableListOf<Group>()
        for ((c, order) in parts.sortedBy { it.first.x }) {
            val g = groups.firstOrNull { g -> c.x <= g.right + 3 && g.x <= c.right + 3 && g.parts.none { it.second == order } }
            if (g != null) { g.parts += c to order; g.x = minOf(g.x, c.x); g.right = maxOf(g.right, c.right) } else groups += Group(c.x, c.right, mutableListOf(c to order))
        }
        return groups.map { g -> Cell(g.parts.sortedBy { it.second }.joinToString(" ") { it.first.text }, g.x, g.right - g.x) }
    }

    /** "Paid In(£) Withdrawn(£)" run together in one cell: two headings, each where it sits. */
    private fun splitHeadings(cells: List<Cell>): List<Cell> = cells.flatMap { c ->
        val found = ANY_HEADING.findAll(c.text).toList()
        if (found.size < 2) return@flatMap listOf(c)
        val per = c.w / maxOf(c.text.length, 1)
        found.map { m -> Cell(m.value.trim(), c.x + m.range.first * per, m.value.length * per) }
    }

    /** A header row: a date heading, and at least one money heading. */
    private fun headerOf(line: Line, above: Line?, below: Line?): Header? {
        if (!HEADER_DATE.containsMatchIn(line.text)) return null
        val st = stacked(line, above, below)
        val columns = splitHeadings(st).mapNotNull { c ->
            HEADER_AMOUNT_WORDS.firstOrNull { it.re.matches(c.text.trim()) }?.let { Column(it.kind, c.x, c.right) }
        }
        if (columns.isEmpty()) return null
        return Header(columns, st.firstOrNull { HEADER_DESC.containsMatchIn(it.text) }?.x)
    }

    private fun nearestColumn(x: Float, right: Float, columns: List<Column>): Column? {
        var best: Column? = null
        var bestD = Float.MAX_VALUE
        for (col in columns) {
            // Figures sit under their heading, usually right-aligned to it.
            val d = minOf(abs(right - col.right), abs((x + right) / 2 - (col.x + col.right) / 2))
            if (d < bestD) { bestD = d; best = col }
        }
        return if (bestD <= 70) best else null
    }

    private class Money(val amount: Amount, val col: Column?, val text: String)

    private class Amounts(val amounts: List<Money>, val textCells: List<Cell>)

    /** The money on a line: cells that are amounts, or end in one, matched to the heading's columns. */
    private fun amountsOf(line: Line, columns: List<Column>?, whole: Boolean): Amounts {
        val found = mutableListOf<Money>()
        val textCells = mutableListOf<Cell>()
        for (c in line.cells) {
            var amt = parseAmount(c.text)
            if (amt == null && whole && columns != null) {
                val a = parseAmount(c.text, true)
                val col = a?.let { nearestColumn(c.x, c.right, columns) }
                // Exactly under a money column: right-aligned to its heading, or centred under it.
                if (col != null && minOf(abs(c.right - col.right), abs((c.x + c.right) / 2 - (col.x + col.right) / 2)) < 25) amt = a
            }
            var x = c.x; var right = c.right; var text = c.text; var rest: String? = null
            if (amt == null) {
                val t = TRAILING_AMOUNT.find(c.text)
                if (t != null && c.text.length > t.groupValues[1].length) {
                    val tail = t.groupValues[1]
                    val a = parseAmount(tail)
                    // A figure run on to the end of the text before it: only where it sits under a money column.
                    val width = (c.w / c.text.length) * tail.length
                    if (a != null && (columns == null || nearestColumn(c.right - width, c.right, columns) != null)) {
                        amt = a; x = c.right - width; right = c.right; text = tail
                        rest = c.text.substring(0, c.text.length - tail.length).trim()
                    }
                }
            }
            if (amt != null) {
                found += Money(amt, columns?.let { nearestColumn(x, right, it) }, text)
                if (rest != null) textCells += Cell(rest, c.x, c.w)
            } else {
                textCells += c
            }
        }
        return Amounts(found, textCells)
    }

    // ---- the statement ------------------------------------------------------------------------

    private class Tx(
        var date: LocalDate, var postDate: LocalDate?, var description: String, val reference: String?, val pence: Long, val page: Int, val ds: List<D>,
    ) {
        var balanceOk: Boolean? = null
        var balanceChecked = false
        var printed: Long? = null
    }

    private class Pending(val dates: List<D>?, val parts: MutableList<String>, var y: Float, val before: Boolean = false)

    /** The whole statement: each page's text runs, in order. */
    fun read(pages: List<List<Item>>, today: LocalDate = LocalDate.now()): Result {
        val pageLines = pages.map { toLines(it) }
        val allText = pageLines.joinToString("\n") { ls -> ls.joinToString("\n") { it.text } }
        val period = findPeriod(allText)
        val pageHeaders = pageLines.map { ls -> ls.indices.map { i -> headerOf(ls[i], ls.getOrNull(i - 1), ls.getOrNull(i + 1)) } }
        // Which way round its figures go. Paid out and Paid in columns say so; a single Amount
        // column says it with its marks: "626.04 -" or "12.50CR" on the odd line means the plain
        // figures are spending (a card); "-3.20" or "DR" means they're money in.
        val hasInOut = pageHeaders.any { hs -> hs.any { h -> h != null && h.columns.any { it.kind == "out" || it.kind == "in" } } }
        // Pence on any figure after a heading means figures without them aren't money.
        val afterHeading = pageLines.flatMapIndexed { p, ls -> ls.filterIndexed { i, _ -> pageHeaders[p].take(i).any { it != null } } }
        val whole = afterHeading.isNotEmpty() && afterHeading.none { Regex("\\d\\.\\d{2}\\b").containsMatchIn(it.text) }
        var creditMarks = 0
        var signedMarks = 0
        pageLines.forEach { ls ->
            ls.forEach { l ->
                l.cells.forEach { c ->
                    val a = parseAmount(c.text) ?: TRAILING_AMOUNT.find(c.text)?.let { parseAmount(it.groupValues[1]) }
                    if (a?.mark == Mark.CR || a?.mark == Mark.TRAIL_MINUS) creditMarks++
                    if (a?.mark == Mark.MINUS || a?.mark == Mark.DR) signedMarks++
                }
            }
        }
        val isCard = !hasInOut && (creditMarks > 0 || signedMarks == 0 || CARD_HINTS.containsMatchIn(allText))
        var undated = false
        val notes = mutableListOf<String>()
        val transactions = mutableListOf<Tx>()
        val report = mutableListOf<List<Seen>>()
        val checks = mutableListOf<Check>()

        var columns: List<Column>? = null
        var descX: Float? = null
        var sawHeader = false
        // Where descriptions really start, learnt from dated rows: a heading is often centred over them.
        var descStart: Float? = null
        var closed = false // after the closing balance: the table is over until a heading starts another
        var closedForPage = false // after "carried forward": over for this page, on again at the next
        var openAtPageEnd = false // the list ran to the foot of the last page without a closing line
        var dateX: Float? = null // where dates sit, for a year printed on the line below one
        var undatedRows = 0
        var lastActivePage = -1
        var pendingSummary: String? = null // "Balance brought forward from" / "£1,234.56" / "previous statement"
        var lastDate: List<D>? = null // for statements that print the date once per day
        // Running sums, the way the statement keeps them.
        var section = 0L // since the last sub-total (owed, for a card)
        var running: Long? = null // an account's balance, row by row
        var opening: Long? = null
        var total = 0L
        val summary = mutableMapOf<String, Long>()
        var closingWeak = false

        // Where the amount goes: on the first line of a transaction (most cards), or on the last,
        // after the date and a line or two of details (First Direct, HSBC and others).
        var amountLast: Boolean
        run {
            var afterHeader = false
            var dateOnly = 0
            pageLines.forEachIndexed { p, ls ->
                ls.forEachIndexed { i, l ->
                    if (pageHeaders[p][i] != null) { afterHeader = true; return@forEachIndexed }
                    if (!afterHeader) return@forEachIndexed
                    val lead = leadingDates(l.text)
                    val hasMoney = l.cells.any { c -> parseAmount(c.text, whole) != null || TRAILING_AMOUNT.containsMatchIn(c.text) }
                    if (lead.dates.isNotEmpty() && !hasMoney && CHECK_LINES.none { it.re.containsMatchIn(lead.rest) }) dateOnly++
                }
            }
            amountLast = dateOnly >= 2
        }

        /** A description without the bank's payment-type code in front: ")))", "DD", "VIS". */
        fun tidy(text: String): String {
            var t = leadingDates(text.trim()).rest
            TYPE_CODE.find(t)?.let { t = t.substring(it.value.length) }
            return t.replace(Regex("\\s{2,}"), "  ").trim()
        }

        /** A cover page or summary box, kept to check against at the end, never counted. True if it held one. */
        fun readSummary(text: String): Boolean {
            var found = false
            val alone = parseAmount(text.replace(Regex("^[=+]\\s*"), ""))
            val waiting = pendingSummary
            if (waiting != null && alone != null) {
                if (waiting !in summary) summary[waiting] = signedForCheck(alone, isCard)
                pendingSummary = null
                return true
            }
            pendingSummary = null
            // "Balance on 01 February 2024. £4,978.68." - the first such is the opening, the next the closing.
            Regex("balance on (?:\\d{1,2} [a-z]+ \\d{4}|\\d{1,2}/\\d{1,2}/\\d{2,4})[^£\\d]{0,6}(£?[\\d,]+\\.\\d{2})", I).find(text)?.let { on ->
                parseAmount(on.groupValues[1])?.let { a ->
                    found = true
                    if ("opening" !in summary) summary["opening"] = signedForCheck(a, isCard) else if ("closing" !in summary) summary["closing"] = signedForCheck(a, isCard)
                }
            }
            for (k in CHECK_LINES.filter { it.kind == "opening" || it.kind == "closing" } + SUMMARY_TOTALS) {
                val m = Regex("(?:${k.re.pattern})[^£\\d(\\-]{0,25}(\\(?-?£?\\s?[\\d,]+\\.\\d{2}\\)?(?:\\s?(?:CR|DR|C|D|-(?!\\d))(?![a-z]))?)", I).find(text)
                val a = m?.let { parseAmount(it.groupValues.last()) }
                if (a == null) {
                    if (k.re.containsMatchIn(text) && k !in SUMMARY_TOTALS) pendingSummary = k.kind
                    continue
                }
                found = true
                val value = if (k in SUMMARY_TOTALS) a.pence else signedForCheck(a, isCard)
                val weak = k.kind == "closing" && Regex("carried", I).containsMatchIn(m.value)
                if (k.kind == "closing" && "closing" in summary && closingWeak && !weak) {
                    // "Balance carried forward £0.00 ... Statement balance £877.97": the first was last time's.
                    if ("opening" !in summary) summary["opening"] = summary.getValue("closing")
                    summary["closing"] = value
                    closingWeak = false
                } else if (k.kind !in summary) {
                    summary[k.kind] = value
                    if (k.kind == "closing") closingWeak = weak
                }
            }
            return found
        }

        pageLines.forEachIndexed { p, lines ->
            // After "carried forward", the list goes on overleaf only where the page says it does.
            if (closedForPage && (pageHeaders[p].any { it != null } || lines.any { Regex("brought forward|continued", I).containsMatchIn(it.text) })) closed = false
            closedForPage = false
            val seen = mutableListOf<Seen>()
            var headerHere = false
            var prevTx: Tx? = null
            var prevY: Float? = null
            var pending: Pending? = null // details waiting for their amount, or run on from the row above
            fun flush() {
                // Left over: on a statement whose amounts come first, the end of the transaction above.
                val pd = pending
                if (pd != null && !amountLast && pd.dates == null && !pd.before && prevTx != null && pd.parts.isNotEmpty()) prevTx!!.description += " " + pd.parts.joinToString(" ")
                pending = null
            }
            val tableRows = lines.count { leadingDates(it.text).dates.isNotEmpty() }
            lines.forEachIndexed lines@{ li, line ->
                fun tag(kind: String) { seen += Seen(line.text, kind) }
                if (line.cells.isEmpty()) return@lines // nothing but screen-reader labels
                val h = pageHeaders[p][li]
                // A box beside the table - Barclays' "At a glance" - shares its lines: whatever sits
                // right of the last column and names a summary figure is that box.
                fun sideOf(cols: List<Column>) {
                    val right = cols.maxOf { it.right } + 8
                    val names = { t: String -> (CHECK_LINES.filter { it.kind != "subtotal" } + SUMMARY_TOTALS).any { it.re.containsMatchIn(t) } }
                    val at = line.cells.indexOfFirst { it.x > right && names(it.text) }
                    if (at < 0) return
                    readSummary(line.cells.drop(at).joinToString("  ") { it.text })
                    line.reset(line.cells.take(at))
                }
                if (h != null) {
                    sideOf(h.columns)
                    flush()
                    // The same heading again, row by row - as a screen-reader-friendly PDF repeats it -
                    // keeps the full heading's columns; it only says a new row starts.
                    val cols = columns
                    val repeat = cols != null && headerHere && h.columns.all { c -> cols.any { it.kind == c.kind } }
                    if (!repeat) {
                        if (h.descX != descX) descStart = null
                        columns = h.columns
                        descX = h.descX ?: descX
                    }
                    sawHeader = true; headerHere = true; closed = false
                    tag("header")
                    prevTx = null
                    return@lines
                }
                // Headings alone - "Description", "Money In (£)" - are labels, not details.
                if (line.cells.all { HEADING_WORD.matches(it.text) }) { tag("ignored"); return@lines }
                // Before any table on this page - a cover page, the top of a page - only checks are read.
                val tableOpen = sawHeader && !closed && (headerHere || tableRows >= 3 || (openAtPageEnd && tableRows >= 1))
                if (tableOpen && columns != null) {
                    sideOf(columns!!)
                    if (line.cells.isEmpty()) { tag("summary"); return@lines }
                }
                val lead = leadingDates(line.text)
                val dates = lead.dates
                val rest = lead.rest
                val am = amountsOf(line, if (tableOpen) columns else null, whole)
                val amounts = am.amounts
                // A check line names itself before its figure: "Closing balance 1,234.56", never a
                // note after one ("... 25,092  closing balance,").
                val subject = rest.ifEmpty { line.text }
                val firstMoney = if (amounts.isNotEmpty()) subject.indexOf(amounts[0].text.trim()) else -1
                val check = CHECK_LINES.firstOrNull { c -> c.re.find(subject)?.let { firstMoney < 0 || it.range.first < firstMoney } == true }

                if (!tableOpen) { tag(if (readSummary(line.text)) "summary" else "ignored"); return@lines }

                if (check != null && amounts.isNotEmpty()) {
                    flush()
                    val wasActive = lastActivePage
                    lastActivePage = p
                    // "14 Feb Interim balance brought forward": the day the rows below it belong to.
                    if (dates.isNotEmpty()) lastDate = dates
                    val value = signedForCheck(amounts.last().amount, isCard)
                    val counted = if (isCard) total else (running ?: total)
                    when {
                        check.kind == "opening" && opening == null -> {
                            opening = value; section += value; running = value; total += value
                        }
                        check.kind == "opening" && headerHere && wasActive < p - 1 && abs(counted - value) >= 1 -> {
                            // A page or more of something else, then a heading and a balance brought
                            // forward that isn't where the last one ended: the next statement in a pack.
                            section = value; running = value; total = value
                            notes += "Another statement starts on page ${p + 1}."
                        }
                        check.kind == "opening" -> {
                            // Brought forward again at the top of a page: where the last page ended.
                            val ok = abs(counted - value) < 1
                            checks += Check("Brought forward", ok, value, counted, p + 1)
                            if (!ok) running = value
                        }
                        check.kind == "subtotal" || check.kind == "total" -> {
                            checks += Check(if (check.kind == "subtotal") "Sub-total" else "Total", abs(section - value) < 1, value, section, p + 1)
                            if (check.kind == "subtotal") section = 0
                        }
                        else -> {
                            // A card adds up what's owed; an account follows its balance.
                            val carried = Regex("carried", I).containsMatchIn(line.text)
                            checks += Check(if (carried) "Carried forward" else "Closing balance", abs(counted - value) < 1, value, counted, p + 1)
                            // Whatever follows - interest rates, fees - isn't the statement's list. A
                            // balance carried forward only ends the page: the list goes on overleaf.
                            closed = true
                            closedForPage = carried
                        }
                    }
                    tag("check")
                    prevTx = null
                    return@lines
                }

                // A transaction: money in a money column, and a date - its own, the day's above it,
                // or the one its details started under.
                val money = amounts.filter { it.col != null && it.col.kind != "balance" }
                val balance = amounts.firstOrNull { it.col?.kind == "balance" }
                val descFrom = descStart ?: descX
                val inDescColumn = descFrom == null || line.cells.first().x >= descFrom - 8
                fun near(y: Float) = line.y - y < line.h * 4.5f
                if (money.isNotEmpty() && !Regex("\\d%").containsMatchIn(line.text)) {
                    var ds: List<D>? = null
                    var prefix: List<String> = emptyList()
                    val pd = pending
                    if (dates.isNotEmpty()) {
                        if (pd != null && pd.before && pd.dates == null && near(pd.y)) { prefix = pd.parts; pending = null }
                        flush(); ds = dates; dateX = line.cells.first().x
                        val first = line.cells.firstOrNull { !LEAD_DATE.containsMatchIn(it.text) && parseAmount(it.text, whole) == null }
                        if (first != null && first.x > line.cells.first().x) descStart = descStart?.let { minOf(it, first.x) } ?: first.x
                    } else if (amountLast && pd != null && near(pd.y)) {
                        ds = pd.dates ?: lastDate; prefix = pd.parts; pending = null
                    } else {
                        flush()
                        if (lastDate != null && (inDescColumn || amountLast)) ds = lastDate
                    }
                    if (ds != null) {
                        lastDate = ds
                        var description = am.textCells.filterNot { TYPE_CELL.matches(it.text) }.joinToString("  ") { it.text }
                        description = leadingDates(description).rest
                        var reference: String? = null
                        // A reference before the details: 8 digits on a consumer card, 23 on a commercial one.
                        Regex("^(\\d{6,24})\\s+(.*)$").find(description)?.let { reference = it.groupValues[1]; description = it.groupValues[2] }
                        description = tidy((prefix + description).filter { it.isNotEmpty() }.joinToString("  "))
                        val a = (money.firstOrNull { it.amount.pence != 0L } ?: money.first()).amount
                        // Signs: by the column where there's one for each way, else by the statement's
                        // marks. Some banks fill both - "Payments Out £0.00  Payments In £30.00".
                        val pence = when {
                            money.all { it.col?.kind == "out" || it.col?.kind == "in" } -> money.sumOf { if (it.col?.kind == "in") it.amount.pence else -it.amount.pence }
                            isCard -> if (a.mark in setOf(Mark.MINUS, Mark.TRAIL_MINUS, Mark.CR)) a.pence else -a.pence // a card: plain figures are spending
                            else -> if (a.mark in setOf(Mark.MINUS, Mark.TRAIL_MINUS, Mark.DR)) -a.pence else a.pence
                        }
                        if (ds.any { it.y == null }) undated = true
                        val d0 = resolve(ds[0], period, today)
                        if (d0 != null) {
                            val tx = Tx(d0, ds.getOrNull(1)?.let { resolve(it, period, today) }, description, reference, pence, p + 1, ds)
                            transactions += tx
                            // What the statement adds up: owed, for a card; the balance, for an account.
                            section += if (isCard) -pence else pence
                            total += if (isCard) -pence else pence
                            running = running?.plus(pence)
                            if (balance != null) {
                                val printed = signedForCheck(balance.amount, isCard)
                                tx.printed = printed
                                if (running == null) running = printed // the first balance shown starts the count
                                val ok = abs(running!! - printed) < 1
                                tx.balanceOk = ok
                                if (!ok) { checks += Check("Balance after ${tx.description.take(24)}", false, printed, running, p + 1); running = printed } else tx.balanceChecked = true
                            }
                            tag("transaction")
                            prevTx = tx; prevY = line.y; lastActivePage = p
                            return@lines
                        }
                    }
                    undatedRows++
                }

                if (FOOTER.matches(line.text.trim())) { tag("footer"); return@lines }
                // "03 Nov" with "25" printed under it: that row's year.
                val yy = Regex("^(\\d{2})$").find(line.cells.first().text)
                val above = if (prevTx != null && prevY != null && near(prevY!!)) prevTx
                else if (prevTx == null && transactions.none { it.page == p + 1 }) transactions.lastOrNull() else null // over the page
                if (yy != null && above != null && dateX != null && abs(line.cells.first().x - dateX!!) < 8 && above.ds.all { it.y == null }) {
                    val year = 2000 + yy.groupValues[1].toInt()
                    above.ds.forEach { it.y = year }
                    resolve(above.ds[0], period, today)?.let { above.date = it }
                    above.postDate = above.ds.getOrNull(1)?.let { resolve(it, period, today) }
                    tag("year")
                    return@lines
                }
                if (dates.isNotEmpty() && amounts.isEmpty() && !amountLast) { flush(); lastDate = dates; dateX = line.cells.first().x; tag("date"); return@lines }
                // A dated line with no money: where a transaction's details begin, on a statement
                // that puts the amount last.
                if (dates.isNotEmpty() && amounts.isEmpty() && amountLast) {
                    flush()
                    lastDate = dates
                    pending = Pending(dates, mutableListOf(rest), line.y)
                    tag("details")
                    return@lines
                }
                // Details without a date or money: more of the transaction above, or the start of the next.
                val pd = pending
                if (amounts.isEmpty() && dates.isEmpty() && line.text.length < 80 &&
                    ((pd != null && near(pd.y)) || (prevTx != null && prevY != null && near(prevY!!)) || (prevTx == null && headerHere)) &&
                    (descFrom == null || line.cells.first().x >= descFrom - 8 || amountLast)
                ) {
                    val target = pd ?: Pending(null, mutableListOf(), line.y, before = prevTx == null).also { pending = it }
                    target.parts += line.text
                    target.y = line.y
                    tag(if (amountLast) "details" else "continued")
                    return@lines
                }
                flush()
                tag("ignored")
                if (line.y - (prevY ?: 0f) > line.h * 2.2f) prevTx = null
            }
            flush()
            openAtPageEnd = sawHeader && !closed && transactions.any { it.page == p + 1 }
            report += seen
        }

        // The statement's own summary: what was owed before, plus this period, should be what's owed now.
        summary["closing"]?.let { closing ->
            val start = summary["opening"] ?: opening ?: 0L
            val counted = if (isCard) start - transactions.sumOf { it.pence } else (running ?: (start + transactions.sumOf { it.pence }))
            checks += Check("Statement summary", abs(counted - closing) < 1, closing, counted, 1)
        }
        if (!isCard) {
            summary["totalIn"]?.let { v -> val c = transactions.filter { it.pence > 0 }.sumOf { it.pence }; checks += Check("Money in", abs(c - v) < 1, v, c, 1) }
            summary["totalOut"]?.let { v -> val c = transactions.filter { it.pence < 0 }.sumOf { -it.pence }; checks += Check("Money out", abs(c - v) < 1, v, c, 1) }
        }
        // Newest first - as an online-banking printout lists them - each balance follows from the
        // row below it, not above: checked again oldest first, in place of the checks made going down.
        val newestFirst = transactions.size > 2 && transactions.first().date.isAfter(transactions.last().date)
        if (newestFirst) {
            checks.removeAll { it.what.startsWith("Balance after") }
            var run: Long? = null
            for (t in transactions.asReversed()) {
                t.balanceOk = null; t.balanceChecked = false
                if (run != null) run += t.pence
                val printed = t.printed ?: continue
                if (run == null) { run = printed; continue }
                val ok = abs(run - printed) < 1
                t.balanceOk = ok
                if (ok) t.balanceChecked = true else { checks += Check("Balance after ${t.description.take(24)}", false, printed, run, t.page); run = printed }
            }
            notes += "Listed newest first - read and checked oldest first."
        }
        val balanceRows = transactions.count { it.balanceChecked }
        if (balanceRows > 0) checks += Check("Running balance on $balanceRows rows", true)
        // Only worth saying when the checks don't all agree: where they do, those lines were totals.
        if (undatedRows > 0 && !(checks.isNotEmpty() && checks.all { it.ok })) {
            notes += "$undatedRows row${if (undatedRows == 1) "" else "s"} with money but no date ${if (undatedRows == 1) "was" else "were"} left out."
        }
        if (!sawHeader) notes += "No table heading found - this layout isn't one Penny knows yet."
        if (period == null && undated) notes += "No statement period or date found - dates without a year are taken as this year."
        return Result(
            kind = if (isCard) Kind.CARD else Kind.ACCOUNT,
            period = period,
            transactions = transactions.map { Transaction(it.date, it.postDate, it.description, it.reference, it.pence, it.page, it.balanceOk) },
            checks = checks,
            notes = notes,
            report = report,
            closing = summary["closing"] ?: if (isCard) null else running,
        )
    }

    /** The layout without the content: letters to x, digits to 9, the words that give the shape kept. */
    fun anonymise(result: Result): String {
        val keep = Regex(
            "^($MONTH_RE|date|trans|post|description|details|amount|balance|paid|in|out|money|credit|debit|cr|dr|sub-?total|total|" +
                "brought|carried|forward|previous|opening|closing|new|statement|page|of|from|to|card|ending|payment|direct|minimum|limit|£|-)$",
            I,
        )
        val head = buildList {
            add(
                "Verdict: " + when {
                    result.transactions.isEmpty() -> "no transactions"
                    result.checks.isEmpty() -> "nothing to check"
                    result.allChecksOk -> "everything adds up"
                    else -> "doesn't add up"
                },
            )
            add("${result.transactions.size} transactions, read as ${result.kind.name.lowercase()}, period ${if (result.period != null) "found" else "not found"}")
            result.checks.forEach { c -> add("Check ${if (c.ok) "OK" else "FAILED"}: ${if (c.what.startsWith("Balance after")) "Balance after a row" else c.what}${c.page?.let { " (page $it)" }.orEmpty()}") }
            addAll(result.notes)
        }
        val body = result.report.mapIndexed { p, lines ->
            "--- page ${p + 1}\n" + lines.joinToString("\n") { l ->
                "[${l.kind}] " + Regex("(\\s+)|([^\\s]+)").findAll(l.text).joinToString("") { m ->
                    val w = m.value
                    if (w.isBlank() || keep.matches(w.trimEnd('.', ',', ':'))) w else w.replace(Regex("[A-Za-z]"), "x").replace(Regex("\\d"), "9")
                }
            }
        }
        return (head + body).joinToString("\n")
    }
}
