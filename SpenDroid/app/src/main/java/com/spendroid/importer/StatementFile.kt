package com.spendroid.importer

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import org.json.JSONObject

/**
 * Reading a bank's own file - CSV, OFX or QIF - into transactions, on the phone. The same rules as
 * the computer page's reader (assets/web/import.js), so a file reads the same in either place;
 * StatementFileTest holds the two to the same files.
 *
 * A CSV's columns each have a role: [DATE], [PAYEE], [AMOUNT] (signed), [OUT] and [IN] (two
 * columns, unsigned), [FLAG] (a debit/credit marker for an unsigned amount), [BALANCE], or none.
 * Roles are the strings the page uses, so a layout matched in one place is known in the other.
 */
object StatementFile {

    const val DATE = "date"
    const val PAYEE = "payee"
    const val AMOUNT = "amount"
    const val OUT = "out"
    const val IN = "in"
    const val FLAG = "flag"
    const val BALANCE = "balance"
    const val NONE = ""

    enum class Kind { CSV, OFX, QIF }

    /** The byte-order mark some exports start with, which isn't part of the first heading. */
    private const val BOM = "\uFEFF"

    /** A whole file as read: a CSV's grid and where its headings are, or an OFX or QIF's rows. */
    data class Parsed(
        val kind: Kind,
        val grid: List<List<String>> = emptyList(),
        val start: Int = 0,
        val fromFile: List<FileRow> = emptyList(),
    ) {
        val heads: List<String> get() = if (start >= 0) grid.getOrNull(start).orEmpty() else emptyList()
        val data: List<List<String>> get() = grid.drop(start + 1)
        val width: Int get() = grid.drop(maxOf(0, start)).take(40).maxOfOrNull { it.size } ?: 0
    }

    /** An OFX or QIF entry, which says what each value is. */
    data class FileRow(val date: LocalDate?, val amount: Long?, val payee: String)

    /** What each column holds, and how to read it. [sure] per column: green, or amber "check". */
    data class Layout(
        val roles: List<String>,
        val sure: List<Boolean>,
        val dayFirst: Boolean = true,
        val flip: Boolean = false,
        val known: String = "",
    ) {
        fun col(role: String) = roles.indexOf(role)
        fun with(column: Int, role: String): Layout {
            // One column per role, so taking a role from one column clears it from the others.
            val next = roles.mapIndexed { i, r -> if (i == column) role else if (r == role && role != NONE) NONE else r }
            return copy(roles = next, sure = sure.mapIndexed { i, s -> if (i == column) true else s && next[i] == roles[i] }, known = "")
        }
    }

    /** One line as SpenDroid reads it: a transaction, or why not - [left] when left out on purpose. */
    data class Read(
        val line: Int,
        val date: LocalDate? = null,
        val amount: Long? = null,
        val payee: String = "",
        val why: String? = null,
        val left: Boolean = false,
    ) {
        val ok: Boolean get() = why == null && date != null && amount != null
    }

    // ---- reading files ----------------------------------------------------------------------

