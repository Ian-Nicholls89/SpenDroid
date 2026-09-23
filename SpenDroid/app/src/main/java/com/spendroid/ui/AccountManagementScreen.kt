@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.data.Connection
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
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
) {
    var showLinkDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("Your linked accounts", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.accounts) { account ->
                AccountManagementCard(
                    account = account,
                    connection = state.connections.find { it.accountIds.contains(account.id) },
                    onRelink = { onRelink(it) },
                    onUpdateAccount = onUpdateAccount,
                    allAccounts = state.accounts,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { showLinkDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Add another bank")
        }
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

@Composable
private fun AccountManagementCard(
    account: AccountEntity,
    connection: Connection?,
    onRelink: (Connection) -> Unit,
    onUpdateAccount: (AccountEntity) -> Unit,
    allAccounts: List<AccountEntity>,
) {
    val daysLeft = connection?.let { calculateDaysUntilExpiry(it) }
    val isExpiringSoon = daysLeft != null && daysLeft <= 14

    var selectedType by remember { mutableStateOf(account.accountType) }
    var linkedCardId by remember { mutableStateOf(account.linkedCreditCardAccountId) }
    var label by remember { mutableStateOf(account.label) }
    var showEditLabel by remember { mutableStateOf(false) }

    val personalAccounts = allAccounts.filter { it.accountType == AccountType.PERSONAL && it.id != account.id }
    val creditCardAccounts = allAccounts.filter { it.accountType == AccountType.CREDIT_CARD }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isExpiringSoon) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpiringSoon) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header with label (editable) and type badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (showEditLabel) {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${account.institutionName} · ${account.currency} ${formatBalance(account.balanceMinor)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Type badge
                        Text(
                            selectedType.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                        )
                        if (isExpiringSoon) {
                            Text(
                                "⚠ $daysLeft days left",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                // Edit label button
                TextButton(onClick = { showEditLabel = !showEditLabel }) {
                    Text(if (showEditLabel) "✓" else "✎", fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            // Wraps rather than running off the edge. As a single Row, "Personal current
            // account" and "Joint account" consumed the whole width of a phone and every
            // type after them - Credit card included - was clipped out of reach.
            Text(
                "Type",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AccountType.entries.forEach { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(type.shortName) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // Linked accounts (credit card linking)
            if (selectedType == AccountType.PERSONAL && creditCardAccounts.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Linked cards:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        creditCardAccounts.forEach { cc ->
                            val isLinked = linkedCardId == cc.id
                            TextButton(
                                onClick = { linkedCardId = if (isLinked) null else cc.id },
                                modifier = Modifier.padding(horizontal = 2.dp),
                                colors = if (isLinked) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.textButtonColors(),
                            ) {
                                Text(cc.label, fontSize = 11.sp, fontWeight = if (isLinked) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            } else if (selectedType == AccountType.CREDIT_CARD && personalAccounts.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Paid from:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        personalAccounts.forEach { pa ->
                            val isLinked = linkedCardId == pa.id
                            TextButton(
                                onClick = { linkedCardId = if (isLinked) null else pa.id },
                                modifier = Modifier.padding(horizontal = 2.dp),
                                colors = if (isLinked) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.textButtonColors(),
                            ) {
                                Text(pa.label, fontSize = 11.sp, fontWeight = if (isLinked) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }

            // Reauthorisation warning
            if (daysLeft != null && daysLeft <= 30) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Reauth needed by ${formatExpiryDate(connection!!.createdAt + 90 * 24 * 60 * 60 * 1000L)} ($daysLeft days)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (daysLeft <= 7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (daysLeft <= 30) {
                        TextButton(
                            onClick = { onRelink(connection!!) },
                            colors = if (daysLeft <= 7) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer) else ButtonDefaults.textButtonColors(),
                        ) {
                            Text(if (daysLeft <= 7) "Reauthorise now" else "Reauthorise soon")
                        }
                    }
                }
            }
        }
    }

    // Save changes
    LaunchedEffect(selectedType, linkedCardId, label) {
        if (selectedType != account.accountType || linkedCardId != account.linkedCreditCardAccountId || label != account.label) {
            onUpdateAccount(account.copy(
                accountType = selectedType,
                linkedCreditCardAccountId = linkedCardId,
                label = label,
            ))
        }
    }
}

private fun calculateDaysUntilExpiry(connection: Connection): Int {
    val expiryMs = connection.createdAt + 90L * 24 * 60 * 60 * 1000
    val now = System.currentTimeMillis()
    val days = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate(),
        Instant.ofEpochMilli(expiryMs).atZone(ZoneId.systemDefault()).toLocalDate(),
    )
    return days.toInt()
}

private fun formatExpiryDate(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault())
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatter)
}

private fun formatBalance(balanceMinor: Long?): String {
    val abs = balanceMinor?.let { abs(it) } ?: 0L
    val pounds = abs / 100
    val pence = abs % 100
    val sign = if ((balanceMinor ?: 0L) < 0) "-" else ""
    return "$sign$pounds.${"%02d".format(pence)}"
}