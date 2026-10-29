package com.spendroid.data

import android.content.Context
import android.content.SharedPreferences
import com.spendroid.data.db.BudgetDao
import com.spendroid.data.db.ChoiceEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * What the user has decided - payee names, contract dates, subscription choices, Penny's answers,
 * money watch, reminders - kept in the
 * database ([ChoiceEntity]) rather than files of their own: encrypted with it when it is, written
 * with it, and gone with it.
 *
 * Read as they always were, as preferences ([prefs]), and answered at once from memory - payee
 * names are looked up for every row drawn. Loaded at start before anything shows; anything that
 * reads them away from the screens waits for that first ([awaitLoaded]). Written to memory at
 * once and to the database in order, one at a time.
 *
 * On the first start with the database holding none, what the old files held is moved across,
 * and the files emptied.
 */
object Choices {

    private val stores = ConcurrentHashMap<String, ChoicePrefs>()
    private var dao: BudgetDao? = null
    @Volatile private var loaded = CompletableDeferred<Unit>()
    /** The app these belong to: a phone has one; tests start one each, and each starts afresh. */
    @Volatile private var owner: Any? = null

    /**
     * Writes, done one after another, each finished before the next starts - in the order they
     * were made. (One thread wasn't enough: a write paused on the database let the next one by.)
     */
    private val queue = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (write in queue) runCatching { write() }
        }
    }

    private fun enqueue(write: suspend () -> Unit) {
        queue.trySend(write)
    }

    /**
     * A different app than before - a new test - starts with nothing remembered. Not started for
     * it (a test of one part alone), choices are kept in memory only, and read at once.
     */
    private fun ownedBy(context: Context) {
        val app = context.applicationContext
        if (owner !== app) {
            owner = app
            dao = null
            stores.values.forEach { it.reset() }
            loaded = CompletableDeferred<Unit>().apply { complete(Unit) }
        }
    }

    /** A store's choices as they change, from once they're loaded. */
    fun flow(context: Context, name: String): kotlinx.coroutines.flow.Flow<Map<String, Any?>> {
        ownedBy(context)
        val store = prefs(name) as ChoicePrefs
        return kotlinx.coroutines.flow.flow {
            awaitLoaded()
            emitAll(store.version.map { store.all })
        }
    }

    /** The stores kept here rather than in files: by [Stores] name. */
    val NAMES: Set<String> = setOf(
        Stores.PAYEE_NAMES, Stores.CONTRACTS, Stores.SUBSCRIPTIONS,
        // 0.6.5: Penny's answers, money watch, reminders, moments.
        Stores.PENNY_ASK, Stores.PENNY_BILLS, Stores.PENNY_NEWS, Stores.PENNY_VOICE,
        Stores.MONEY_WATCH, Stores.MANUAL_REMINDERS, Stores.MOMENTS,
        // 0.6.6: what the settings store held that's the user's - notification reading, bills left
        // out, transfers, the cycle's income, alerts already sent ([SecretsStore.MOVED]).
        Stores.DECISIONS,
        // 0.7.2: renewals.
        Stores.RENEWALS,
    )

    /** Where a store's choices were before, other than a preferences file: read, then let go. */
    class Import(val read: suspend () -> Map<String, Any?>, val done: suspend () -> Unit)

    fun prefs(context: Context, name: String): SharedPreferences {
        ownedBy(context)
        return prefs(name)
    }

    private fun prefs(name: String): SharedPreferences {
        require(name in NAMES) { "$name isn't kept in the database" }
        return stores.getOrPut(name) { ChoicePrefs(name) }
    }

    /** Loads every choice, moving any still in the old files across; [then] once they're in. */
    fun start(context: Context, dao: BudgetDao, imports: Map<String, Import> = emptyMap(), then: () -> Unit = {}) {
        this.dao = dao
        // Once per process on a phone; afresh each time a test starts the app.
        loaded = CompletableDeferred()
        owner = context.applicationContext
        stores.values.forEach { it.reset() }
        val loading = loaded
        enqueue {
            runCatching {
                val rows = dao.choices().groupBy { it.store }
                NAMES.forEach { name ->
                    val store = prefs(name) as ChoicePrefs
                    val held = rows[name].orEmpty()
                    if (held.isEmpty()) {
                        // The first start since they moved: what the file - or the settings store -
                        // held comes across, and only then is let go of there.
                        val import = imports[name]
                        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                        val old = import?.read?.invoke() ?: legacy.all
                        if (old.isNotEmpty()) {
                            val moved = old.mapNotNull { (key, value) -> encode(value)?.let { ChoiceEntity(name, key, it) } }
                            dao.upsertChoices(moved)
                            store.fill(old)
                            if (import != null) import.done() else legacy.edit().clear().commit()
                        }
                    } else {
                        store.fill(held.associate { it.key to decode(it.value) })
                    }
                }
            }.onFailure { CrashLog.record(context, "phone · choices didn't load", CrashLog.stackOf(it)) }
            loading.complete(Unit)
            then()
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    /**
     * Once every write so far is in the database: for work that may end before the writer gets to
     * it - a notification's button, a job in the background - after which the app can be closed.
     */
    suspend fun flush() {
        awaitLoaded()
        val done = CompletableDeferred<Unit>()
        enqueue { done.complete(Unit) }
        done.await()
    }

    val isLoaded: Boolean get() = loaded.isCompleted

    // ---- in the database: each value with its type, as the backup writes them -------------------

    internal fun encode(value: Any?): String? = when (value) {
        is String -> JSONObject().put("t", "s").put("v", value)
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.filterIsInstance<String>()))
        else -> null
    }?.toString()

    internal fun decode(stored: String): Any? = runCatching {
        val o = JSONObject(stored)
        when (o.optString("t")) {
            "s" -> o.optString("v")
            "b" -> o.optBoolean("v")
            "i" -> o.optInt("v")
            "l" -> o.optLong("v")
            "f" -> o.optDouble("v").toFloat()
            "set" -> o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            else -> null
        }
    }.getOrNull()

    private fun persist(store: String, changes: Map<String, Any?>, cleared: Boolean) {
        val dao = dao ?: return
        enqueue {
            if (cleared) dao.deleteChoices(store)
            changes.forEach { (key, value) ->
                val encoded = encode(value)
                if (encoded == null) dao.deleteChoice(store, key) else dao.upsertChoice(ChoiceEntity(store, key, encoded))
            }
        }
    }

    /** One store, as preferences: answered from memory, written through to the database. */
    private class ChoicePrefs(private val name: String) : SharedPreferences {
        private val values = ConcurrentHashMap<String, Any>()
        /** Changed before the load finished: the load mustn't put back what was there. */
        private val touched = ConcurrentHashMap.newKeySet<String>()
        private val listeners = java.util.Collections.newSetFromMap(ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>())

        /** Counts changes, for [flow]: a new number each time anything here changes. */
        val version = kotlinx.coroutines.flow.MutableStateFlow(0)

        fun reset() {
            values.clear()
            touched.clear()
            version.value++
        }

        fun fill(from: Map<String, Any?>) {
            from.forEach { (key, value) -> if (value != null && key !in touched) values.putIfAbsent(key, value) }
            version.value++
        }

        override fun getAll(): Map<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (values[key] as? Set<String>)?.toSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += listener }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= listener }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                if (!isLoaded) touched += changes.keys
                if (clear) { if (!isLoaded) touched += values.keys; values.clear() }
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                persist(name, changes.toMap(), clear)
                version.value++
                changes.keys.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@ChoicePrefs, key) } }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
