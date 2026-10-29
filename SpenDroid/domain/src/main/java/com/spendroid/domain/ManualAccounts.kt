package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * Accounts with no bank link, kept up to date from statement files, banking notifications and
 * the user's own balance checks. Pure logic: the repository stores what it works out.
 *
 * The balance is worked out, not reported: a balance known for certain (the anchor) - a
 * statement's closing balance, or what the user's banking app showed at a check - moved on by
 * every transaction dated after it. Payments read from notifications are left out, as a bank's
 * balance leaves them out until it reports them: the budget takes them off itself.
 */
object ManualAccounts {

    /** A balance check's entry for money gone that nothing else accounts for. */
    const val CHECK_PREFIX = "check:"

    /** The balance today: the anchor, moved on by transactions dated after it. Null with no anchor. */
    fun balance(account: AccountEntity, transactions: List<TransactionEntity>): Long? {
        val anchor = account.anchorMinor ?: return null
        val from = account.anchorDate?.let { RecurringAnalyzer.parseBookingDate(it) }
        return anchor + transactions
            .filter { it.accountId == account.id && !it.transactionId.startsWith(NotificationSpend.SEEN_PREFIX) }
            .filter { tx -> from == null || RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.isAfter(from) == true }
            .sumOf { it.amountMinor }
    }

    /** The last day an imported statement covers for [accountId]: up to it, the bank's record is in. */
    fun coveredUntil(accountId: String, transactions: List<TransactionEntity>): LocalDate? =
        transactions
            .filter { it.accountId == accountId && it.transactionId.startsWith(DuplicateCheck.IMPORTED_PREFIX) }
            .mapNotNull { RecurringAnalyzer.parseBookingDate(it.bookingDate) }
            .maxOrNull()

    /** For each manual account, the last day a statement covers it - null for none yet. */
    fun coverage(accounts: List<AccountEntity>, transactions: List<TransactionEntity>): Map<String, LocalDate?> =
        accounts.filter { it.manual }.associate { it.id to coveredUntil(it.id, transactions) }

    /**
     * Whether a statement covers the day [spend] was seen, with a few days for the bank to post
     * it: until then a payment seen on a manual account is waiting, not missing.
     */
    fun covers(coveredUntil: LocalDate?, spend: SeenSpendEntity, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        coveredUntil != null && !coveredUntil.isBefore(NotificationSpend.dateOf(spend.seenAt, zone).plusDays(POSTING_DAYS))

    /** Days a bank can take to post a card payment: a statement must reach this far past it. */
    const val POSTING_DAYS = 5L

    /** What a balance check found. */
    data class Check(
        /** What SpenDroid expected the bank to show: the balance, less payments seen but not yet listed. */
        val expectedMinor: Long,
        val typedMinor: Long,
        /** A payment guessed onto another account that may explain some of the gap. */
        val misplaced: SeenSpendEntity?,
    ) {
        /** How much less than expected: money gone that nothing accounts for. Negative for more. */
        val gapMinor: Long get() = expectedMinor - typedMinor
    }

    /**
     * Checks [account]'s balance against [typedMinor], what the user's banking app shows.
     * [elsewhere] are payments read from notifications, counted on another account but only as a
     * guess - or still waiting on an answer - that could have been from this one. The largest
     * that fits inside the gap is offered as the likely explanation.
     */
    fun check(
        account: AccountEntity,
        typedMinor: Long,
        transactions: List<TransactionEntity>,
        seen: List<SeenSpendEntity>,
        elsewhere: List<SeenSpendEntity> = emptyList(),
    ): Check {
        val expected = (balance(account, transactions) ?: typedMinor) - unlisted(account.id, seen)
        val gap = expected - typedMinor
        val misplaced = if (gap <= 0) null else elsewhere
            .filter { it.countedOn != account.id && -it.amountMinor in 1..gap }
            .maxByOrNull { -it.amountMinor }
        return Check(expected, typedMinor, misplaced)
    }

    /** Payments read from notifications, counted on [accountId] and not yet listed by a statement. */
    fun unlisted(accountId: String, seen: List<SeenSpendEntity>): Long =
        NotificationSpend.counted(seen).filter { it.countedOn == accountId }.sumOf { -it.amountMinor }

    /**
     * After a check: the new anchor - what the bank showed, plus the payments it already
     * reflects that SpenDroid still counts from notifications, so they aren't taken off twice -
     * and, when money has gone that nothing accounts for, the entry that counts it as spending.
     */
    fun afterCheck(account: AccountEntity, check: Check, seen: List<SeenSpendEntity>, today: LocalDate, id: String): Pair<AccountEntity, TransactionEntity?> {
        val anchored = account.copy(
            anchorMinor = check.typedMinor + unlisted(account.id, seen),
            anchorDate = today.toString(),
            checkedAt = System.currentTimeMillis(),
        )
        val entry = if (check.gapMinor <= 0) null else TransactionEntity(
            accountId = account.id,
            transactionId = CHECK_PREFIX + id,
            bookingDate = today.toString(),
            valueDate = null,
            amountMinor = -check.gapMinor,
            currency = account.currency,
            payee = NOT_SEEN,
            description = "Your balance was this much lower than expected: something not seen yet, like a direct debit or cash",
            isPending = false,
            rawJson = null,
        )
        return anchored to entry
    }

    const val NOT_SEEN = "Not seen (balance check)"

    /**
     * Balance-check entries a newly imported statement explains: those dated within the days it
     * covers, as the statement now shows what the money went on.
     */
    fun explained(entries: List<TransactionEntity>, from: LocalDate, to: LocalDate): List<TransactionEntity> =
        entries.filter { tx ->
            tx.transactionId.startsWith(CHECK_PREFIX) &&
                RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { !it.isBefore(from) && !it.isAfter(to) } == true
        }

    /**
     * The new anchor after an import with a closing balance: taken when it's at least as recent
     * as the one held, so an old statement never winds the balance back.
     */
    fun anchorFrom(account: AccountEntity, closingDate: LocalDate, closingMinor: Long): AccountEntity? {
        val held = account.anchorDate?.let { RecurringAnalyzer.parseBookingDate(it) }
        if (held != null && closingDate.isBefore(held)) return null
        return account.copy(anchorMinor = closingMinor, anchorDate = closingDate.toString())
    }

    /** When to ask for a balance check. */
    enum class BalanceWhen { SUNDAY, PAYDAY, NEVER }

    /** Whether a new statement is due: [everyDays] since the last one covered, or none yet. */
    fun statementDue(coveredUntil: LocalDate?, everyDays: Int, today: LocalDate): Boolean =
        everyDays > 0 && (coveredUntil == null || !today.isBefore(coveredUntil.plusDays(everyDays.toLong())))

    /**
     * Whether a balance check is due: not checked since the most recent Sunday, or since payday
     * ([cycleStart], the day this cycle's pay came).
     */
    fun balanceDue(checkedOn: LocalDate?, whenTo: BalanceWhen, today: LocalDate, cycleStart: LocalDate?): Boolean {
        val since = when (whenTo) {
            BalanceWhen.NEVER -> return false
            BalanceWhen.SUNDAY -> today.minusDays((today.dayOfWeek.value % 7).toLong())
            BalanceWhen.PAYDAY -> cycleStart ?: return false
        }
        return checkedOn == null || checkedOn.isBefore(since)
    }

    /** The day a manual account was last checked, from [AccountEntity.checkedAt]. */
    fun checkedOn(account: AccountEntity, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        account.checkedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
}
