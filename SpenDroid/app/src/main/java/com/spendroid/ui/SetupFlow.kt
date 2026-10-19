package com.spendroid.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.domain.Cadence
import com.spendroid.domain.Direction
import com.spendroid.domain.NotificationSpend
import com.spendroid.importer.StatementFile
import com.spendroid.ui.theme.Charcoal
import com.spendroid.ui.theme.Lato
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.delay

/** The steps of first-run setup, in the order the statements route takes them. */
enum class SetupStep { WELCOME, ACCOUNTS, HISTORY, READING, FOUND, FRESH, NOTIFY, APPS, REMINDERS, DONE, GOCARDLESS }

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val SHORT = DateTimeFormatter.ofPattern("d MMM")

/** What "Start from today" and "Add your pay" collect about the user's pay. */
data class PayAnswer(val payMinor: Long, val cadence: Cadence, val anchorDay: Int, val lastPaid: LocalDate)

/**
 * First-run setup, for someone with no bank link: accounts added by hand, their history from
 * statement files, a check of what was found, then notifications and reminders to keep it
 * current. Or, with no statement to hand, four answers and the budget starts from today. Shown
 * only until it's finished; anyone with GoCardless keys never sees it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupFlow(
    state: RootUiState,
    onAddAccount: (String, String, AccountType, Long?) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onStartFromToday: (name: String, bank: String, balanceMinor: Long, pay: PayAnswer) -> Unit,
    onAddPay: (PayAnswer, String?) -> Unit,
    onSetBalance: (String, Long) -> Unit,
    onIgnoreRule: (String, Boolean) -> Unit,
    onSaveSources: (List<NotificationSpend.Source>) -> Unit,
    onUpdateAccount: (AccountEntity) -> Unit,
    onReload: () -> Unit,
    onFinish: () -> Unit,
    /** Today's GoCardless setup, for anyone who already has keys. */
    gocardless: @Composable () -> Unit,
    /** The import screen, full size, for one account's statement. */
    importScreen: @Composable (accountId: String, onDone: () -> Unit) -> Unit,
    initialStep: SetupStep = SetupStep.WELCOME,
    /** For screenshots: Android's notification access, as if granted. */
    accessGranted: Boolean? = null,
) {
    // Drawn outside the app's Scaffold, which is what gives text its colour everywhere else.
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Charcoal.Text) {
        SetupSteps(
            state, onAddAccount, onRemoveAccount, onStartFromToday, onAddPay, onSetBalance, onIgnoreRule,
            onSaveSources, onUpdateAccount, onReload, onFinish, gocardless, importScreen, initialStep, accessGranted,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupSteps(
    state: RootUiState,
    onAddAccount: (String, String, AccountType, Long?) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onStartFromToday: (name: String, bank: String, balanceMinor: Long, pay: PayAnswer) -> Unit,
    onAddPay: (PayAnswer, String?) -> Unit,
    onSetBalance: (String, Long) -> Unit,
    onIgnoreRule: (String, Boolean) -> Unit,
    onSaveSources: (List<NotificationSpend.Source>) -> Unit,
    onUpdateAccount: (AccountEntity) -> Unit,
    onReload: () -> Unit,
    onFinish: () -> Unit,
    gocardless: @Composable () -> Unit,
    importScreen: @Composable (accountId: String, onDone: () -> Unit) -> Unit,
    initialStep: SetupStep,
    accessGranted: Boolean?,
) {
    var step by rememberSaveable { mutableStateOf(initialStep) }
    // Which way in: statements, or starting from today. Back follows it.
    var fresh by rememberSaveable { mutableStateOf(initialStep == SetupStep.FRESH) }
    var importing by rememberSaveable { mutableStateOf<String?>(null) }
    var skipped by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val accounts = state.accounts.filter { it.manual }

    fun back() {
        step = when (step) {
            SetupStep.ACCOUNTS, SetupStep.FRESH, SetupStep.GOCARDLESS -> SetupStep.WELCOME
            SetupStep.HISTORY -> SetupStep.ACCOUNTS
            SetupStep.READING, SetupStep.FOUND -> SetupStep.HISTORY
            SetupStep.NOTIFY -> if (fresh) SetupStep.FRESH else SetupStep.FOUND
            SetupStep.APPS -> SetupStep.NOTIFY
            SetupStep.REMINDERS -> SetupStep.NOTIFY
            SetupStep.DONE -> SetupStep.REMINDERS
            SetupStep.WELCOME -> SetupStep.WELCOME
        }
    }
    BackHandler(enabled = step != SetupStep.WELCOME && importing == null) { back() }

    importing?.let { id ->
        importScreen(id) { importing = null }
        return
    }
    if (step == SetupStep.GOCARDLESS) {
        Column(Modifier.fillMaxSize().background(Charcoal.Background).statusBarsPadding()) {
            IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Box(Modifier.weight(1f)) { gocardless() }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Charcoal.Background)) {
        HeaderGlow(MaterialTheme.colorScheme.primary, height = 260.dp)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                if (step != SetupStep.WELCOME && step != SetupStep.READING) {
                    IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Wordmark("SpenDroid", MaterialTheme.colorScheme.primary)
            }
            progressOf(step)?.let { (n, label) -> Progress(n, label) }
            when (step) {
                SetupStep.WELCOME -> Welcome(
                    onStatements = { fresh = false; step = SetupStep.ACCOUNTS },
                    onFresh = { fresh = true; step = SetupStep.FRESH },
                    onGoCardless = { step = SetupStep.GOCARDLESS },
                )
                SetupStep.ACCOUNTS -> Accounts(accounts, state.accounts, onAddAccount, onRemoveAccount) { step = SetupStep.HISTORY }
                SetupStep.HISTORY -> History(
                    accounts, state, skipped,
                    onImport = { importing = it },
                    onSkip = { skipped = skipped + it },
                ) { step = SetupStep.READING }
                SetupStep.READING -> Reading(onReload) { step = SetupStep.FOUND }
                SetupStep.FOUND -> Found(state, accounts, onAddPay, onSetBalance, onIgnoreRule) { step = SetupStep.NOTIFY }
                SetupStep.FRESH -> Fresh(onStartFromToday) { step = SetupStep.NOTIFY }
                SetupStep.NOTIFY -> Notify(accessGranted, onGranted = { step = SetupStep.APPS }) { step = SetupStep.REMINDERS }
                SetupStep.APPS -> Apps(state, onSaveSources, onUpdateAccount) { step = SetupStep.REMINDERS }
                SetupStep.REMINDERS -> {
                    Title("Two small habits keep it right")
                    Lead("Notifications catch card spending. Direct debits and transfers turn up in your next statement, and a balance check catches anything else.")
                    ManualRemindersPanel(heading = false)
                    Spacer(Modifier.height(14.dp))
                    Primary("Finish") { step = SetupStep.DONE }
                }
                SetupStep.DONE -> Done(state, accounts, onFinish)
                SetupStep.GOCARDLESS -> Unit
            }
        }
    }
}

/** Which of the five stages a step belongs to, for the bar along the top. */
private fun progressOf(step: SetupStep): Pair<Int, String>? = when (step) {
    SetupStep.ACCOUNTS -> 1 to "Accounts"
    SetupStep.FRESH -> 1 to "Start from today"
    SetupStep.HISTORY -> 2 to "History"
    SetupStep.READING, SetupStep.FOUND -> 3 to "Check"
    SetupStep.NOTIFY, SetupStep.APPS, SetupStep.REMINDERS -> 4 to "Keep it current"
    SetupStep.DONE -> 5 to "Done"
    SetupStep.WELCOME, SetupStep.GOCARDLESS -> null
}

@Composable
private fun Progress(n: Int, label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(5) { i ->
            Box(
                Modifier.weight(1f).height(4.dp)
                    .background(if (i < n) MaterialTheme.colorScheme.primary else Color(0xFF33363E), RoundedCornerShape(2.dp)),
            )
        }
    }
    Text(
        "STEP $n OF 5 · ${label.uppercase()}",
        style = MaterialTheme.typography.labelSmall,
        color = Charcoal.Muted,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
    )
}

