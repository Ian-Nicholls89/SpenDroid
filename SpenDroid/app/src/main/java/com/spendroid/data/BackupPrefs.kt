package com.spendroid.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the user has said that lives outside the database, carried in a backup: names given to
 * payees, contract end dates and accepted prices, and subscription choices (subscription or
 * household bill, reminders, cancelled). Each file whole, entry by entry with its type, so a
 * restore puts back exactly what was there.
 */
object BackupPrefs {

    /** The preferences files in a backup. */
    val FILES get() = Stores.inBackup

    fun export(context: Context): JSONObject = JSONObject().also { root ->
        FILES.forEach { file ->
            val entries = JSONObject()
            Stores.prefs(context, file).all.forEach { (key, value) ->
                when (value) {
                    is String -> entries.put(key, JSONObject().put("t", "s").put("v", value))
                    is Boolean -> entries.put(key, JSONObject().put("t", "b").put("v", value))
                    is Int -> entries.put(key, JSONObject().put("t", "i").put("v", value))
                    is Long -> entries.put(key, JSONObject().put("t", "l").put("v", value))
                    is Set<*> -> entries.put(key, JSONObject().put("t", "set").put("v", JSONArray(value.filterIsInstance<String>())))
                }
            }
            root.put(file, entries)
        }
    }

    /** Merges [prefs] in: what the backup has replaces the same key; anything else stays. */
    fun restore(context: Context, prefs: JSONObject?) {
        prefs ?: return
        FILES.forEach { file ->
            val entries = prefs.optJSONObject(file) ?: return@forEach
            Stores.prefs(context, file).edit(commit = true) {
                entries.keys().forEach { key ->
                    val o = entries.optJSONObject(key) ?: return@forEach
                    when (o.optString("t")) {
                        "s" -> putString(key, o.optString("v"))
                        "b" -> putBoolean(key, o.optBoolean("v"))
                        "i" -> putInt(key, o.optInt("v"))
                        "l" -> putLong(key, o.optLong("v"))
                        "set" -> o.optJSONArray("v")?.let { arr -> putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet()) }
                    }
                }
            }
        }
    }
}
