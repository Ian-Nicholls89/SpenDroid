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
import kotlinx.coroutines.flow.distinctUntilChanged
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

/**
 * A sync that did not land, kept so the account can say so instead of quietly showing old
 * figures under a fresher account's "Updated" time.
 */
data class SyncFailure(val at: Long, val reason: Reason) {
    enum class Reason(val label: String) {
        LIMIT("bank's daily limit reached"),
        REAUTH("access needs renewing"),
        OFFLINE("no connection"),
        ERROR("the bank returned an error"),
    }
}

class SecretsStore(private val context: Context) {

    /** The user's decisions, kept in the database since 0.6.6: see [Decisions]. First, as the flows below read them. */
    private val decisions: Flow<Decisions> = Choices.flow(context, Stores.DECISIONS).map { Decisions(it) }

    private object Keys {
        val SECRET_ID = stringPreferencesKey("secret_id")
        val SECRET_KEY = stringPreferencesKey("secret_key")
        val CONNECTIONS = stringPreferencesKey("connections")
        val IGNORED_RULES = stringPreferencesKey("ignored_rules")
        val NOTIFIED_GOAL_WARNINGS = stringPreferencesKey("notified_goal_warnings")
        val NOTIFIED_LARGE_TRANSACTIONS = stringPreferencesKey("notified_large_transactions")
        val NOTIFIED_CARD_WARNINGS = stringPreferencesKey("notified_card_warnings")
        val NOTIFIED_DUPLICATES = stringPreferencesKey("notified_duplicates")
        val TRANSFER_GROUPS = stringPreferencesKey("transfer_groups")
        val SYNC_FAILURES = stringPreferencesKey("sync_failures")
        val SYNC_ALLOWANCES = stringPreferencesKey("sync_allowances")
        val GENERAL_ALLOWANCE = stringPreferencesKey("general_allowance")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val NOTIFICATION_TIME = stringPreferencesKey("notification_time")
        val LAST_NOTIFIED_VERSION = intPreferencesKey("last_notified_version_code")
        val PRIMARY_INCOME_KEY = stringPreferencesKey("primary_income_key")
        val BUDGET_MODEL = stringPreferencesKey("budget_model")
        val CARD_TIMING = stringPreferencesKey("card_timing")
        val ACCENT = stringPreferencesKey("accent")
        val ACCOUNT_COLOURS = androidx.datastore.preferences.core.booleanPreferencesKey("account_colours")
        val SPEND_SOURCES = stringPreferencesKey("spend_sources")
        val SPEND_LEARNED = stringPreferencesKey("spend_learned")
        val SPEND_PLACES = stringPreferencesKey("spend_places")
        val SPEND_READING_ON = androidx.datastore.preferences.core.booleanPreferencesKey("spend_reading_on")
        val MISDATED_REPAIRED = androidx.datastore.preferences.core.booleanPreferencesKey("misdated_repaired")
    }

    val secretId: Flow<String?> = sealedFlow(Keys.SECRET_ID)
    val secretKey: Flow<String?> = sealedFlow(Keys.SECRET_KEY)
    val refreshToken: Flow<String?> = sealedFlow(Keys.REFRESH_TOKEN)

    private fun sealedFlow(key: androidx.datastore.preferences.core.Preferences.Key<String>): Flow<String?> =
        stringFlow(key).map { stored -> stored?.let { SecretCipher.open(it) } }

    /**
     * Seals credentials saved in the clear before 3.6. Run once at start-up; after that every
     * write seals, and a value already sealed is left alone.
     */
    suspend fun sealLegacySecrets() {
        context.dataStore.edit { prefs ->
            listOf(Keys.SECRET_ID, Keys.SECRET_KEY, Keys.REFRESH_TOKEN).forEach { key ->
                val stored = prefs[key] ?: return@forEach
                if (!SecretCipher.isSealed(stored)) prefs[key] = SecretCipher.seal(stored)
            }
        }
    }

    /** Which income the user chose to drive the pay cycle. */
    val primaryIncomeKey: Flow<String?> = decisions.map { it[Keys.PRIMARY_INCOME_KEY] }.distinctUntilChanged()
    val budgetModel: Flow<String?> = stringFlow(Keys.BUDGET_MODEL)
    val cardTiming: Flow<String?> = stringFlow(Keys.CARD_TIMING)

    /** The app's accent colour, by [com.spendroid.ui.theme.Accent] name. */
    val accent: Flow<String?> = stringFlow(Keys.ACCENT)