@Composable
private fun Title(text: String) {
    Text(text, fontFamily = Lato, fontWeight = FontWeight.Black, fontSize = 25.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
}

@Composable
private fun Lead(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB8BCC5), modifier = Modifier.padding(bottom = 14.dp))
}

@Composable
private fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    BigButton(
        text, null, { if (enabled) onClick() }, Modifier.fillMaxWidth(),
        colour = if (enabled) MaterialTheme.colorScheme.primary else Charcoal.PanelHigh,
        textColour = if (enabled) Color(0xFF111111) else Charcoal.Muted,
    )
}

@Composable
private fun Welcome(onStatements: () -> Unit, onFresh: () -> Unit, onGoCardless: () -> Unit) {
    Spacer(Modifier.height(36.dp))
    Title("Know what you can spend, every day")
    Lead("Meet Penny. She works out what's left until payday, after your bills and card statements. Everything stays on this phone: no account, no server.")
    Choice(
        "⇪", "Use your bank's statements", "RECOMMENDED",
        "Download a statement from your bank's app or website. Penny reads it, then stays up to date from your banking notifications.",
        onStatements,
    )
    Choice("✎", "Start from today", null, "No statement to hand? Tell Penny your balance and payday, and she learns your bills as they come.", onFresh)
    Spacer(Modifier.height(18.dp))
    Text(
        "I have GoCardless keys · link a bank",
        style = MaterialTheme.typography.labelLarge,
        color = Charcoal.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onGoCardless).padding(12.dp),
    )
}

