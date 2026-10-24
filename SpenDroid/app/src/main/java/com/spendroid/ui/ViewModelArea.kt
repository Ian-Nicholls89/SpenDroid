package com.spendroid.ui

import android.app.Application
import com.spendroid.data.GoCardlessRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What one area of [RootViewModel] shares with the rest: the screen's state, the repository, the
 * view model's scope, and the reloads every area ends with. Handed to each area by the view model,
 * which owns them.
 */
internal class AreaHost(
    val app: Application,
    val repo: GoCardlessRepository,
    val state: MutableStateFlow<RootUiState>,
    val scope: CoroutineScope,
    val loadLocal: suspend () -> Unit,
    val refreshConfig: suspend () -> Unit,
)

/**
 * One area of [RootViewModel] - backups, bank linking, updates - in a file of its own. The names
 * are the view model's own, so code reads the same in either.
 */
internal abstract class ViewModelArea(private val host: AreaHost) {
    protected val _state: MutableStateFlow<RootUiState> get() = host.state
    protected val viewModelScope: CoroutineScope get() = host.scope
    protected val repo: GoCardlessRepository get() = host.repo

    @Suppress("UNCHECKED_CAST")
    protected fun <T : Application> getApplication(): T = host.app as T

    protected suspend fun loadLocal() = host.loadLocal()
    protected suspend fun refreshConfig() = host.refreshConfig()

    protected fun setupPrefs() = host.app.getSharedPreferences(com.spendroid.data.Stores.FIRST_RUN, android.content.Context.MODE_PRIVATE)
}
