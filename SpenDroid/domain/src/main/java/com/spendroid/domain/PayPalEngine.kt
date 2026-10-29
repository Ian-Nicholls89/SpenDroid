package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import kotlin.math.abs

/**
 * Puts the merchant back into a PayPal payment.
 *
 * A bank statement records a PayPal purchase as "PAYPAL *4KDJ2" - the money is right, the
 * reason is missing. PayPal's own account has the reason. Pairing the two recovers it.
 *
 * The pairing also has to stop the same purchase being counted twice, and that is the harder
 * half. A pass-through PayPal account funds each payment from the bank, so one purchase can
 * appear as three rows: the merchant debit on PayPal, the funding credit on PayPal, and the
 * debit on the bank. Left alone that is one purchase counted twice and one phantom payment in.
 *
 * Two shapes are handled, because the arithmetic differs:
 *
 *  - **Funded in full.** The bank debit and the PayPal merchant debit are the same amount.
 *    The bank debit is kept and renamed; the PayPal rows are marked as transfers.
 *  - **Funded in part**, where PayPal held some balance. The bank tops up less than the
 *    purchase, so no PayPal debit matches it. Here the *bank* leg and the top-up are the
 *    transfers, and PayPal's own rows carry the spending - which nets to the purchase minus
 *    the balance used, exactly right.
 *
 * A purchase paid entirely from a PayPal balance matches nothing, and counts as it stands.
 */
object PayPalEngine {

    /** How a bank writes a PayPal payment. Distinctive enough not to need word boundaries. */
    private val REFERENCE = Regex("""paypal|pp\*|\bpypl\b""", RegexOption.IGNORE_CASE)

    /**
     * PayPal pays the merchant when the order is placed and collects from the bank afterwards,
     * so the bank debit trails. Days, not hours: a weekend alone can account for three.
     */
    private const val MAX_LAG_DAYS = 8L

    /** Booking dates occasionally cross the other way, so allow a little the other side. */
    private const val MAX_LEAD_DAYS = 2L

    /**
     * How far a converted amount may sit from the amount charged. Wide enough for any real
     * currency against sterling, narrow enough that a coincidence ten times the size cannot
     * pass for one.
     */
    private const val MIN_PLAUSIBLE_RATE = 0.4
    private const val MAX_PLAUSIBLE_RATE = 2.5

    /**
     * Returns [transactions] with PayPal payments named after their merchant and the duplicate
     * legs marked as internal transfers, which is how everything downstream already knows to
     * leave a row out of spending.
     */
    fun reconcile(
        transactions: List<TransactionEntity>,
        accounts: List<AccountEntity>,
    ): List<TransactionEntity> {
        val payPalAccountIds = accounts
            .filter { it.accountType == AccountType.PAYPAL }
            .map { it.id }
            .toSet()
        if (payPalAccountIds.isEmpty()) return transactions

        val dated = transactions.mapNotNull { tx ->
            RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { Dated(it, tx) }
        }
        // Pending included, because pending now counts towards spending: leaving these
        // unmatched would let a purchase be counted on both legs until the bank booked it.
        val payPalRows = dated.filter { it.tx.accountId in payPalAccountIds }
        if (payPalRows.isEmpty()) return transactions

        val merchantDebits = payPalRows.filter { it.tx.amountMinor < 0L }
        val fundingCredits = payPalRows.filter { it.tx.amountMinor > 0L }

        // Oldest first, so when two purchases share an amount the earlier bank debit takes the
        // earlier PayPal payment rather than whichever happened to be listed first.
        val bankLegs = dated
            .filter { it.tx.accountId !in payPalAccountIds }
            .filter { it.tx.amountMinor < 0L }
            .filter { looksLikePayPal(it.tx) }
            .sortedBy { it.date }

        // Withdrawing a balance to the bank is the mirror of a purchase: the money leaves
        // PayPal and arrives in a real account. Both halves are the same movement, so both
        // are transfers - counting the arrival as income and the departure as spending
        // would invent money and then spend it.
        val withdrawalLegs = dated
            .filter { it.tx.accountId !in payPalAccountIds }
            .filter { it.tx.amountMinor > 0L }
            .filter { looksLikePayPal(it.tx) }
            .sortedBy { it.date }

        val consumed = mutableSetOf<String>()
        val renamed = mutableMapOf<String, String>()
        val transfers = mutableSetOf<String>()

        for (leg in bankLegs) {
            val wanted = abs(leg.tx.amountMinor)

            // Funded in full: a PayPal payment of the same size, at or before the bank debit.
            val merchant = nearest(merchantDebits, leg, wanted, consumed)
            if (merchant != null) {
                consumed.add(key(merchant.tx))
                transfers.add(key(merchant.tx))
                merchantName(merchant.tx)?.let { renamed[key(leg.tx)] = it }

                // PayPal may also show the top-up that covered it. Left alone it reads as
                // money coming in, so it has to go with the leg it belongs to.
                nearest(fundingCredits, merchant, wanted, consumed)?.let {
                    consumed.add(key(it.tx))
                    transfers.add(key(it.tx))
                }
                continue
            }

            // Funded in part, where PayPal shows the top-up it received.
            val topUp = nearest(fundingCredits, leg, wanted, consumed)
            if (topUp != null) {
                consumed.add(key(topUp.tx))
                transfers.add(key(topUp.tx))
                transfers.add(key(leg.tx))
                continue
            }

            // Charged in another currency, where no amount can match because PayPal reports
            // what the merchant charged and the bank reports what it converted that into.
            // Pairing them needs no exchange rate at all: the bank already did the
            // conversion, and its leg is the one kept, so the sterling figure is exact.
            val foreign = onlyForeignCandidate(merchantDebits, leg, consumed)
            if (foreign != null) {
                consumed.add(key(foreign.tx))
                transfers.add(key(foreign.tx))
                merchantName(foreign.tx)?.let { renamed[key(leg.tx)] = it }
                continue
            }

            // Funded in part, where it does not. Nothing balances exactly, so the only trace
            // of the balance used is that PayPal spent more than the bank was asked for.
            val larger = onlyLargerCandidate(merchantDebits, leg, wanted, consumed) ?: continue
            consumed.add(key(larger.tx))
            transfers.add(key(leg.tx))
        }

        for (leg in withdrawalLegs) {
            val wanted = abs(leg.tx.amountMinor)
            val out = nearest(merchantDebits, leg, wanted, consumed) ?: continue
            consumed.add(key(out.tx))
            transfers.add(key(out.tx))
            transfers.add(key(leg.tx))
        }

        if (renamed.isEmpty() && transfers.isEmpty()) return transactions

        return transactions.map { tx ->
            val id = key(tx)
            val newName = renamed[id]
            when {
                newName != null -> tx.copy(
                    payee = newName,
                    // The bank's own reference is the only proof of where this came from, so
                    // it is kept rather than overwritten.
                    description = "via PayPal · ${tx.payee}",
                    // This row is the purchase. Generic transfer detection may already have
                    // paired it with a PayPal top-up and stored it as a transfer, and leaving
                    // that in place while PayPal's own rows are suppressed would erase the
                    // spending altogether.
                    isInternalTransfer = false,
                )
                id in transfers -> tx.copy(isInternalTransfer = true)
                else -> tx
            }
        }
    }