@Composable
private fun Choice(icon: String, title: String, tag: String?, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).background(Charcoal.Panel, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Box(
            Modifier.size(42.dp).background(if (tag != null) Color(0xFF1F3A2A) else Charcoal.PanelHigh, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(icon, color = if (tag != null) Charcoal.In else Color(0xFFCFD2D8), fontWeight = FontWeight.Black, fontSize = 18.sp) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            tag?.let {
                Text(
                    it, color = Charcoal.In, fontWeight = FontWeight.Black, fontSize = 10.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp).background(Color(0xFF1F3A2A), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAB0BB), modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun Accounts(
    accounts: List<AccountEntity>,
    all: List<AccountEntity>,
    onAdd: (String, String, AccountType, Long?) -> Unit,
    onRemove: (String) -> Unit,
    onNext: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    Title("Which accounts do you use?")
    Lead("Add the account you're paid into first, then any credit cards. You can add more later.")
    Panel {
        if (accounts.isEmpty()) Text("No accounts yet", style = MaterialTheme.typography.bodySmall, color = Charcoal.Muted)
        accounts.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(colourOf(a, all), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = MaterialTheme.typography.titleSmall)
                    Text("${a.institutionName} · ${a.accountType.shortName}" + if (i == 0 && a.accountType != AccountType.CREDIT_CARD) " · paid in here" else "", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                }
                TextButton(onClick = { onRemove(a.id) }) { Text("Remove", color = Charcoal.Muted) }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    BigButton("Add an account", Icons.Filled.Add, { adding = true }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    Primary("Next", enabled = accounts.isNotEmpty(), onClick = onNext)
    if (adding) {
        ManualAccountDialog(onAdd = { n, b, t, bal -> adding = false; onAdd(n, b, t, bal) }, onDismiss = { adding = false })
    }
}

@Composable
private fun History(
    accounts: List<AccountEntity>,
    state: RootUiState,
    skipped: List<String>,
    onImport: (String) -> Unit,
    onSkip: (String) -> Unit,
    onNext: () -> Unit,
) {
    var howFor by remember { mutableStateOf<String?>(null) }
    Title("Bring in your history")
    Lead("Download a statement from each bank's app or website, as a CSV, OFX or QIF file. The more months, the better Penny knows your bills.")
    accounts.forEach { a ->
        val covered = state.manualCoverage[a.id]
        val imported = state.transactions.count { it.accountId == a.id && it.transactionId.startsWith(com.spendroid.domain.DuplicateCheck.IMPORTED_PREFIX) }
        Panel(Modifier.padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(colourOf(a, state.accounts), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(10.dp))
                Text(a.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                when {
                    covered != null -> Text(if (imported > 0) "✓ $imported transactions" else "✓ Imported", color = Charcoal.In, style = MaterialTheme.typography.labelLarge)
                    a.id in skipped -> Text("Skipped", color = Charcoal.Muted, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (covered != null) {
                Text("Statement to ${covered.format(SHORT)}", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { onImport(a.id) }) { Text("Add another file") }
            } else if (a.id !in skipped) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    BigButton("Choose file", Icons.Filled.FileUpload, { onImport(a.id) }, colour = MaterialTheme.colorScheme.primary, textColour = Color(0xFF111111))
                    TextButton(onClick = { howFor = if (howFor == a.id) null else a.id }) { Text("How do I get one?") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onSkip(a.id) }) { Text("Skip", color = Charcoal.Muted) }
                }
                if (howFor == a.id) HowToGetOne(a.institutionName)
            }
        }
    }
    Primary("Next", enabled = accounts.all { state.manualCoverage[it.id] != null || it.id in skipped }, onClick = onNext)
    Text(
        "Choose a file for each account, or skip it.",
        style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** Short steps for getting a statement file out of the bank's app, by bank where known. */
@Composable
private fun HowToGetOne(bank: String) {
    val steps = statementSteps(bank)
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().background(Charcoal.PanelHigh, RoundedCornerShape(10.dp)).padding(12.dp)) {
        steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        Text("No export in the app? Most banks offer it on their website.", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The way to a statement file, for the big UK banks; the general way for any other. */
internal fun statementSteps(bank: String): List<String> {
    val b = bank.lowercase()
    val app = bank.ifBlank { "your bank" }
    return when {
        "monzo" in b -> listOf("Open Monzo and tap your account", "Tap the ⋯ menu, then Export transactions", "Choose CSV and the dates", "Share it to SpenDroid, or save it and come back")
        "starling" in b -> listOf("Open Starling and go to Spending", "Tap Statements, then Export", "Choose CSV and the months", "Share it to SpenDroid, or save it and come back")
        "first direct" in b || "hsbc" in b -> listOf("Log in on the $app website", "Open the account, then Download transactions", "Choose CSV (or OFX) and the dates", "Save it to your phone and come back")
        "barclays" in b || "natwest" in b || "lloyds" in b || "halifax" in b || "santander" in b || "nationwide" in b ->
            listOf("Open the $app app or website and tap the account", "Find Statements, then Export or Download", "Choose CSV (or OFX) and as many months as it allows", "Share it to SpenDroid, or save it and come back")
        else -> listOf("Open $app's app or website and tap the account", "Look for Statements, Export or Download transactions", "Choose CSV, OFX or QIF, and as many months as it allows", "Share it to SpenDroid, or save it and come back")
    }
}

@Composable
private fun Reading(onReload: () -> Unit, onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        onReload()
        delay(1_600)
        onDone()
    }
    Spacer(Modifier.height(120.dp))
    Text("Reading your statements…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 18.dp, start = 24.dp, end = 24.dp))
    Text("Finding your pay, your bills and your card's statement dates", style = MaterialTheme.typography.bodySmall, color = Charcoal.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
}

@Composable
private fun Found(
    state: RootUiState,
    accounts: List<AccountEntity>,
    onAddPay: (PayAnswer, String?) -> Unit,
    onSetBalance: (String, Long) -> Unit,
    onIgnoreRule: (String, Boolean) -> Unit,
    onNext: () -> Unit,
) {
    val budget = state.budget
    // The bills as first found: unticking one takes it out of the budget, but not off this list.
    val bills = remember(budget == null) {
        budget?.fixedRules.orEmpty().filter { it.direction == Direction.OUT }.sortedByDescending { kotlin.math.abs(it.amountMinor) }
    }
    var addingPay by remember { mutableStateOf(false) }
    Title("Here's what we found")
    Lead("Untick anything that's wrong. You can change it all later in Regular and Accounts.")

    Panel(Modifier.padding(bottom = 10.dp)) {
        SectionHeading("Your pay")
        val pay = budget?.primaryIncomeRule
        if (pay != null) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pay.payee.tidyPayee(), style = MaterialTheme.typography.titleSmall)
                    Text(
                        cadenceWords(pay.cadence, pay.anchorDay) + (budget.nextIncomeDate?.let { " · next ${it.format(DAY)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted,
                    )
                }
                Text("+" + formatMoney(pay.amountMinor, pay.currency), color = Charcoal.In, fontWeight = FontWeight.Black)
            }
        } else {
            Text("No regular pay found yet - the statement may be too short.", style = MaterialTheme.typography.bodySmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 4.dp))
            if (!addingPay) TextButton(onClick = { addingPay = true }) { Text("Add your pay") }
            else PayForm(onDone = { onAddPay(it, accounts.firstOrNull { a -> a.accountType != AccountType.CREDIT_CARD }?.id); addingPay = false })
        }
    }

    if (bills.isNotEmpty()) {
        Panel(Modifier.padding(bottom = 10.dp)) {
            SectionHeading("Regular bills")
            bills.forEach { rule ->
                val on = rule.key !in state.ignoredRules
                Row(Modifier.fillMaxWidth().clickable { onIgnoreRule(rule.key, on) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = { onIgnoreRule(rule.key, !it) })
                    Column(Modifier.weight(1f)) {
                        Text(rule.payee.tidyPayee(), style = MaterialTheme.typography.bodyMedium)
                        Text(cadenceWords(rule.cadence, rule.anchorDay), style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                    }
                    Text(formatMoney(kotlin.math.abs(rule.amountMinor), rule.currency), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    Panel(Modifier.padding(bottom = 10.dp)) {
        SectionHeading("Balances")
        accounts.forEach { a ->
            var typed by remember(a.id) { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(colourOf(a, state.accounts), RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            a.balanceMinor == null -> "Not in a file · type it in"
                            a.anchorDate != null -> "From the statement · ${LocalDate.parse(a.anchorDate).format(SHORT)}"
                            else -> "As you said"
                        },
                        style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted,
                    )
                }
                val balance = a.balanceMinor
                if (balance != null) {
                    Text(formatMoney(balance, a.currency), fontWeight = FontWeight.Bold)
                } else {
                    OutlinedTextField(
                        typed, { typed = it }, singleLine = true, prefix = { Text("£") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(130.dp),
                    )
                    TextButton(onClick = {
                        StatementFile.toMinor(typed)?.let { onSetBalance(a.id, if (a.accountType == AccountType.CREDIT_CARD) -kotlin.math.abs(it) else it) }
                    }) { Text("Save") }
                }
            }
        }
    }

    budget?.cardBills.orEmpty().forEach { bill ->
        val card = accounts.firstOrNull { it.id == bill.cardAccountId } ?: return@forEach
        Panel(Modifier.padding(bottom = 10.dp)) {
            SectionHeading(card.label)
            val statement = bill.statementClose?.dayOfMonth
            val due = bill.dueDate?.dayOfMonth
            Text(
                if (statement != null && due != null) "Statement on the ${ordinalOf(statement)}, paid on the ${ordinalOf(due)} - from the payments in the file."
                else "Its statement and payment days aren't clear from the file yet. Set them under Accounts.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    Primary("Looks right", onClick = onNext)
}

/** "Monthly · 1st", "Last working day", "Weekly": how often a rule comes. */
internal fun cadenceWords(cadence: Cadence, anchorDay: Int): String = when (cadence) {
    Cadence.WEEKLY -> "Weekly"
    Cadence.FORTNIGHTLY -> "Every 2 weeks"
    Cadence.MONTHLY -> "Monthly · ${ordinalOf(anchorDay)}"
    Cadence.MONTHLY_LAST_DAY -> "Last day of the month"
    Cadence.MONTHLY_LAST_BUSINESS_DAY -> "Last working day of the month"
    Cadence.QUARTERLY -> "Quarterly"
    Cadence.ANNUAL -> "Yearly"
}

/** How you're paid, how much, and when it last came. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayForm(today: LocalDate = LocalDate.now(), button: String = "Add", onDone: (PayAnswer) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(Cadence.MONTHLY_LAST_BUSINESS_DAY) }
    var day by rememberSaveable { mutableStateOf("25") }
    var amount by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf<String?>(null) }
    Text("When are you paid?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Cadence.MONTHLY_LAST_BUSINESS_DAY to "Last working day",
            Cadence.MONTHLY to "A set date",
            Cadence.FORTNIGHTLY to "Every 2 weeks",
            Cadence.WEEKLY to "Weekly",
        ).forEach { (c, label) -> FilterChip(selected = kind == c, onClick = { kind = c; last = null }, label = { Text(label) }) }
    }
    when (kind) {
        Cadence.MONTHLY -> OutlinedTextField(
            day, { day = it.filter(Char::isDigit).take(2) }, label = { Text("Day of the month") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(180.dp),
        )
        Cadence.WEEKLY, Cadence.FORTNIGHTLY -> {
            Text("When were you last paid?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0L until if (kind == Cadence.WEEKLY) 7L else 14L).map { today.minusDays(it) }.forEach { d ->
                    FilterChip(selected = last == d.toString(), onClick = { last = d.toString() }, label = { Text(d.format(DateTimeFormatter.ofPattern("EEE d"))) })
                }
            }
        }
        else -> Unit
    }
    OutlinedTextField(
        amount, { amount = it }, label = { Text("Usual take-home pay") }, prefix = { Text("£") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    val answer = payAnswer(kind, day.toIntOrNull(), last?.let(LocalDate::parse), StatementFile.toMinor(amount), today)
    Spacer(Modifier.height(10.dp))
    Primary(button, enabled = answer != null) { answer?.let(onDone) }
}

/** The pay as a rule can hold it: the day it last came, worked out where it can be. */
internal fun payAnswer(kind: Cadence, day: Int?, lastPaid: LocalDate?, payMinor: Long?, today: LocalDate): PayAnswer? {
    val pay = payMinor?.takeIf { it > 0 } ?: return null
    return when (kind) {
        Cadence.MONTHLY -> {
            val d = day?.takeIf { it in 1..31 } ?: return null
            val thisMonth = today.withDayOfMonth(minOf(d, today.lengthOfMonth()))
            val last = if (thisMonth.isAfter(today)) today.minusMonths(1).let { it.withDayOfMonth(minOf(d, it.lengthOfMonth())) } else thisMonth
            PayAnswer(pay, kind, d, last)
        }
        Cadence.MONTHLY_LAST_BUSINESS_DAY -> {
            fun lastWorking(month: LocalDate): LocalDate {
                var d = month.with(TemporalAdjusters.lastDayOfMonth())
                while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
                return d
            }
            val thisMonth = lastWorking(today)
            PayAnswer(pay, kind, 31, if (thisMonth.isAfter(today)) lastWorking(today.minusMonths(1)) else thisMonth)
        }
        Cadence.WEEKLY, Cadence.FORTNIGHTLY -> {
            val last = lastPaid ?: return null
            PayAnswer(pay, kind, last.dayOfWeek.value, last)
        }
        else -> null
    }
}

@Composable
private fun Fresh(onStart: (String, String, Long, PayAnswer) -> Unit, onNext: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("Everyday Account") }
    var bank by rememberSaveable { mutableStateOf("") }
    var balance by rememberSaveable { mutableStateOf("") }
    Title("Start from today")
    Lead("Four quick answers. SpenDroid learns your bills as they come.")
    Panel {
        OutlinedTextField(name, { name = it }, label = { Text("Account name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bank, { bank = it }, label = { Text("Bank") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        OutlinedTextField(
            balance, { balance = it }, label = { Text("Balance right now") }, prefix = { Text("£") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        val balanceMinor = StatementFile.toMinor(balance)
        PayForm(button = "Next") { pay ->
            if (balanceMinor != null) {
                onStart(name.trim(), bank.trim(), balanceMinor, pay)
                onNext()
            }
        }
        if (balanceMinor == null) {
            Text("Add your balance to carry on.", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Notify(accessGranted: Boolean?, onGranted: () -> Unit, onNotNow: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var asked by remember { mutableStateOf(false) }
    // Coming back from Android's settings with access on: carry on.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if ((accessGranted ?: hasAccess(context)) && asked) onGranted()
        }
    }
    Title("Stay up to date between statements")
    Lead("When you pay, your banking app and Google Wallet show a notification. SpenDroid can read those to count spending the moment it happens.")
    Panel {
        Text("✓ Reads payment notifications from the apps you choose", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
        Text("✓ Stays on this phone: nothing is sent anywhere", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
        Text("✕ Never reads messages, emails or any other app", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
    }
    Spacer(Modifier.height(14.dp))
    if (accessGranted ?: hasAccess(context)) {
        Text("Notification access is on.", color = Charcoal.In, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Primary("Next", onClick = onGranted)
    } else {
        Primary("Allow notification access") {
            asked = true
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Text(
            "Greyed out? Android locks it for apps installed from outside the Play Store: open App info, tap ⋮, then Allow restricted settings, and try again.",
            style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("App info") }
        Spacer(Modifier.height(4.dp))
        BigButton("Not now", null, onNotNow, Modifier.fillMaxWidth())
        Text(
            "Without it, SpenDroid is up to date as of your last statement.",
            style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun Apps(
    state: RootUiState,
    onSaveSources: (List<NotificationSpend.Source>) -> Unit,
    onUpdateAccount: (AccountEntity) -> Unit,
    onNext: () -> Unit,
) {
    val sources = state.spendSources
    Title("Read payments from")
    Lead("Banking apps found on this phone, matched to your accounts by the bank's name. Change any of it later in Settings → Card alerts.")
    if (sources.isEmpty()) {
        Panel { Text("No banking app found for these accounts. Add one any time in Settings → Card alerts.", style = MaterialTheme.typography.bodySmall, color = Charcoal.Muted) }
    }
    sources.forEach { source ->
        Panel(Modifier.padding(bottom = 10.dp)) {
            Text(source.label, style = MaterialTheme.typography.titleSmall)
            Text(
                "→ " + source.accountIds.mapNotNull { id -> state.accounts.firstOrNull { it.id == id }?.label }.joinToString(" and "),
                style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted,
            )
            if (source.accountIds.size > 1) UnsureChoice(source, state.accounts) { changed -> onSaveSources(sources.map { if (it == source) changed else it }) }
        }
    }
    Panel(Modifier.padding(bottom = 10.dp)) {
        SectionHeading("Cards in Google Wallet")
        Text("Turn on each one you pay with from your phone.", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
        state.accounts.filter { it.manual && it.accountType != AccountType.SAVINGS }.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = a.walletLinked, onCheckedChange = { onUpdateAccount(a.copy(walletLinked = it)) })
            }
        }
    }
    Primary("Next", onClick = onNext)
}

@Composable
private fun Done(state: RootUiState, accounts: List<AccountEntity>, onFinish: () -> Unit) {
    val transactions = state.transactions.count { t -> accounts.any { it.id == t.accountId } }
    val bills = state.budget?.fixedRules.orEmpty().count { it.direction == Direction.OUT }
    Spacer(Modifier.height(48.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(76.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text("✓", color = Color(0xFF111111), fontSize = 40.sp, fontWeight = FontWeight.Black)
        }
    }
    Text("You're set up", fontFamily = Lato, fontWeight = FontWeight.Black, fontSize = 25.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
    Text(
        buildString {
            append("${accounts.size} account${if (accounts.size == 1) "" else "s"}")
            if (transactions > 0) append(", $transactions transactions")
            if (state.budget?.primaryIncomeRule != null) append(", your pay")
            if (bills > 0) append(" and $bills bills")
            append(".")
        },
        style = MaterialTheme.typography.bodyMedium, color = Charcoal.Muted, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
    )
    Panel {
        Text("Add it to your watch face or home screen?", style = MaterialTheme.typography.titleSmall)
        Text("Left to spend at a glance. Long-press your home screen and choose Widgets, or add the complication on your watch.", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
    }
    Spacer(Modifier.height(14.dp))
    Primary("See my budget", onClick = onFinish)
}
