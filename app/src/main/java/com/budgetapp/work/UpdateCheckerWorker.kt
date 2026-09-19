package com.budgetapp.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class UpdateCheckerWorker(
    app: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(app, parameters) {

    override suspend fun doWork(): Result {
        val currentVersionCode = inputData.getInt(VERSION_CODE_KEY, -1)
        if (currentVersionCode == -1) return Result.failure()

        return withContext(Dispatchers.IO) {
            try {
                val latestVersionCode = checkForUpdate()
                latestVersionCode?.let { versionCode ->
                    if (versionCode > currentVersionCode) {
                        showUpdateNotification(versionCode)
                    }
                }
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }

    private suspend fun checkForUpdate(): Int? {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        // GitHub API: GET /repos/{owner}/{repo}/releases/latest
        val request = Request.Builder()
            .url("https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases/latest")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        val json = JSONObject(body)
        
        // Try multiple sources for versionCode:
        // 1. From release body (format: "versionCode: 16")
        // 2. From asset names (format: "app-16.apk" or "app-v16.apk")
        // 3. From tag_name (fallback: "v1.15" -> parse)
        
        // Try release body first
        val releaseBody = json.optString("body", "")
        val versionFromBody = extractVersionCode(releaseBody)
        if (versionFromBody != null) return versionFromBody

        // Try assets
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val versionFromAsset = extractVersionCode(name)
                if (versionFromAsset != null) return versionFromAsset
            }
        }

        // Fallback: parse tag_name (e.g., "v1.15" -> need versionCode mapping)
        // This is imprecise, so we return null and rely on body/assets
        return null
    }

    private fun extractVersionCode(text: String): Int? {
        // Pattern: "versionCode: 16" or "versionCode=16" or "v16" or "app-16.apk"
        val patterns = listOf(
            Pattern.compile("versionCode[\\s:=]+(\\d+)"),
            Pattern.compile("version[\\s:=]+(\\d+)"),
            Pattern.compile("v(\\d+)(?:\\.\\d+)?[-\\.]"),
            Pattern.compile("app[-\\s](\\d+)\\."),
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1).toIntOrNull()
            }
        }
        return null
    }

    private fun showUpdateNotification(versionCode: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifies when a new app version is available"
        }
        manager.createNotificationChannel(channel)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/releases/latest"))
        val pendingIntent = android.app.PendingIntent.getActivity(
            applicationContext, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("BudgetApp update available")
            .setContentText("Version $versionCode is ready. Tap to view releases.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("A new version of BudgetApp ($versionCode) is available on GitHub. Tap to open the releases page and download the latest APK."))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        // TODO: Replace with your GitHub username/repo
        private const val GITHUB_OWNER = "YOUR_GITHUB_USERNAME"
        private const val GITHUB_REPO = "BudgetApp"
        private const val CHANNEL_ID = "update_checker"
        private const val NOTIFICATION_ID = 3001
        const val VERSION_CODE_KEY = "version_code"
    }
}