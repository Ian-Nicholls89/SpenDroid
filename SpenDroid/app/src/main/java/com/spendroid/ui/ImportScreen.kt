package com.spendroid.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.importer.StatementFile
import com.spendroid.importer.StatementFile.AMOUNT
import com.spendroid.importer.StatementFile.DATE
import com.spendroid.importer.StatementFile.IN
import com.spendroid.importer.StatementFile.NONE
import com.spendroid.importer.StatementFile.OUT
import com.spendroid.importer.StatementFile.PAYEE
import com.spendroid.ui.theme.Charcoal
import com.spendroid.web.WebImport
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opening the import: for an account, with a file shared from another app, or neither. */
data class ImportRequest(val accountId: String? = null, val uri: Uri? = null)

/** What the file picker offers: the kinds banks export, named every way they get named. */
private val FILE_TYPES = arrayOf(
    "text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/vnd.ms-excel",
    "application/x-ofx", "application/ofx", "application/vnd.intu.qfx", "application/qif", "application/x-qif",
    "application/octet-stream",
)

private const val MAX_BYTES = 5_000_000
private const val MAX_ROWS = 20_000

private enum class Step { FILE, MATCH, CHECK, DONE }

/** A loaded file: its name, and what it holds. */
internal class Loaded(val name: String, val parsed: StatementFile.Parsed)

/**
 * Older history from a bank's own file, imported on the phone: choose the file and the account,
 * check it reads right, see what's new, add. Reads files as the computer page does, checks them
 * the same way, and adds them as one batch with Move and Undo - with no notification to approve,
 * as the phone is already in hand.
 */
