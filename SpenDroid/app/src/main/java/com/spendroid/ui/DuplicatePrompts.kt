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
import com.spendroid.ui.theme.Charcoal
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
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
        questions.forEachIndexed { i, q ->
            val account = labels[q.vanished.accountId]
            val candidate = q.candidate
            val counter = if (questions.size > 1) "${i + 1} of ${questions.size}" else null
            if (candidate != null) {
                QuestionCard("Same transaction?", Charcoal.Warn, trailing = counter) {
                    Text(
                        "${account ?: "The bank"} stopped listing the first, and listed the second.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Charcoal.Muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    QuestionRow(q.vanished, "No longer listed", struck = true)
                    Spacer(Modifier.height(6.dp))
                    QuestionRow(candidate, "New · not counted yet")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("Yes, the same", null, { onSame(q) }, Modifier.weight(1f), colour = Charcoal.Warn, textColour = Color(0xFF111111))
                        BigButton("No, count both", null, { onKeep(q) }, Modifier.weight(1f))
                    }
                }
            } else {
                QuestionCard("No longer listed", Charcoal.Warn, trailing = counter) {
                    QuestionRow(q.vanished, account ?: "Your bank")
                    Text(
                        "${account ?: "Your bank"} no longer lists this. It may have been reversed or cancelled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Charcoal.Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("Keep it", null, { onKeep(q) }, Modifier.weight(1f))
                        BigButton("Remove it", null, { onRemove(q) }, Modifier.weight(1f), colour = Charcoal.Warn, textColour = Color(0xFF111111))
                    }
                }
            }
        }
    }
}

/** One of the two rows being compared, as a small card of its own. */
@Composable
private fun QuestionRow(tx: TransactionEntity, note: String, struck: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Charcoal.PanelHigh).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtTile(payeeColour(tx.payee), size = 36.dp) { Monogram(tx.payee, 36.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tx.payee.tidyPayee().ifBlank { "Unknown" },
                style = MaterialTheme.typography.titleSmall,
                color = if (struck) Charcoal.Muted else Color.White,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
                maxLines = 1,
            )
            val day = runCatching { LocalDate.parse(tx.bookingDate).format(DAY) }.getOrDefault(tx.bookingDate)
            Text("$note · $day", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
        }
        Text(formatMoney(tx.amountMinor, tx.currency), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
    }
}

private fun line(tx: TransactionEntity): String {
    val day = runCatching { LocalDate.parse(tx.bookingDate).format(DAY) }.getOrDefault(tx.bookingDate)
    return "${formatMoney(tx.amountMinor, tx.currency)} · ${tx.payee.tidyPayee().ifBlank { "Unknown" }} · $day"
}