    suspend fun saveAccent(name: String) {
        context.dataStore.edit { prefs -> prefs[Keys.ACCENT] = name }
    }

    /** Whether each account wears its own colour. On unless turned off. */
    val accountColours: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[Keys.ACCOUNT_COLOURS] ?: true }

    suspend fun saveAccountColours(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.ACCOUNT_COLOURS] = on }
    }

    suspend fun saveCardTiming(name: String) {
        context.dataStore.edit { prefs -> prefs[Keys.CARD_TIMING] = name }
    }

    suspend fun savePrimaryIncomeKey(key: String?) {
        decide { prefs ->
            if (key == null) prefs.remove(Keys.PRIMARY_INCOME_KEY) else prefs[Keys.PRIMARY_INCOME_KEY] = key
        }
    }

    suspend fun saveBudgetModel(name: String) {
        context.dataStore.edit { prefs -> prefs[Keys.BUDGET_MODEL] = name }
    }

    val ignoredRules: Flow<Set<String>> = decisions
        .map { prefs -> parseStringSet(prefs[Keys.IGNORED_RULES]) }
        .distinctUntilChanged()

    /**
     * Budget-cap warnings already sent, as "category|threshold|cycleStart".
     *
     * A cap that has been passed stays passed for the rest of the cycle, so without a record
     * of what has been said the same warning would arrive every day until payday.
     */
    val notifiedGoalWarnings: Flow<Set<String>> = decisions
        .map { prefs -> parseStringSet(prefs[Keys.NOTIFIED_GOAL_WARNINGS]) }
        .distinctUntilChanged()

    /** "accountId|transactionId" of large transactions already announced. */
    val notifiedLargeTransactions: Flow<Set<String>> = decisions
        .map { prefs -> parseStringSet(prefs[Keys.NOTIFIED_LARGE_TRANSACTIONS]) }
        .distinctUntilChanged()

    /** Replaces the record wholesale, so keys outside the alert window fall away. */
    suspend fun saveNotifiedLargeTransactions(keys: Set<String>) {
        decide { prefs ->
            prefs[Keys.NOTIFIED_LARGE_TRANSACTIONS] = JSONArray(keys.toList()).toString()
        }
    }

    /**
     * Regular payments the user has said are moves between their own accounts, by the same
     * key recurring detection groups them under. Every occurrence, past and future, follows.
     */
    val transferGroups: Flow<Set<String>> = decisions
        .map { prefs -> parseStringSet(prefs[Keys.TRANSFER_GROUPS]) }
        .distinctUntilChanged()

    suspend fun setTransferGroup(key: String, isTransfer: Boolean) =
        updateStringSet(Keys.TRANSFER_GROUPS) { if (isTransfer) it + key else it - key }

    /** Adds to the set rather than replacing it, so a restore cannot undo a choice. */
    suspend fun addTransferGroups(keys: Set<String>) {
        if (keys.isEmpty()) return
        updateStringSet(Keys.TRANSFER_GROUPS) { it + keys }
    }

    /**
     * Reads and writes a stored set in one step. Read first and written after, two changes
     * made close together each started from the same set, and the second undid the first.
     */
    private suspend fun updateStringSet(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        change: (Set<String>) -> Set<String>,
    ) {
        decide { prefs ->
            prefs[key] = JSONArray(change(parseStringSet(prefs[key])).toList()).toString()
        }
    }

    /** Each account's last reported allowance, per scope the bank rations. */
    val syncAllowances: Flow<Map<String, Map<SyncAllowance.Scope, SyncAllowance.Reading>>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseAllowances(prefs[Keys.SYNC_ALLOWANCES]) }

    /** Merges fresh readings in, keeping scopes this sync did not touch. */
    suspend fun saveAllowances(accountId: String, readings: Map<SyncAllowance.Scope, SyncAllowance.Reading>) {
        if (readings.isEmpty()) return
        context.dataStore.edit { prefs ->
            val all = parseAllowances(prefs[Keys.SYNC_ALLOWANCES]).toMutableMap()
            all[accountId] = all[accountId].orEmpty() + readings
            prefs[Keys.SYNC_ALLOWANCES] = JSONObject().also { root ->
                all.forEach { (id, scopes) ->
                    root.put(id, JSONObject().also { o ->
                        scopes.forEach { (scope, r) -> o.put(scope.name, readingJson(r)) }
                    })
                }
            }.toString()
        }
    }

    /** The bank apps whose notifications are read, and the accounts each speaks for. */
    val spendSources: Flow<List<com.spendroid.domain.NotificationSpend.Source>> = decisions
        .map { prefs -> parseSpendSources(prefs[Keys.SPEND_SOURCES]) }
        .distinctUntilChanged()

    suspend fun saveSpendSources(sources: List<com.spendroid.domain.NotificationSpend.Source>) {
        decide { prefs ->
            prefs[Keys.SPEND_SOURCES] = JSONArray(
                sources.map { s ->
                    JSONObject()
                        .put("package", s.packageName)
                        .put("label", s.label)
                        .put("accounts", JSONArray(s.accountIds))
                        .put("default", s.defaultAccountId ?: JSONObject.NULL)
                        .put("unsure", s.unsure.name)
                },
            ).toString()
        }
    }

    private fun parseSpendSources(json: String?): List<com.spendroid.domain.NotificationSpend.Source> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val accounts = o.optJSONArray("accounts")
            com.spendroid.domain.NotificationSpend.Source(
                packageName = o.optString("package").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                label = o.optString("label"),
                accountIds = (0 until (accounts?.length() ?: 0)).map { accounts!!.getString(it) },
                defaultAccountId = if (o.isNull("default")) null else o.optString("default"),
                // Before 4.8 there was no choice: the starred account was the fallback, and it
                // now becomes where a new shop is guessed to be.
                unsure = com.spendroid.domain.NotificationSpend.Unsure.entries.firstOrNull { it.name == o.optString("unsure") }
                    ?: com.spendroid.domain.NotificationSpend.Unsure.SMART,
            )
        }
    }

    /**
     * Where the user said a shop's payments go, by [com.spendroid.domain.NotificationSpend.placeKey]:
     * "Always put Tesco here".
     */
    val spendPlaces: Flow<Map<String, String>> = decisions
        .map { prefs ->
            val o = prefs[Keys.SPEND_PLACES]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            o.keys().asSequence().associateWith { o.getString(it) }
        }
        .distinctUntilChanged()

    suspend fun setSpendPlace(key: String, accountId: String?) {
        decide { prefs ->
            val o = prefs[Keys.SPEND_PLACES]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            if (accountId == null) o.remove(key) else o.put(key, accountId)
            prefs[Keys.SPEND_PLACES] = o.toString()
        }
    }

    /** Wordings the user taught from their own notifications. */
    val spendLearned: Flow<List<com.spendroid.domain.NotificationSpend.Learned>> = decisions
        .map { prefs ->
            val array = prefs[Keys.SPEND_LEARNED]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                com.spendroid.domain.NotificationSpend.Learned(o.optString("source"), com.spendroid.domain.NotificationSpend.repairPattern(o.optString("pattern")), o.optBoolean("count"))
            }
        }
        .distinctUntilChanged()

    suspend fun updateSpendLearned(change: (List<com.spendroid.domain.NotificationSpend.Learned>) -> List<com.spendroid.domain.NotificationSpend.Learned>) {
        decide { prefs ->
            val array = prefs[Keys.SPEND_LEARNED]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            val current = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                com.spendroid.domain.NotificationSpend.Learned(o.optString("source"), com.spendroid.domain.NotificationSpend.repairPattern(o.optString("pattern")), o.optBoolean("count"))
            }
            prefs[Keys.SPEND_LEARNED] = JSONArray(
                change(current).map { JSONObject().put("source", it.source).put("pattern", it.pattern).put("count", it.count) },
            ).toString()
        }
    }

    /** Whether spending read from notifications is counted. On once set up; the user can pause it. */
    val spendReadingOn: Flow<Boolean> = decisions
        .map { prefs -> prefs[Keys.SPEND_READING_ON] ?: true }
        .distinctUntilChanged()

    suspend fun setSpendReadingOn(on: Boolean) {
        decide { prefs -> prefs[Keys.SPEND_READING_ON] = on }
    }

    /** Whether 5.5.3's repair - seen spends paired with a row dated before them - has been run. */
    val misdatedRepaired: Flow<Boolean> = decisions
        .map { prefs -> prefs[Keys.MISDATED_REPAIRED] ?: false }
        .distinctUntilChanged()

    suspend fun setMisdatedRepaired() {
        decide { prefs -> prefs[Keys.MISDATED_REPAIRED] = true }
    }

    /** GoCardless's own limit on all calls, as last reported. */
    val generalAllowance: Flow<SyncAllowance.Reading?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[Keys.GENERAL_ALLOWANCE]?.let { runCatching { readingFrom(JSONObject(it)) }.getOrNull() } }

    suspend fun saveGeneralAllowance(reading: SyncAllowance.Reading) {
        context.dataStore.edit { prefs -> prefs[Keys.GENERAL_ALLOWANCE] = readingJson(reading).toString() }
    }

    private fun readingJson(r: SyncAllowance.Reading): JSONObject =
        JSONObject()
            .put("limit", r.limit ?: JSONObject.NULL)
            .put("remaining", r.remaining)
            .put("resetAt", r.resetAt)
            .put("observedAt", r.observedAt)

    private fun readingFrom(r: JSONObject): SyncAllowance.Reading =
        SyncAllowance.Reading(
            limit = if (r.isNull("limit")) null else r.optInt("limit"),
            remaining = r.optInt("remaining"),
            resetAt = r.optLong("resetAt"),
            observedAt = r.optLong("observedAt"),
        )

    private fun parseAllowances(json: String?): Map<String, Map<SyncAllowance.Scope, SyncAllowance.Reading>> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = JSONObject(json)
        return root.keys().asSequence().associateWith { id ->
            val o = root.optJSONObject(id) ?: JSONObject()
            o.keys().asSequence().mapNotNull { name ->
                val scope = runCatching { SyncAllowance.Scope.valueOf(name) }.getOrNull() ?: return@mapNotNull null
                val r = o.optJSONObject(name) ?: return@mapNotNull null
                scope to readingFrom(r)
            }.toMap()
        }
    }

    /** The last sync that failed for each account, and why, cleared when one succeeds. */
    val syncFailures: Flow<Map<String, SyncFailure>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseFailures(prefs[Keys.SYNC_FAILURES]) }

    suspend fun setSyncFailure(accountId: String, failure: SyncFailure?) {
        context.dataStore.edit { prefs ->
            val current = parseFailures(prefs[Keys.SYNC_FAILURES]).toMutableMap()
            if (failure == null) current.remove(accountId) else current[accountId] = failure
            prefs[Keys.SYNC_FAILURES] = JSONObject().also { o ->
                current.forEach { (id, f) -> o.put(id, JSONObject().put("at", f.at).put("reason", f.reason.name)) }
            }.toString()
        }
    }

    private fun parseFailures(json: String?): Map<String, SyncFailure> {
        if (json.isNullOrBlank()) return emptyMap()
        val o = JSONObject(json)
        return o.keys().asSequence().mapNotNull { id ->
            val f = o.optJSONObject(id) ?: return@mapNotNull null
            val reason = runCatching { SyncFailure.Reason.valueOf(f.optString("reason")) }.getOrNull() ?: return@mapNotNull null
            id to SyncFailure(f.optLong("at"), reason)
        }.toMap()
    }

    /** Card warnings already sent, as "card|what|statementClose". */
    val notifiedCardWarnings: Flow<Set<String>> = decisions
        .map { prefs -> parseStringSet(prefs[Keys.NOTIFIED_CARD_WARNINGS]) }
        .distinctUntilChanged()

    /** "Same transaction?" questions already announced, as "accountId|transactionId". */
    val notifiedDuplicates: Flow<Set<String>> = decisions
        .map { prefs -> parseStringSet(prefs[Keys.NOTIFIED_DUPLICATES]) }
        .distinctUntilChanged()

    suspend fun saveNotifiedDuplicates(keys: Set<String>) {
        decide { prefs -> prefs[Keys.NOTIFIED_DUPLICATES] = JSONArray(keys.toList()).toString() }
    }

    suspend fun saveNotifiedCardWarnings(keys: Set<String>) {
        decide { prefs ->
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
            prefs[Keys.SECRET_ID] = SecretCipher.seal(id)
            prefs[Keys.SECRET_KEY] = SecretCipher.seal(key)
            prefs.remove(Keys.REFRESH_TOKEN) // clear old token on new credentials
        }
    }

    suspend fun saveRefreshToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REFRESH_TOKEN] = SecretCipher.seal(token)
        }
    }

    suspend fun saveConnections(connections: List<Connection>) {
        context.dataStore.edit { prefs -> prefs[Keys.CONNECTIONS] = connectionsToJson(connections) }
    }

    /** Changes the stored connections in one step, so two changes at once cannot lose one. */
    suspend fun updateConnections(change: (List<Connection>) -> List<Connection>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONNECTIONS] = connectionsToJson(change(parseConnections(prefs[Keys.CONNECTIONS])))
        }
    }

    /** Adds to the ignored set rather than replacing it, so a restore cannot un-ignore. */
    suspend fun addIgnoredRules(keys: Set<String>) {
        if (keys.isEmpty()) return
        updateStringSet(Keys.IGNORED_RULES) { it + keys }
    }

    /** Replaces the record wholesale, so keys from finished cycles fall away with it. */
    suspend fun saveNotifiedGoalWarnings(keys: Set<String>) {
        decide { prefs ->
            prefs[Keys.NOTIFIED_GOAL_WARNINGS] = JSONArray(keys.toList()).toString()
        }
    }

    suspend fun setRuleIgnored(key: String, ignored: Boolean) =
        updateStringSet(Keys.IGNORED_RULES) { if (ignored) it + key else it - key }

    /**
     * Wipes everything the "Clear all data" action promises to remove: credentials, tokens,
     * bank connections and ignored-rule keys. Notification time is a preference rather than
     * data, so it survives.
     */
    suspend fun clearAll() {
        decide { prefs ->
            prefs.remove(Keys.IGNORED_RULES)
            prefs.remove(Keys.TRANSFER_GROUPS)
            prefs.remove(Keys.PRIMARY_INCOME_KEY)
        }
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.SECRET_ID)
            prefs.remove(Keys.SECRET_KEY)
            prefs.remove(Keys.REFRESH_TOKEN)
            prefs.remove(Keys.CONNECTIONS)
            // About accounts that are gone; left behind, they described nothing.
            prefs.remove(Keys.SYNC_ALLOWANCES)
            prefs.remove(Keys.GENERAL_ALLOWANCE)
            prefs.remove(Keys.SYNC_FAILURES)
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

    // ---- the user's decisions, in the database since 0.6.6 ----------------------------------------

    /**
     * What this store held that's the user's - [MOVED] - read as the store's own values are, by
     * the same keys and in the same form; kept in the database ([Choices]), encrypted with it.
     */
    class Decisions(private val values: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        operator fun <T> get(key: androidx.datastore.preferences.core.Preferences.Key<T>): T? = values[key.name] as T?
    }

    /** The same, to change: as a store edit, made all at once. */
    class DecisionsEdit(private val values: MutableMap<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        operator fun <T> get(key: androidx.datastore.preferences.core.Preferences.Key<T>): T? = values[key.name] as T?
        operator fun <T> set(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) { values[key.name] = value }
        fun remove(key: androidx.datastore.preferences.core.Preferences.Key<*>) { values[key.name] = null }
    }


    private fun decisionsNow(): Map<String, Any?> = Stores.prefs(context, Stores.DECISIONS).all

    /** Read, changed and written in one step - as a store edit was - so two at once can't undo each other. */
    private suspend fun decide(change: (DecisionsEdit) -> Unit) {
        Choices.awaitLoaded()
        synchronized(DECIDING) {
            val prefs = Stores.prefs(context, Stores.DECISIONS)
            val before = prefs.all
            val after = before.toMutableMap()
            change(DecisionsEdit(after))
            val edit = prefs.edit()
            (before.keys + after.keys).forEach { key ->
                when (val value = after[key]) {
                    before[key] -> Unit
                    null -> edit.remove(key)
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                }
            }
            edit.commit()
        }
    }

    /** On the first start since they moved: what this store holds of [MOVED], to go to the database. */
    suspend fun decisionsToMove(): Map<String, Any?> =
        context.dataStore.data.first().asMap().mapKeys { it.key.name }.filterKeys { it in MOVED }

    /** For tests: a decision as releases before 0.6.6 kept it, here. */
    internal suspend fun keepAsBefore(name: String, value: Any) {
        context.dataStore.edit { prefs ->
            when (value) {
                is String -> prefs[stringPreferencesKey(name)] = value
                is Boolean -> prefs[androidx.datastore.preferences.core.booleanPreferencesKey(name)] = value
            }
        }
    }

    /** Once they're in the database: no longer kept here. */
    suspend fun forgetMovedDecisions() {
        context.dataStore.edit { prefs -> prefs.asMap().keys.filter { it.name in MOVED }.forEach { prefs.remove(it) } }
    }

    // ---- in a backup ---------------------------------------------------------------------------

    /**
     * This store's settings for a backup, each with its type. The bank link - GoCardless keys,
     * token, connections - only when [includeBank]: in an encrypted backup, never a plain file.
     */
    suspend fun exportStore(includeBank: Boolean = false): JSONObject {
        val out = JSONObject()
        val held = context.dataStore.data.first().asMap().mapKeys { it.key.name }.filterKeys { it !in MOVED } +
            decisionsNow().filterKeys { it in MOVED }
        held.forEach { (name, stored) ->
            if (stored == null) return@forEach
            if (name !in IN_BACKUP && !(includeBank && name in BANK)) return@forEach
            // Sealed by this phone's keystore, which another phone hasn't: opened for the backup.
            val value = if (name in SEALED) SecretCipher.open(stored as String) ?: return@forEach else stored
            when (value) {
                is String -> out.put(name, JSONObject().put("t", "s").put("v", value))
                is Boolean -> out.put(name, JSONObject().put("t", "b").put("v", value))
                is Int -> out.put(name, JSONObject().put("t", "i").put("v", value))
                is Long -> out.put(name, JSONObject().put("t", "l").put("v", value))
            }
        }
        return out
    }

    /**
     * A backup's settings put back. Sets - rules ignored, transfers, alerts already sent - are
     * added to, as a restore merges; everything else takes the backup's value. The bank link,
     * from an encrypted backup, is sealed again with this phone's keystore.
     */
    suspend fun restoreStore(json: JSONObject) {
        // The user's decisions to the database, as everyday writes of them go (0.6.6).
        decide { prefs ->
            json.keys().forEach { name ->
                if (name !in MOVED) return@forEach
                val o = json.optJSONObject(name) ?: return@forEach
                when (o.optString("t")) {
                    "s" -> {
                        val key = stringPreferencesKey(name)
                        val value = o.optString("v")
                        prefs[key] = if (name in MERGED) JSONArray((parseStringSet(prefs[key]) + parseStringSet(value)).toList()).toString() else value
                    }
                    "b" -> prefs[androidx.datastore.preferences.core.booleanPreferencesKey(name)] = o.optBoolean("v")
                }
            }
        }
        context.dataStore.edit { prefs ->
            json.keys().forEach { name ->
                if (name in MOVED) return@forEach
                if (name !in IN_BACKUP && name !in BANK) return@forEach
                val o = json.optJSONObject(name) ?: return@forEach
                when (o.optString("t")) {
                    "s" -> {
                        val key = stringPreferencesKey(name)
                        val value = o.optString("v")
                        prefs[key] = when {
                            name in SEALED -> SecretCipher.seal(value)
                            name in MERGED -> JSONArray((parseStringSet(prefs[key]) + parseStringSet(value)).toList()).toString()
                            else -> value
                        }
                    }
                    "b" -> prefs[androidx.datastore.preferences.core.booleanPreferencesKey(name)] = o.optBoolean("v")
                    "i" -> prefs[intPreferencesKey(name)] = o.optInt("v")
                    "l" -> prefs[androidx.datastore.preferences.core.longPreferencesKey(name)] = o.optLong("v")
                }
            }
        }
    }

    companion object {
        /** The user's decisions, kept in the database since 0.6.6 rather than here. */
        internal val MOVED = setOf(
            "ignored_rules", "transfer_groups", "primary_income_key",
            "spend_sources", "spend_learned", "spend_places", "spend_reading_on", "misdated_repaired",
            "notified_goal_warnings", "notified_large_transactions", "notified_card_warnings", "notified_duplicates",
        )

        private val DECIDING = Any()

        /** The bank link - GoCardless keys, token, connections: only in an encrypted backup. */
        internal val BANK = setOf("secret_id", "secret_key", "refresh_token", "connections")

        /** Sealed by this phone's keystore: opened for a backup, sealed again on restore. */
        internal val SEALED = setOf("secret_id", "secret_key", "refresh_token")

        /** Sets, as JSON arrays: a restore adds to them, so it can't undo a choice or resend an alert. */
        internal val MERGED = setOf("ignored_rules", "transfer_groups", "notified_goal_warnings", "notified_large_transactions", "notified_card_warnings", "notified_duplicates")

        /** Carried in every backup. */
        internal val IN_BACKUP = setOf(
            "ignored_rules", "transfer_groups", "primary_income_key", "budget_model", "card_timing",
            "notified_goal_warnings", "notified_large_transactions", "notified_card_warnings", "notified_duplicates",
            "notification_time", "last_notified_version_code", "accent", "account_colours",
            "spend_sources", "spend_learned", "spend_places", "spend_reading_on", "misdated_repaired",
        )

        /**
         * Not carried: about today, not the user's data - how many syncs GoCardless allows
         * today, and the last sync's failures. A new phone starts these afresh.
         */
        internal val NOT_CARRIED = setOf("sync_failures", "sync_allowances", "general_allowance")

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