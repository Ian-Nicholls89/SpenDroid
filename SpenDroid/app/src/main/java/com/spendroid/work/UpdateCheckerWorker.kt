package com.spendroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendroid.BudgetApplication
import com.spendroid.BuildConfig
import com.spendroid.data.ReleaseInfo
import com.spendroid.data.UpdateChecker
import kotlinx.coroutines.flow.first

class UpdateCheckerWorker(
    app: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result {
        val release = try {
            UpdateChecker.latestRelease()
        } catch (e: Exception) {
            return if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        } ?: return if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()

        if (!UpdateChecker.isNewer(release)) return Result.success()

        // A weekly check would otherwise re-announce the same release every run.
        val repo = (applicationContext as BudgetApplication).repository
        if (repo.lastNotifiedVersionCode.first() >= release.versionCode) return Result.success()

        showUpdateNotification(release)
        repo.saveLastNotifiedVersionCode(release.versionCode)
        return Result.success()
    }

    private fun showUpdateNotification(release: ReleaseInfo) {
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifies when a new app version is available"
        }
        manager.createNotificationChannel(channel)

        val intent = Intent(Intent.ACTION_VIEW, RELEASES_URL.toUri())
        val pendingIntent = android.app.PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SpenDroid update available")
            .setContentText("Version ${release.versionName} (${release.versionCode}) is ready. Tap to view releases.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "A new version of SpenDroid v${release.versionName} (${release.versionCode}) " +
                        "is available on GitHub. Tap to open the releases page and download the latest APK.",
                ),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "update_checker"
        private const val NOTIFICATION_ID = 3001
        private const val MAX_ATTEMPTS = 3
        private val RELEASES_URL =
            "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
    }
}
