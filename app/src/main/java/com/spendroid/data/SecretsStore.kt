package com.spendroid.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "budget_settings")

data class Connection(
    val institutionId: String,
    val institutionName: String,
    val requisitionId: String,
    val accountIds: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
)

class SecretsStore(private val context: Context) {

    private object Keys {
        val SECRET_ID = stringPreferencesKey("secret_id")
        val SECRET_KEY = stringPreferencesKey("secret_key")
        val CONNECTIONS = stringPreferencesKey("connections")
        val IGNORED_RULES = stringPreferencesKey("ignored_rules")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }

    val secretId: Flow<String?> = stringFlow(Keys.SECRET_ID)
    val secretKey: Flow<String?> = stringFlow(Keys.SECRET_KEY)
    val refreshToken: Flow<String?> = stringFlow(Keys.REFRESH_TOKEN)

    val ignoredRules: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseStringSet(prefs[Keys.IGNORED_RULES]) }

    val connections: Flow<List<Connection>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseConnections(prefs[Keys.CONNECTIONS]) }

    private fun stringFlow(key: androidx.datastore.preferences.core.Preferences.Key<String>): Flow<String?> =
        context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { prefs -> prefs[key] }

    suspend fun saveSecret(id: String, key: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SECRET_ID] = id
            prefs[Keys.SECRET_KEY] = key
            prefs.remove(Keys.REFRESH_TOKEN) // clear old token on new credentials
        }
    }

    suspend fun saveRefreshToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REFRESH_TOKEN] = token
        }
    }

    suspend fun saveConnections(connections: List<Connection>) {        context.dataStore.edit { prefs ->
            val arr = JSONArray()
            connections.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("institutionId", c.institutionId)
                        .put("institutionName", c.institutionName)
                        .put("requisitionId", c.requisitionId)
                        .put("accountIds", JSONArray(c.accountIds)),
                )
            }
            prefs[Keys.CONNECTIONS] = arr.toString()
        }
    }

    suspend fun setRuleIgnored(key: String, ignored: Boolean) {
        val current = parseStringSet(context.dataStore.data.first()[Keys.IGNORED_RULES]).toMutableSet()
        if (ignored) current.add(key) else current.remove(key)
        context.dataStore.edit { prefs ->
            prefs[Keys.IGNORED_RULES] = JSONArray(current.toList()).toString()
        }
    }

    private fun parseStringSet(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        val arr = JSONArray(json)
        return LinkedHashSet<String>(arr.length()).also { out ->
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        }
    }

    private fun parseConnections(json: String?): List<Connection> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = JSONArray(json)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val idsArr = o.optJSONArray("accountIds") ?: JSONArray()
            Connection(
                institutionId = o.optString("institutionId"),
                institutionName = o.optString("institutionName"),
                requisitionId = o.optString("requisitionId"),
                accountIds = List(idsArr.length()) { idsArr.getString(it) },
            )
        }
    }
}