package com.spendroid

import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.spendroid.ui.LinkBankDialog
import com.spendroid.ui.ManualRuleDialog
import com.spendroid.ui.RootViewModel

/** The dialogs over the app: a balance check, a bank just linked, the banks to link, a bill to add. */
@Composable
internal fun AppDialogs(
    state: com.spendroid.ui.RootUiState,
    viewModel: RootViewModel,
    checkBalance: MutableState<String?>,
    linkDialog: MutableState<Boolean>,
    manualDialog: MutableState<Boolean>,
) {
    var showLinkDialog by linkDialog
    var showManualDialog by manualDialog
    // A manual account's balance check, from Home, Accounts or a reminder.
    checkBalance.value?.let { id -> state.accounts.firstOrNull { it.id == id } }?.let { account ->
        com.spendroid.ui.BalanceCheckSheet(
            account = account,
            accounts = state.accounts,
            onPreview = { typed -> viewModel.previewBalanceCheck(account.id, typed) },
            onApply = { typed, move ->
                viewModel.applyBalanceCheck(account.id, typed, move)
                checkBalance.value = null
            },
            onDismiss = { checkBalance.value = null },
        )
    }

    state.justLinked?.let { linkedName ->
        AlertDialog(
            onDismissRequest = viewModel::dismissLinkPrompt,
            title = { Text("$linkedName linked") },
            text = {
                Text(
                    "Its accounts are syncing now. Would you like to link another " +
                        "bank while you are here?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissLinkPrompt()
                    showLinkDialog = true
                }) { Text("Link another") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissLinkPrompt) { Text("Done") }
            },
        )
    }

    if (showLinkDialog) {
        LinkBankDialog(
            state = state,
            onDismiss = { showLinkDialog = false },
            onLink = { institution ->
                showLinkDialog = false
                viewModel.link(institution)
            },
        )
    }

    if (showManualDialog) {
        ManualRuleDialog(
            onDismiss = { showManualDialog = false },
            onAdd = { payee, direction, amountMinor, currency, cadence, anchorDay, startDate, accountId ->
                showManualDialog = false
                viewModel.addManualRule(payee, direction, amountMinor, currency, cadence, anchorDay, startDate, accountId)
            },
            onAddFromCandidate = { candidate ->
                showManualDialog = false
                viewModel.addManualRuleFromCandidate(candidate)
            },
            viewModel = viewModel,
        )
    }
}
