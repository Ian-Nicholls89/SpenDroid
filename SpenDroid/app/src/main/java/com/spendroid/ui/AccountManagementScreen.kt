@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendroid.data.Connection
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.domain.CreditCardEngine
import com.spendroid.data.remote.InstitutionDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@Composable
fun AccountManagementScreen(
    state: RootUiState,
    onLink: (InstitutionDto) -> Unit,
    onRelink: (Connection) -> Unit,
    onUpdateAccount: (AccountEntity) -> Unit,
    balanceTypesFor: (AccountEntity) -> List<Pair<String, Boolean>> = { emptyList() },
    onSeeTransactions: (AccountEntity) -> Unit = {},
) {
    var showLinkDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountEntity?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.accounts, key = { it.id }) { account ->
            AccountWalletCard(
                account = account,
                connection = state.connections.find { it.accountIds.contains(account.id) },
                onClick = { editing = account },
                onRelink = onRelink,
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            Button(onClick = { showLinkDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add another bank")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { account ->
        AccountDetailSheet(
            account = account,
            allAccounts = state.accounts,
            balanceTypes = balanceTypesFor(account),
            bill = state.budget?.cardBills?.firstOrNull { it.cardAccountId == account.id },
            onSeeTransactions = {
                editing = null
                onSeeTransactions(account)
            },
            onDismiss = { editing = null },
            onSave = {
                onUpdateAccount(it)
                editing = null
            },
        )
    }

    if (showLinkDialog) {
        LinkBankDialog(
            state = state,
            onDismiss = { showLinkDialog = false },
            onLink = { institution ->
                showLinkDialog = false
                onLink(institution)
            },
        )
    }
}

/** Colour by what the account is, so a liability never looks like money in the bank. */
private fun AccountType.gradient(): List<Color> = when (this) {
    AccountType.CREDIT_CARD -> listOf(Color(0xFF8E2C2C), Color(0xFF5D1A1A))
    AccountType.JOINT -> listOf(Color(0xFF00695C), Color(0xFF004D40))
    AccountType.SAVINGS -> listOf(Color(0xFF1565C0), Color(0xFF0D47A1))
    AccountType.PAYPAL -> listOf(Color(0xFF1B3A6B), Color(0xFF0E2347))
    AccountType.OTHER -> listOf(Color(0xFF455A64), Color(0xFF263238))
    AccountType.PERSONAL -> listOf(Color(0xFF2E7D32), Color(0xFF1B5E20))
}

private fun AccountType.icon() = when (this) {
    AccountType.CREDIT_CARD -> Icons.Filled.CreditCard
    AccountType.JOINT -> Icons.Filled.Group
    AccountType.SAVINGS -> Icons.Filled.Savings
    AccountType.PAYPAL -> Icons.Filled.Payments
    else -> Icons.Filled.AccountBalance
}

@Composable
private fun AccountWalletCard(
    account: AccountEntity,
    connection: Connection?,
    onClick: () -> Unit,
    onRelink: (Connection) -> Unit,
) {
    val daysLeft = connection?.let { calculateDaysUntilExpiry(it) }
    val isCard = account.accountType == AccountType.CREDIT_CARD
    val balance = account.balanceMinor
    val amount = balance?.let { formatMoney(it, account.currency) } ?: "—"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                Brush.linearGradient(account.accountType.gradient()),
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${account.label}, ${account.accountType.shortName}, " +
                    if (isCard) "$amount owed" else amount
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                account.accountType.icon(),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${account.institutionName} · ${account.accountType.shortName}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            account.label,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1,
        )
        Text(
            amount,
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Row {
            Text(
                if (isCard) "owed · synced ${syncedAt(account.lastSynced)}"
                else "synced ${syncedAt(account.lastSynced)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
            )
            if (daysLeft != null && daysLeft > 14) {
                Text(
                    "access $daysLeft days",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
        if (connection != null && daysLeft != null && daysLeft <= 14) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (daysLeft <= 0) "Access has expired" else "Access ends in $daysLeft days",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onRelink(connection) }) {
                    Text("Reconnect", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Everything editable about an account, in the same bottom sheet pattern the transaction
 * list uses. Keeping it off the card is what stops the list overflowing: a 23-character type
 * name has room here and never had room in a chip beside a balance.
 */
@Composable
private fun AccountDetailSheet(
    account: AccountEntity,
    allAccounts: List<AccountEntity>,
    balanceTypes: List<Pair<String, Boolean>>,
    bill: CreditCardEngine.CardBill?,
    onSeeTransactions: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (AccountEntity) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var label by remember(account.id) { mutableStateOf(account.label) }
    var type by remember(account.id) { mutableStateOf(account.accountType) }
    var linkedId by remember(account.id) { mutableStateOf(account.linkedCreditCardAccountId) }
    var statementDay by remember(account.id) {
        mutableStateOf(account.statementDayOfMonth?.toString().orEmpty())
    }
    var paymentDay by remember(account.id) {
        mutableStateOf(account.paymentDayOfMonth?.toString().orEmpty())
    }

    val counterparts = when (type) {
        AccountType.CREDIT_CARD -> allAccounts.filter {
            it.accountType != AccountType.CREDIT_CARD && it.id != account.id
        }
        else -> allAccounts.filter { it.accountType == AccountType.CREDIT_CARD && it.id != account.id }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(account.institutionName, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSeeTransactions, contentPadding = PaddingValues(0.dp)) {
                Text("See this account's transactions")
            }
            Spacer(Modifier.height(6.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text("Type", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AccountType.entries.forEach { option ->
                    FilterChip(
                        selected = type == option,
                        onClick = {
                            type = option
                            linkedId = null
                        },
                        label = { Text(option.shortName) },
                    )
                }
            }
            Text(
                when (type) {
                    AccountType.CREDIT_CARD ->
                        "Spending on a card is left out of your budget; the bill that pays it is counted instead."
                    AccountType.PAYPAL ->
                        "PayPal payments are matched to the bank debit that funded them, so the " +
                            "merchant's name replaces the bank's reference and neither is counted twice."
                    else -> "Spending from this account counts towards your budget."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (counterparts.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (type == AccountType.CREDIT_CARD) "Paid from" else "Pays which card",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    counterparts.forEach { other ->
                        FilterChip(
                            selected = linkedId == other.id,
                            onClick = { linkedId = if (linkedId == other.id) null else other.id },
                            label = { Text(other.label, maxLines = 1) },
                        )
                    }
                }
                Text(
                    "Linking the two lets the bill be matched to the card it pays.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (type == AccountType.CREDIT_CARD) {
                Spacer(Modifier.height(16.dp))
                Text("Billing cycle", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    cycleExplanation(bill),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = statementDay,
                        onValueChange = { entered ->
                            statementDay = entered.filter { it.isDigit() }.take(2)
                        },
                        label = { Text("Statement closes") },
                        placeholder = { Text(dayHint(bill?.statementDay)) },
                        supportingText = { Text("Day of month") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = statementDay.isNotEmpty() && statementDay.toIntOrNull() !in 1..31,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = paymentDay,
                        onValueChange = { entered ->
                            paymentDay = entered.filter { it.isDigit() }.take(2)
                        },
                        label = { Text("Bill taken") },
                        placeholder = { Text(dayHint(bill?.nominalPaymentDay)) },
                        supportingText = { Text("Day of month") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = paymentDay.isNotEmpty() && paymentDay.toIntOrNull() !in 1..31,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Leave these empty to keep using what the app worked out.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (balanceTypes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Balances your bank sends", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Which one means \"what you owe\" differs by bank, so this shows the choice " +
                        "rather than hiding it. The one in use is marked.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                balanceTypes.forEach { (label, inUse) ->
                    Text(
                        if (inUse) "● $label" else "○ $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (inUse) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        onSave(
                            account.copy(
                                label = label.trim().ifBlank { account.label },
                                accountType = type,
                                linkedCreditCardAccountId = linkedId,
                                statementDayOfMonth = statementDay.toIntOrNull()
                                    ?.takeIf { it in 1..31 },
                                paymentDayOfMonth = paymentDay.toIntOrNull()
                                    ?.takeIf { it in 1..31 },
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}

private fun calculateDaysUntilExpiry(connection: Connection): Int {
    val expiryMs = connection.createdAt + 90L * 24 * 60 * 60 * 1000
    val days = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate(),
        Instant.ofEpochMilli(expiryMs).atZone(ZoneId.systemDefault()).toLocalDate(),
    )
    return days.toInt()
}

private fun syncedAt(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.getDefault()))

/**
 * Says where the cycle came from, because a solved cycle and a guessed one deserve very
 * different amounts of trust from the person reading it.
 */
private fun cycleExplanation(bill: CreditCardEngine.CardBill?): String = when {
    bill == null ->
        "Once a bill has been paid, the app works out when your statement closes."
    bill.cycleSource == CreditCardEngine.CycleSource.USER ->
        "Using the days you set below."
    bill.cycleSource == CreditCardEngine.CycleSource.INFERRED ->
        "Worked out from your past bills: the statement closes on the " +
            ordinal(bill.statementDay) + ". Set it below if that is wrong."
    else ->
        "Your past bills did not match any regular cycle, so this is an estimate. " +
            "Setting the real days makes the forecast exact."
}

private fun dayHint(day: Int?): String = day?.toString() ?: "--"

private fun ordinal(day: Int?): String {
    if (day == null) return "--"
    val suffix = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}
