package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.ui.formatMoney
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, Long, String, String, Int, String) -> Unit,
    onAddFromCandidate: (RecurringAnalyzer.RecurringCandidate) -> Unit,
    viewModel: RootViewModel,
) {
    var payee by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("OUT") }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("GBP") }
    var cadence by remember { mutableStateOf("MONTHLY") }
    var anchorDay by remember { mutableStateOf(LocalDate.now().dayOfMonth) }
    var startDate by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ISO_DATE)) }
    var candidates by remember { mutableStateOf<List<RecurringAnalyzer.RecurringCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showManual by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        candidates = viewModel.getRecurringCandidates()
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add recurring rule") },
        text = {
            Column(modifier = Modifier.padding(8.dp).widthIn(min = 400.dp)) {
                if (!loading) {
                    if (candidates.isNotEmpty()) {
                        Text(
                            "Found ${candidates.size} potential recurring payment${if (candidates.size > 1) "s" else ""}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                        ) {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(candidates, key = { c ->
                                    c.sampleTransactions.firstOrNull()?.transactionId
                                        ?: "${c.payee}-${c.amountMinor}-${System.identityHashCode(c)}"
                                }) { candidate ->
                                    CandidateRow(
                                        candidate = candidate,
                                        onClick = { onAddFromCandidate(candidate); onDismiss() },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Don't see yours?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                            TextButton(onClick = { showManual = true }) {
                                Text("Enter manually")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Text(
                            "No recurring patterns found. Enter manually:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        showManual = true
                    }
                } else {
                    Text("Scanning transactions…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (showManual) {
                    ManualEntryForm(
                        payee = payee,
                        onPayeeChange = { payee = it },
                        direction = direction,
                        onDirectionChange = { direction = it },
                        amount = amount,
                        onAmountChange = { amount = it },
                        currency = currency,
                        onCurrencyChange = { currency = it },
                        cadence = cadence,
                        onCadenceChange = { cadence = it },
                        anchorDay = anchorDay,
                        onAnchorDayChange = { anchorDay = it },
                        startDate = startDate,
                        onStartDateChange = { startDate = it },
                    )
                }
            }
        },
        confirmButton = {
            if (showManual) {
                Button(onClick = {
                    val amountMinor = ((amount.toDoubleOrNull() ?: 0.0) * 100).roundToLong()
                    onAdd(payee.trim(), direction, amountMinor, currency, cadence, anchorDay, startDate)
                }, enabled = payee.isNotBlank() && amount.isNotBlank()) {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun CandidateRow(
    candidate: RecurringAnalyzer.RecurringCandidate,
    onClick: () -> Unit,
) {
    val directionColor = if (candidate.direction == com.spendroid.domain.Direction.IN)
        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val amountText = formatMoney(candidate.amountMinor, candidate.currency)
    val cadenceText = describeCadence(candidate.cadence, candidate.anchorDay)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(72.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    candidate.payee,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$amountText · $cadenceText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${candidate.occurrenceCount} occurrence${if (candidate.occurrenceCount > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                "Add",
                style = MaterialTheme.typography.labelMedium,
                color = directionColor,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ManualEntryForm(
    payee: String,
    onPayeeChange: (String) -> Unit,
    direction: String,
    onDirectionChange: (String) -> Unit,
    amount: String,
    onAmountChange: (String) -> Unit,
    currency: String,
    onCurrencyChange: (String) -> Unit,
    cadence: String,
    onCadenceChange: (String) -> Unit,
    anchorDay: Int,
    onAnchorDayChange: (Int) -> Unit,
    startDate: String,
    onStartDateChange: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(8.dp)) {
        OutlinedTextField(
            value = payee,
            onValueChange = onPayeeChange,
            label = { Text("Payee (e.g. Netflix, Rent, Salary)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Direction:",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedTextField(
                value = direction,
                onValueChange = onDirectionChange,
                label = { Text("IN or OUT") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = amount,
            onValueChange = onAmountChange,
            label = { Text("Amount (e.g. 12.99)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = currency,
            onValueChange = onCurrencyChange,
            label = { Text("Currency (e.g. GBP)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Cadence:",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedTextField(
                value = cadence,
                onValueChange = onCadenceChange,
                label = { Text("WEEKLY, FORTNIGHTLY, MONTHLY, QUARTERLY, ANNUAL") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Anchor day (1-31):",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedTextField(
                value = anchorDay.toString(),
                onValueChange = { onAnchorDayChange(it.toIntOrNull() ?: anchorDay) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = startDate,
            onValueChange = onStartDateChange,
            label = { Text("Start date (YYYY-MM-DD)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun describeCadence(cadence: com.spendroid.domain.Cadence, anchorDay: Int): String = when (cadence) {
    com.spendroid.domain.Cadence.WEEKLY -> "Weekly · ${dayName(anchorDay)}"
    com.spendroid.domain.Cadence.FORTNIGHTLY -> "Every 2 weeks"
    com.spendroid.domain.Cadence.MONTHLY -> "Monthly · day $anchorDay"
    com.spendroid.domain.Cadence.MONTHLY_LAST_DAY -> "Monthly · last day"
    com.spendroid.domain.Cadence.MONTHLY_LAST_BUSINESS_DAY -> "Monthly · last working day"
    com.spendroid.domain.Cadence.QUARTERLY -> "Quarterly"
    com.spendroid.domain.Cadence.ANNUAL -> "Yearly"
    else -> cadence.name
}

private fun dayName(value: Int): String = try {
    java.time.DayOfWeek.of(value).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
} catch (e: Exception) {
    ""
}