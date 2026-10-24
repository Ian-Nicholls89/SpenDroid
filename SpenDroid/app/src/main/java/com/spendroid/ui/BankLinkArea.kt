package com.spendroid.ui

import android.app.Application
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.spendroid.data.Connection
import com.spendroid.data.remote.InstitutionDto
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [RootViewModel]'s bank linking: the banks GoCardless offers, a link made or renewed through the bank's own page, and its progress on screen.
 */
internal class BankLinkArea(host: AreaHost) : ViewModelArea(host) {

    private var cachedInstitutions: List<InstitutionDto>? = null

    fun loadInstitutions(query: String) {
        viewModelScope.launch {
            val all = runCatching {
                cachedInstitutions?.takeIf { it.isNotEmpty() }
                    ?: repo.institutions("GB").also { cachedInstitutions = it }
            }.getOrElse { e ->
                _state.update { it.copy(error = e.message) }
                return@launch
            }
            val q = query.trim().lowercase()
            val filtered =
                if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) }
            _state.update { it.copy(institutions = filtered) }
        }
    }

    fun link(institution: InstitutionDto) {
        viewModelScope.launch {
            doLink(institution)
        }
    }

    fun relink(connection: Connection) {
        viewModelScope.launch {
            val institution = _state.value.institutions.firstOrNull { it.id == connection.institutionId }
                ?: runCatching { repo.institutions("GB") }
                    .getOrNull()
                    ?.firstOrNull { it.id == connection.institutionId }
            if (institution == null) {
                _state.update { it.copy(error = "${connection.institutionName} is no longer available.") }
                return@launch
            }
            doLink(institution, replacing = connection)
        }
    }

    /** [replacing] is the consent being renewed, when this is a reauthorisation. */
    private suspend fun doLink(institution: InstitutionDto, replacing: Connection? = null) {
        _state.update { it.copy(linkingBank = institution.name, error = null) }
        runCatching {
            _state.update { it.copy(linkProgress = "Contacting GoCardless…") }
            val req = repo.createRequisition(institution)
            _state.update { it.copy(linkProgress = "Opening ${institution.name}…") }
            val link = req.link ?: error("GoCardless did not return a link")
            val appContext = getApplication<Application>().applicationContext
            val customTabs = CustomTabsIntent.Builder().build()
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabs.launchUrl(appContext, link.toUri())
            _state.update { it.copy(linkProgress = "Waiting for you to approve access…") }
            val done = repo.pollUntilAuthorised(req.id ?: error("Missing requisition id"))
            _state.update { it.copy(linkProgress = "Approved. Fetching accounts…") }
            if (done.accounts.isEmpty()) error("No accounts returned by ${institution.name}. Status: ${done.status}")
            val connection = Connection(
                institutionId = institution.id,
                institutionName = institution.name,
                requisitionId = done.id ?: req.id!!,
                accountIds = done.accounts,
            )
            repo.saveConnection(connection, replacing)
            _state.update { it.copy(linkProgress = "Importing ${done.accounts.size} account(s)…") }
            done.accounts.forEach { repo.importAccount(institution.name, it) }
            // A renewed consent reissues every account under a new id; fold them back into
            // the accounts already here rather than counting them twice.
            repo.adoptPredecessors(connection)
        }
            .onSuccess {
                _state.update {
                    it.copy(
                        linkingBank = null,
                        error = null,
                        linkProgress = null,
                        // Most people have more than one account, and this is the moment they
                        // are already in the flow and have the bank's app to hand.
                        justLinked = institution.name,
                        reauthNeeded = it.reauthNeeded.filter { c ->
                            c.requisitionId != replacing?.requisitionId && c.institutionId != institution.id
                        },
                    )
                }
                loadLocal()
            }
            .onFailure { e ->
                // A DNS or connection failure is worth naming as such: the fix is to check
                // the connection and retry, not to pick a different bank.
                val message = when (e) {
                    is java.net.UnknownHostException ->
                        "Couldn't reach GoCardless. Check your connection and try again."
                    is java.io.IOException ->
                        e.message ?: "Network error while linking. Try again."
                    else -> "Link failed: ${e.message}"
                }
                _state.update { it.copy(linkingBank = null, linkProgress = null, error = message) }
            }
        _state.update { it.copy(linkingBank = null) }
    }

    fun dismissLinkPrompt() {
        _state.update { it.copy(justLinked = null) }
    }

    /** After "Delete all data": the banks are listed afresh if a link is made again. */
    fun forgetInstitutions() {
        cachedInstitutions = null
    }
}
