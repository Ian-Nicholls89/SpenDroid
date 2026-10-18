package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendroid.data.ManualReminders
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.domain.ManualAccounts
import com.spendroid.domain.NotificationSpend
import com.spendroid.domain.countedOn
import com.spendroid.importer.StatementFile
import com.spendroid.ui.theme.Charcoal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val SHORT_DAY = DateTimeFormatter.ofPattern("d MMM")

/** Where a manual account's figures come from, in place of "synced at". */
internal fun manualStatus(account: AccountEntity, coveredUntil: LocalDate?): String {
    val statement = coveredUntil?.let { "Statement to ${it.format(SHORT_DAY)}" } ?: "No statement yet"
    val checked = ManualAccounts.checkedOn(account)?.let { " · checked ${it.format(SHORT_DAY)}" }.orEmpty()
    return if (account.balanceMinor == null) "$statement · add its balance" else statement + checked
}

/** Adding an account by hand: a name, the bank, what kind it is, and its balance if known. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManualAccountDialog(onAdd: (String, String, AccountType, Long?) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var bank by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.PERSONAL) }
    var balance by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an account by hand") },
        text = {
            Column {
                Text(
                    "No bank link: it's kept up to date from your statements and your banking app's notifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Charcoal.Muted,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf(AccountType.PERSONAL, AccountType.CREDIT_CARD, AccountType.SAVINGS, AccountType.JOINT).forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.shortName) })
                    }
                }
                OutlinedTextField(
                    balance, { balance = it },
                    label = { Text(if (type == AccountType.CREDIT_CARD) "Owed now (optional)" else "Balance now (optional)") },
                    prefix = { Text("£") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text(
                    "Or leave it: importing a statement with a balance column sets it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Charcoal.Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val typed = StatementFile.toMinor(balance)?.let { if (type == AccountType.CREDIT_CARD) -kotlin.math.abs(it) else it }
                onAdd(name.trim(), bank.trim(), type, typed)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The balance check: the user types what their banking app shows. A gap is money gone that
 * SpenDroid hasn't seen - counted as spending until a statement says what it was - unless a
 * payment guessed onto another account explains it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BalanceCheckSheet(
    account: AccountEntity,
    accounts: List<AccountEntity>,
    onPreview: suspend (Long) -> ManualAccounts.Check?,
    onApply: (Long, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ManualAccounts.Check?>(null) }
    val money = { m: Long -> formatMoney(m, account.currency) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Charcoal.Background) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Check ${account.label}'s balance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Open your banking app and type the balance it shows.",
                style = MaterialTheme.typography.bodySmall,
                color = Charcoal.Muted,
            )
            val r = result
            if (r == null) {
                OutlinedTextField(
                    typed, { typed = it },
                    label = { Text("Your bank says") },
                    prefix = { Text("£") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Spacer(Modifier.height(14.dp))
                BigButton(
                    "Check", null,
                    {
                        val minor = StatementFile.toMinor(typed) ?: return@BigButton
                        scope.launch { result = onPreview(minor) }
                    },
                    Modifier.fillMaxWidth(),
                    colour = MaterialTheme.colorScheme.primary,
                    textColour = Color(0xFF111111),
                )
            } else {
                Spacer(Modifier.height(12.dp))
                when {
                    r.gapMinor == 0L -> Text("Spot on: ${money(r.typedMinor)}, as expected.", style = MaterialTheme.typography.titleMedium, color = Charcoal.In)
                    r.gapMinor < 0L -> Text(
                        "${money(-r.gapMinor)} more than expected - a refund or money paid in that SpenDroid hasn't seen. The balance is updated.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text("${money(r.gapMinor)} less than expected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                }
                val misplaced = r.misplaced
                if (r.gapMinor > 0 && misplaced != null) {
                    val from = accounts.firstOrNull { it.id == misplaced.accountId ?: misplaced.provisionalAccountId }?.label ?: "another account"
                    Column(
                        Modifier.padding(top = 10.dp).fillMaxWidth()
                            .background(Charcoal.Panel, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            "${money(-misplaced.amountMinor)} of it matches ${misplaced.merchant} " +
                                "(${NotificationSpend.dateOf(misplaced.seenAt).format(SHORT_DAY)}), which was put on $from. " +
                                "Was it paid from ${account.label}?",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    BigButton("Yes, move it", null, { onApply(r.typedMinor, misplaced.id) }, Modifier.fillMaxWidth(), colour = MaterialTheme.colorScheme.primary, textColour = Color(0xFF111111))
                    Spacer(Modifier.height(8.dp))
                    BigButton("No, count the whole gap as spending", null, { onApply(r.typedMinor, null) }, Modifier.fillMaxWidth())
                } else {
                    if (r.gapMinor > 0) {
                        Text(
                            "Something SpenDroid hasn't seen yet, like a direct debit or cash. It's counted as spending until a statement shows what it was.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Charcoal.Muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    BigButton("Done", null, { onApply(r.typedMinor, null) }, Modifier.fillMaxWidth(), colour = MaterialTheme.colorScheme.primary, textColour = Color(0xFF111111))
                }
            }
        }
    }
}

/**
 * On Home, for accounts with no bank link: how current the figures are - the latest statement
 * and the payments seen since - turning amber, with the thing to do, when a check or a statement
 * is due.
 */
