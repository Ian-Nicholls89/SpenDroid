package com.spendroid.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Crashes, kept on the phone - the phone's own and the watch's, which it passes over - for the
 * user to send if they choose, from Settings. Nothing leaves the phone by itself; there's no
 * company server to send to. The last [KEEP] are kept.
 */
object CrashLog {

    data class Crash(val at: Long, val from: String, val text: String)

    private const val DIR = "crashes"
    private const val KEEP = 10
    private val WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss")

    /** Notes any crash before the app goes, then lets Android deal with it as before. */
    fun install(context: Context, version: String) {
        val app = context.applicationContext
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, "phone $version · thread ${thread.name}", stackOf(error)) }
            before?.uncaughtException(thread, error)
        }
    }

    fun stackOf(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

    /** A crash, from the phone or as the watch sent it. Written at once: the app is on its way out. */
    fun record(context: Context, from: String, text: String, at: Long = System.currentTimeMillis()) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        File(dir, "crash-$at.txt").writeText("$from\n$text")
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    fun all(context: Context): List<Crash> =
        File(context.filesDir, DIR).listFiles()?.mapNotNull { f ->
            val at = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: return@mapNotNull null
            val body = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            Crash(at, body.substringBefore('\n'), body.substringAfter('\n'))
        }?.sortedByDescending { it.at }.orEmpty()

    /** All of them as one report, for sharing: what the device is, then each crash, newest first. */
    fun report(context: Context): String = buildString {
        append("SpenDroid crash report\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        all(context).forEach { c ->
            append("\n--- ").append(Instant.ofEpochMilli(c.at).atZone(ZoneId.systemDefault()).format(WHEN)).append(" · ").append(c.from).append('\n')
            append(c.text.trimEnd()).append('\n')
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
    }
}
