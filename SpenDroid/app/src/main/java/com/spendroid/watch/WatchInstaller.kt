package com.spendroid.watch

import android.content.Context
import android.os.Build
import com.spendroid.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Installs the watch app from the phone, the way a computer would with `adb install`.
 *
 * A phone cannot hand an app to a watch on its own - only the Play Store can - so this uses the
 * watch's Wireless debugging: pair once with the code the watch shows, then connect and install
 * the watch APK from the same GitHub release as this phone app.
 */
internal object WatchInstaller {

    sealed interface Result {
        data object Done : Result
        data class Failed(val message: String) : Result
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(2, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    /** The watch APK published with this build of the phone app. */
    internal fun apkUrl(versionName: String = BuildConfig.VERSION_NAME, versionCode: Int = BuildConfig.VERSION_CODE): String =
        "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/download/" +
            "v$versionName-$versionCode/wear-release.apk"

    /** Pairs with the watch using the code from its "Pair new device" screen. Once only. */
    suspend fun pair(context: Context, host: String, port: Int, code: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            allowPlatformTls()
            if (WatchAdb.get(context).pair(host.trim(), port, code.trim())) {
                Result.Done
            } else {
                Result.Failed("The watch didn't accept the code. Check it and try again.")
            }
        }.getOrElse { Result.Failed("Couldn't pair: ${it.message ?: it.javaClass.simpleName}") }
    }

    /** Downloads the watch app and installs it on the watch at [host]:[port]. */
    suspend fun install(
        context: Context,
        host: String,
        port: Int,
        progress: (String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        runCatching {
            progress("Downloading the watch app…")
            val apk = download(context)
            progress("Connecting to the watch…")
            allowPlatformTls()
            val adb = WatchAdb.get(context)
            adb.setHostAddress(host.trim())
            if (!adb.connect(host.trim(), port)) {
                return@runCatching Result.Failed("The watch refused the connection. Pair it again, then try.")
            }
            try {
                progress("Installing on the watch…")
                val output = push(adb, apk)
                if (output.contains("Success")) Result.Done
                else Result.Failed("The watch said: ${output.trim().ifBlank { "nothing" }}")
            } finally {
                runCatching { adb.disconnect() }
            }
        }.getOrElse { e ->
            Result.Failed(
                when (e) {
                    is io.github.muntashirakon.adb.AdbPairingRequiredException ->
                        "The watch doesn't know this phone yet. Pair it first."
                    is java.net.ConnectException, is java.net.SocketTimeoutException ->
                        "Couldn't reach the watch. Check Wireless debugging is on and the address is right."
                    else -> "Couldn't install: ${e.message ?: e.javaClass.simpleName}"
                },
            )
        }
    }

    private fun download(context: Context): File {
        val target = File(context.cacheDir, "wear-release.apk")
        http.newCall(Request.Builder().url(apkUrl()).build()).execute().use { response ->
            if (!response.isSuccessful) error("the watch app isn't on this release's page (HTTP ${response.code})")
            target.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
        return target
    }

    /** `pm install` reading the APK from the stream, as `adb install` does on Android 7 and later. */
    private fun push(adb: WatchAdb, apk: File): String {
        val stream = adb.openStream("exec:cmd package install -S ${apk.length()}")
        stream.openOutputStream().use { out ->
            apk.inputStream().use { it.copyTo(out) }
            out.flush()
            return stream.openInputStream().bufferedReader().readText()
        }
    }

    /**
     * Pairing needs a TLS key export that Android only offers through its own copy of Conscrypt,
     * which apps are normally kept from calling. This lets that one class through.
     */
    private fun allowPlatformTls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/")
        }
    }
}
