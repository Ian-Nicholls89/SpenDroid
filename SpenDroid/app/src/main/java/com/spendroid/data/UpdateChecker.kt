package com.spendroid.data

import com.spendroid.BuildConfig
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String?,
    val apkSha256: String?,
)

/**
 * Finds the newest published release.
 *
 * The build publishes an update.json asset alongside the APKs, so the version code is read
 * as a number the build already knew rather than recovered from prose. The release tag is
 * kept as a fallback for releases cut before the manifest existed, or if the asset is
 * missing; it is machine-generated ("v1.26-27") so it parses deterministically.
 */
object UpdateChecker {

    /** GitHub redirects /releases/latest/download/<asset> to the newest release's copy. */
    private val MANIFEST_URL =
        "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}" +
            "/releases/latest/download/update.json"

    private val LATEST_RELEASE_URL =
        "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}" +
            "/releases/latest"

    /** GitHub's API rejects requests without one. */
    private val USER_AGENT = "SpenDroid/${BuildConfig.VERSION_NAME}"

    private val TAG_PATTERN = Pattern.compile("^v?(\\d+(?:\\.\\d+)*)[-_.](\\d+)$")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun latestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        fromManifest() ?: fromReleaseTag()
    }

    /** True when [release] is newer than the running build. */
    fun isNewer(release: ReleaseInfo): Boolean = release.versionCode > BuildConfig.VERSION_CODE

    private fun fromManifest(): ReleaseInfo? = runCatching {
        val request = Request.Builder()
            .url(MANIFEST_URL)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            val json = JSONObject(body)
            val code = json.optInt("versionCode", -1)
            if (code <= 0) return@use null
            ReleaseInfo(
                versionCode = code,
                versionName = json.optString("versionName", ""),
                apkUrl = json.optString("apkUrl").ifBlank { null },
                apkSha256 = json.optString("apkSha256").ifBlank { null },
            )
        }
    }.getOrNull()

    private fun fromReleaseTag(): ReleaseInfo? = runCatching {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            val tag = JSONObject(body).optString("tag_name", "").trim()
            val matcher = TAG_PATTERN.matcher(tag)
            if (!matcher.matches()) return@use null
            val name = matcher.group(1) ?: return@use null
            val code = matcher.group(2)?.toIntOrNull() ?: return@use null
            ReleaseInfo(versionCode = code, versionName = name, apkUrl = null, apkSha256 = null)
        }
    }.getOrNull()
}