    fun parse(text: String, dayFirst: Boolean = true): Parsed {
        val t = text.removePrefix(BOM)
        return when {
            Regex("<OFX>|OFXHEADER", RegexOption.IGNORE_CASE).containsMatchIn(t) -> Parsed(Kind.OFX, fromFile = parseOfx(t))
            Regex("^!Type:", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(t) -> Parsed(Kind.QIF, fromFile = parseQif(t, dayFirst))
            else -> parseCsv(t).let { grid -> Parsed(Kind.CSV, grid, findStart(grid)) }
        }
    }

    fun parseCsv(text: String): List<List<String>> {
        val firstLines = text.split(Regex("\r?\n")).take(10).joinToString("\n")
        val sep = listOf(',', ';', '\t').maxBy { c -> firstLines.count { it == c } }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"' && text.getOrNull(i + 1) == '"') { cell.append('"'); i++ }
                else if (ch == '"') quoted = false
                else cell.append(ch)
            } else if (ch == '"') quoted = true
            else if (ch == sep) { row += cell.toString().trim(); cell.clear() }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                row += cell.toString().trim(); cell.clear()
                if (row.any { it.isNotEmpty() }) rows += row
                row = mutableListOf()
            } else cell.append(ch)
            i++
        }
        row += cell.toString().trim()
        if (row.any { it.isNotEmpty() }) rows += row
        return rows
    }

    private fun parseOfx(text: String): List<FileRow> =
        text.split(Regex("<STMTTRN>", RegexOption.IGNORE_CASE)).drop(1).map { block ->
            fun tag(t: String) = Regex("<$t>([^<\\r\\n]*)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val d = tag("DTPOSTED")
            val date = if (d.length >= 8) parseDate("${d.take(4)}-${d.substring(4, 6)}-${d.substring(6, 8)}", true) else null
            FileRow(date, toMinor(tag("TRNAMT")), tag("NAME").ifEmpty { tag("MEMO") })
        }

    private fun parseQif(text: String, dayFirst: Boolean): List<FileRow> {
        val out = mutableListOf<FileRow>()
        var date: LocalDate? = null
        var amount: Long? = null
        var payee = ""
        for (line in text.split(Regex("\r?\n"))) {
            if (line.isEmpty()) continue
            val v = line.drop(1).trim()
            when (line[0]) {
                'D' -> date = parseDate(v.replace("'", "/"), dayFirst)
                'T', 'U' -> amount = toMinor(v)
                'P' -> payee = v
                'M' -> if (payee.isEmpty()) payee = v
                '^' -> { out += FileRow(date, amount, payee); date = null; amount = null; payee = "" }
            }
        }
        return out
    }

    // ---- values -------------------------------------------------------------------------------

    private val MONTHS = mapOf("jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12)

    fun parseDate(raw: String?, dayFirst: Boolean): LocalDate? {
        val s = raw.orEmpty().trim()
        fun ok(y: Int, mo: Int, d: Int) = runCatching { LocalDate.of(y, mo, d) }.getOrNull()
        fun year(y: String) = if (y.length == 2) 2000 + y.toInt() else y.toInt()
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(s)?.let { val (y, m, d) = it.destructured; return ok(y.toInt(), m.toInt(), d.toInt()) }
        Regex("^(\\d{4})(\\d{2})(\\d{2})$").find(s)?.let { val (y, m, d) = it.destructured; return ok(y.toInt(), m.toInt(), d.toInt()) }
        Regex("^(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})").find(s)?.let {
            val (a, b, y) = it.destructured
            return if (dayFirst) ok(year(y), b.toInt(), a.toInt()) else ok(year(y), a.toInt(), b.toInt())
        }
        Regex("^(\\d{1,2})[\\s\\-]([A-Za-z]{3,4})[A-Za-z]*[\\s\\-](\\d{2,4})").find(s)?.let {
            val (d, m, y) = it.destructured
            val mo = MONTHS[m.lowercase()] ?: return null
            return ok(year(y), mo, d.toInt())
        }
        return null
    }

    /** "£1,234.56", "(12.00)", "12.00 DR" and "-12" all read; anything else is not an amount. */
    fun toMinor(raw: String?): Long? {
        var t = raw.orEmpty().trim()
        if (t.isEmpty()) return null
        var neg = false
        if (t.length >= 2 && t.startsWith("(") && t.endsWith(")")) { neg = true; t = t.substring(1, t.length - 1) }
        if (Regex("\\bDR$", RegexOption.IGNORE_CASE).containsMatchIn(t)) { neg = true; t = t.replace(Regex("\\s*DR$", RegexOption.IGNORE_CASE), "") }
        t = t.replace(Regex("\\s*CR$", RegexOption.IGNORE_CASE), "").replace(Regex("[£$€,\\s]"), "")
        if (t.startsWith("-") || t.startsWith("−")) { neg = !neg; t = t.drop(1) }
        if (t.startsWith("+")) t = t.drop(1)
        if (!Regex("^\\d+(\\.\\d+)?$").matches(t)) return null
        val v = BigDecimal(t).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        return if (neg) -v else v
    }

    // ---- guessing the columns -----------------------------------------------------------------

    /** The heading row: the first whose next row holds a date, and which holds none itself. */
    fun findStart(grid: List<List<String>>): Int {
        for (r in 0 until minOf(grid.size - 1, 20)) {
            val here = grid[r].any { parseDate(it, true) != null }
            val next = grid[r + 1].any { parseDate(it, true) != null }
            if (!here && next && grid[r].count { it.isNotEmpty() } >= 2) return r
        }
        return if (grid.isNotEmpty() && grid[0].any { parseDate(it, true) != null }) -1 else 0
    }

    fun signature(heads: List<String>): String = heads.joinToString("|") { h -> h.lowercase().replace(Regex("[^a-z/]"), "") }

    /** PayPal's activity download: its own headings, and several lines for each purchase. */
    fun isPayPal(p: Parsed): Boolean {
        val h = p.heads.map { it.trim().lowercase() }
        return listOf("type", "status", "currency", "transaction id", "time zone").all { it in h }
    }

    /** Each column's role, from its heading where it has a telling one, else from what it holds. */
    fun guess(p: Parsed, layouts: JSONObject?, accountIsCard: Boolean): Layout {
        val heads = p.heads
        val width = p.width
        if (isPayPal(p)) {
            val roles = heads.map { h ->
                when (h.trim().lowercase()) { "date" -> DATE; "name" -> PAYEE; "amount", "gross" -> AMOUNT; else -> NONE }
            }
            return Layout(roles, roles.map { true }, dayFirst = true, flip = false, known = "PayPal")
        }
        val saved = if (heads.isNotEmpty()) layouts?.optJSONObject(signature(heads)) else null
        if (saved != null) {
            val arr = saved.optJSONArray("roles")
            val roles = (0 until width).map { i -> arr?.optString(i, NONE) ?: NONE }
            return Layout(roles, roles.map { true }, saved.optBoolean("dayFirst", true), saved.optBoolean("flip", false), "a layout you matched before")
        }
        fun byName(raw: String?): String? {
            val h = raw.orEmpty().lowercase()
            return when {
                Regex("balance|\\bbal\\b").containsMatchIn(h) -> BALANCE
                Regex("debit\\s*/\\s*credit|dr\\s*/\\s*cr|flag|indicator").containsMatchIn(h) -> FLAG
                Regex("^(transaction\\s+date|date|txn\\s*date|trans(action)?\\s*date)$").containsMatchIn(h) -> DATE
                Regex("paid\\s*out|money\\s*out|withdrawal|^debit").containsMatchIn(h) -> OUT
                Regex("paid\\s*in|money\\s*in|deposit|^credit").containsMatchIn(h) -> IN
                Regex("^(amount|value|billing\\s+amount|transaction\\s+amount)$").containsMatchIn(h) -> AMOUNT
                Regex("description|details|merchant|payee|narrative|^name$|counter\\s*party").containsMatchIn(h) -> PAYEE
                Regex("posting\\s*date").containsMatchIn(h) -> NONE
                else -> null
            }
        }
        val sample = p.data.take(39)
        val roles = mutableListOf<String>()
        val sure = mutableListOf<Boolean>()
        for (c in 0 until width) {
            val named = byName(heads.getOrNull(c))
            if (named != null) { roles += named; sure += true; continue }
            val vals = sample.map { it.getOrNull(c).orEmpty() }.filter { it.isNotEmpty() }
            val dates = vals.count { parseDate(it, true) != null }
            val nums = vals.count { toMinor(it) != null }
            roles += when {
                vals.isNotEmpty() && dates.toDouble() / vals.size > 0.8 -> DATE
                vals.isNotEmpty() && nums.toDouble() / vals.size > 0.8 -> AMOUNT
                vals.isNotEmpty() -> PAYEE
                else -> NONE
            }
            sure += false
        }
        // One of each: a column whose heading says so beats one only guessed from what it holds.
        for (r in listOf(DATE, PAYEE, AMOUNT, FLAG)) {
            val all = roles.indices.filter { roles[it] == r }
            val keep = all.firstOrNull { sure[it] } ?: all.firstOrNull()
            all.forEach { if (it != keep) { roles[it] = NONE; sure[it] = false } }
        }
        // Money out and in, where the file has them, are the amount; a single amount column besides
        // them is more likely a balance or a total.
        if (OUT in roles || IN in roles) roles.indices.forEach { if (roles[it] == AMOUNT && !sure[it]) roles[it] = NONE }
        val sig = signature(heads)
        val known = when {
            Regex("date\\|description\\|amount").containsMatchIn(sig) && heads.size <= 4 -> "first direct"
            sig.contains("type") && sig.contains("accountname") -> "NatWest"
            Regex("debit/creditflag|creditdebitflag").containsMatchIn(sig) || (sig.contains("merchant") && sig.contains("billingamount")) -> "Tesco Bank"
            else -> ""
        }
        // A card's single amount column: banks mostly show spending as positive there.
        var flip = false
        val amountCol = roles.indexOf(AMOUNT)
        if (accountIsCard && amountCol >= 0 && FLAG !in roles) {
            val vals = sample.mapNotNull { toMinor(it.getOrNull(amountCol)) }
            flip = vals.count { it > 0 } > vals.size / 2.0
            sure[amountCol] = false
        }
        return Layout(roles, sure, dayFirst = true, flip = flip, known = known)
    }

    // ---- reading the rows ---------------------------------------------------------------------

    /** The rows as SpenDroid would read them, and those it can't, with why. */
    fun read(p: Parsed, layout: Layout): List<Read> {
        if (p.kind != Kind.CSV) {
            return p.fromFile.mapIndexed { i, r ->
                when {
                    r.date == null -> Read(i + 1, payee = r.payee, why = "no date")
                    r.amount == null -> Read(i + 1, payee = r.payee, why = "no amount")
                    else -> Read(i + 1, r.date, if (layout.flip) -r.amount else r.amount, r.payee.ifEmpty { "Unknown" })
                }
            }
        }
        val d = layout.col(DATE); val pc = layout.col(PAYEE); val a = layout.col(AMOUNT)
        val o = layout.col(OUT); val n = layout.col(IN); val f = layout.col(FLAG)
        val rows = p.data.mapIndexed { i, r ->
            val line = p.start + 2 + i
            fun cell(c: Int) = if (c >= 0) r.getOrNull(c).orEmpty() else ""
            val payee = cell(pc).trim()
            val date = if (d >= 0) parseDate(cell(d), layout.dayFirst) else null
            if (date == null) {
                return@mapIndexed Read(line, payee = payee.ifEmpty { r.joinToString(" ").take(40) }, why = if (d < 0) "choose the date column" else "\"${cell(d).take(16)}\" isn't a date")
            }
            var amount: Long? = null
            if (a >= 0 && o < 0 && n < 0) {
                amount = toMinor(cell(a))
                if (amount != null && f >= 0) {
                    val flag = cell(f).trim().lowercase()
                    amount = when {
                        Regex("^(d|dr|debit)").containsMatchIn(flag) -> -kotlin.math.abs(amount)
                        Regex("^(c|cr|credit)").containsMatchIn(flag) -> kotlin.math.abs(amount)
                        else -> amount
                    }
                } else if (amount != null && layout.flip) amount = -amount
            } else if (o >= 0 || n >= 0) {
                val out = if (o >= 0) toMinor(cell(o)) else null
                val inn = if (n >= 0) toMinor(cell(n)) else null
                amount = when {
                    out != null && out != 0L -> -kotlin.math.abs(out)
                    inn != null && inn != 0L -> kotlin.math.abs(inn)
                    out == 0L || inn == 0L -> 0L
                    else -> null
                }
            }
            if (amount == null) return@mapIndexed Read(line, payee = payee, why = if (a < 0 && o < 0 && n < 0) "choose the amount column" else "no amount it can read")
            Read(line, date, amount, payee.ifEmpty { "Unknown" })
        }
        return if (isPayPal(p)) payPal(p, rows) else rows
    }

    /**
     * PayPal writes one purchase as several lines: holds ("General Authorisation"), the payment,
     * the top-up it took from the bank or card, and for another currency a pair of conversions.
     * Only the payment and the top-up moved money; a foreign payment takes the pound figure PayPal
     * converted it into. The top-ups stay so the bank's "PAYPAL *..." debit is matched to its
     * purchase and not counted twice.
     */
    private fun payPal(p: Parsed, rows: List<Read>): List<Read> {
        val heads = p.heads.map { it.trim().lowercase() }
        val ty = heads.indexOf("type"); val st = heads.indexOf("status"); val cu = heads.indexOf("currency"); val tm = heads.indexOf("time")
        val raw = p.data
        fun cell(i: Int, c: Int) = if (c >= 0) raw.getOrNull(i)?.getOrNull(c).orEmpty().trim() else ""
        fun moment(i: Int) = "${rows[i].date} ${cell(i, tm)}"
        val pounds = mutableMapOf<String, Long>()
        rows.forEachIndexed { i, r ->
            if (r.ok && cell(i, ty).contains("currency conversion", true) && cell(i, cu).equals("GBP", true) && r.amount!! < 0) pounds[moment(i)] = r.amount
        }
        return rows.mapIndexed { i, r ->
            if (!r.ok) return@mapIndexed r
            val type = cell(i, ty); val status = cell(i, st); val currency = cell(i, cu).ifEmpty { "GBP" }
            fun leave(why: String) = r.copy(why = why, left = true)
            when {
                Regex("authori[sz]ation", RegexOption.IGNORE_CASE).containsMatchIn(type) -> leave("a hold, not a payment")
                type.contains("currency conversion", true) -> leave("PayPal changing currency")
                Regex("denied|cancel|void|revers|fail", RegexOption.IGNORE_CASE).containsMatchIn(status) -> leave(status.lowercase())
                !currency.equals("GBP", true) -> pounds[moment(i)]?.takeIf { r.amount!! < 0 }?.let { r.copy(amount = it) } ?: leave("in $currency, with no pound figure")
                r.amount!! > 0 && type.contains("deposit", true) -> r.copy(payee = if (type.contains("card", true)) "PayPal top-up from card" else "PayPal top-up from bank")
                else -> r
            }
        }
    }

    /** The layout to remember for next time, in the form the page stores it. */
    fun remembered(p: Parsed, layout: Layout): JSONObject? {
        if (p.kind != Kind.CSV || p.heads.isEmpty() || isPayPal(p)) return null
        return JSONObject()
            .put("signature", signature(p.heads))
            .put("roles", org.json.JSONArray(layout.roles))
            .put("dayFirst", layout.dayFirst)
            .put("flip", layout.flip)
    }
}
