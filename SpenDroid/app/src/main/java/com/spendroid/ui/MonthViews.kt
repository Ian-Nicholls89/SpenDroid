package com.spendroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spendroid.domain.MonthView
import com.spendroid.ui.theme.Charcoal
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("d MMM")

/**
 * Next month, looked at now (0.8.0): the card statements that fall in the next pay period, by
 * their own dates, against what it can pay them from - its own money, and what this month leaves
 * in the account. Statements don't keep to paydays, so a month can look fine while the next
 * carries spending from before this payday; this is where that shows, while there's time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NextMonthSheet(month: MonthView, currency: String, onDismiss: () -> Unit) {
    val money = { minor: Long -> formatMoney(minor, currency) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val short = month.nextShortMinor
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Charcoal.Panel) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Next month", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (short > 0L) "${money(short)} short" else "Covered",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (short > 0L) Charcoal.Warn else Charcoal.Good,
            )
            Line("Next month's statements so far", money(month.nextStatementsMinor), bold = true)
            month.nextStatements.forEach { bill ->
                Line("${bill.label}${bill.due?.let { " · due ${it.format(DAY)}" }.orEmpty()}", money(bill.amountMinor), indent = true)
            }
            Line("Still to spend this month", money(month.leftMinor.coerceAtLeast(0L)), bold = true)
            Line("Next month's money, before statements", money(month.nextBudgetMinor), bold = true)
            Line("Your account now, after this month's", money(month.accountLeftMinor), bold = true)
            Text(
                if (short > 0L) {
                    "However the rest of this month is paid - from your account, or on cards - next month carries it. " +
                        "Statements don't keep to paydays, so some of next month's can be spending from before this payday. " +
                        "Spend ${money(short)} less this month and next month is covered."
                } else {
                    "However the rest of this month is paid - from your account, or on cards - next month's statements fit within its money and what your account holds now."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Charcoal.Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Line(label: String, amount: String, bold: Boolean = false, indent: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 12.dp else 0.dp)) {
        Text(
            label,
            style = if (indent) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            color = if (indent) Charcoal.Muted else Color(0xFFCFD2D8),
            modifier = Modifier.weight(1f),
        )
        Text(
            amount,
            style = if (indent) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (indent) Charcoal.Muted else Color.White,
        )
    }
}
