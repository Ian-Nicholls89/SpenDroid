package com.spendroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.DuplicateCheck
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * "Same transaction?" - rows the bank has stopped listing, above the transaction list. With a
 * new row of the same amount beside it, the new one is not counted until the user answers;
 * with none, the old one stays counted until they say otherwise.
 */
@Composable
internal fun DuplicatePrompts(
    questions: List<DuplicateCheck.Question>,
    accounts: List<AccountEntity>,
    onSame: (DuplicateCheck.Question) -> Unit,
    onKeep: (DuplicateCheck.Question) -> Unit,
    onRemove: (DuplicateCheck.Question) -> Unit,
) {
    if (questions.isEmpty()) return
    val labels = accounts.associate { it.id to it.label }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        questions.forEach { q ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    val account = labels[q.vanished.accountId]
                    val candidate = q.candidate
                    if (candidate != null) {
                        Text("Same transaction?", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${account ?: "The bank"} no longer lists the first, and listed the second instead.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(line(q.vanished), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        Text(line(candidate), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "The second isn't counted until you say.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Row {
                            TextButton(onClick = { onSame(q) }) { Text("Yes, the same") }
                            TextButton(onClick = { onKeep(q) }) { Text("No, count both") }
                        }
                    } else {
                        Text(line(q.vanished), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${account ?: "Your bank"} no longer lists this. It may have been reversed or cancelled.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(onClick = { onKeep(q) }) { Text("Keep it") }
                            TextButton(onClick = { onRemove(q) }) { Text("Remove it") }
                        }
                    }
                }
            }
        }
    }
}

private fun line(tx: TransactionEntity): String {
    val day = runCatching { LocalDate.parse(tx.bookingDate).format(DAY) }.getOrDefault(tx.bookingDate)
    return "${formatMoney(tx.amountMinor, tx.currency)} · ${tx.payee.tidyPayee().ifBlank { "Unknown" }} · $day"
}
