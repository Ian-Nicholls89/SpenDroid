package com.spendroid.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Names the user has given payees whose bank names are hard to make out - "VIS 4471 SQ *KTH
 * LDN" as "Coffee by the station". For showing only: matching, categories and regular payments
 * keep working from the bank's own name, so naming something changes no figure.
 *
 * One name per bank name: every transaction from it, past and future, shows the new name. Held
 * as Compose state, so anything on screen showing a name redraws the moment it changes.
 */
object PayeeNames {

    private const val PREFS = "payee_names"
    private const val KEY = "names"

    private var names by mutableStateOf<Map<String, String>>(emptyMap())
    private var prefs: android.content.SharedPreferences? = null

    /** Changes each time a name does, for anything that works names out once and keeps them. */
    var version by mutableStateOf(0)
        private set

    /** The bank's name as it's looked up: spacing and case don't make it a different payee. */
    fun key(bankName: String): String = bankName.trim().replace(Regex("\\s+"), " ").lowercase()

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        val json = runCatching { JSONObject(p.getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())
        names = json.keys().asSequence().associateWith { json.optString(it) }.filterValues { it.isNotBlank() }
    }

    /** The user's name for [bankName], or null if it has none. */
    fun nameFor(bankName: String): String? = if (names.isEmpty()) null else names[key(bankName)]

    /** How many bank names have a name of the user's. */
    val count: Int get() = names.size

    /** Names [bankName]; a blank [name], or the bank's own, takes the name away. */
    fun set(bankName: String, name: String?) {
        val k = key(bankName)
        if (k.isBlank()) return
        val clean = name?.trim()?.replace(Regex("\\s+"), " ").orEmpty().take(60)
        names = if (clean.isBlank() || clean.lowercase() == k) names - k else names + (k to clean)
        version++
        prefs?.edit { putString(KEY, JSONObject(names).toString()) }
    }
}
