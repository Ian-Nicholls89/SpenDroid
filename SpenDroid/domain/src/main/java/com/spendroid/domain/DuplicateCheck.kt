package com.spendroid.domain

import com.spendroid.data.db.DuplicateCheckEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * "Same transaction?" - booked rows the bank has stopped listing.
 *
 * A sync keeps every booked row it stored, so when a bank re-issues a transaction under a new id
 * the old row stays and the new one arrives beside it: the same money, counted twice. Nothing
 * here decides that on its own. A row the bank has left out of two syncs in a row, though its
 * date is inside what the bank sent, is put to the user - paired with a new row of the same
 * amount if there is one, and that new row is held out of the budget until they answer.
 */
object DuplicateCheck {

    enum class State {
        /** Missed once; could be a hiccup. */
        WATCHING,

        /** Put to the user. Its candidate, if any, is held out of the budget. */
        ASK,

        /** The user kept it: a different transaction, or one the bank dropped that did happen. */
        KEPT,
    }

    /** Ids of rows added from an imported file start with this, then the batch. */
    const val IMPORTED_PREFIX = "imp:"

    /** Syncs in a row a row must be missing from before the user is asked. */
    const val MISSED_SYNCS = 2

    /** Rows younger than this are left alone: banks still move recent ones about. */
    const val SETTLE_DAYS = 2L

    /** How far apart the old row and its new copy may be dated. */
    const val CANDIDATE_DAYS = 5L

    /** A check still waiting on the user, and what it concerns. */
    data class Question(
        val check: DuplicateCheckEntity,
        val vanished: TransactionEntity,
        /** The row it may now be, held out of the budget meanwhile; null if there is none. */
        val candidate: TransactionEntity?,
    )

    /**
     * The checks for one account after a sync: [stored] is what the account held before it,
     * [fetchedBooked] the booked rows the bank just sent, [checks] this account's checks so far.
     */
    fun afterSync(
        stored: List<TransactionEntity>,
        fetchedBooked: List<TransactionEntity>,
        checks: List<DuplicateCheckEntity>,
        today: LocalDate,
    ): List<DuplicateCheckEntity> {
        val fetchedById = fetchedBooked.associateBy { it.transactionId }
        // Imported history is the user's own file, not the bank's word, and lies outside what the bank sends.
        val storedBooked = stored.filter { !it.isPending && !it.transactionId.startsWith(IMPORTED_PREFIX) }
        val storedIds = storedBooked.mapTo(HashSet()) { it.transactionId }
        // What the bank's answer covered. Rows older than this simply fell out of its window.
        val windowStart = fetchedBooked.mapNotNull { date(it) }.minOrNull()
            ?: return checks.filter { it.transactionId in storedIds }
        val fresh = fetchedBooked.filter { it.transactionId !in storedIds }
        val byId = checks.associateBy { it.transactionId }
        val claimed = checks
            .filter { it.state == State.ASK.name }
            .flatMapTo(HashSet()) { ids(it.candidates) }
            .filter { it in fetchedById }
            .toMutableSet()

        val out = mutableListOf<DuplicateCheckEntity>()
        storedBooked.sortedBy { it.bookingDate }.forEach { row ->
            val check = byId[row.transactionId]
            if (row.transactionId in fetchedById) return@forEach // listed again: nothing to ask
            if (check?.state == State.KEPT.name) {
                out += check
                return@forEach
            }
            val on = date(row) ?: return@forEach
            if (on.isBefore(windowStart) || on.isAfter(today.minusDays(SETTLE_DAYS))) {
                check?.let { out += it }
                return@forEach
            }
            when (check?.state) {
                null -> out += DuplicateCheckEntity(
                    accountId = row.accountId,
                    transactionId = row.transactionId,
                    missedSyncs = 1,
                    candidates = join(nearby(row, fresh).map { it.transactionId }),
                    state = State.WATCHING.name,
                )
                State.WATCHING.name -> {
                    val seen = (ids(check.candidates) + nearby(row, fresh).map { it.transactionId }).distinct()
                    val next = check.copy(missedSyncs = check.missedSyncs + 1, candidates = join(seen))
                    out += if (next.missedSyncs >= MISSED_SYNCS) {
                        choose(next.copy(state = State.ASK.name), row, fetchedById, claimed)
                    } else {
                        next
                    }
                }
                else -> out += check.copy(
                    missedSyncs = check.missedSyncs + 1,
                    // A candidate the bank has since dropped too is no longer one.
                    candidates = join(ids(check.candidates).filter { it in fetchedById }),
                )
            }
        }
        return out
    }

    /**
     * The one new row the vanished one most likely became, or none: those that arrived while it
     * was missing, not claimed by another question, nearest in date and then closest in payee.
     */
    private fun choose(
        check: DuplicateCheckEntity,
        row: TransactionEntity,
        fetchedById: Map<String, TransactionEntity>,
        claimed: MutableSet<String>,
    ): DuplicateCheckEntity {
        val on = date(row)
        val best = ids(check.candidates)
            .filter { it !in claimed }
            .mapNotNull { fetchedById[it] }
            .filter { it.amountMinor == row.amountMinor && it.currency == row.currency }
            .minWithOrNull(
                compareBy<TransactionEntity> { c -> date(c)?.let { on?.let { o -> abs(ChronoUnit.DAYS.between(o, it)) } } ?: Long.MAX_VALUE }
                    .thenByDescending { c -> sharedWords(c.payee, row.payee) },
            )
        best?.let { claimed += it.transactionId }
        return check.copy(candidates = best?.transactionId.orEmpty())
    }

    /** Rows new in this sync of the same amount, dated close enough to be a re-issue. */
    private fun nearby(row: TransactionEntity, fresh: List<TransactionEntity>): List<TransactionEntity> {
        val on = date(row) ?: return emptyList()
        return fresh.filter { c ->
            c.amountMinor == row.amountMinor && c.currency == row.currency &&
                date(c)?.let { abs(ChronoUnit.DAYS.between(on, it)) <= CANDIDATE_DAYS } == true
        }
    }

    /** Keys ("accountId|transactionId") of rows held out of the budget while a question is open. */
    fun heldKeys(checks: List<DuplicateCheckEntity>): Set<String> =
        checks.filter { it.state == State.ASK.name }
            .flatMap { c -> ids(c.candidates).map { "${c.accountId}|$it" } }
            .toSet()

    /** The open questions, with the rows they are about; checks whose row has gone are skipped. */
    fun questions(checks: List<DuplicateCheckEntity>, transactions: List<TransactionEntity>): List<Question> {
        val byKey = transactions.associateBy { "${it.accountId}|${it.transactionId}" }
        return checks.filter { it.state == State.ASK.name }.mapNotNull { c ->
            val vanished = byKey["${c.accountId}|${c.transactionId}"] ?: return@mapNotNull null
            Question(c, vanished, ids(c.candidates).firstNotNullOfOrNull { byKey["${c.accountId}|$it"] })
        }
    }

    private fun sharedWords(a: String, b: String): Int {
        fun words(s: String) = s.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 }.toSet()
        return (words(a) intersect words(b)).size
    }

    private fun date(tx: TransactionEntity): LocalDate? = runCatching { LocalDate.parse(tx.bookingDate) }.getOrNull()

    // A new line apart: a bank's own ids may hold spaces.
    private fun ids(s: String): List<String> = s.split('\n').filter { it.isNotEmpty() }

    private fun join(ids: List<String>): String = ids.joinToString("\n")
}
