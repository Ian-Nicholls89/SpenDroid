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
     * An account's allowance for a full sync, now. Null until balances or transactions has reported.
     *
     * Nobody documents whether a bank's allowance comes back all at once or call by call, each 24
     * hours after it was made. So a reset that has passed only promises one call back - true either
     * way - until the next sync brings the bank's own figure. A day after the reading, every call it
     * counted has come back whichever way the bank works.
     */
    fun forAccount(readings: Map<Scope, Reading>, now: Long): Account? {
        val needed = listOfNotNull(readings[Scope.BALANCES], readings[Scope.TRANSACTIONS])
        if (needed.isEmpty()) return null
        val current = needed.map { r ->
            when {
                now < r.resetAt -> Account(r.limit, r.remaining, r.resetAt)
                now >= r.observedAt + DAY_MS -> Account(r.limit, r.limit ?: maxOf(r.remaining, 1), null)
                else -> Account(r.limit, (r.remaining + 1).coerceAtMost(r.limit ?: Int.MAX_VALUE), null)
            }
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

    /** "03:14": hours and minutes until the reset. */
    fun countdown(resetAt: Long, now: Long): String {
        val minutes = ((resetAt - now + 59_999L) / 60_000L).coerceAtLeast(0L)
        return "%02d:%02d".format(minutes / 60, minutes % 60)
    }

    /** How an account stands with the bank, without counting calls whose return is a guess. */
    enum class Status { AVAILABLE, LAST_ONE, UNAVAILABLE }

    fun status(allowance: Account?, now: Long): Status = when {
        exhausted(allowance, now) -> Status.UNAVAILABLE
        allowance != null && allowance.remaining <= 1 -> Status.LAST_ONE
        else -> Status.AVAILABLE
    }

    /** The line under an account. */
    fun summary(allowance: Account?, now: Long): String = when (status(allowance, now)) {
        Status.AVAILABLE -> "Refresh available"
        Status.LAST_ONE -> "Only 1 refresh remaining"
        Status.UNAVAILABLE -> "Refresh unavailable · resets in ${countdown(allowance!!.resetAt!!, now)}"
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

    private const val DAY_MS = 24 * 60 * 60_000L
}
