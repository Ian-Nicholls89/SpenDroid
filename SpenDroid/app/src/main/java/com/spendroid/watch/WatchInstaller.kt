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

    /**
     * Pairs with the watch using the code from its "Pair new device" screen. Once only.
     *
     * The library waits on the watch without any time limit, and a watch whose screen has gone
     * off drops its Wi-Fi - so a pairing could wait forever, the button greyed out and nothing
     * said. It now checks the watch answers at all, and gives up after thirty seconds.
     */
    suspend fun pair(context: Context, host: String, port: Int, code: String): Result {
        reachable(host, port)?.let { return it }
        return withBlockingTimeout(PAIR_TIMEOUT_MS, SLEEPING_WATCH) {
            allowPlatformTls()
            if (WatchAdb.fresh(context).pair(host.trim(), port, code.trim())) {
                Result.Done
            } else {
                Result.Failed("The watch didn't accept the code. Check it and try again.")
            }
        }.let { result ->
            if (result is Result.Failed && result.message.contains("SSL", ignoreCase = true)) {
                Result.Failed("The watch turned the code down. Open Pair new device again for a fresh code, and try once more.")
            } else {
                result
            }
        }
    }

    /**
     * A blocking call with a time limit that holds even when the call ignores interruption:
     * it runs on its own thread, and the answer is given up on if it has not come in time.
     */
    private suspend fun withBlockingTimeout(ms: Long, onTimeout: String, block: () -> Result): Result {
        val deferred = kotlinx.coroutines.CompletableDeferred<Result>()
        Thread({
            deferred.complete(runCatching(block).getOrElse { describe(it) })
        }, "watch-adb").apply { isDaemon = true }.start()
        return kotlinx.coroutines.withTimeoutOrNull(ms) { deferred.await() } ?: Result.Failed(onTimeout)
    }

    /** A five-second check that anything answers at the address, before waiting on it longer. */
    private suspend fun reachable(host: String, port: Int): Result? = withContext(Dispatchers.IO) {
        runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host.trim(), port), REACH_TIMEOUT_MS) }
            null
        }.getOrElse {
            Result.Failed(
                "Nothing answered at $host:$port. Check the address, that the phone and watch are on the same " +
                    "Wi-Fi, and keep the watch's Wireless debugging screen open and awake.",
            )
        }
    }

    private fun describe(e: Throwable): Result.Failed = Result.Failed(
        when (e) {
            is io.github.muntashirakon.adb.AdbPairingRequiredException ->
                "The watch doesn't know this phone yet. Pair it first."
            is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
                SLEEPING_WATCH
            else -> "Couldn't finish: ${e.message ?: e.javaClass.simpleName}"
        },
    )

    private const val PAIR_TIMEOUT_MS = 30_000L
    private const val INSTALL_TIMEOUT_MS = 180_000L
    private const val REACH_TIMEOUT_MS = 5_000
    private const val SLEEPING_WATCH =
        "The watch didn't answer. Keep its Wireless debugging screen open and awake - the watch turns " +
            "Wi-Fi off when its screen sleeps - then try again."

    /** Downloads the watch app and installs it on the watch at [host]:[port]. */
    suspend fun install(
        context: Context,
        host: String,
        port: Int,
        progress: (String) -> Unit,
    ): Result {
        progress("Downloading the watch app…")
        val apk = withContext(Dispatchers.IO) { runCatching { download(context) } }
            .getOrElse { return Result.Failed("Couldn't download the watch app: ${it.message ?: it.javaClass.simpleName}") }
        progress("Checking the watch answers…")
        reachable(host, port)?.let { return it }
        progress("Installing on the watch…")
        return withBlockingTimeout(INSTALL_TIMEOUT_MS, SLEEPING_WATCH) {
            allowPlatformTls()
            val adb = WatchAdb.fresh(context)
            adb.setHostAddress(host.trim())
            if (!adb.connect(host.trim(), port)) {
                return@withBlockingTimeout Result.Failed("The watch refused the connection. Pair it again, then try.")
            }
            try {
                val output = push(adb, apk)
                if (output.contains("Success")) Result.Done
                else Result.Failed("The watch said: ${output.trim().ifBlank { "nothing" }}")
            } finally {
                runCatching { adb.disconnect() }
            }
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
