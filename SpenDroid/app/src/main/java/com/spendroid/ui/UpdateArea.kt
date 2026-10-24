package com.spendroid.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.spendroid.BuildConfig
import com.spendroid.data.ApkInstaller
import com.spendroid.data.UpdateChecker
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [RootViewModel]'s update check: the user's own, which goes on to download and install.
 */
internal class UpdateArea(host: AreaHost) : ViewModelArea(host) {

    /**
     * Checks, and when the user asked for it, goes through with the update.
     *
     * A check the user started is a request to update; the weekly background check only
     * raises a notification and never downloads anything on its own.
     */
    fun checkForUpdate() {
        viewModelScope.launch {
            _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Checking("Checking for updates…")) }

            val latest = try {
                UpdateChecker.latestRelease()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        updateCheckStatus = UpdateCheckStatus.Error(
                            "Error: ${e.message ?: e.javaClass.simpleName}",
                        ),
                    )
                }
                return@launch
            }

            if (latest == null) {
                _state.update {
                    it.copy(updateCheckStatus = UpdateCheckStatus.Error("Unable to check for updates"))
                }
                return@launch
            }
            if (!UpdateChecker.isNewer(latest)) {
                _state.update {
                    it.copy(
                        updateCheckStatus = UpdateCheckStatus.Success(
                            "You're up to date (v${latest.versionName}, build ${BuildConfig.VERSION_CODE})",
                        ),
                    )
                }
                return@launch
            }

            val context = getApplication<Application>()
            if (!ApkInstaller.canInstall(context)) {
                _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.NeedsInstallPermission) }
                return@launch
            }

            _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Downloading(0)) }
            val result = ApkInstaller.downloadAndInstall(context, latest) { percent ->
                _state.update { it.copy(updateCheckStatus = UpdateCheckStatus.Downloading(percent)) }
            }
            _state.update {
                it.copy(
                    updateCheckStatus = when (result) {
                        is ApkInstaller.Result.Started -> UpdateCheckStatus.Installing
                        ApkInstaller.Result.NeedsPermission -> UpdateCheckStatus.NeedsInstallPermission
                        is ApkInstaller.Result.Failed -> UpdateCheckStatus.Error(result.message)
                    },
                )
            }
        }
    }

    /** Opens the settings page where installing from this app is allowed. */
    fun openInstallPermissionSettings() {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(
                ApkInstaller.permissionIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
