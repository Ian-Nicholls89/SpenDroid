package com.spendroid.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AccountType(val displayName: String, val shortName: String) {
    PERSONAL("Personal current account", "Personal"),
    JOINT("Joint account", "Joint"),
    CREDIT_CARD("Credit card", "Credit card"),
    SAVINGS("Savings", "Savings"),
    PAYPAL("PayPal", "PayPal"),
    OTHER("Other", "Other"),
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val institutionName: String,
    val label: String,
    val currency: String,
    val balanceMinor: Long?,
    val lastSynced: Long,
    val accountType: AccountType = AccountType.PERSONAL,
    val linkedCreditCardAccountId: String? = null, // For credit cards: which personal account pays this card
    /**
     * Every balance the bank returned, as sent. Which one to show depends on the account
     * type, and the type can change after the sync that fetched them - keeping the payload
     * means the choice can be redone immediately instead of waiting for the next sync.
     */
    val rawBalancesJson: String? = null,
    /**
     * For a credit card: the day of the month its statement closes. Solved from past bills
     * where possible, but a card that has never been cleared in full leaves nothing to solve
     * against, so the user can set it. A stored day outlives the API's 90-day window.
     */
    val statementDayOfMonth: Int? = null,
    /** For a credit card: the day of the month the bill is taken, when the user set it. */
    val paymentDayOfMonth: Int? = null,
    /**
     * What the bank calls this account independent of the consent - its IBAN, sort code and
     * number, or masked card number. The id above is issued per consent, so reauthorising
     * brings the same account back under a new one, and this is how it is recognised.
     */
    val identity: String? = null,
    /**
     * For a credit card: how much the user means to put on it between statements. Not the
     * bank's credit limit, which is how much they could.
     */
    val spendingCapMinor: Long? = null,
    /** Whether this account's card is in Google Wallet, so Wallet's notifications can be put on it. */
    val walletLinked: Boolean = false,
    /** The card's last four digits, as payment notifications show them. */
    val cardLastFour: String? = null,
)

/**
 * A card payment announced by a notification - Google Wallet's, or a bank app's - before the bank
 * has reported it. Counted like a pending transaction until the bank's own row arrives and takes
 * over. Kept apart from [TransactionEntity], which every sync rewrites.
 */
@Entity(tableName = "seen_spends")
data class SeenSpendEntity(
    @PrimaryKey val id: String,
    /** Package of the app that posted the notification. */
    val source: String,
    val seenAt: Long,
    /** Negative, like a debit. */
    val amountMinor: Long,
    val currency: String,
    val merchant: String,
    /** Last four digits of the card, when the notification showed them. */
    val cardDigits: String? = null,
    /** Which account it was spent from; null until known, and not counted until then. */
    val accountId: String? = null,
    /** The bank's transaction that took over, once one has. */
    val matchedTransactionId: String? = null,
    /** The user said it never happened. */
    val dismissed: Boolean = false,
    /** The user said to keep counting it although the bank never showed it. */
    val keptByUser: Boolean = false,
    val categoryOverride: String? = null,
    /** The notification named no account, so [accountId] is the likeliest one, not a certainty. */
    val accountGuessed: Boolean = false,
)

/**
 * A notification from one of the apps the user picked, kept for thirty days so the wording
 * the parser has to read can be seen, and a missed payment explained.
 */
@Entity(tableName = "notification_samples")
data class NotificationSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val postedAt: Long,
    val title: String?,
    val text: String?,
    /** Whether it was read as a payment. */
    val parsed: Boolean,
    /** What became of it: a [com.spendroid.domain.NotificationSpend.Outcome] by name. */
    val outcome: String? = null,
)

@Entity(
    tableName = "transactions",
    primaryKeys = ["accountId", "transactionId"],
    indices = [Index("accountId"), Index("bookingDate")],
)
data class TransactionEntity(
    val accountId: String,
    val transactionId: String,
    val bookingDate: String,
    val valueDate: String?,
    val amountMinor: Long,
    val currency: String,
    val payee: String,
    val description: String?,
    val isPending: Boolean,
    val rawJson: String?,
    val isInternalTransfer: Boolean = false,
    val isRecurring: Boolean = false,
    /** A category the user set for this one transaction, overriding every rule. */
    val categoryOverride: String? = null,
    /**
     * Set by the user on a credit on a card to say it settles a bill rather than reversing a
     * purchase. Only needed where the paying account is not linked, since a payment made
     * from an account the app can see is matched to its other leg automatically.
     */
    val isCardPayment: Boolean = false,
    /**
     * True once the user has decided for themselves whether this is a transfer. Detection
     * then leaves the row alone: it only ever set the flag and never cleared it, so an
     * un-marked transfer came back on the next sync.
     */
    val transferOverridden: Boolean = false,
)

@Entity(tableName = "manual_recurring_rules")
data class ManualRecurringRuleEntity(
    @PrimaryKey val id: String,
    val payee: String,
    val direction: String, // "IN" or "OUT"
    val amountMinor: Long,
    val currency: String,
    val cadence: String,
    val anchorDay: Int,
    val startDate: String, // ISO date string
    val isActive: Boolean = true,
)
/** A user-set monthly cap for a spending category. */
@Entity(tableName = "budget_goals")
data class BudgetGoalEntity(
    @PrimaryKey val category: String,
    val limitMinor: Long,
    val currency: String = "GBP",
)

/**
 * A user's own categorisation rule, matched against a normalised payee.
 *
 * These take precedence over the built-in keyword list, so a correction made once keeps
 * applying instead of being re-guessed on every sync.
 */
@Entity(tableName = "category_rules")
data class CategoryRuleEntity(
    @PrimaryKey val pattern: String,
    val category: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A UK bank holiday, as published by gov.uk. */
@Entity(tableName = "bank_holidays", primaryKeys = ["date", "division"])
data class BankHolidayEntity(
    val date: String,
    val division: String,
    val title: String,
)

/**
 * A user's correction to a detected rule.
 *
 * Detection infers the day from what it has seen, which lands close but not always right -
 * a salary paid on the 25th can look like the 24th if the 25th kept falling on a weekend.
 */
@Entity(tableName = "rule_overrides")
data class RuleOverrideEntity(
    @PrimaryKey val ruleKey: String,
    /** Day of the month the payment is really due, before any working-day adjustment. */
    val anchorDay: Int? = null,
    /** Name of a PaymentShift: how the date moves when it lands on a non-working day. */
    val shift: String? = null,
    /**
     * Day of the month this is paid in December, where that differs. Many employers pay
     * early before Christmas; plenty do not, so this is empty unless the user sets it.
     */
    val decemberAnchorDay: Int? = null,
)

/**
 * A booked transaction the bank has stopped listing, followed until the user has said what it
 * was. Banks now and then re-issue a transaction under a new id; the old row stays stored and the
 * new one arrives beside it, and the same money counts twice. See [com.spendroid.domain.DuplicateCheck].
 */
@Entity(tableName = "duplicate_checks", primaryKeys = ["accountId", "transactionId"])
data class DuplicateCheckEntity(
    val accountId: String,
    /** The stored row the bank no longer lists. */
    val transactionId: String,
    /** Syncs in a row that left it out. */
    val missedSyncs: Int,
    /**
     * Ids of rows that could be it under a new id, one per line: while watching, every new
     * row of the same amount near its date; once asking, the one chosen, or none.
     */
    val candidates: String,
    /** A [com.spendroid.domain.DuplicateCheck.State] name. */
    val state: String,
)
