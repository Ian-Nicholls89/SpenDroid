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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = CategoryEngine.classify(transaction, userRules)
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

            transaction.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "As your bank sent it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(description, style = MaterialTheme.typography.bodySmall)
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
        }
    }
}
