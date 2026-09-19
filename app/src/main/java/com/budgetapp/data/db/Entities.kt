package com.budgetapp.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AccountType(val displayName: String) {
    PERSONAL("Personal current account"),
    JOINT("Joint account"),
    CREDIT_CARD("Credit card"),
    SAVINGS("Savings"),
    OTHER("Other"),
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