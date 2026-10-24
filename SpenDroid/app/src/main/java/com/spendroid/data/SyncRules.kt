package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import com.spendroid.data.remote.AccountDetailsDto
import com.spendroid.data.remote.AccountInfoDto
import com.spendroid.data.remote.BalancesDto
import com.spendroid.domain.RecurringAnalyzer
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * The rules a sync follows, as pure functions: which stored account a fetched one is, how
 * transactions without the bank's own id get one, which pairs are transfers between the user's
 * accounts, what becomes of pending rows once booked, which edits of the user's a sync keeps, and
 * which balance is the one to show. Kept apart from [GoCardlessRepository], which does the
 * fetching and storing, so they can be read - and tested - on their own.
 */
object SyncRules {

    /**
     * Carries the user's own edits across a re-sync.
     *
     * The bank is the authority on what a transaction is - its amount, date and payee -
     * but not on what the user decided about it. Those decisions exist nowhere else and
     * cannot be re-derived, so they win over the freshly built row.
     */
    /**
     * The stored account with what the bank knows brought up to date.
     *
     * Built the other way round - a fresh row with the user's settings copied in one by
     * one - every setting added later had to be remembered here too, and the card's
     * statement and payment days were not: a sync wiped them nightly. Starting from the
     * stored row means anything not named below is kept.
     */
    internal fun refreshedAccount(existing: AccountEntity?, fetched: AccountEntity): AccountEntity =
        existing?.copy(
            institutionName = fetched.institutionName,
            currency = fetched.currency,
            balanceMinor = fetched.balanceMinor,
            lastSynced = fetched.lastSynced,
            rawBalancesJson = fetched.rawBalancesJson,
            identity = fetched.identity ?: existing.identity,
        ) ?: fetched

    /**
     * What the bank calls this account whichever consent it arrived under: the IBAN, the
     * UK sort code and number, or a card's masked number, in that order of certainty.
     * Formatting is stripped so "60-16-13" and "601613" agree.
     */
    internal fun identityFor(metadata: AccountDetailsDto, info: AccountInfoDto): String? {
        fun clean(value: String?) = value?.filter { it.isLetterOrDigit() }?.uppercase()?.takeIf { it.isNotBlank() }
        clean(info.iban ?: metadata.iban)?.let { return "iban:$it" }
        val sortCode = clean(info.sortCode)
        val number = clean(info.accountNumber)
        if (sortCode != null && number != null) return "uk:$sortCode/$number"
        clean(info.bban ?: metadata.bban)?.let { return "bban:$it" }
        clean(info.maskedPan)?.let { return "pan:$it" }
        return null
    }

    /**
     * Pairs each newly linked account with the stored account it replaces, as (old, new).
     *
     * The same bank's identity is proof, provided exactly one stored account carries it.
     * Failing that - cards often have no number the API will share - an identical name
     * will do, but only among accounts no live consent still holds, or whose consent has
     * just been shown to be the one being replaced. Anything less certain is left alone:
     * a wrong merge would move one person's history onto another's account.
     */
    internal fun matchSuccessors(
        newAccounts: List<AccountEntity>,
        others: List<AccountEntity>,
        otherConnections: List<Connection>,
    ): List<Pair<AccountEntity, AccountEntity>> {
        val pairs = mutableListOf<Pair<AccountEntity, AccountEntity>>()
        val usedOld = mutableSetOf<String>()

        for (new in newAccounts) {
            val identity = new.identity ?: continue
            val match = others
                .filter { it.institutionName == new.institutionName && it.identity == identity }
                .singleOrNull() ?: continue
            // Two new accounts claiming one old one is as ambiguous as the reverse.
            if (newAccounts.count { it.identity == identity } != 1) continue
            pairs += match to new
            usedOld += match.id
        }

        val superseded = otherConnections.filter { c -> c.accountIds.any { it in usedOld } }
        val stillHeld = otherConnections.filter { it !in superseded }.flatMap { it.accountIds }.toSet()
        val matchedNew = pairs.map { it.second.id }.toSet()

        for (new in newAccounts.filter { it.id !in matchedNew }) {
            val match = others
                .filter { old ->
                    old.id !in usedOld &&
                        old.id !in stillHeld &&
                        old.institutionName == new.institutionName &&
                        old.label == new.label &&
                        // Two different numbers are two different accounts, whatever the name.
                        (old.identity == null || new.identity == null || old.identity == new.identity)
                }
                .singleOrNull() ?: continue
            pairs += match to new
            usedOld += match.id
        }
        return pairs
    }

