package com.spendroid.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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
    /** When access was granted, in epoch millis. Zero when it is not known yet. */
    val createdAt: Long = System.currentTimeMillis(),
) {
    /**
     * Days until the bank's consent runs out, negative once it has. Null when the grant date
     * is unknown - a guess here either nags for nothing or stays quiet until syncing stops.
     */
    fun daysUntilExpiry(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Int? {
        if (createdAt <= 0L) return null
        val granted = Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(today, granted.plusDays(ACCESS_DAYS)).toInt()
    }

    companion object {
        /** How long a bank's consent lasts under PSD2, and what GoCardless grants by default. */
        const val ACCESS_DAYS = 90L
    }
}

class SecretsStore(private val context: Context) {

    private object Keys {
        val SECRET_ID = stringPreferencesKey("secret_id")
        val SECRET_KEY = stringPreferencesKey("secret_key")
        val CONNECTIONS = stringPreferencesKey("connections")
        val IGNORED_RULES = stringPreferencesKey("ignored_rules")
        val NOTIFIED_GOAL_WARNINGS = stringPreferencesKey("notified_goal_warnings")
        val NOTIFIED_LARGE_TRANSACTIONS = stringPreferencesKey("notified_large_transactions")
        val NOTIFIED_CARD_WARNINGS = stringPreferencesKey("notified_card_warnings")
        val TRANSFER_GROUPS = stringPreferencesKey("transfer_groups")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val NOTIFICATION_TIME = stringPreferencesKey("notification_time")
        val LAST_NOTIFIED_VERSION = intPreferencesKey("last_notified_version_code")
        val PRIMARY_INCOME_KEY = stringPreferencesKey("primary_income_key")
        val BUDGET_MODEL = stringPreferencesKey("budget_model")
        val CARD_TIMING = stringPreferencesKey("card_timing")
    }

    val secretId: Flow<String?> = stringFlow(Keys.SECRET_ID)
    val secretKey: Flow<String?> = stringFlow(Keys.SECRET_KEY)
    val refreshToken: Flow<String?> = stringFlow(Keys.REFRESH_TOKEN)

    /** Which income the user chose to drive the pay cycle. */
    val primaryIncomeKey: Flow<String?> = stringFlow(Keys.PRIMARY_INCOME_KEY)
    val budgetModel: Flow<String?> = stringFlow(Keys.BUDGET_MODEL)
    val cardTiming: Flow<String?> = stringFlow(Keys.CARD_TIMING)

    suspend fun saveCardTiming(name: String) {
        context.dataStore.edit { prefs -> prefs[Keys.CARD_TIMING] = name }
    }

    suspend fun savePrimaryIncomeKey(key: String?) {
        context.dataStore.edit { prefs ->
            if (key == null) prefs.remove(Keys.PRIMARY_INCOME_KEY) else prefs[Keys.PRIMARY_INCOME_KEY] = key
        }
    }

    suspend fun saveBudgetModel(name: String) {
        context.dataStore.edit { prefs -> prefs[Keys.BUDGET_MODEL] = name }
    }

    val ignoredRules: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseStringSet(prefs[Keys.IGNORED_RULES]) }

    /**
     * Budget-cap warnings already sent, as "category|threshold|cycleStart".
     *
     * A cap that has been passed stays passed for the rest of the cycle, so without a record
     * of what has been said the same warning would arrive every day until payday.
     */
    val notifiedGoalWarnings: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseStringSet(prefs[Keys.NOTIFIED_GOAL_WARNINGS]) }

    /** "accountId|transactionId" of large transactions already announced. */
    val notifiedLargeTransactions: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseStringSet(prefs[Keys.NOTIFIED_LARGE_TRANSACTIONS]) }

    /** Replaces the record wholesale, so keys outside the alert window fall away. */
    suspend fun saveNotifiedLargeTransactions(keys: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFIED_LARGE_TRANSACTIONS] = JSONArray(keys.toList()).toString()
        }
    }

    /**
     * Regular payments the user has said are moves between their own accounts, by the same
     * key recurring detection groups them under. Every occurrence, past and future, follows.
     */
    val transferGroups: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseStringSet(prefs[Keys.TRANSFER_GROUPS]) }

    suspend fun setTransferGroup(key: String, isTransfer: Boolean) {
        val current = parseStringSet(context.dataStore.data.first()[Keys.TRANSFER_GROUPS]).toMutableSet()
        if (isTransfer) current.add(key) else current.remove(key)
        context.dataStore.edit { prefs -> prefs[Keys.TRANSFER_GROUPS] = JSONArray(current.toList()).toString() }
    }

    /** Adds to the set rather than replacing it, so a restore cannot undo a choice. */
    suspend fun addTransferGroups(keys: Set<String>) {
        if (keys.isEmpty()) return
        val current = parseStringSet(context.dataStore.data.first()[Keys.TRANSFER_GROUPS]).toMutableSet()
        current.addAll(keys)
        context.dataStore.edit { prefs -> prefs[Keys.TRANSFER_GROUPS] = JSONArray(current.toList()).toString() }
    }

    /** Card warnings already sent, as "card|what|statementClose". */
    val notifiedCardWarnings: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseStringSet(prefs[Keys.NOTIFIED_CARD_WARNINGS]) }

    suspend fun saveNotifiedCardWarnings(keys: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFIED_CARD_WARNINGS] = JSONArray(keys.toList()).toString()
        }
    }

    val connections: Flow<List<Connection>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseConnections(prefs[Keys.CONNECTIONS]) }

    val notificationTime: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[Keys.NOTIFICATION_TIME] ?: "21:00" }

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

    suspend fun saveConnections(connections: List<Connection>) {
        context.dataStore.edit { prefs -> prefs[Keys.CONNECTIONS] = connectionsToJson(connections) }
    }

    /** Adds to the ignored set rather than replacing it, so a restore cannot un-ignore. */
    suspend fun addIgnoredRules(keys: Set<String>) {
        if (keys.isEmpty()) return
        val current = parseStringSet(context.dataStore.data.first()[Keys.IGNORED_RULES]).toMutableSet()
        current.addAll(keys)
        context.dataStore.edit { prefs ->
            prefs[Keys.IGNORED_RULES] = JSONArray(current.toList()).toString()
        }
    }

    /** Replaces the record wholesale, so keys from finished cycles fall away with it. */
    suspend fun saveNotifiedGoalWarnings(keys: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFIED_GOAL_WARNINGS] = JSONArray(keys.toList()).toString()
        }
    }

    suspend fun setRuleIgnored(key: String, ignored: Boolean) {
        val current = parseStringSet(context.dataStore.data.first()[Keys.IGNORED_RULES]).toMutableSet()
        if (ignored) current.add(key) else current.remove(key)
        context.dataStore.edit { prefs ->
            prefs[Keys.IGNORED_RULES] = JSONArray(current.toList()).toString()
        }
    }

    /**
     * Wipes everything the "Clear all data" action promises to remove: credentials, tokens,
     * bank connections and ignored-rule keys. Notification time is a preference rather than
     * data, so it survives.
     */
    suspend fun clearAll() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.SECRET_ID)
            prefs.remove(Keys.SECRET_KEY)
            prefs.remove(Keys.REFRESH_TOKEN)
            prefs.remove(Keys.CONNECTIONS)
            prefs.remove(Keys.IGNORED_RULES)
            prefs.remove(Keys.TRANSFER_GROUPS)
            prefs.remove(Keys.PRIMARY_INCOME_KEY)
        }
    }

    suspend fun saveNotificationTime(time: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFICATION_TIME] = time
        }
    }

    /** Highest version code already announced, so a repeating check only notifies once. */
    val lastNotifiedVersionCode: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[Keys.LAST_NOTIFIED_VERSION] ?: 0 }

    suspend fun saveLastNotifiedVersionCode(versionCode: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_NOTIFIED_VERSION] = versionCode
        }
    }

    private fun parseStringSet(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        val arr = JSONArray(json)
        return LinkedHashSet<String>(arr.length()).also { out ->
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        }
    }

    companion object {
        /**
         * The grant date is written out with the rest. It used not to be, so every read
         * stamped the connection "now" - always ninety days to go, and the reminder to
         * reauthorise could never fire.
         */
        internal fun connectionsToJson(connections: List<Connection>): String {
            val arr = JSONArray()
            connections.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("institutionId", c.institutionId)
                        .put("institutionName", c.institutionName)
                        .put("requisitionId", c.requisitionId)
                        .put("accountIds", JSONArray(c.accountIds))
                        .put("createdAt", c.createdAt),
                )
            }
            return arr.toString()
        }

        /** Connections saved before the date was kept come back as unknown, not as today. */
        internal fun parseConnections(json: String?): List<Connection> {
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
                    createdAt = o.optLong("createdAt", 0L),
                )
            }
        }
    }
}