@Composable
fun ImportScreen(
    request: ImportRequest,
    accounts: List<AccountEntity>,
    onClose: () -> Unit,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    var step by remember { mutableStateOf(Step.FILE) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf(request.accountId) }
    var layout by remember { mutableStateOf<StatementFile.Layout?>(null) }
    // Matched by hand: the guess no longer replaces it, and it's remembered once added.
    var edited by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<WebImport.Check?>(null) }
    var adding by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(0) }

    val account = accounts.firstOrNull { it.id == accountId }
    val layouts = remember { WebImport.layouts(context) }
    fun reguess() {
        val p = loaded?.parsed ?: return
        if (p.kind == StatementFile.Kind.CSV && !edited) layout = StatementFile.guess(p, layouts, account?.accountType == AccountType.CREDIT_CARD)
        else if (layout == null) layout = StatementFile.Layout(emptyList(), emptyList())
    }

    fun load(uri: Uri) {
        scope.launch {
            problem = null
            val result = withContext(Dispatchers.IO) { readFile(context, uri) }
            result.onFailure { problem = it.message ?: "Couldn't read that file." }
            result.onSuccess { (name, text) ->
                val parsed = StatementFile.parse(text)
                if (parsed.kind == StatementFile.Kind.CSV && parsed.grid.size < 2 || parsed.kind != StatementFile.Kind.CSV && parsed.fromFile.isEmpty()) {
                    problem = "$name doesn't look like a bank statement - a CSV, OFX or QIF with transactions in it."
                    return@onSuccess
                }
                loaded = Loaded(name, parsed)
                edited = false; layout = null; check = null
                if (accountId == null) accountId = likelyAccount(parsed, accounts)
                reguess()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) }
    LaunchedEffect(Unit) {
        if (request.uri != null) load(request.uri) else picker.launch(FILE_TYPES)
    }
    // A card's file reads differently, so the guess follows the account chosen.
    LaunchedEffect(accountId) { reguess() }

    val rows = remember(loaded, layout) {
        val p = loaded?.parsed
        val l = layout
        if (p == null || l == null) emptyList() else StatementFile.read(p, l)
    }
    val good = rows.filter { it.ok }
    LaunchedEffect(good, accountId, step) {
        if (step != Step.CHECK || accountId == null) return@LaunchedEffect
        check = null
        delay(200)
        check = WebImport.check(context, accountId!!, good.map { WebImport.Row(it.date!!, it.amount!!, it.payee.take(200)) })
    }

    val back = {
        when (step) {
            Step.MATCH -> step = Step.FILE
            Step.CHECK -> step = if (needsMatching(loaded, layout)) Step.MATCH else Step.FILE
            else -> onClose()
        }
    }
    BackHandler(onBack = back)

    Box(Modifier.fillMaxSize().background(Charcoal.Background)) {
        HeaderGlow(accent, height = 260.dp, strength = 0.36f)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(start = 6.dp, top = 6.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                Wordmark(
                    when (step) { Step.FILE -> "Import"; Step.MATCH -> "Match"; Step.CHECK -> "Check"; Step.DONE -> "Added" },
                    accent,
                )
            }
            if (step != Step.DONE) Progress(step, accent)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    Step.FILE -> FileStep(loaded, problem, accounts, accountId, { picker.launch(FILE_TYPES) }, { accountId = it })
                    Step.MATCH -> MatchStep(loaded!!.parsed, layout!!, rows, onChange = { layout = it; edited = true })
                    Step.CHECK -> CheckStep(loaded!!, layout!!, rows, check, account, onFlip = { layout = layout!!.copy(flip = it); edited = true })
                    Step.DONE -> DoneStep(added, account)
                }
                Spacer(Modifier.height(8.dp))
            }
            val toAdd = check?.toAdd?.size ?: 0
            val label = when (step) {
                Step.FILE -> if (needsMatching(loaded, layout)) "Next: does it read right?" else "Next: check what's new"
                Step.MATCH -> "Looks right"
                Step.CHECK -> when {
                    adding -> "Adding…"
                    check == null -> "Checking what's new…"
                    toAdd == 0 -> "Nothing new to add"
                    else -> "Add $toAdd to ${account?.label ?: "the account"}"
                }
                Step.DONE -> "Done"
            }
            val enabled = when (step) {
                Step.FILE -> loaded != null && accountId != null && layout != null
                Step.MATCH -> good.isNotEmpty()
                Step.CHECK -> !adding && toAdd in 1..MAX_ROWS
                Step.DONE -> true
            }
            val action: () -> Unit = {
                when (step) {
                    Step.FILE -> step = if (needsMatching(loaded, layout)) Step.MATCH else Step.CHECK
                    Step.MATCH -> step = Step.CHECK
                    Step.CHECK -> {
                        val c = check
                        val p = loaded
                        val id = accountId
                        val l = layout
                        if (c != null && p != null && id != null && l != null) {
                            adding = true
                            scope.launch {
                                WebImport.add(context, id, p.name, c.toAdd, if (edited) StatementFile.remembered(p.parsed, l) else null)
                                added = c.toAdd.size
                                adding = false
                                step = Step.DONE
                                onAdded()
                            }
                        }
                    }
                    Step.DONE -> onClose()
                }
            }
            Box(
                Modifier.padding(14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(accent).alpha(if (enabled) 1f else 0.45f)
                    .clickable(enabled = enabled, onClick = action)
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Color(0xFF111111), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** A CSV the reader didn't recognise needs checking; a known bank, PayPal, OFX or QIF doesn't. */
private fun needsMatching(loaded: Loaded?, layout: StatementFile.Layout?): Boolean =
    loaded?.parsed?.kind == StatementFile.Kind.CSV && layout?.known.isNullOrEmpty()

/** The account a recognised file is plainly for: PayPal's, or the one account at that bank. */
private fun likelyAccount(parsed: StatementFile.Parsed, accounts: List<AccountEntity>): String? {
    if (parsed.kind != StatementFile.Kind.CSV) return null
    if (StatementFile.isPayPal(parsed)) return accounts.singleOrNull { it.accountType == AccountType.PAYPAL }?.id
    val known = StatementFile.guess(parsed, null, false).known.lowercase()
    if (known.isEmpty()) return null
    return accounts.singleOrNull { it.institutionName.lowercase().contains(known) }?.id
}

/** The file's name and text: UTF-8, or Windows' own encoding where it isn't. */
private fun readFile(context: Context, uri: Uri): Result<Pair<String, String>> = runCatching {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "statement"
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) error("That file is over 5 MB - export a shorter period.")
        }
        out.toByteArray()
    } ?: error("Couldn't open that file.")
    val utf8 = String(bytes, Charsets.UTF_8)
    name to if ('�' in utf8) String(bytes, charset("windows-1252")) else utf8
}

