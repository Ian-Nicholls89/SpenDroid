package com.spendroid.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AccountType(val displayName: String, val shortName: String) {
    PERSONAL("Personal current account", "Personal"),
    JOINT("Joint account", "Joint"),
    CREDIT_CARD("Credit card", "Credit card"),
    SAVINGS("Savings", "Savings"),
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
