package com.spendroid.web

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who may see the page: a one-use six-digit code shown on the phone, and the computers the user
 * chose to remember for 30 days. A remembered computer holds a long random token in a cookie; the
 * phone keeps only its hash, so the settings file alone lets nobody in.
 */
object WebAccess {

    /** A computer that was told to remember. */
    data class Computer(val hash: String, val name: String, val created: Long, val expires: Long, val lastSeen: Long)

    /** What the phone shows while the page is on. */
    data class Running(val addresses: List<Served>, val code: String)

    /** One place the page is served: Wi-Fi or the hotspot, and its address with the port. */
    data class Served(val label: String, val address: String)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running

    /** Tokens for computers paired without remembering: good until the page is switched off. */
    private val sessions = mutableSetOf<String>()
    private var failures = 0
    private val random = SecureRandom()

    @Volatile var lastActivity: Long = 0L

    fun started(addresses: List<Served>) {
        _running.value = Running(addresses, newCode())
        failures = 0
        lastActivity = System.currentTimeMillis()
    }

    fun stopped() {
        _running.value = null
        synchronized(sessions) { sessions.clear() }
    }

    private fun newCode(): String = "%06d".format(random.nextInt(1_000_000))

    /**
     * A token for a correct code, or null. The code works once; five wrong tries replace it, so it
     * cannot be guessed by trying them all.
     */
    fun pair(context: Context, code: String, remember: Boolean, name: String): String? {
        val now = _running.value ?: return null
        if (code.filter { it.isDigit() } != now.code) {
            if (++failures >= MAX_FAILURES) {
                _running.value = now.copy(code = newCode())
                failures = 0
            }
            return null
        }
        _running.value = now.copy(code = newCode())
        failures = 0
        val token = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        if (remember) {
            val all = computers(context) + Computer(hash(token), name, System.currentTimeMillis(), System.currentTimeMillis() + REMEMBER_MS, System.currentTimeMillis())
            save(context, all)
        } else {
            synchronized(sessions) { sessions += hash(token) }
        }
        return token
    }

    /** Whether [token] lets its holder in; a remembered computer's last visit is noted. */
    fun allowed(context: Context, token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val h = hash(token)
        if (synchronized(sessions) { h in sessions }) return true
        val all = computers(context)
        val match = all.firstOrNull { it.hash == h } ?: return false
        if (System.currentTimeMillis() - match.lastSeen > 60_000L) {
            save(context, all.map { if (it.hash == h) it.copy(lastSeen = System.currentTimeMillis()) else it })
        }
        return true
    }

    /** The remembered computers still within their 30 days. */
    fun computers(context: Context): List<Computer> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val now = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Computer(o.optString("hash"), o.optString("name"), o.optLong("created"), o.optLong("expires"), o.optLong("lastSeen"))
        }.filter { it.expires > now }
    }

    fun forget(context: Context, hash: String) = save(context, computers(context).filterNot { it.hash == hash })

    private fun save(context: Context, all: List<Computer>) {
        val arr = JSONArray()
        all.forEach {
            arr.put(JSONObject().put("hash", it.hash).put("name", it.name).put("created", it.created).put("expires", it.expires).put("lastSeen", it.lastSeen))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, arr.toString()) }
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val PREFS = "web_access"
    private const val KEY = "computers"
    private const val MAX_FAILURES = 5
    const val REMEMBER_MS = 30L * 24 * 60 * 60 * 1000
}
