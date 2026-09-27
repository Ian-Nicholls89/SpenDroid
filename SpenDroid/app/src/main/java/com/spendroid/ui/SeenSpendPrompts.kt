package com.spendroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.domain.NotificationSpend
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * The questions card alerts can raise, above the transaction list: which card a Wallet payment
 * was on when that cannot be told, and whether a payment the bank never reported really happened.
 * Nothing is counted for the first until answered; the second is counted until answered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeenSpendPrompts(
    seen: List<SeenSpendEntity>,
    accounts: List<AccountEntity>,
    onAssign: (String, String) -> Unit,
    onKeep: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    val now = System.currentTimeMillis()
    val unassigned = seen.filter { it.accountId == null && !it.dismissed && it.matchedTransactionId == null }
    val unconfirmed = NotificationSpend.unconfirmed(seen, now)
    if (unassigned.isEmpty() && unconfirmed.isEmpty()) return
    val wallet = accounts.filter { it.walletLinked }.ifEmpty { accounts }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        unassigned.forEach { spend ->
            PromptCard {
                Text(
                    "${formatMoney(-spend.amountMinor, spend.currency)} at ${spend.merchant} · ${NotificationSpend.dateOf(spend.seenAt).format(DAY)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Which card was this on?", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    wallet.forEach { account ->
                        AssistChip(onClick = { onAssign(spend.id, account.id) }, label = { Text(account.label, maxLines = 1) })
                    }
                    AssistChip(onClick = { onDismiss(spend.id) }, label = { Text("Ignore") })
                }
            }
        }
        unconfirmed.forEach { spend ->
            PromptCard {
                Text(
                    "${formatMoney(-spend.amountMinor, spend.currency)} at ${spend.merchant} · seen ${NotificationSpend.dateOf(spend.seenAt).format(DAY)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Your bank hasn't reported this a week on. It may have been cancelled.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    TextButton(onClick = { onKeep(spend.id) }) { Text("Keep counting it") }
                    TextButton(onClick = { onDismiss(spend.id) }) { Text("Remove it") }
                }
            }
        }
    }
}

@Composable
private fun PromptCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}
