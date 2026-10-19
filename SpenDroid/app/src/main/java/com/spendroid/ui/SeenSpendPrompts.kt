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
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The questions card alerts can raise, pinned above the transaction list and on Home: which
 * account a payment came from when the notification didn't say, and whether a payment the bank
 * never reported really happened. The first is counted on the likeliest account while it waits;
 * the second is counted until answered. [accountsOnly] leaves out the second, for Home.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeenSpendPrompts(
    seen: List<SeenSpendEntity>,
    accounts: List<AccountEntity>,
    onAssign: (String, String) -> Unit,
    onKeep: (String) -> Unit,
    onDismiss: (String) -> Unit,
    sources: List<NotificationSpend.Source> = emptyList(),
    accountsOnly: Boolean = false,
    /** Accounts with no bank link, and the last day a statement covers each. */
    coverage: Map<String, java.time.LocalDate?> = emptyMap(),
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    /** Penny's "once, or twice?": the payment asked about, its twin, and whether it was just once. */
    onRepeat: (spendId: String, twinId: String, once: Boolean) -> Unit = { _, _, _ -> },
) {
    val now = System.currentTimeMillis()
    val unassigned = NotificationSpend.unanswered(seen).sortedByDescending { it.seenAt }
    val unconfirmed = if (accountsOnly) emptyList() else NotificationSpend.unconfirmed(seen, now, coverage)
    // Asked on Home as well as Spending: it changes the budget either way.
    val repeats = NotificationSpend.possibleRepeats(seen)
    if (unassigned.isEmpty() && unconfirmed.isEmpty() && repeats.isEmpty()) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeats.forEach { repeat ->
            val spend = repeat.spend
            val times = listOf(repeat.twin.seenAt, spend.seenAt).sorted().joinToString(" and ") {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(TIME)
            }
            QuestionCard("Penny has a question", Charcoal.Warn) {
                Text(
                    "${formatMoney(-spend.amountMinor, spend.currency)} at ${spend.merchant}, twice · ${NotificationSpend.dateOf(spend.seenAt).format(DAY)}, $times",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "The same amount at the same place on one day. It could be two purchases, or one counted twice. Both are counted until you say.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Charcoal.Muted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Just once", null, { onRepeat(spend.id, repeat.twin.id, true) }, Modifier.weight(1f), colour = Charcoal.Warn, textColour = Color(0xFF111111))
                    BigButton("Both were real", null, { onRepeat(spend.id, repeat.twin.id, false) }, Modifier.weight(1f))
                }
            }
        }
        unassigned.forEach { spend ->
            // The accounts it could be: the ones its app covers, or the Wallet cards.
            val choices = NotificationSpend.candidatesFor(spend.source, sources, accounts)?.takeIf { it.isNotEmpty() }
                ?: accounts.filter { it.walletLinked }.ifEmpty { accounts }
            QuestionCard("Which account?", Charcoal.Warn) {
                Text(
                    "${formatMoney(-spend.amountMinor, spend.currency)} at ${spend.merchant} · ${NotificationSpend.dateOf(spend.seenAt).format(DAY)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (spend.provisionalAccountId != null) "Counted in your spending already. Just say which account it came from."
                    else "Seen in a notification; nothing is counted until you say.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Charcoal.Muted,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { account ->
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
