package com.spendroid.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.spendroid.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a release and hands it to the system installer.
 *
 * Uses PackageInstaller rather than an ACTION_VIEW on a file URI: it needs no FileProvider,
 * and the confirmation the user sees is Android's own rather than anything this app draws.
 */
object ApkInstaller {

    sealed interface Result {
        object Started : Result
        data class Failed(val message: String) : Result
        /** The user has not allowed this app to install packages; send them to settings. */
        object NeedsPermission : Result
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings page where the user grants permission to install from this app. */
    fun permissionIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        )

    suspend fun downloadAndInstall(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: return@withContext Result.Failed("This release has no APK to download.")
        if (!canInstall(context)) return@withContext Result.NeedsPermission

        // Downloaded before and not yet installed - the prompt missed, or cancelled: that copy, checked again.
        val kept = kept(context).takeIf { downloaded(context)?.versionCode == release.versionCode && matches(it.readBytes(), release.apkSha256) }
        if (kept == null) {
            val bytes = try {
                download(url, onProgress)
            } catch (e: Exception) {
                return@withContext Result.Failed(e.message ?: "Download failed")
            }
            // The published manifest carries the hash, so a truncated or tampered download is
            // caught here rather than by the installer refusing something halfway through.
            if (!matches(bytes, release.apkSha256)) {
                return@withContext Result.Failed(
                    "The download did not match the published checksum, so it was discarded.",
                )
            }
            keep(context, bytes, release.versionName, release.versionCode)
        }
        onProgress(100)

        try {
            commit(context, kept(context), release.versionCode, release.versionName)
            Result.Started
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Could not start the installer")
        }
    }

    private fun matches(bytes: ByteArray, expected: String?): Boolean {
        expected ?: return true
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    // ---- the download kept, until it's installed -------------------------------------------------

    /** An update downloaded and checked, not yet installed. */
    data class Downloaded(val versionName: String, val versionCode: Int)

    private fun kept(context: Context) = File(File(context.filesDir, "updates").apply { mkdirs() }, "spendroid.apk")

    private fun prefs(context: Context) = context.getSharedPreferences(Stores.APP_UPDATE, Context.MODE_PRIVATE)

    private fun keep(context: Context, apk: ByteArray, name: String, code: Int) {
        val target = kept(context)
        val part = File(target.parentFile, "spendroid.apk.part")
        part.writeBytes(apk)
        check(part.renameTo(target) || run { target.delete(); part.renameTo(target) }) { "Couldn't keep the download" }
        prefs(context).edit().putString("name", name).putInt("code", code).commit()
    }

    /** The update downloaded and waiting to be installed, if there is one newer than this app. */
    fun downloaded(context: Context, installed: Int = BuildConfig.VERSION_CODE): Downloaded? {
        val p = prefs(context)
        val code = p.getInt("code", 0)
        val name = p.getString("name", null) ?: return null
        return if (code > installed && kept(context).exists()) Downloaded(name, code) else null
    }

    /** Installs the kept download: Android asks to confirm, as it did the first time. */
    suspend fun installDownloaded(context: Context): Result = withContext(Dispatchers.IO) {
        val waiting = downloaded(context) ?: return@withContext Result.Failed("There's no downloaded update waiting.")
        if (!canInstall(context)) return@withContext Result.NeedsPermission
        try {
            commit(context, kept(context), waiting.versionCode, waiting.versionName)
            Result.Started
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Could not start the installer")
        }
    }

    /** At start: once it's installed - or this app is newer - the kept download goes, and its notice. */
    fun tidy(context: Context, installed: Int = BuildConfig.VERSION_CODE) {
        val code = prefs(context).getInt("code", 0)
        if (code in 1..installed) {
            kept(context).delete()
            prefs(context).edit().clear().commit()
            UpdateNotice.clear(context)
        }
    }

    private fun download(url: String, onProgress: (Int) -> Unit): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", "SpenDroid").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
            val body = response.body ?: error("Empty download")
            val total = body.contentLength()
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var read = input.read(buffer)
                var done = 0L
                while (read >= 0) {
                    out.write(buffer, 0, read)
                    done += read
                    if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    read = input.read(buffer)
                }
            }
            return out.toByteArray()
        }
    }

    private fun commit(context: Context, apk: File, versionCode: Int, versionName: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("spendroid-$versionCode", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
                .putExtra(InstallResultReceiver.EXTRA_NAME, versionName)
            val pending = PendingIntent.getBroadcast(
                context,
                versionCode,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pending.intentSender)
        }
    }
}

/**
 * Receives the installer's outcome. Asked to confirm, it shows Android's prompt - and, as Android
 * won't let an app in the background open one, a notification that opens it when tapped. Not
 * installed - the prompt cancelled - a notification that takes the user back to install it.
 */
class InstallResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android is asking to show its own confirmation; it must be started from here.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                UpdateNotice.ready(context, name, confirm)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateNotice.clear(context)
            else -> UpdateNotice.notInstalled(context, name)
        }
    }

    companion object {
        const val EXTRA_NAME = "com.spendroid.UPDATE_NAME"
    }
}

/** The notification an update waiting to install leaves, until it's installed. */
object UpdateNotice {
    private const val CHANNEL = "app_updates"
    private const val ID = 5130

    /** Opens the app to install the kept download: set on [notInstalled]'s tap. */
    const val EXTRA_INSTALL = "com.spendroid.INSTALL_UPDATE"

    fun ready(context: Context, name: String, confirm: Intent) {
        val tap = PendingIntent.getActivity(context, ID, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(context, "SpenDroid $name is ready to install", "Tap to install it.", tap)
    }

    fun notInstalled(context: Context, name: String) {
        post(
            context,
            "SpenDroid $name wasn't installed",
            "It's downloaded - tap to install it, or find it in Settings → About.",
            com.spendroid.work.openAppIntent(context, ID, EXTRA_INSTALL),
        )
    }

    fun clear(context: Context) {
        androidx.core.app.NotificationManagerCompat.from(context).cancel(ID)
    }

    private fun post(context: Context, title: String, text: String, tap: PendingIntent?) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "App updates", android.app.NotificationManager.IMPORTANCE_HIGH))
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.spendroid.R.drawable.ic_stat_droid)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(context).notify(ID, notification)
    }
}
