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
import com.spendroid.ui.theme.Charcoal
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
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
            QuestionCard("Which card?", Color(0xFF29B6F6)) {
                Text(
                    "${formatMoney(-spend.amountMinor, spend.currency)} at ${spend.merchant} · ${NotificationSpend.dateOf(spend.seenAt).format(DAY)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("Seen in a notification; nothing is counted until you say.", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    wallet.forEach { account ->
                        ColourPill(account.label, colourOf(account, accounts), { onAssign(spend.id, account.id) })
                    }
                    ColourPill("Ignore", Charcoal.PanelHigh, { onDismiss(spend.id) }, Color(0xFFAAB0BB))
                }
            }
        }
        unconfirmed.forEach { spend ->
            QuestionCard("Not reported yet", Charcoal.Warn) {
                Text(
                    "${formatMoney(-spend.amountMinor, spend.currency)} at ${spend.merchant} · seen ${NotificationSpend.dateOf(spend.seenAt).format(DAY)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Your bank hasn't reported this a week on. It may have been cancelled.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Charcoal.Muted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Keep counting it", null, { onKeep(spend.id) }, Modifier.weight(1f))
                    BigButton("Remove it", null, { onDismiss(spend.id) }, Modifier.weight(1f), colour = Charcoal.Warn, textColour = Color(0xFF111111))
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