@Composable
internal fun ManualFreshness(state: RootUiState, onCheck: (String) -> Unit, onImport: (String) -> Unit) {
    val manual = state.accounts.filter { it.manual }
    if (manual.isEmpty()) return
    val context = LocalContext.current
    val settings = remember { ManualReminders(context) }
    val today = LocalDate.now()
    // The budget's own account first: it's the one whose figures matter most.
    val account = manual.firstOrNull { it.id == state.budget?.potAccountId } ?: manual.first()
    val covered = state.manualCoverage[account.id]
    val seen = NotificationSpend.counted(state.seenSpends).count { it.countedOn == account.id }
    val statementDue = ManualAccounts.statementDue(covered, settings.statementEveryDays, today)
    val checkDue = account.accountType != AccountType.CREDIT_CARD &&
        ManualAccounts.balanceDue(ManualAccounts.checkedOn(account), settings.balanceWhen, today, state.budget?.cycleStart)
    val due = statementDue || checkDue
    val text = buildString {
        append(covered?.let { "Up to date from your statement (${it.format(SHORT_DAY)})" } ?: "No statement yet for ${account.label}")
        if (seen > 0) append(" and $seen ${if (seen == 1) "payment" else "payments"} seen since")
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (due) Color(0xFF2C2618) else Color(0xFF1D2A22), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (due) "!" else "✓", color = if (due) Charcoal.Warn else Charcoal.In, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        when {
            checkDue -> Text("Check balance", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { onCheck(account.id) })
            statementDue -> Text("Import", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { onImport(account.id) })
        }
    }
}

/** How often to be reminded: a new statement, and a balance check. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManualRemindersPanel() {
    val context = LocalContext.current
    val settings = remember { ManualReminders(context) }
    var every by remember { mutableStateOf(settings.statementEveryDays) }
    var whenTo by remember { mutableStateOf(settings.balanceWhen) }
    Panel {
        SectionHeading("Accounts without a bank link")
        Text(
            "Notifications catch card spending. A statement now and then fills in direct debits and transfers, and a balance check catches anything else.",
            style = MaterialTheme.typography.bodySmall,
            color = Charcoal.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text("Remind me to import a statement", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(7 to "Every week", 14 to "Every fortnight", 30 to "Every month", 0 to "Never").forEach { (days, label) ->
                FilterChip(selected = every == days, onClick = { every = days; settings.statementEveryDays = days }, label = { Text(label) })
            }
        }
        Text("Ask me to check my balance", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ManualAccounts.BalanceWhen.SUNDAY to "Every Sunday", ManualAccounts.BalanceWhen.PAYDAY to "On payday", ManualAccounts.BalanceWhen.NEVER to "Never")
                .forEach { (w, label) -> FilterChip(selected = whenTo == w, onClick = { whenTo = w; settings.balanceWhen = w }, label = { Text(label) }) }
        }
    }
}