    /**
     * The history an account carries into its successor. Before the new consent's first
     * row, the old account is the only copy and moves across; from there on, the fresh
     * fetch is the authority, keeping what the user decided about the rows the two share.
     */
    internal fun succeededHistory(
        old: List<TransactionEntity>,
        fresh: List<TransactionEntity>,
        newId: String,
    ): List<TransactionEntity> {
        val rekeyed = old.map { it.copy(accountId = newId) }
        val start = fresh.mapNotNull { runCatching { LocalDate.parse(it.bookingDate) }.getOrNull() }.minOrNull()
            ?: return rekeyed + fresh
        val before = rekeyed.filter { tx ->
            runCatching { LocalDate.parse(tx.bookingDate) }.getOrNull()?.isBefore(start) == true
        }
        return before + preserveUserEdits(fresh, rekeyed)
    }

    /**
     * An id for a row the bank sent without one, from what it contains. The first of a kind
     * keeps the id rows have always been stored under, so nothing already stored is
     * duplicated; a second identical row on the same day is numbered rather than dropped.
     */
    /** An id for a row the bank gave none, from its date - the value date while pending - amount and payee. */
    internal fun madeUpId(bookingDate: String?, valueDate: String?, minor: Long, payee: String, seen: MutableMap<String, Int>): String {
        val date = bookingDate ?: valueDate
        val content = "$date|$minor|$payee"
        val occurrence = (seen[content] ?: 0) + 1
        seen[content] = occurrence
        return fallbackTransactionId(date, minor, payee, occurrence)
    }

    internal fun fallbackTransactionId(
        bookingDate: String?,
        minor: Long,
        payee: String,
        occurrence: Int,
    ): String {
        val base = "$bookingDate|$minor|$payee".hashCode().toString()
        return if (occurrence <= 1) base else "$base#$occurrence"
    }

    /**
     * The transactions transfer detection may pair.
     *
     * A row the user said is *not* a transfer is theirs, and pairing it anyway marked only
     * the other leg, leaving half a transfer counted and half hidden. A row they said *is*
     * one stays in: it is still one half of a move, and without it its other half has
     * nothing to pair with and is counted as spending. That was 2.24's mistake - it left
     * out every row the user had ruled on, either way, and a £500 move to savings whose
     * receiving side had been marked by hand turned into a £500 monthly commitment.
     */
    /**
     * The transactions a marked group covers. A row the user individually said is not a
     * transfer keeps their word: the narrower decision wins over the broader one.
     */
    internal fun inTransferGroups(
        transactions: List<TransactionEntity>,
        groups: Set<String>,
    ): List<TransactionEntity> {
        if (groups.isEmpty()) return emptyList()
        return transactions.filter { tx ->
            RecurringAnalyzer.groupKey(tx) in groups && !(tx.transferOverridden && !tx.isInternalTransfer)
        }
    }

    internal fun transferCandidates(
        transactions: List<TransactionEntity>,
        excludedAccounts: Set<String>,
    ): List<TransactionEntity> = transactions.filter {
        it.accountId !in excludedAccounts && !(it.transferOverridden && !it.isInternalTransfer)
    }

