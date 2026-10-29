package com.spendroid.data

import android.content.Context
import androidx.core.content.edit
import com.spendroid.domain.Renewals
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The renewals the user has added ([Renewals.Renewal]), each kept whole under its own id - in the
 * database with the rest of their choices.
 */
class RenewalStore(context: Context) {

    private val prefs = Stores.prefs(context, Stores.RENEWALS)

    fun all(): List<Renewals.Renewal> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith(PREFIX)) return@mapNotNull null
        runCatching { read(JSONObject(v as String)) }.getOrNull()
    }.sortedBy { it.ends }

    fun get(id: String): Renewals.Renewal? = all().firstOrNull { it.id == id }

    fun save(r: Renewals.Renewal) = prefs.edit { putString(PREFIX + r.id, write(r).toString()) }

    fun remove(id: String) = prefs.edit { remove(PREFIX + id) }

    /** A new one's id: unique enough, and never reused. */
    fun newId(): String = java.util.UUID.randomUUID().toString().take(12)

    // ---- what the answers do -------------------------------------------------------------------

    /** "Remind me in 2 weeks". */
    fun snooze(id: String, today: LocalDate = LocalDate.now()) = update(id) { it.copy(snoozedUntil = today.plusWeeks(2)) }

    /** "Sorted": nothing more said about it until its next end date. */
    fun sorted(id: String) = update(id) { it.copy(sorted = true) }

    /** The cover now ends on [ends]: reminded afresh before it. */
    fun setEnds(id: String, ends: LocalDate) = update(id) { it.copy(ends = ends, sorted = false, snoozedUntil = null) }

    /** [payment] was it renewing, and the new cover ends on [ends]. */
    fun renewed(id: String, payment: Renewals.Payment, ends: LocalDate) = update(id) { r ->
        r.copy(
            ends = ends,
            sorted = false,
            snoozedUntil = null,
            renewedBy = (listOf(payment) + r.renewedBy.filter { it.key != payment.key }).sortedByDescending { it.date },
            // Paid once a year, the payment is what it cost; paid monthly, it's one instalment.
            lastMinor = if (r.paid == Renewals.Paid.YEARLY) payment.amountMinor else r.lastMinor,
        )
    }

    /** [paymentKey] wasn't it: not asked about again. */
    fun notIt(id: String, paymentKey: String) = update(id) { it.copy(notIt = it.notIt + paymentKey) }

    private fun update(id: String, change: (Renewals.Renewal) -> Renewals.Renewal) {
        get(id)?.let { save(change(it)) }
    }

    private companion object {
        const val PREFIX = "renewal:"

        fun write(r: Renewals.Renewal) = JSONObject().apply {
            put("id", r.id)
            put("name", r.name)
            put("ends", r.ends.toString())
            put("remind", r.remind.name)
            put("paid", r.paid.name)
            r.lastMinor?.let { put("last", it) }
            r.snoozedUntil?.let { put("snoozed", it.toString()) }
            put("sorted", r.sorted)
            put("renewedBy", JSONArray().apply {
                r.renewedBy.forEach { p ->
                    put(JSONObject().put("key", p.key).put("date", p.date.toString()).put("payee", p.payee).put("amount", p.amountMinor))
                }
            })
            put("notIt", JSONArray(r.notIt.toList()))
        }

        fun read(o: JSONObject) = Renewals.Renewal(
            id = o.getString("id"),
            name = o.getString("name"),
            ends = LocalDate.parse(o.getString("ends")),
            remind = runCatching { Renewals.Remind.valueOf(o.getString("remind")) }.getOrDefault(Renewals.Remind.SIX_WEEKS),
            paid = runCatching { Renewals.Paid.valueOf(o.getString("paid")) }.getOrDefault(Renewals.Paid.YEARLY),
            lastMinor = if (o.has("last")) o.getLong("last") else null,
            snoozedUntil = o.optString("snoozed").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
            sorted = o.optBoolean("sorted"),
            renewedBy = o.optJSONArray("renewedBy")?.let { a ->
                (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    Renewals.Payment(p.getString("key"), LocalDate.parse(p.getString("date")), p.getString("payee"), p.getLong("amount"))
                }
            }.orEmpty(),
            notIt = o.optJSONArray("notIt")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
        )
    }
}