@Composable
private fun Progress(step: Step, accent: Color) {
    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Step.entries.take(3).forEach { s ->
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (s.ordinal <= step.ordinal) accent else Charcoal.Line))
        }
    }
}

// ---- 1 · the file and the account ------------------------------------------------------------

@Composable
internal fun FileStep(
    loaded: Loaded?,
    problem: String?,
    accounts: List<AccountEntity>,
    accountId: String?,
    onChoose: () -> Unit,
    onAccount: (String) -> Unit,
) {
    Panel {
        SectionHeading("The file")
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Charcoal.PanelHigh).clickable(onClick = onChoose).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp, 44.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF5A6B7D)), contentAlignment = Alignment.BottomCenter) {
                Text(loaded?.parsed?.kind?.name ?: "?", fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(loaded?.name ?: "Choose a file", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    loaded?.parsed?.let { p -> if (p.kind == StatementFile.Kind.CSV) "${p.grid.size} lines" else "${p.fromFile.size} transactions" }
                        ?: "From your bank's website or app · CSV, OFX or QIF",
                    style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted,
                )
            }
            Text(if (loaded == null) "Choose" else "Change", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
        problem?.let { Text(it, color = Charcoal.Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        loaded?.parsed?.let { p ->
            val known = if (p.kind == StatementFile.Kind.CSV) StatementFile.guess(p, null, false).known.ifEmpty { if (StatementFile.isPayPal(p)) "PayPal" else "" } else p.kind.name
            Spacer(Modifier.height(10.dp))
            if (known.isNotEmpty()) {
                Chip("✓ Recognised: $known", colour = Color(0xFF1F3A2A), textColour = Charcoal.In)
            } else {
                Text("An unfamiliar layout: you'll check how it reads next.", style = MaterialTheme.typography.labelSmall, color = Charcoal.Warn)
            }
        }
    }
    Panel {
        SectionHeading("Into which account?")
        Spacer(Modifier.height(6.dp))
        accounts.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onAccount(a.id) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = a.id == accountId, onClick = { onAccount(a.id) })
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(colourOf(a, accounts)))
                Spacer(Modifier.width(10.dp))
                Text(a.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(a.institutionName, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
            }
        }
        Text(
            "Files rarely say which account they're from, so it's asked. Move fixes it later if it's wrong.",
            style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ---- 2 · does it read right? ------------------------------------------------------------------

private enum class Ask(val title: String) { WHEN("When"), WHO("Who"), HOW_MUCH("How much") }

@Composable
internal fun MatchStep(
    parsed: StatementFile.Parsed,
    layout: StatementFile.Layout,
    rows: List<StatementFile.Read>,
    onChange: (StatementFile.Layout) -> Unit,
) {
    var asking by remember { mutableStateOf<Ask?>(null) }
    val heads = parsed.heads
    fun name(c: Int) = heads.getOrNull(c)?.ifBlank { null }?.let { "\"$it\"" } ?: "column ${c + 1}"
    Panel {
        SectionHeading("How it reads now")
        val good = rows.filter { it.ok }
        if (good.isEmpty()) {
            Text("Nothing reads yet - choose the columns below.", style = MaterialTheme.typography.bodySmall, color = Charcoal.Warn, modifier = Modifier.padding(top = 8.dp))
        }
        good.take(3).forEach { ReadRow(it) }
        Text(
            "Look right? Then you're done. If not, change the line that's wrong:",
            style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 8.dp),
        )
    }
    Panel {
        val d = layout.col(DATE); val p = layout.col(PAYEE); val a = layout.col(AMOUNT); val o = layout.col(OUT); val n = layout.col(IN)
        val f = layout.col(StatementFile.FLAG)
        fun sure(vararg cols: Int) = cols.filter { it >= 0 }.all { layout.sure.getOrElse(it) { false } }
        AskLine(
            Ask.WHEN,
            found = d >= 0, sure = sure(d),
            from = if (d >= 0) "from ${name(d)} · ${if (layout.dayFirst) "day / month" else "month / day"}" else "choose the date column",
        ) { asking = Ask.WHEN }
        AskLine(Ask.WHO, found = p >= 0, sure = sure(p), from = if (p >= 0) "from ${name(p)}" else "choose who was paid") { asking = Ask.WHO }
        AskLine(
            Ask.HOW_MUCH,
            found = a >= 0 || o >= 0 || n >= 0,
            sure = sure(a, o, n),
            from = when {
                o >= 0 || n >= 0 -> listOfNotNull(if (o >= 0) "${name(o)} out" else null, if (n >= 0) "${name(n)} in" else null).joinToString(", ")
                a >= 0 -> "from ${name(a)}" + (if (f >= 0) ", marked by ${name(f)}" else "") + (if (layout.flip) " · spending shown as positive" else "")
                else -> "choose the amount"
            },
        ) { asking = Ask.HOW_MUCH }
        val ignored = heads.indices.filter { layout.roles.getOrElse(it) { NONE } == NONE || layout.roles[it] == StatementFile.BALANCE }.map { name(it) }
        if (ignored.isNotEmpty()) {
            Text("Left out: ${ignored.joinToString(", ")}.", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 8.dp))
        }
    }
    asking?.let { ask -> ColumnPicker(ask, parsed, layout, onChange, onDismiss = { asking = null }) }
}

@Composable
private fun AskLine(ask: Ask, found: Boolean, sure: Boolean, from: String, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onChange).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val (mark, bg, fg) = when {
            !found -> Triple("!", Color(0xFF3A1F1F), Charcoal.Bad)
            sure -> Triple("✓", Color(0xFF1F3A2A), Charcoal.In)
            else -> Triple("?", Color(0xFF3A2F18), Charcoal.Warn)
        }
        Box(Modifier.size(24.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Text(mark, color = fg, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ask.title, style = MaterialTheme.typography.titleSmall)
            Text(from, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, fontFamily = FontFamily.Monospace, maxLines = 2)
        }
        Text("Change", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Behind each "Change": only the columns that could answer - dates for when, text for who,
 * numbers for how much - each with a few of its values, the current choice picked.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ColumnPicker(
    ask: Ask,
    parsed: StatementFile.Parsed,
    layout: StatementFile.Layout,
    onChange: (StatementFile.Layout) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sample = parsed.data.take(40)
    fun values(c: Int) = sample.map { it.getOrNull(c).orEmpty() }.filter { it.isNotEmpty() }
    fun share(c: Int, test: (String) -> Boolean) = values(c).let { v -> if (v.isEmpty()) 0.0 else v.count(test).toDouble() / v.size }
    val cols = (0 until parsed.width)
    val dates = cols.filter { share(it) { v -> StatementFile.parseDate(v, true) != null } > 0.8 }
    val numbers = cols.filter { share(it) { v -> StatementFile.toMinor(v) != null } > 0.8 && it !in dates }
    val text = cols.filter { it !in dates && it !in numbers && values(it).isNotEmpty() }
    fun name(c: Int) = parsed.heads.getOrNull(c)?.ifBlank { null } ?: "Column ${c + 1}"
    fun preview(c: Int) = values(c).take(3).joinToString(" · ").ifEmpty { "(empty)" }
    // Clears out a role's columns, for switching between one amount column and two.
    fun StatementFile.Layout.without(vararg gone: String) = copy(roles = roles.map { if (it in gone) NONE else it })

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).verticalScroll(rememberScrollState())) {
            when (ask) {
                Ask.WHEN -> {
                    Text("Which column is the date?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    (dates.ifEmpty { cols.toList() }).forEach { c ->
                        Choice(name(c), preview(c), layout.col(DATE) == c) { onChange(layout.with(c, DATE)) }
                    }
                    Text("Dates are written", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted, modifier = Modifier.padding(top = 14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Choice("day / month", null, layout.dayFirst, Modifier.weight(1f)) { onChange(layout.copy(dayFirst = true, known = "")) }
                        Choice("month / day", null, !layout.dayFirst, Modifier.weight(1f)) { onChange(layout.copy(dayFirst = false, known = "")) }
                    }
                }
                Ask.WHO -> {
                    Text("Which column says who was paid?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    (text.ifEmpty { cols.toList() }).forEach { c ->
                        Choice(name(c), preview(c), layout.col(PAYEE) == c) { onChange(layout.with(c, PAYEE)) }
                    }
                }
                Ask.HOW_MUCH -> {
                    val two = layout.col(OUT) >= 0 || layout.col(IN) >= 0
                    Text("How much was it?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        Choice("One column, + and −", null, !two, Modifier.weight(1f)) {
                            if (two) onChange(layout.without(OUT, IN).let { l -> (numbers.firstOrNull())?.let { l.with(it, AMOUNT) } ?: l })
                        }
                        Choice("Money out and in apart", null, two, Modifier.weight(1f)) {
                            if (!two) onChange(layout.without(AMOUNT).let { l -> numbers.firstOrNull()?.let { l.with(it, OUT) } ?: l })
                        }
                    }
                    val pool = numbers.ifEmpty { cols.toList() }
                    if (!two) {
                        pool.forEach { c -> Choice(name(c), preview(c), layout.col(AMOUNT) == c) { onChange(layout.with(c, AMOUNT)) } }
                        if (layout.col(StatementFile.FLAG) < 0) {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Spending shows as positive", style = MaterialTheme.typography.titleSmall)
                                    Text("As many card files do: on, and a purchase of 12.50 reads as spent.", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                                }
                                Switch(checked = layout.flip, onCheckedChange = { onChange(layout.copy(flip = it, known = "")) })
                            }
                        }
                    } else {
                        Text("Money out", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted, modifier = Modifier.padding(top = 14.dp))
                        pool.forEach { c -> Choice(name(c), preview(c), layout.col(OUT) == c) { onChange(layout.with(c, OUT)) } }
                        Text("Money in", style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted, modifier = Modifier.padding(top = 14.dp))
                        pool.forEach { c -> Choice(name(c), preview(c), layout.col(IN) == c) { onChange(layout.with(c, IN)) } }
                        Choice("None - this file only shows money out", null, layout.col(IN) < 0) { onChange(layout.without(IN)) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}

@Composable
private fun Choice(title: String, values: String?, picked: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(if (picked) Color(0xFF1F3A2A) else Charcoal.PanelHigh)
            .clickable(onClick = onPick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = picked, onClick = onPick)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            values?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun ReadRow(r: StatementFile.Read) {
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (r.ok) Charcoal.PanelHigh else Color(0xFF3A1F1F))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(if (r.ok) payeeColour(r.payee) else Charcoal.Bad), contentAlignment = Alignment.Center) {
            if (r.ok) Monogram(r.payee, 28.dp) else Text("!", fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.payee.tidyPayee().ifEmpty { "line ${r.line}" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (r.ok) r.date!!.format(DAY) else "line ${r.line}", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
        }
        if (r.ok) {
            Text(
                (if (r.amount!! > 0) "+" else "") + formatMoney(r.amount, "GBP"),
                fontWeight = FontWeight.Black,
                color = if (r.amount > 0) Charcoal.In else Charcoal.Text,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("${r.why} - skipped", color = Charcoal.Bad, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---- 3 · check and add ---------------------------------------------------------------------

@Composable
internal fun CheckStep(
    loaded: Loaded,
    layout: StatementFile.Layout,
    rows: List<StatementFile.Read>,
    check: WebImport.Check?,
    account: AccountEntity?,
    onFlip: (Boolean) -> Unit,
) {
    val good = rows.filter { it.ok }
    val bad = rows.filter { !it.ok && !it.left }
    val left = rows.count { it.left }
    Panel {
        SectionHeading("Before you add it")
        Spacer(Modifier.height(6.dp))
        if (check == null) {
            Text("Checking against what's in ${account?.label ?: "the account"}…", style = MaterialTheme.typography.bodySmall, color = Charcoal.Muted)
        } else {
            val range = check.toAdd.takeIf { it.isNotEmpty() }?.let { r -> " · ${r.minOf { it.date }.format(DAY)} – ${r.maxOf { it.date }.format(DAY)}" }.orEmpty()
            CountLine(check.toAdd.size, "to add$range", Charcoal.In)
            if (check.alreadyThere > 0) CountLine(check.alreadyThere, "already in SpenDroid - skipped", Charcoal.Muted)
            if (check.coveredByBank > 0) CountLine(check.coveredByBank, "your bank already sends" + (check.from?.let { " (from ${it.format(DAY)})" } ?: "") + " - skipped", Charcoal.Muted)
            if (check.toAdd.size > MAX_ROWS) Text("That's more than $MAX_ROWS - export a shorter period.", color = Charcoal.Bad, style = MaterialTheme.typography.bodySmall)
        }
        if (left > 0) CountLine(left, "PayPal lines that aren't payments - holds and currency changes - left out", Charcoal.Muted)
        if (bad.isNotEmpty()) CountLine(bad.size, "can't be read - skipped", Charcoal.Bad)
        val out = -good.filter { it.amount!! < 0 }.sumOf { it.amount!! }
        val inn = good.filter { it.amount!! > 0 }.sumOf { it.amount!! }
        Text(
            "In the file: money out ${formatMoney(out, "GBP")} · in ${formatMoney(inn, "GBP")}",
            style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 6.dp),
        )
    }
    Panel {
        SectionHeading("How it reads")
        good.take(5).forEach { ReadRow(it) }
        bad.take(2).forEach { ReadRow(it) }
        // A single signed amount can be the wrong way round on a card's file; two columns can't.
        val signed = loaded.parsed.kind != StatementFile.Kind.CSV || (layout.col(AMOUNT) >= 0 && layout.col(StatementFile.FLAG) < 0 && layout.col(OUT) < 0)
        if (signed && !StatementFile.isPayPal(loaded.parsed)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).clickable { onFlip(!layout.flip) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = layout.flip, onCheckedChange = onFlip)
                Column {
                    Text("Spending shows as positive", style = MaterialTheme.typography.titleSmall)
                    Text("Tick if purchases above read as money in.", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                }
            }
        }
    }
}

@Composable
private fun CountLine(n: Int, text: String, colour: Color) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Text("$n", color = colour, fontWeight = FontWeight.Black, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DoneStep(added: Int, account: AccountEntity?) {
    Panel {
        Text("Added $added to ${account?.label ?: "the account"}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Charcoal.In)
        Text(
            "It's being worked into your budget now. Under Accounts → Imported history it can be moved to another account or undone.",
            style = MaterialTheme.typography.bodyMedium, color = Charcoal.Muted, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