    /**
     * Pairs off the two halves of a move between the user's own accounts.
     *
     * The payee is deliberately not required to match. It names the *counterparty*, so the
     * two legs of the same transfer are named differently by design - the money leaves as
     * "JOINT ACCOUNT BILLS" and arrives as "BILLS NICHO". Insisting they agree meant genuine
     * transfers were never paired, and the standing order into the joint account was read as
     * a monthly salary.
     *
     * What is required instead is that the pairing be unambiguous. Only the user's own
     * accounts are visible here, so an exact opposite amount within a day is already strong
     * evidence; where more than one transaction could be the other half, the references
     * break the tie, and if they cannot, nothing is paired. A wrong pair hides real spending,
     * which is worse than leaving a transfer on show.
     */
    internal fun detectInternalTransfers(
        transactions: List<TransactionEntity>,
    ): List<Pair<TransactionEntity, TransactionEntity>> {
        // A fixed order, whatever order the accounts arrived in. It used to follow the
        // order they last synced, so the same data could pair one way at 16:26 and another
        // at 16:50 - and did, turning a transfer into a £500 commitment.
        val sorted = transactions.sortedWith(
            compareBy<TransactionEntity>({ it.bookingDate }, { it.accountId }, { it.transactionId }),
        )
        val credits = sorted.filter { it.amountMinor > 0 }.groupBy { it.amountMinor }
        val debits = sorted.filter { it.amountMinor < 0 }.groupBy { -it.amountMinor }

        fun creditsFor(debit: TransactionEntity) = credits[-debit.amountMinor].orEmpty()
            .filter { it.accountId != debit.accountId && couldOffset(debit, it) }
        fun debitsFor(credit: TransactionEntity) = debits[credit.amountMinor].orEmpty()
            .filter { it.accountId != credit.accountId && couldOffset(it, credit) }

        val pairs = mutableListOf<Pair<TransactionEntity, TransactionEntity>>()
        val used = mutableSetOf<String>()
        fun open(list: List<TransactionEntity>) = list.filter { transferKey(it) !in used }

        /**
         * The credit this debit pairs with, when the pairing is certain from both ends.
         *
         * One-sided certainty was the old rule, and it is not enough: a credit that two
         * debits could claim was taken by whichever came first, even when its reference
         * named the other. So a pair needs each side to be the other's only match - or,
         * failing that, the references to single out one pairing on both sides.
         */
        fun partner(debit: TransactionEntity): TransactionEntity? {
            val options = open(creditsFor(debit))
            options.singleOrNull()?.let { credit ->
                if (open(debitsFor(credit)).singleOrNull() == debit) return credit
            }
            val named = options.filter { referencesAgree(debit, it) }.singleOrNull() ?: return null
            val namedBack = open(debitsFor(named)).filter { referencesAgree(it, named) }.singleOrNull()
            return named.takeIf { namedBack == debit }
        }

        // Each pair made can settle another that was ambiguous only because of it.
        var progress = true
        while (progress) {
            progress = false
            for (debit in sorted) {
                if (debit.amountMinor >= 0 || transferKey(debit) in used) continue
                val credit = partner(debit) ?: continue
                pairs += debit to credit
                used += transferKey(debit)
                used += transferKey(credit)
                progress = true
            }
        }
        return pairs
    }

    private fun transferKey(tx: TransactionEntity) = "${tx.accountId}|${tx.transactionId}"

    /** Equal and opposite, same currency, close enough in time to be one movement. */
    private fun couldOffset(tx1: TransactionEntity, tx2: TransactionEntity): Boolean {
        if (tx1.amountMinor != -tx2.amountMinor || tx1.currency != tx2.currency) return false
        val date1 = try { LocalDate.parse(tx1.bookingDate) } catch (_: Exception) { return false }
        val date2 = try { LocalDate.parse(tx2.bookingDate) } catch (_: Exception) { return false }
        return Math.abs(date1.toEpochDay() - date2.toEpochDay()) <= 1
    }

    /** Whether the two sides name the same movement, used only to break a tie. */
    private fun referencesAgree(tx1: TransactionEntity, tx2: TransactionEntity): Boolean {
        val ref1 = (tx1.description ?: "").trim().lowercase()
        val ref2 = (tx2.description ?: "").trim().lowercase()
        val payee1 = tx1.payee.trim().lowercase()
        val payee2 = tx2.payee.trim().lowercase()

        if (ref1.isNotBlank() && ref1 == ref2) return true
        if (payee1.isNotBlank() && payee1 == payee2) return true
        if (ref1.isNotBlank() && ref2.isNotBlank()) {
            val a = ref1.split("[^a-z0-9]+".toRegex()).filter { it.length > 2 }.toSet()
            val b = ref2.split("[^a-z0-9]+".toRegex()).filter { it.length > 2 }.toSet()
            if (a.isNotEmpty() && a == b) return true
        }
        return false
    }

