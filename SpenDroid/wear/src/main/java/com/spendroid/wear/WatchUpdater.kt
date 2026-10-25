package com.spendroid.wear

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The watch app updating itself: the watch APK from the phone app's GitHub release, downloaded on
 * the watch and handed to the system installer, which asks the user to confirm - as the phone app
 * updates itself. Settings → Watch on the phone remains the way in when this cannot be used.
 */
internal object WatchUpdater {

    /** The newer watch app the phone knows of, or null when this one is current. */
    fun available(context: Context, reading: BudgetReading): Pair<String, String>? {
        val latest = reading.map.getInt("wearLatestCode", 0)
        val url = reading.text("wearApkUrl").takeIf { it.isNotEmpty() } ?: return null
        val installed = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(Long.MAX_VALUE)
        return if (latest > installed) reading.text("wearLatestName") to url else null
    }

    sealed interface Result {
        data object Started : Result
        data object NeedsPermission : Result
        data class Failed(val message: String) : Result
    }

    suspend fun install(context: Context, url: String, code: Int, name: String, progress: (String) -> Unit): Result {
        // Allowed once, like "install unknown apps" for the phone app.
        if (!context.packageManager.canRequestPackageInstalls()) return Result.NeedsPermission
        // Downloaded before and not yet installed - the prompt missed, or cancelled: that copy.
        val apk = kept(context).takeIf { it.exists() && prefs(context).getInt("code", 0) == code }
            ?: run {
                progress("Downloading…")
                withContext(Dispatchers.IO) { runCatching { download(context, url).also { keep(context, it, code) } } }
                    .getOrElse { return Result.Failed("Couldn't download the update: ${it.message ?: it.javaClass.simpleName}") }
            }
        progress("Installing…")
        return withContext(Dispatchers.IO) {
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(context.packageName) }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("wear-release.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val done = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(context, InstallResultReceiver::class.java).putExtra(InstallResultReceiver.EXTRA_NAME, name),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(done.intentSender)
                }
                Result.Started
            }.getOrElse { Result.Failed("Couldn't start the install: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    // ---- the download kept, until it's installed ------------------------------------------------

    private fun kept(context: Context) = File(File(context.filesDir, "updates").apply { mkdirs() }, "wear.apk")

    private fun prefs(context: Context) = context.getSharedPreferences("wear_update", Context.MODE_PRIVATE)

    private fun keep(context: Context, downloaded: File, code: Int) {
        val target = kept(context)
        target.delete()
        check(downloaded.renameTo(target) || runCatching { downloaded.copyTo(target, overwrite = true); downloaded.delete() }.isSuccess) { "Couldn't keep the download" }
        prefs(context).edit().putInt("code", code).commit()
    }

    /** At start: once it's installed - or this app is newer - the kept download goes, and its notice. */
    fun tidy(context: Context) {
        val code = prefs(context).getInt("code", 0)
        val installed = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0L)
        if (code in 1..installed) {
            kept(context).delete()
            prefs(context).edit().clear().commit()
            WatchUpdateNotice.clear(context)
        }
    }

    /** The system's page for letting this app install updates, where the watch has one. */
    fun openPermission(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    private fun download(context: Context, url: String): File {
        val target = File(context.cacheDir, "wear-update.apk")
        var address = url
        // GitHub answers a release download with a redirect to where the file really is.
        repeat(5) {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            val code = connection.responseCode
            if (code in 300..399) {
                address = connection.getHeaderField("Location") ?: error("redirected nowhere")
                connection.disconnect()
                return@repeat
            }
            if (code != 200) error("HTTP $code")
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            connection.disconnect()
            return target
        }
        error("too many redirects")
    }
}

/**
 * Where the system says how an install went. Asked to confirm, it shows Android's prompt - and a
 * notification that opens it when tapped, as one asked for while the screen's off never shows. Not
 * installed, it says so; the update page stays, its download kept, to install from again.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                WatchUpdateNotice.ready(context, name, confirm)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> WatchUpdateNotice.clear(context)
            else -> {
                WatchUpdateNotice.clear(context)
                Toast.makeText(
                    context,
                    "Update didn't install: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason") +
                        ". It's kept - tap Install to try again.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "com.spendroid.wear.UPDATE_NAME"
    }
}

/** The notification an update waiting for its install prompt leaves, until it's dealt with. */
internal object WatchUpdateNotice {
    private const val CHANNEL = "watch_updates"
    private const val ID = 7130

    fun ready(context: Context, name: String, confirm: Intent) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "App updates", android.app.NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val tap = PendingIntent.getActivity(context, ID, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_roundup)
            .setContentTitle("SpenDroid ${name.ifEmpty { "update" }} is ready")
            .setContentText("Tap to install it.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(context).notify(ID, notification)
    }

    fun clear(context: Context) {
        androidx.core.app.NotificationManagerCompat.from(context).cancel(ID)
    }
}
