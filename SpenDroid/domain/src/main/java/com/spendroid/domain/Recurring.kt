package com.spendroid.domain

import com.spendroid.data.db.ManualRecurringRuleEntity
import java.time.LocalDate
import kotlin.math.abs

enum class Direction { IN, OUT }

/** Key prefix for user-entered rules, which have no matching transaction groupKey. */
const val MANUAL_KEY_PREFIX = "manual-"

/** A monthly payment whose amount changes each time - energy, a salary with overtime. */
const val VARIABLE_KEY_PREFIX = "VAR|"

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
    /**
     * The account the latest occurrence was paid from, or the one the user named for a manual
     * rule. A payment that moved to a card is a card payment from then on, whatever came before.
     */
    val paidFrom: String? = null,
) {
    val isManual: Boolean get() = key.startsWith(MANUAL_KEY_PREFIX)

    /** Its amount changes each time; [amountMinor] is the usual of the latest few. */
    val isVariable: Boolean get() = key.startsWith(VARIABLE_KEY_PREFIX)
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
    /**
     * How much of [spendableThisCycle] is gone: the full bar less what is left. Spending, and
     * on a fresh start the card bills due before payday too, which the headline takes off.
     */
    val usedThisCycle: Long = 0L,
    /**
     * Carrying over: what is held back towards quarterly and yearly bills not due before payday,
     * built up a little each cycle so the one they land in takes no sudden hit.
     */
    val setAsideMinor: Long = 0L,
    val setAside: List<Pair<RecurringRule, Long>> = emptyList(),
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
    /** This cycle's day-to-day spending, row by row: where today's went, and the biggest. */
    val cycleSpends: List<com.spendroid.data.db.TransactionEntity> = emptyList(),
    /** Day-to-day spending by day, as far back as there's history: for "Saturdays usually...". */
    val spendByDay: Map<java.time.LocalDate, Long> = emptyMap(),
    /**
     * Today's spending as it happened, wherever it was paid: from the accounts, and on credit
     * cards. With card spending counted at the bill, [spentToday] leaves cards out - right for
     * the budget, but a lunch on the card read as nothing spent. These are for saying so.
     */
    val spentTodayFromAccountsMinor: Long = 0L,
    val spentTodayOnCardsMinor: Long = 0L,
    /** How much of today's spending, accounts and cards together, is still pending. */
    val spentTodayPendingMinor: Long = 0L,
    /** The part of [spentTodayOnCardsMinor] still pending. */
    val spentTodayOnCardsPendingMinor: Long = 0L,
    /**
     * The day these figures are for: today once today's first sync has landed, yesterday until
     * then. Screens measure "today" from this, not the clock, so they agree with the figures.
     */
    val asOf: java.time.LocalDate = java.time.LocalDate.now(),
    /** True when the user picked the income that sets the cycle, rather than it being guessed. */
    val primaryIncomeDesignated: Boolean = false,
    /** True when a designated income no longer matches any rule and the guess took over. */
    val designationLost: Boolean = false,
    /**
     * The month, cards and all (0.8.0): when there are credit cards, [availableToSpend] and the
     * figures that go with it - the bar, the pace, this cycle's and today's spending - are the
     * month's, account and cards together, and the account's own are kept here.
     */
    val month: MonthView? = null,
)

/** One card's statement: this pay period's ([paid] already, or to come), or next period's. */
data class MonthBill(val label: String, val amountMinor: Long, val paid: Boolean, val due: LocalDate?)

/**
 * What's left this month, account and cards together, rests on these (0.8.0). Pay less the
 * bills from the account is the month's money; this period's card statements are last month's
 * card spending, paid from it. So the account can only spend what the statements leave, and the
 * rest of the month has to go on the cards - which next month's statements then take.
 */
data class MonthView(
    /** The account's own figures, as Home showed them before: what can leave it and stay in the black. */
    val account: BudgetSnapshot,
    /** This pay period's card statements, paid and still to come. */
    val statements: List<MonthBill>,
    /** Everyday spending from the account this period. */
    val spentFromAccountMinor: Long,
    /** Everyday spending on the cards since payday: bills paid by card aren't in it. */
    val spentOnCardsMinor: Long,
    /** Bills paid by card this period, charged and still to come: taken off the month like any bill. */
    val cardBillsMinor: Long,
    /** Next pay period's statements, by their real dates: what's on the cards so far and their known payments, not a guess at the rest. */
    val nextStatements: List<MonthBill>,
    /** Next period's money before card statements: pay less the bills from the account. */
    val nextBudgetMinor: Long,
    /** Left this month, before it's floored at zero: below it, how far over. */
    val leftMinor: Long,
    /** Refunds onto the cards this period, as the user said: already off [spentOnCardsMinor]. */
    val refundsMinor: Long = 0L,
    /** Money onto a card this period that isn't paying it off, not yet asked about: refund, or not? */
    val creditsToAsk: List<com.spendroid.data.db.TransactionEntity> = emptyList(),
) {
    val statementsMinor: Long get() = statements.sumOf { it.amountMinor }
    val nextStatementsMinor: Long get() = nextStatements.sumOf { it.amountMinor }

    /** What the account can still pay before payday and stay in the black; below zero, how far it's short. */
    val accountLeftMinor: Long get() = account.availableToSpend - account.shortfallMinor

    /** The most of what's left this month the account can pay: the rest has to go on cards. */
    val fromAccountMinor: Long get() = minOf(accountLeftMinor, leftMinor).coerceAtLeast(0L)

    /**
     * How far next period's statements go past what next period can pay, once the rest of this
     * month is spent. Spent from the account, it leaves that much less there at payday; spent on
     * cards, the statements grow by it: either way next period carries it, so it's counted once,
     * here, against next period's money and what the account holds now.
     */
    val nextShortMinor: Long get() =
        (nextStatementsMinor + leftMinor.coerceAtLeast(0L) - accountLeftMinor - nextBudgetMinor).coerceAtLeast(0L)
}

/** The account's own figures: the month's [MonthView.account] where there is one, else these. */
val BudgetSnapshot.accountOnly: BudgetSnapshot get() = month?.account ?: this

fun ManualRecurringRuleEntity.toRecurringRule(): RecurringRule? {
    val cadenceValue = runCatching { Cadence.valueOf(cadence) }.getOrNull() ?: return null
    val directionValue = runCatching { Direction.valueOf(direction) }.getOrNull() ?: return null
    val last = runCatching { LocalDate.parse(startDate) }.getOrElse { LocalDate.now() }
    // The dialog stores a positive magnitude alongside a direction, so apply the sign here
    // to match the convention detected rules already use.
    val signedAmount = if (directionValue == Direction.OUT) -abs(amountMinor) else abs(amountMinor)
    return RecurringRule(
        accountIds = setOfNotNull(accountId),
        paidFrom = accountId,
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
/**
 * Today's spending as the roundup says it - the same figure as Home's "Spent today": from the
 * accounts and on the credit cards, with the cards' share named. The budget's own figure leaves card
 * spending out while it's counted at the bill, so the roundup once said less than Home did.
 */
fun BudgetSnapshot.spentTodayLine(money: (Long) -> String): String {
    val total = spentTodayFromAccountsMinor + spentTodayOnCardsMinor
    // Only the half there is: the cards' share named when there's both, said once when it's all.
    return when {
        spentTodayOnCardsMinor <= 0L -> "Spent today ${money(total)}"
        spentTodayFromAccountsMinor <= 0L -> "Spent today ${money(total)} on credit cards"
        else -> "Spent today ${money(total)} (${money(spentTodayOnCardsMinor)} on credit cards)"
    }
}
