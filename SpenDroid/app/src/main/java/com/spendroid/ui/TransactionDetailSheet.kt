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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.NotificationSpend
import com.spendroid.ui.theme.Charcoal
import com.spendroid.ui.theme.Lato
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** How a payee is going this pay cycle, for the bar on a transaction's page. */
data class PayeeCycle(val spentMinor: Long, val visits: Int, val usualMinor: Long?)

private val LONG_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * A transaction's page, as nzb360 shows a film: its category's tile as the poster on a wash of
 * the category's colour, chips beneath, three big actions, how this payee is going this cycle,
 * and what the bank sent. Everything the user can decide about it is here.
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
    accountLabel: String? = null,
    payeeCycle: PayeeCycle? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = CategoryEngine.classify(transaction, userRules, cardPaymentKeys, creditCardAccountIds)
    val visual = current.visual
    val seen = transaction.transactionId.startsWith(NotificationSpend.SEEN_PREFIX)
    val name = transaction.payee.tidyPayee().ifBlank { "Unknown" }
    var choosing by rememberSaveable(transaction.transactionId) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Charcoal.Background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            // The hero: the category's colour washing down into the charcoal, the tile as a poster.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(visual.color.copy(alpha = 0.75f), Charcoal.Background)))
                    .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    ArtTile(visual.color, size = 84.dp, height = 112.dp) {
                        Icon(visual.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(name, fontFamily = Lato, fontWeight = FontWeight.Black, fontSize = 23.sp, maxLines = 2)
                        val day = runCatching { LocalDate.parse(transaction.bookingDate).format(LONG_DAY) }.getOrDefault(transaction.bookingDate)
                        Text(
                            listOfNotNull(day, accountLabel).joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatMoney(transaction.amountMinor, transaction.currency),
                            fontFamily = Lato,
                            fontWeight = FontWeight.Black,
                            fontSize = 28.sp,
                            color = if (transaction.amountMinor >= 0) InColor else Color.White,
                        )
                    }
                }
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(current.label, visual.color, Color.White)
                Chip(
                    when {
                        seen -> "Seen, not yet listed"
                        transaction.isPending -> "Pending"
                        else -> "Booked"
                    },
                    Charcoal.PanelHigh,
                    Color(0xFFCFD2D8),
                )
                if (transaction.isInternalTransfer) Chip("Transfer", Charcoal.PanelHigh, Color(0xFFCFD2D8))
                if (isRegularBill) Chip("Regular bill", Charcoal.PanelHigh, Color(0xFFCFD2D8))
                payeeCycle?.takeIf { it.visits > 1 }?.let { Chip("${ordinalOf(it.visits)} this cycle", Charcoal.PanelHigh, Color(0xFFCFD2D8)) }
            }

            Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Category", Icons.Filled.Category, { choosing = !choosing }, Modifier.weight(1f))
                if (!seen) {
                    BigButton(
                        if (transaction.isInternalTransfer) "Not a transfer" else "Transfer",
                        Icons.Filled.SwapHoriz,
                        { onMarkTransfer(transaction, !transaction.isInternalTransfer) },
                        Modifier.weight(1f),
                    )
                }
                if (onTreatAsBill != null && transaction.amountMinor < 0 && !seen && !isRegularBill) {
                    BigButton("Bill", Icons.Filled.Repeat, { onTreatAsBill(transaction) }, Modifier.weight(1f))
                }
            }

            // The category: the two a swipe offers first, then every one, then the rule for the payee.
            if (choosing || suggestions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Panel(Modifier.padding(horizontal = 14.dp)) {
                    SectionHeading("Category")
                    if (suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEach { category ->
                                AssistChip(
                                    onClick = { onOverrideCategory(transaction, category) },
                                    label = { Text(category.label) },
                                    leadingIcon = { Icon(category.visual.icon, contentDescription = null, tint = category.visual.color, modifier = Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                    if (choosing) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Category.entries.forEach { category ->
                                FilterChip(
                                    selected = category == current,
                                    onClick = { onOverrideCategory(transaction, category) },
                                    label = { Text(category.label) },
                                    modifier = Modifier.semantics {
                                        contentDescription = if (category == current) "${category.label}, selected" else category.label
                                    },
                                )
                            }
                        }
                    }
                    if (transaction.payee.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Always file ${name.take(24)} as", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            androidx.compose.material3.TextButton(onClick = { onAlwaysCategorise(transaction, current) }) {
                                Text("${current.label} ›", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            "Applies to every transaction whose name contains this one's, now and in future.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Charcoal.Muted,
                        )
                    }
                }
            }

            payeeCycle?.let { p ->
                Spacer(Modifier.height(12.dp))
                Panel(Modifier.padding(horizontal = 14.dp)) {
                    SectionHeading("Here this cycle")
                    Spacer(Modifier.height(10.dp))
                    val label = "${formatMoney(p.spentMinor, transaction.currency)} · ${p.visits} ${if (p.visits == 1) "visit" else "visits"}"
                    LabelledBar(
                        fraction = p.usualMinor?.takeIf { it > 0L }?.let { p.spentMinor.toFloat() / it } ?: 1f,
                        label = label,
                        colour = visual.color,
                    )
                    p.usualMinor?.let { u ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Usually about ${formatMoney(u, transaction.currency)} a cycle" +
                                if (p.visits > 0) " · ${formatMoney(p.spentMinor / p.visits, transaction.currency)} a visit" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = Charcoal.Muted,
                        )
                    }
                }
            }

            val hasDetails = seen || transaction.isPending || !transaction.description.isNullOrBlank()
            if (hasDetails) {
            Spacer(Modifier.height(12.dp))
            Panel(Modifier.padding(horizontal = 14.dp)) {
                SectionHeading("Details")
                Spacer(Modifier.height(6.dp))
                if (seen) {
                    // Seen in a notification: not from the bank at all yet, so say where it was seen.
                    val parts = transaction.description.orEmpty().removePrefix("Seen · ").split(" · ")
                    val from = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "a"
                    val at = parts.getOrNull(1)?.let { ", $it" }.orEmpty()
                    Detail("Seen first", "$from notification$at")
                    Text(
                        "Counted as pending until your bank reports it; then the bank's own record takes over.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Charcoal.Muted,
                    )
                } else {
                    transaction.description?.takeIf { it.isNotBlank() }?.let { Detail("As the bank sent it", it) }
                    // Pending is the bank's word at the last sync: its own app may already show it landed.
                    if (transaction.isPending) {
                        Detail("Pending", "as the bank said at the last sync" + (accountLastSynced?.takeIf { it > 0L }?.let { " (${syncedLabel(it)})" }.orEmpty()))
                    }
                }
            }
            }

            // Transfers, bills and card payments are the bank's rows to decide about; a seen payment
            // is only a stand-in until one arrives, and its category carries over to it.
            if (!seen) {
                Spacer(Modifier.height(12.dp))
                Panel(Modifier.padding(horizontal = 14.dp)) {
                    SectionHeading("Mark as")
                    Spacer(Modifier.height(8.dp))
                    FilterChip(
                        selected = transaction.isInternalTransfer,
                        onClick = { onMarkTransfer(transaction, !transaction.isInternalTransfer) },
                        label = { Text("Money moved between my accounts") },
                    )
                    Text("Transfers are left out of spending totals.", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
                    // A regular move between accounts should be said once, not every month.
                    if (similarCount > 1 || groupMarkedAsTransfer) {
                        Spacer(Modifier.height(8.dp))
                        FilterChip(
                            selected = groupMarkedAsTransfer,
                            onClick = { onMarkTransferGroup(transaction, !groupMarkedAsTransfer) },
                            label = { Text("Every payment like this ($similarCount so far)") },
                        )
                        Text(
                            "Marks every ${formatMoney(kotlin.math.abs(transaction.amountMinor), transaction.currency)} " +
                                "${if (transaction.amountMinor < 0) "to" else "from"} ${name.take(24)} as money moved between your " +
                                "accounts, including ones that arrive later.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Charcoal.Muted,
                        )
                    }
                    if (onTreatAsBill != null && transaction.amountMinor < 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (isRegularBill) {
                                "A regular bill: set aside from the start of each cycle. Change it under Regular."
                            } else {
                                "Bill takes ${formatMoney(kotlin.math.abs(transaction.amountMinor), transaction.currency)} off your budget " +
                                    "on the ${ordinal(transaction.bookingDate)} of every month, from the start of each cycle, instead of " +
                                    "counting it when it leaves."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Charcoal.Muted,
                        )
                    }
                    // Only a credit on a card raises the question, and only when its other leg was never found.
                    if (transaction.accountId in creditCardAccountIds && transaction.amountMinor > 0) {
                        Spacer(Modifier.height(8.dp))
                        FilterChip(
                            selected = transaction.isCardPayment,
                            onClick = { onMarkCardPayment(transaction, !transaction.isCardPayment) },
                            label = { Text("This pays my card bill") },
                        )
                        Text(
                            "Money coming onto a card is either a bill payment or a refund, and they look identical. Saying which " +
                                "lets the statement cycle and the bill forecast be worked out when the paying account is not linked. " +
                                "Applies to every payment on this card named the same way, so this only needs saying once.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Charcoal.Muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, color = Charcoal.Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

private fun ordinalOf(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

private fun syncedLabel(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))

/** "28th" for a booking date of the 28th. */
private fun ordinal(bookingDate: String): String {
    val day = runCatching { java.time.LocalDate.parse(bookingDate).dayOfMonth }.getOrElse { return "same day" }
    return ordinalOf(day)
}
