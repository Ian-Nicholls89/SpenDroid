package com.spendroid.ui

import androidx.lifecycle.viewModelScope
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [RootViewModel]'s setup, and accounts with no bank link: setup finished, starting from today
 * or from statements, pay added, manual accounts added and removed, and their balances checked.
 */
internal class SetupArea(host: AreaHost) : ViewModelArea(host) {

    /** Setup is finished: from now on the app opens on Home. */
    fun finishSetup() {
        setupPrefs().edit().putBoolean("done", true).apply()
        _state.update { it.copy(setupDone = true) }
        viewModelScope.launch { loadLocal() }
    }

    /**
     * "Start from today": an account with its balance now, and the pay that sets the cycle -
     * [lastPaid] the day it last came, so the next is worked out from it.
     */
    fun startFromToday(
        name: String,
        bank: String,
        balanceMinor: Long,
        payMinor: Long,
        cadence: com.spendroid.domain.Cadence,
        anchorDay: Int,
        lastPaid: java.time.LocalDate,
    ) {
        viewModelScope.launch {
            val account = repo.addManualAccount(name, bank, com.spendroid.data.db.AccountType.PERSONAL, balanceMinor)
            linkBankApp(account, bank)
            val id = "manual-${System.currentTimeMillis()}"
            repo.addManualRule(
                ManualRecurringRuleEntity(
                    id = id, payee = "Pay", direction = "IN", amountMinor = payMinor, currency = "GBP",
                    cadence = cadence.name, anchorDay = anchorDay, startDate = lastPaid.toString(), accountId = account.id,
                ),
            )
            repo.savePrimaryIncomeKey(com.spendroid.domain.MANUAL_KEY_PREFIX + id)
            loadLocal()
        }
    }

    /** The pay that sets the cycle, when the statements didn't show it. */
    fun addPay(payMinor: Long, cadence: com.spendroid.domain.Cadence, anchorDay: Int, lastPaid: java.time.LocalDate, accountId: String?) {
        viewModelScope.launch {
            val id = "manual-${System.currentTimeMillis()}"
            repo.addManualRule(
                ManualRecurringRuleEntity(
                    id = id, payee = "Pay", direction = "IN", amountMinor = payMinor, currency = "GBP",
                    cadence = cadence.name, anchorDay = anchorDay, startDate = lastPaid.toString(), accountId = accountId,
                ),
            )
            repo.savePrimaryIncomeKey(com.spendroid.domain.MANUAL_KEY_PREFIX + id)
            loadLocal()
        }
    }

    /**
     * Adds an account by hand. If its bank's app is on the phone, that app's notifications are
     * read for it - added to Card alerts, or the account added to the app already there.
     */
    fun addManualAccount(label: String, bank: String, type: com.spendroid.data.db.AccountType, balanceMinor: Long?) {
        viewModelScope.launch {
            val account = repo.addManualAccount(label, bank, type, balanceMinor)
            linkBankApp(account, bank)
            loadLocal()
        }
    }

    /** If [bank]'s app is on the phone, its notifications are read for [account]. */
    private suspend fun linkBankApp(account: AccountEntity, bank: String) {
        val name = bank.trim().lowercase()
        if (name.length < 3) return
        val app = withContext(Dispatchers.IO) { runCatching { launcherApps(getApplication()) }.getOrDefault(emptyList()) }
            .firstOrNull { val app = it.label.lowercase(); app.contains(name) || (app.length >= 5 && name.contains(app)) }
            ?: return
        val sources = repo.spendSources.first()
        val existing = sources.firstOrNull { it.packageName == app.packageName }
        repo.saveSpendSources(
            if (existing != null) sources.map { if (it == existing) it.copy(accountIds = it.accountIds + account.id) else it }
            else sources + com.spendroid.domain.NotificationSpend.Source(app.packageName, app.label, listOf(account.id), account.id),
        )
    }

    fun removeManualAccount(accountId: String) {
        viewModelScope.launch {
            repo.deleteManualAccount(accountId)
            loadLocal()
        }
    }

    fun setManualBalance(accountId: String, balanceMinor: Long) {
        viewModelScope.launch {
            repo.setManualBalance(accountId, balanceMinor)
            loadLocal()
        }
    }

    fun applyBalanceCheck(accountId: String, typedMinor: Long, moveSpendId: String?) {
        viewModelScope.launch {
            repo.applyBalanceCheck(accountId, typedMinor, moveSpendId)
            loadLocal()
        }
    }
}
