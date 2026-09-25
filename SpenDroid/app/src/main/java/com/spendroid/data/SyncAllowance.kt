package com.spendroid.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How many syncs each account has left, as the bank reports it.
 *
 * Banks ration unattended access - as few as four calls a day per account - separately for
 * details, balances and transactions. GoCardless reports the allowance on every successful
 * call, and a refusal says when to try again. The app used to ignore both and assume four,
 * so a day of heavy use ran every account dry with nothing to say so, and a salary sat
 * "pending" for hours while the bank refused to be asked.
 */
object SyncAllowance {

    /** The kinds of data the bank rations separately. A sync needs balances and transactions. */
    enum class Scope { DETAILS, BALANCES, TRANSACTIONS }

    /** One scope's allowance as last reported. Times are epoch millis. */
    data class Reading(val limit: Int?, val remaining: Int, val resetAt: Long, val observedAt: Long)

    /** An account's allowance for a whole sync: the scarcer of balances and transactions. */
    data class Account(val limit: Int?, val remaining: Int, val resetAt: Long?)

    private val ACCOUNT_PATH = Regex("/accounts/([^/]+)/(details|balances|transactions)/?")

    /** Which account and scope a request is for, or null when it is not a rationed call. */
    fun scopeFor(path: String): Pair<String, Scope>? {
        val match = ACCOUNT_PATH.find(path) ?: return null
        val scope = when (match.groupValues[2]) {
            "details" -> Scope.DETAILS
            "balances" -> Scope.BALANCES
            else -> Scope.TRANSACTIONS
        }
        return match.groupValues[1] to scope
    }

    /**
     * The allowance a successful response reports. GoCardless documents the headers with an
     * HTTP_ prefix and underscores; both that and the conventional dashed form are read.
     */
    fun fromHeaders(header: (String) -> String?, now: Long): Reading? {
        fun read(name: String): String? =
            header("HTTP_X_RATELIMIT_ACCOUNT_SUCCESS_$name")
                ?: header("X_RATELIMIT_ACCOUNT_SUCCESS_$name")
                ?: header("X-RateLimit-Account-Success-" + name.lowercase().replaceFirstChar { it.uppercase() })
        val remaining = read("REMAINING")?.trim()?.toIntOrNull() ?: return null
        val resetSeconds = read("RESET")?.trim()?.toLongOrNull() ?: return null
        return Reading(
            limit = read("LIMIT")?.trim()?.toIntOrNull(),
            remaining = remaining,
            resetAt = now + resetSeconds * 1000L,
            observedAt = now,
        )
    }

    private val TRY_AGAIN = Regex("""try again in (\d+) seconds""", RegexOption.IGNORE_CASE)
    private val LIMIT_IS = Regex("""rate limit for this resource is (\d+)""", RegexOption.IGNORE_CASE)

    /**
     * A refusal's allowance: none left, until the time the message gives. The headers are only
     * sent on success, so this is the one place a refused call says when it will work again.
     */
    fun fromRefusal(body: String, now: Long): Reading? {
        val seconds = TRY_AGAIN.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return Reading(
            limit = LIMIT_IS.find(body)?.groupValues?.get(1)?.toIntOrNull(),
            remaining = 0,
            resetAt = now + seconds * 1000L,
            observedAt = now,
        )
    }

    /**
     * An account's allowance for a full sync, now. A scope whose reset has passed is full again
     * without waiting for a sync to say so. Null until balances or transactions has reported.
     */
    fun forAccount(readings: Map<Scope, Reading>, now: Long): Account? {
        val needed = listOfNotNull(readings[Scope.BALANCES], readings[Scope.TRANSACTIONS])
        if (needed.isEmpty()) return null
        val current = needed.map { r ->
            if (now >= r.resetAt) Account(r.limit, r.limit ?: maxOf(r.remaining, 1), null)
            else Account(r.limit, r.remaining, r.resetAt)
        }
        val scarcest = current.minBy { it.remaining }
        return scarcest.copy(
            limit = current.mapNotNull { it.limit }.minOrNull(),
            // When nothing is left, the reset that matters is the one that frees the scarce scope.
            resetAt = scarcest.resetAt ?: current.mapNotNull { it.resetAt }.minOrNull(),
        )
    }

    /** True when a sync of this account would be refused now. */
    fun exhausted(allowance: Account?, now: Long): Boolean =
        allowance != null && allowance.remaining <= 0 && (allowance.resetAt ?: 0L) > now

    /** "19:40", or "tomorrow 07:12" once it is not today. */
    fun resetLabel(resetAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(resetAt).atZone(zone)
        val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (at.toLocalDate()) {
            today -> time
            today.plusDays(1) -> "tomorrow $time"
            else -> at.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
        }
    }

    /** "in 3h 14m", "in 25m". */
    fun countdown(resetAt: Long, now: Long): String {
        val minutes = ((resetAt - now) / 60_000L).coerceAtLeast(0L)
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "in ${h}h ${m}m" else "in ${m}m"
    }

    /** The line under an account: what is left and when it refills. */
    fun summary(allowance: Account?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (allowance == null) return "Sync allowance shows after the next sync"
        val reset = allowance.resetAt?.let { resetLabel(it, now, zone) }
        if (allowance.remaining <= 0 && reset != null) {
            return "Limit reached · resets $reset (${countdown(allowance.resetAt, now)})"
        }
        val of = allowance.limit?.let { " of $it" }.orEmpty()
        val plural = if (allowance.remaining == 1 && allowance.limit == null) "" else "s"
        return "${allowance.remaining}$of sync$plural left" + (reset?.let { " · resets $it" }.orEmpty())
    }

    /**
     * When to try again for accounts the bank has refused: just after the earliest reset, when
     * that comes before the next scheduled sync - otherwise the schedule will get there first.
     */
    fun catchUpAt(resets: List<Long>, nextScheduled: Long, now: Long): Long? {
        val earliest = resets.filter { it > now }.minOrNull() ?: return null
        val at = earliest + CATCH_UP_MARGIN_MS
        return at.takeIf { it < nextScheduled - CATCH_UP_MARGIN_MS }
    }

    /** A little after the reset, so a clock a minute apart from the bank's is not refused. */
    private const val CATCH_UP_MARGIN_MS = 2 * 60_000L
}
