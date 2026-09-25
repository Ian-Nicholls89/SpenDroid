package com.spendroid.domain

import com.spendroid.data.db.ManualRecurringRuleEntity
import java.time.LocalDate
import kotlin.math.abs

enum class Direction { IN, OUT }

/** Key prefix for user-entered rules, which have no matching transaction groupKey. */
const val MANUAL_KEY_PREFIX = "manual-"

/** Key prefix for forecast credit card bills, which are computed rather than detected. */
const val CARD_BILL_KEY_PREFIX = "card-"

enum class Cadence {
    WEEKLY,
    FORTNIGHTLY,
    MONTHLY,
    MONTHLY_LAST_DAY,
    MONTHLY_LAST_BUSINESS_DAY,
    QUARTERLY,
    ANNUAL,
}

/**
 * [amountMinor] is signed the same way transactions are: negative is money out.
 * Detected rules inherit the sign from the transactions they were built from; manual
 * rules are normalised to match in [toRecurringRule].
 */
data class RecurringRule(
    val key: String,
    val payee: String,
    val direction: Direction,
    val amountMinor: Long,
    val currency: String,
    val cadence: Cadence,
    val anchorDay: Int,
    val lastOccurrence: LocalDate,
    val occurrences: Int,
    val score: Float,
    /**
     * The accounts the detected occurrences came from. Empty for a manual rule, which is not
     * tied to one. Card spending is settled by the bill rather than leaving the current
     * account, so the budget has to be able to tell where a rule came from.
     */
    val accountIds: Set<String> = emptySet(),
    /**
     * True when every occurrence is money moving between the user's own accounts. Such a
     * rule is not income and not an outgoing, however regular it looks.
     */
    val internalTransfer: Boolean = false,
    /**
     * How many identical payments make up one occurrence - two for £50 to each of two
     * children on the same day. [amountMinor] is already the total.
     */
    val perOccurrence: Int = 1,
) {
    val isManual: Boolean get() = key.startsWith(MANUAL_KEY_PREFIX)
}

data class UpcomingPayment(
    val rule: RecurringRule,
    val dueDate: LocalDate,
    /** Positive magnitude of the payment, not the rule's signed amount. */
    val amountMinor: Long,
)

data class BudgetSnapshot(
    val averageMonthlyIncome: Long,
    val fixedMonthlyOutgoings: Long,
    val variableMonthlyBudget: Long,
    val nextIncomeDate: LocalDate?,
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate?, // end of current pay cycle
    val spentThisCycle: Long,
    val spentToday: Long,
    val upcomingFixed: List<UpcomingPayment>,
    val availableToSpend: Long,
    val incomeRules: List<RecurringRule>,
    val fixedRules: List<RecurringRule>,
    val primaryIncomeRule: RecurringRule?, // the main income that drives the cycle
    val daysUntilNextIncome: Int?,
    /** Outstanding credit card balances, for showing the forecast bill as an estimate. */
    val cardBills: List<CreditCardEngine.CardBill> = emptyList(),
    /** "accountId|transactionId" of the debits that pay a card, for categorising them. */
    val cardPaymentKeys: Set<String> = emptySet(),
    /** Which accounts are credit cards, where a positive amount is never earnings. */
    val creditCardAccountIds: Set<String> = emptySet(),
    /**
     * Credits on a card that settle a bill and whose paying debit was found. The same event
     * is already on the list as that debit, so these are the duplicate half.
     */
    val confirmedSettlementKeys: Set<String> = emptySet(),
    /** The debits that pay a card: real money leaving, however transfer-shaped they look. */
    val cardPayerKeys: Set<String> = emptySet(),
    /** The currency every figure here is expressed in. */
    val baseCurrency: String = "GBP",
    /**
     * Currencies seen on spending that could not be expressed in [baseCurrency], and so were
     * left out of the totals. Non-empty means the figures are short by a known amount.
     */
    val unconvertedCurrencies: Set<String> = emptySet(),
    /** Which model produced [availableToSpend]. */
    val budgetModel: BudgetModel = BudgetModel.FRESH_START,
    /** The account the main income is paid into, and whose balance forms the pot. */
    val potAccountId: String? = null,
    /** What was in the pot when the cycle began, derived by rewinding today's balance. */
    val openingBalanceMinor: Long? = null,
    /**
     * What [availableToSpend] was measured against. The ring and the headline have to share
     * this, or they describe the same cycle differently.
     */
    val spendableThisCycle: Long = 0L,
    /** How far below nothing the figure landed before it was floored at zero. */
    val shortfallMinor: Long = 0L,
    /** What is in the pot now. */
    val potBalanceMinor: Long? = null,
    /** When card spending came off [availableToSpend]. */
    val cardTiming: CardTiming = CardTiming.AT_BILL,
    /** Card bills the next pay cycle will pay; zero when card spending counts at the till. */
    val cardsNextCycleMinor: Long = 0L,
    /** Discretionary spending on each of the last seven days, oldest first, today last. */
    val lastSevenDaysMinor: List<Long> = emptyList(),
    /** True when the user picked the income that sets the cycle, rather than it being guessed. */
    val primaryIncomeDesignated: Boolean = false,
    /** True when a designated income no longer matches any rule and the guess took over. */
    val designationLost: Boolean = false,
)

fun ManualRecurringRuleEntity.toRecurringRule(): RecurringRule? {
    val cadenceValue = runCatching { Cadence.valueOf(cadence) }.getOrNull() ?: return null
    val directionValue = runCatching { Direction.valueOf(direction) }.getOrNull() ?: return null
    val last = runCatching { LocalDate.parse(startDate) }.getOrElse { LocalDate.now() }
    // The dialog stores a positive magnitude alongside a direction, so apply the sign here
    // to match the convention detected rules already use.
    val signedAmount = if (directionValue == Direction.OUT) -abs(amountMinor) else abs(amountMinor)
    return RecurringRule(
        key = "$MANUAL_KEY_PREFIX$id",
        payee = payee,
        direction = directionValue,
        amountMinor = signedAmount,
        currency = currency,
        cadence = cadenceValue,
        anchorDay = anchorDay,
        lastOccurrence = last,
        occurrences = 1,
        score = 1f,
    )
}