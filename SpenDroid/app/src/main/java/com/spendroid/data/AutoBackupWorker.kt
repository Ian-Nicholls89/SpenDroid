package com.spendroid.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendroid.BudgetApplication

/**
 * The night's backup: everything a manual export has - with payee names, contract dates and
 * subscription choices - encrypted with the user's passphrase and written to their folder. Tried
 * again later on a failure; after the third, a notification that says what went wrong.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val folder = AutoBackup.status(applicationContext).folder ?: return Result.success()
        val app = applicationContext as? BudgetApplication ?: return Result.success()
        return runCatching {
            val passphrase = AutoBackup.passphrase(applicationContext) ?: error("The passphrase isn't on this phone any more - set it again")
            // Payee names, contracts and subscriptions loaded first, or the backup would go without them.
            Choices.awaitLoaded()
            val json = app.repository.exportJson(BackupPrefs.export(applicationContext), includeBank = true)
            // Nothing here - just after "Delete all data", or a new phone before its restore: an empty
            // backup would push a good one out of the last seven, so none is made.
            if (!AutoBackup.hasData(json)) {
                passphrase.fill(' ')
                AutoBackup.record(applicationContext, ok = true, note = null, skipped = true)
                return Result.success()
            }
            val bytes = BackupCrypto.encrypt(json, passphrase).also { passphrase.fill(' ') }
            AutoBackup.write(applicationContext, folder, AutoBackup.nameFor(System.currentTimeMillis()), bytes)
        }.fold(
            onSuccess = {
                AutoBackup.record(applicationContext, ok = true, note = null)
                Result.success()
            },
            onFailure = { error ->
                val why = AutoBackup.explain(error)
                AutoBackup.record(applicationContext, ok = false, note = why)
                if (runAttemptCount < 2) {
                    Result.retry()
                } else {
                    tell(why)
                    Result.success()
                }
            },
        )
    }

    /** Specific, as Penny's notifications are: what didn't happen, and why. */
    private fun tell(why: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_DEFAULT))
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(com.spendroid.R.drawable.ic_stat_droid)
            .setContentTitle("Backup to your folder didn't save")
            .setContentText("$why Your data on the phone is fine.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$why Your data on the phone is fine - Settings → Data to try again, or choose another folder."))
            .setContentIntent(com.spendroid.work.openAppIntent(applicationContext, NOTIFICATION_ID))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL = "backups"
        const val NOTIFICATION_ID = 5120
    }
}