    private data class Dated(val date: LocalDate, val tx: TransactionEntity)

    private fun key(tx: TransactionEntity) = "${tx.accountId}|${tx.transactionId}"

    private fun looksLikePayPal(tx: TransactionEntity): Boolean =
        REFERENCE.containsMatchIn(tx.payee) ||
            tx.description?.let { REFERENCE.containsMatchIn(it) } == true

    /**
     * The unused candidate of exactly [wanted] closest in time to [leg], within the window.
     *
     * Exact on amount because a near-miss is a different purchase, and nearest-in-time so two
     * payments of the same size in one week pair off in the order they happened.
     */
    private fun nearest(
        candidates: List<Dated>,
        leg: Dated,
        wanted: Long,
        consumed: Set<String>,
    ): Dated? = candidates
        .filter { candidate ->
            key(candidate.tx) !in consumed &&
                abs(candidate.tx.amountMinor) == wanted &&
                candidate.date >= leg.date.minusDays(MAX_LAG_DAYS) &&
                candidate.date <= leg.date.plusDays(MAX_LEAD_DAYS)
        }
        .minByOrNull { abs(it.date.toEpochDay() - leg.date.toEpochDay()) }

    /**
     * The one PayPal payment in the window that the bank under-funded, or null if there is
     * any doubt about which it was.
     *
     * Without a top-up row there is nothing that balances, so this is inference rather than
     * reconciliation: a bank debit smaller than a nearby PayPal payment is taken to be the
     * rest of it, with a balance covering the difference. Two candidates means two readings,
     * and guessing between them would silently move money, so it declines instead - leaving
     * a visible duplicate, which is the better failure.
     */
    private fun onlyLargerCandidate(
        candidates: List<Dated>,
        leg: Dated,
        wanted: Long,
        consumed: Set<String>,
    ): Dated? {
        val plausible = candidates.filter { candidate ->
            key(candidate.tx) !in consumed &&
                abs(candidate.tx.amountMinor) > wanted &&
                candidate.date >= leg.date.minusDays(MAX_LAG_DAYS) &&
                candidate.date <= leg.date.plusDays(MAX_LEAD_DAYS)
        }
        return plausible.singleOrNull()
    }

    /**
     * The one PayPal payment in the window charged in a different currency, or null if there
     * is any doubt about which it was.
     *
     * A foreign purchase can never match on amount, so the usual evidence is unavailable and
     * the date window does most of the work. That makes ambiguity likelier, so more than one
     * candidate is declined rather than guessed - and the implied rate has to be sane, which
     * rules out a coincidence an order of magnitude away.
     */
    private fun onlyForeignCandidate(
        candidates: List<Dated>,
        leg: Dated,
        consumed: Set<String>,
    ): Dated? {
        val plausible = candidates.filter { candidate ->
            if (key(candidate.tx) in consumed) return@filter false
            if (candidate.tx.currency == leg.tx.currency) return@filter false
            if (candidate.date < leg.date.minusDays(MAX_LAG_DAYS)) return@filter false
            if (candidate.date > leg.date.plusDays(MAX_LEAD_DAYS)) return@filter false
            val charged = abs(candidate.tx.amountMinor).toDouble()
            if (charged <= 0.0) return@filter false
            val rate = abs(leg.tx.amountMinor).toDouble() / charged
            rate in MIN_PLAUSIBLE_RATE..MAX_PLAUSIBLE_RATE
        }
        return plausible.singleOrNull()
    }

    /**
     * What PayPal called the other side of the payment, if it is worth showing. PayPal's own
     * boilerplate is no better than the bank's reference, so it is not worth swapping in.
     */
    private fun merchantName(tx: TransactionEntity): String? {
        val name = tx.payee.trim()
        if (name.isBlank()) return null
        if (REFERENCE.containsMatchIn(name)) return null
        if (name.lowercase() in GENERIC) return null
        return name
    }

    private val GENERIC = setOf(
        "payment",
        "express checkout payment",
        "general payment",
        "mobile payment",
        "website payment",
    )
}
