package com.spendroid.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.spendroid.BudgetApplication
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.DuplicateCheck
import com.spendroid.domain.NotificationSpend
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Older history from a bank's own file, sent from the page. The page reads the file and sends the
 * rows; here they are checked against what is stored, and nothing is added until the user allows
 * it on the phone. Each import is one batch that can be undone.
 */
object WebImport {

    data class Row(val date: LocalDate, val amountMinor: Long, val payee: String)

    /** What sending a file would do: how many rows are new, and why the rest would be skipped. */
    data class Check(val toAdd: List<Row>, val alreadyThere: Int, val coveredByBank: Int, val from: LocalDate?)

    enum class Status { WAITING, ALLOWED, CANCELLED, EXPIRED }

    private class Pending(
        val id: String,
        val accountId: String,
        val fileName: String,
        val rows: List<Row>,
        val layout: JSONObject?,
        val at: Long,
        @Volatile var status: Status = Status.WAITING,
    )

    private val pending = mutableMapOf<String, Pending>()

    /**
     * Rows already stored - the same amount within a day, one for one - are skipped; and so is
     * anything dated within what the bank itself already sends for the account, since it will keep
     * sending those under its own ids and the two would double up.
     */
    suspend fun check(context: Context, accountId: String, rows: List<Row>): Check {
        val repo = (context.applicationContext as BudgetApplication).repository
        val stored = repo.storedTransactions(accountId).filter { !it.isPending }
        val fromBank = stored
            .filter { !it.transactionId.startsWith(DuplicateCheck.IMPORTED_PREFIX) && !it.transactionId.startsWith(NotificationSpend.SEEN_PREFIX) }
            .mapNotNull { runCatching { LocalDate.parse(it.bookingDate) }.getOrNull() }
            .minOrNull()
        val (covered, older) = rows.partition { fromBank != null && !it.date.isBefore(fromBank) }
        val unmatched = stored.mapNotNull { s -> runCatching { LocalDate.parse(s.bookingDate) }.getOrNull()?.let { it to s.amountMinor } }.toMutableList()
        val toAdd = mutableListOf<Row>()
        var already = 0
        older.forEach { r ->
            val twin = unmatched.indexOfFirst { (d, a) -> a == r.amountMinor && kotlin.math.abs(ChronoUnit.DAYS.between(d, r.date)) <= 1 }
            if (twin >= 0) {
                unmatched.removeAt(twin)
                already++
            } else {
                toAdd += r
            }
        }
        return Check(toAdd, already, covered.size, fromBank)
    }

    /** Whether the phone can ask: the approval is a notification, so notifications must be allowed. */
    fun canAsk(context: Context): Boolean =
        (android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Waits for the user: a notification on the phone with Allow and Cancel. */
    suspend fun send(context: Context, accountId: String, fileName: String, rows: List<Row>, layout: JSONObject?): Pair<String, Check> {
        val checked = check(context, accountId, rows)
        val id = UUID.randomUUID().toString().take(8)
        synchronized(pending) {
            pending.values.removeAll { System.currentTimeMillis() - it.at > EXPIRY_MS }
            pending[id] = Pending(id, accountId, fileName, checked.toAdd, layout, System.currentTimeMillis())
        }
        ask(context, id, accountId, fileName, checked)
        return id to checked
    }

    fun status(id: String): Status {
        val p = synchronized(pending) { pending[id] } ?: return Status.EXPIRED
        if (p.status == Status.WAITING && System.currentTimeMillis() - p.at > EXPIRY_MS) p.status = Status.EXPIRED
        return p.status
    }

    /** The user's answer, from the notification. Allowing adds the rows as one batch. */
    fun answer(context: Context, id: String, allow: Boolean) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        val p = synchronized(pending) { pending[id] } ?: return
        if (p.status != Status.WAITING) return
        if (!allow || System.currentTimeMillis() - p.at > EXPIRY_MS) {
            p.status = if (allow) Status.EXPIRED else Status.CANCELLED
            return
        }
        p.status = Status.ALLOWED
        CoroutineScope(Dispatchers.IO).launch { add(context, p.accountId, p.fileName, p.rows, p.layout, p.id) }
    }

    /**
     * Adds checked rows to an account as one batch - Undo and Move see it as one import - and
     * remembers a layout matched by hand. Allowed from the notification, or straight from the
     * phone's own import, where the user is already holding the phone.
     */
    suspend fun add(context: Context, accountId: String, fileName: String, rows: List<Row>, layout: JSONObject?, id: String = UUID.randomUUID().toString().take(8)) {
        if (rows.isEmpty()) return
        val repo = (context.applicationContext as BudgetApplication).repository
        val currency = repo.accounts().firstOrNull { it.id == accountId }?.currency ?: "GBP"
        repo.addImported(
            rows.mapIndexed { i, r ->
                TransactionEntity(
                    accountId = accountId,
                    transactionId = "${DuplicateCheck.IMPORTED_PREFIX}$id:$i",
                    bookingDate = r.date.toString(),
                    valueDate = null,
                    amountMinor = r.amountMinor,
                    currency = currency,
                    payee = r.payee,
                    description = "Imported from $fileName",
                    isPending = false,
                    rawJson = null,
                )
            },
        )
        remember(context, Batch(id, accountId, fileName, rows.size, System.currentTimeMillis(), rows.minOf { it.date }.toString(), rows.maxOf { it.date }.toString()))
        layout?.let { saveLayout(context, it) }
        com.spendroid.widget.refreshWidgets(context)
    }