    /**
     * The pending entries the bank has not also reported as booked.
     *
     * Some banks go on listing a payment as pending after it has booked. Under the same id,
     * the pending copy was written after the booked one and replaced it, every sync - and,
     * still being listed, it was never cleared: a salary the bank showed as landed stayed
     * pending in the app. Under a new id, the stale copy sat beside the booked one for good.
     * A booked entry wins. A pending one is dropped when it shares the booked one's id, or
     * is the same amount from the same payee, booked on or within a few days of it.
     */
    internal fun stillPendingOnly(
        booked: List<TransactionEntity>,
        pending: List<TransactionEntity>,
    ): List<TransactionEntity> {
        val bookedIds = booked.mapTo(HashSet()) { it.transactionId }
        val claimed = mutableSetOf<String>()
        fun payee(tx: TransactionEntity) = tx.payee.lowercase().trim().replace(Regex("\\s+"), " ")
        // The words a name is made of, less digits and short fragments: "GREGGS 4168" and
        // "GREGGS PLC AMESBURY" share "greggs".
        fun words(tx: TransactionEntity) = payee(tx).split(Regex("[^a-z]+")).filter { it.length >= 3 }.toSet()
        fun inWindow(p: TransactionEntity, b: TransactionEntity): Boolean {
            val pendingDate = runCatching { LocalDate.parse(p.bookingDate) }.getOrNull() ?: return true
            val bookedOn = runCatching { LocalDate.parse(b.bookingDate) }.getOrNull() ?: return false
            return !bookedOn.isBefore(pendingDate.minusDays(1)) && !bookedOn.isAfter(pendingDate.plusDays(PENDING_TWIN_DAYS))
        }
        return pending.filter { p ->
            if (p.transactionId in bookedIds) return@filter false
            val candidates = booked.filter { b -> b.transactionId !in claimed && b.amountMinor == p.amountMinor && inWindow(p, b) }
            // The same name; else a name sharing a word - NatWest lists a card payment pending
            // as "GREGGS 4168" and books it as "GREGGS PLC AMESBURY", and went on listing
            // the pending one too, so a £4.25 counted twice; else the only pairing there is -
            // one pending, one booked, same amount, in the window.
            val twin = candidates.firstOrNull { payee(it) == payee(p) }
                ?: candidates.firstOrNull { (words(it) intersect words(p)).isNotEmpty() }
                ?: candidates.singleOrNull()?.takeIf {
                    pending.count { other -> other.amountMinor == p.amountMinor && inWindow(other, it) } == 1
                }
            if (twin != null) {
                claimed += twin.transactionId
                false
            } else {
                true
            }
        }
    }

    /** Whether the user has decided anything about this row that a sync must not lose. */
    private fun TransactionEntity.hasUserEdits(): Boolean =
        categoryOverride != null || transferOverridden || isCardPayment

    /** How long after a pending entry its booked version may be dated. */
    private const val PENDING_TWIN_DAYS = 5L

    /** What a refusal from the bank means, in the terms the account card uses. */
    internal fun failureReason(e: Throwable): SyncFailure.Reason = when {
        e is retrofit2.HttpException && e.code() == 429 -> SyncFailure.Reason.LIMIT
        e is retrofit2.HttpException && e.code() in setOf(401, 403, 409) -> SyncFailure.Reason.REAUTH
        e is IOException -> SyncFailure.Reason.OFFLINE
        else -> SyncFailure.Reason.ERROR
    }

