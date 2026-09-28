package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine

/**
 * The correction surface for a single transaction.
 *
 * Categorisation, transfer pairing and recurring detection are all heuristics, and none of
 * them had a way for the user to disagree. This is that way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    transaction: TransactionEntity,
    userRules: List<com.spendroid.data.db.CategoryRuleEntity>,
    onDismiss: () -> Unit,
    onOverrideCategory: (TransactionEntity, Category?) -> Unit,
    onAlwaysCategorise: (TransactionEntity, Category) -> Unit,
    onMarkTransfer: (TransactionEntity, Boolean) -> Unit,
    onMarkCardPayment: (TransactionEntity, Boolean) -> Unit = { _, _ -> },
    cardPaymentKeys: Set<String> = emptySet(),
    creditCardAccountIds: Set<String> = emptySet(),
    /** How many stored transactions share this one's payee and amount, this one included. */
    similarCount: Int = 1,
    /** The app's best alternatives to the category shown, best first - what a swipe offers. */
    suggestions: List<Category> = emptyList(),
    /** When this transaction's account last synced, to say how current "pending" is. */
    accountLastSynced: Long? = null,
    groupMarkedAsTransfer: Boolean = false,
    onMarkTransferGroup: (TransactionEntity, Boolean) -> Unit = { _, _ -> },
    /** Whether a regular bill already covers this payment. */
    isRegularBill: Boolean = false,
    onTreatAsBill: ((TransactionEntity) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current =
        CategoryEngine.classify(transaction, userRules, cardPaymentKeys, creditCardAccountIds)
    val visual = current.visual

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(visual.color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        visual.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        transaction.payee.tidyPayee().ifBlank { "Unknown" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        transaction.bookingDate,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatMoney(transaction.amountMinor, transaction.currency),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    color = if (transaction.amountMinor >= 0) InColor else MaterialTheme.colorScheme.onSurface,
                )
            }

            // A payment seen in a notification has not come from the bank at all yet: say where it
            // was seen and when, not "as your bank reported it", which it was not.
            val seen = transaction.transactionId.startsWith(com.spendroid.domain.NotificationSpend.SEEN_PREFIX)
            if (seen) {
                val parts = transaction.description.orEmpty().removePrefix("Seen · ").split(" · ")
                val from = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "a"
                val at = parts.getOrNull(1)?.let { " at $it" }.orEmpty()
                Spacer(Modifier.height(10.dp))
                Text(
                    "Seen in a $from notification$at. Counted as pending until your bank reports it; " +
                        "then the bank's own record takes over.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (transaction.isPending) {
            // Pending is the bank's word at the last sync, which is worth saying: a payment the
            // bank's own app shows as landed can still be pending in what it reports here.
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pending, as your bank reported it at the last sync" +
                        (accountLastSynced?.takeIf { it > 0L }?.let { " (${syncedLabel(it)})" }.orEmpty()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            transaction.description?.takeIf { it.isNotBlank() && !seen }?.let { description ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "As your bank sent it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(description, style = MaterialTheme.typography.bodySmall)
            }

            // The same two a swipe offers, so the tap and the swipe agree about what is likely.
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Suggested", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { category ->
                        AssistChip(
                            onClick = { onOverrideCategory(transaction, category) },
                            label = { Text(category.label) },
                            leadingIcon = {
                                Icon(
                                    category.visual.icon,
                                    contentDescription = null,
                                    tint = category.visual.color,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Category", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Category.entries.forEach { category ->
                    FilterChip(
                        selected = category == current,
                        onClick = { onOverrideCategory(transaction, category) },
                        label = { Text(category.label) },
                        modifier = Modifier.semantics {
                            contentDescription =
                                if (category == current) "${category.label}, selected" else category.label
                        },
                    )
                }
            }

            if (transaction.payee.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                AssistChip(
                    onClick = { onAlwaysCategorise(transaction, current) },
                    label = { Text("Always ${current.label} for ${transaction.payee.tidyPayee().take(24)}") },
                )
                Text(
                    "Applies to every transaction whose name contains this one's, now and in future.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Transfers and card payments are the bank's rows to decide about; a seen payment is
            // only a stand-in until one arrives, and its category carries over to it.
            if (!seen) {
            Spacer(Modifier.height(18.dp))
            Text("Mark as", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            FilterChip(
                selected = transaction.isInternalTransfer,
                onClick = { onMarkTransfer(transaction, !transaction.isInternalTransfer) },
                label = { Text("Money moved between my accounts") },
            )
            Text(
                "Transfers are left out of spending totals.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // A regular move between accounts should be said once, not every month: marking one
            // occurrence left the rest - and next month's - counted as a commitment.
            if (similarCount > 1 || groupMarkedAsTransfer) {
                Spacer(Modifier.height(8.dp))
                FilterChip(
                    selected = groupMarkedAsTransfer,
                    onClick = { onMarkTransferGroup(transaction, !groupMarkedAsTransfer) },
                    label = { Text("Every payment like this ($similarCount so far)") },
                )
                Text(
                    "Marks every ${formatMoney(kotlin.math.abs(transaction.amountMinor), transaction.currency)} " +
                        "${if (transaction.amountMinor < 0) "to" else "from"} " +
                        "${transaction.payee.tidyPayee().take(24)} as money moved between your accounts, " +
                        "including ones that arrive later.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // A payment out can be made a regular bill: set aside from the start of each cycle,
            // rather than counted as spending - or not at all, as a transfer - the day it leaves.
            if (onTreatAsBill != null && transaction.amountMinor < 0 && !transaction.transactionId.startsWith("seen:")) {
                Spacer(Modifier.height(12.dp))
                if (isRegularBill) {
                    Text(
                        "A regular bill: set aside from the start of each cycle. Change it under Rules.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    androidx.compose.material3.OutlinedButton(onClick = { onTreatAsBill(transaction) }) {
                        Text("Treat as a regular bill")
                    }
                    Text(
                        "Takes ${formatMoney(kotlin.math.abs(transaction.amountMinor), transaction.currency)} off " +
                            "your budget on the ${ordinal(transaction.bookingDate)} of every month, from the start of " +
                            "each cycle, instead of counting it when it leaves.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Only a credit on a card raises the question, and only when its other leg was
            // never found - a payment from an account the app can see is matched already.
            if (transaction.accountId in creditCardAccountIds && transaction.amountMinor > 0) {
                Spacer(Modifier.height(8.dp))
                FilterChip(
                    selected = transaction.isCardPayment,
                    onClick = { onMarkCardPayment(transaction, !transaction.isCardPayment) },
                    label = { Text("This pays my card bill") },
                )
                Text(
                    "Money coming onto a card is either a bill payment or a refund, and " +
                        "they look identical. Saying which lets the statement cycle and the " +
                        "bill forecast be worked out when the paying account is not linked. " +
                        "Applies to every payment on this card named the same way, so this " +
                        "only needs saying once.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }
        }
    }
}

private fun syncedLabel(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))

/** "28th" for a booking date of the 28th. */
private fun ordinal(bookingDate: String): String {
    val day = runCatching { java.time.LocalDate.parse(bookingDate).dayOfMonth }.getOrElse { return "same day" }
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}