    private fun ask(context: Context, id: String, accountId: String, fileName: String, c: Check) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Imports from your computer", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks before history sent from your computer is added"
            },
        )
        val label = runCatching { kotlinx.coroutines.runBlocking { (context.applicationContext as BudgetApplication).repository.accounts() } }
            .getOrNull()?.firstOrNull { it.id == accountId }?.label ?: "the account"
        fun action(allow: Boolean) = PendingIntent.getBroadcast(
            context,
            if (allow) 1 else 2,
            Intent(context, WebImportReceiver::class.java).putExtra("id", id).putExtra("allow", allow),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Add ${c.toAdd.size} transactions to $label?")
            .setContentText("From $fileName - nothing already in SpenDroid is changed")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "From $fileName, sent from your computer. ${c.toAdd.size} to add" +
                        (if (c.alreadyThere > 0) ", ${c.alreadyThere} already in SpenDroid" else "") +
                        (if (c.coveredByBank > 0) ", ${c.coveredByBank} your bank already sends" else "") +
                        ". Nothing already in SpenDroid is changed, and it can be undone under Accounts.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Allow", action(true))
            .addAction(0, "Cancel", action(false))
            .setAutoCancel(true)
            .setTimeoutAfter(EXPIRY_MS)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    // ---- batches, for undo ---------------------------------------------------------------

    data class Batch(val id: String, val accountId: String, val fileName: String, val count: Int, val at: Long, val from: String?, val to: String?)

    fun batches(context: Context): List<Batch> {
        val arr = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("batches", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Batch(o.optString("id"), o.optString("accountId"), o.optString("fileName"), o.optInt("count"), o.optLong("at"), o.optString("from").ifBlank { null }, o.optString("to").ifBlank { null })
        }
    }

    private fun remember(context: Context, b: Batch) = saveBatches(context, batches(context) + b)

    private fun saveBatches(context: Context, all: List<Batch>) {
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject().put("id", it.id).put("accountId", it.accountId).put("fileName", it.fileName).put("count", it.count).put("at", it.at).put("from", it.from ?: "").put("to", it.to ?: "")) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("batches", arr.toString()) }
    }

    suspend fun undo(context: Context, batch: Batch) {
        (context.applicationContext as BudgetApplication).repository.undoImport(batch.accountId, batch.id)
        saveBatches(context, batches(context).filterNot { it.id == batch.id })
        com.spendroid.widget.refreshWidgets(context)
    }

    /**
     * Moves an import to the account it was meant for. Its rows are checked against that account
     * as a new import would be - one already stored there, or within what its bank sends, is
     * dropped rather than doubled - and the batch, with what is left, belongs to it after. [swap]
     * turns money in and out around, for a card's file read as if spending were money in.
     */
    suspend fun move(context: Context, batch: Batch, toAccountId: String, swap: Boolean = false): Check {
        val repo = (context.applicationContext as BudgetApplication).repository
        val prefix = "${DuplicateCheck.IMPORTED_PREFIX}${batch.id}:"
        val rows = repo.storedTransactions(batch.accountId).filter { it.transactionId.startsWith(prefix) }
        val checked = check(context, toAccountId, rows.mapNotNull { t -> runCatching { LocalDate.parse(t.bookingDate) }.getOrNull()?.let { Row(it, if (swap) -t.amountMinor else t.amountMinor, t.payee) } })
        val currency = repo.accounts().firstOrNull { it.id == toAccountId }?.currency ?: "GBP"
        val moved = checked.toAdd.mapIndexed { i, r ->
            TransactionEntity(
                accountId = toAccountId,
                transactionId = "$prefix$i",
                bookingDate = r.date.toString(),
                valueDate = null,
                amountMinor = r.amountMinor,
                currency = currency,
                payee = r.payee,
                description = "Imported from ${batch.fileName}",
                isPending = false,
                rawJson = null,
            )
        }
        repo.replaceImport(batch.accountId, batch.id, moved)
        val rest = batches(context).filterNot { it.id == batch.id }
        saveBatches(
            context,
            if (moved.isEmpty()) rest
            else rest + batch.copy(accountId = toAccountId, count = moved.size, from = checked.toAdd.minOf { it.date }.toString(), to = checked.toAdd.maxOf { it.date }.toString()),
        )
        com.spendroid.widget.refreshWidgets(context)
        return checked
    }

    // ---- layouts the user matched by hand, recognised next time by their headings ----

    fun layouts(context: Context): JSONObject =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("layouts", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private fun saveLayout(context: Context, layout: JSONObject) {
        val key = layout.optString("signature").ifBlank { return }
        val all = layouts(context).put(key, layout)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("layouts", all.toString()) }
    }

    private const val PREFS = "web_import"
    private const val CHANNEL = "web_import"
    private const val NOTIFICATION_ID = 5102
    private const val EXPIRY_MS = 10 * 60 * 1000L
}

/** Allow or Cancel, from the notification. */
class WebImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WebImport.answer(context, intent.getStringExtra("id") ?: return, intent.getBooleanExtra("allow", false))
    }
}