    internal fun preserveUserEdits(
        fetched: List<TransactionEntity>,
        existing: List<TransactionEntity>,
    ): List<TransactionEntity> {
        if (existing.isEmpty()) return fetched
        val byId = existing.associateBy { it.transactionId }
        // A pending row often books under a new id. Matched by id alone, a category set
        // while it was pending was lost the moment it booked; its booked twin - same amount,
        // same payee, dated within the pending window - inherits the edits instead.
        val fetchedIds = fetched.mapTo(HashSet()) { it.transactionId }
        val orphanedPending = existing
            .filter { it.isPending && it.transactionId !in fetchedIds && it.hasUserEdits() }
            .toMutableList()
        fun normalised(tx: TransactionEntity) = tx.payee.lowercase().trim().replace(Regex("\\s+"), " ")
        fun twinOf(row: TransactionEntity): TransactionEntity? {
            if (row.isPending) return null
            val bookedOn = runCatching { LocalDate.parse(row.bookingDate) }.getOrNull() ?: return null
            val twin = orphanedPending.firstOrNull { p ->
                p.amountMinor == row.amountMinor && normalised(p) == normalised(row) &&
                    runCatching { LocalDate.parse(p.bookingDate) }.getOrNull()?.let { pendingOn ->
                        !bookedOn.isBefore(pendingOn.minusDays(1)) &&
                            !bookedOn.isAfter(pendingOn.plusDays(PENDING_TWIN_DAYS))
                    } ?: true
            } ?: return null
            orphanedPending.remove(twin)
            return twin
        }
        return fetched.map { row ->
            val prior = byId[row.transactionId] ?: twinOf(row) ?: return@map row
            row.copy(
                isInternalTransfer = prior.isInternalTransfer,
                isRecurring = prior.isRecurring,
                categoryOverride = prior.categoryOverride,
                isCardPayment = prior.isCardPayment,
                transferOverridden = prior.transferOverridden,
            )
        }
    }
    internal fun detectAccountTypeFor(metadata: AccountDetailsDto, info: AccountInfoDto): AccountType {
        // ISO 20022 cash account type: an outright answer where the bank sends one.
        if (info.cashAccountType?.equals("CARD", ignoreCase = true) == true) {
            return AccountType.CREDIT_CARD
        }

        val text = listOfNotNull(metadata.name, info.name, info.product, info.details)
            .joinToString(" ")
            .lowercase()

        return when {
            // Before the card rules: PayPal calls its product a "card" often enough to
            // be mistaken for one, and it behaves nothing like a credit card here.
            text.contains("paypal") -> AccountType.PAYPAL
            text.contains("credit card") || text.contains("creditcard") -> AccountType.CREDIT_CARD
            text.contains("credit") && !text.contains("credit union") -> AccountType.CREDIT_CARD
            text.contains("joint") -> AccountType.JOINT
            text.contains("saving") || text.contains("isa ") || text.endsWith(" isa") ->
                AccountType.SAVINGS
            // Weakest signal last, so "card" does not outrank an explicit "joint".
            text.contains("card") -> AccountType.CREDIT_CARD
            else -> AccountType.PERSONAL
        }
    }

    /**
     * Picks which of a bank's several balances to show.
     *
     * For a current account the useful figure is what is available to spend. For a credit
     * card it is the opposite: "available" means the unused part of the limit, so preferring
     * it showed a card's headroom as though it were money. Banks differ in which types they
     * return, which is why one card looked right and another did not - an issuer that omits
     * interimAvailable fell through to the real balance by luck rather than by design.
     */

    /**
     * Picks which of a bank's several balances to show.
     *
     * For a current account the useful figure is what is available to spend. For a credit
     * card it is the opposite: "available" means the unused part of the limit, so
     * preferring it showed a card's headroom as though it were money. Banks differ in
     * which types they return, which is why one card looked right and another did not -
     * an issuer that omits interimAvailable fell through to the real balance by luck.
     */
    fun selectBalance(balances: BalancesDto, accountType: AccountType): Pair<Long, String>? {
        val preference = when (accountType) {
            // Owed first, and never an "available" type, which is unused credit.
            AccountType.CREDIT_CARD ->
                listOf("closingBooked", "interimBooked", "expected", "openingBooked")
            else -> listOf("interimAvailable", "expected", "closingBooked")
        }
        for (type in preference) {
            val balance = balances.balances.firstOrNull {
                it.balanceType == type &&
                    it.balanceAmount != null &&
                    // Whatever it is called, a figure carrying the credit limit is
                    // headroom rather than a balance.
                    it.creditLimitIncluded != true
            } ?: continue
            val amount = balance.balanceAmount ?: continue
            return try {
                BigDecimal(amount.amount).toMinorLong() to amount.currency
            } catch (e: Exception) {
                continue
            }
        }

        // Nothing preferred was on offer. For a card, any figure the bank has not flagged
        // as containing the limit beats showing the headroom as though it were a debt.
        if (accountType == AccountType.CREDIT_CARD) {
            balances.balances
                .firstOrNull {
                    it.balanceAmount != null &&
                        it.creditLimitIncluded != true &&
                        it.balanceType?.contains("available", ignoreCase = true) != true
                }
                ?.balanceAmount
                ?.let { amount ->
                    return runCatching {
                        BigDecimal(amount.amount).toMinorLong() to amount.currency
                    }.getOrNull()
                }
        }
        return null
    }
}
