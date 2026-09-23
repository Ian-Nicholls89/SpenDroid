package com.spendroid.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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

        val bytes = try {
            download(url, onProgress)
        } catch (e: Exception) {
            return@withContext Result.Failed(e.message ?: "Download failed")
        }

        // The published manifest carries the hash, so a truncated or tampered download is
        // caught here rather than by the installer refusing something halfway through.
        release.apkSha256?.let { expected ->
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                return@withContext Result.Failed(
                    "The download did not match the published checksum, so it was discarded.",
                )
            }
        }

        try {
            commit(context, bytes, release.versionCode)
            Result.Started
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Could not start the installer")
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

    private fun commit(context: Context, apk: ByteArray, versionCode: Int) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("spendroid-$versionCode", 0, apk.size.toLong()).use { out ->
                out.write(apk)
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
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
 * Receives the installer's outcome. The system needs somewhere to send it, and for a user
 * confirmation flow the interesting part is the prompt it raises, not this callback.
 */
class InstallResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android is asking to show its own confirmation; it must be started from here.
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            confirm?.let(context::startActivity)
        }
    }
}
