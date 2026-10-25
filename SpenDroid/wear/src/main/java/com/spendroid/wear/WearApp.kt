package com.spendroid.wear

import android.app.Application
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The watch app's crashes: kept on the watch as it goes down, then passed to the phone the next
 * time the app starts, to sit with the phone's own in Settings → About for the user to send.
 */
class WearApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // An update kept to install, now installed: the file and its notification go.
        runCatching { WatchUpdater.tidy(this) }
        val dir = File(filesDir, "crashes").apply { mkdirs() }
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText("${versionName()} · thread ${thread.name}\n$stack")
            }
            before?.uncaughtException(thread, error)
        }
        scope.launch { sendToPhone(dir) }
    }

    private fun versionName(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    /** Each kept crash to the phone; gone from the watch once the phone has it. */
    private suspend fun sendToPhone(dir: File) {
        val crashes = dir.listFiles()?.sortedBy { it.name }.orEmpty().takeLast(10)
        if (crashes.isEmpty()) return
        val phones = runCatching { Wearable.getNodeClient(this).connectedNodes.await() }.getOrNull().orEmpty()
        if (phones.isEmpty()) return
        crashes.forEach { file ->
            val sent = phones.any { phone ->
                runCatching { Wearable.getMessageClient(this).sendMessage(phone.id, CRASH_PATH, file.readBytes()).await() }.isSuccess
            }
            if (sent) file.delete()
        }
    }

    private companion object {
        const val CRASH_PATH = "/spendroid/crash"
    }
}
