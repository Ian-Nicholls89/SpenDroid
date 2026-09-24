package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.RuleOverrideEntity
import com.spendroid.data.db.TransactionEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises the local database to JSON.
 *
 * Transactions older than 90 days cannot be fetched again - the API window has moved past
 * them - so once history accumulates beyond that, this file is the only copy in existence.
 * Every field is written out, including the ones the UI never shows, so the export is a
 * complete record rather than a summary.
 */
object BackupExporter {

    /** Bumped when the shape changes, so a future import can tell what it is reading. */
    const val FORMAT_VERSION = 3

    fun toJson(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        manualRules: List<ManualRecurringRuleEntity>,
        budgetGoals: List<BudgetGoalEntity>,
        categoryRules: List<CategoryRuleEntity>,
        ignoredRules: Set<String>,
        transferGroups: Set<String> = emptySet(),
        ruleOverrides: List<RuleOverrideEntity> = emptyList(),
        /** The income chosen to set the cycle, and how the budget is worked out. */
        primaryIncomeKey: String? = null,
        budgetModel: String? = null,
        cardTiming: String? = null,
        exportedAtMillis: Long = System.currentTimeMillis(),
    ): String {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("exportedAt", exportedAtMillis)
        root.put("accountCount", accounts.size)
        root.put("transactionCount", transactions.size)

        root.put(
            "accounts",
            JSONArray().also { arr ->
                accounts.forEach { account ->
                    arr.put(
                        JSONObject()
                            .put("id", account.id)
                            .put("institutionName", account.institutionName)
                            .put("label", account.label)
                            .put("currency", account.currency)
                            .put("balanceMinor", account.balanceMinor ?: JSONObject.NULL)
                            .put("lastSynced", account.lastSynced)
                            .put("accountType", account.accountType.name)
                            .put("rawBalancesJson", account.rawBalancesJson ?: JSONObject.NULL)
                            .put("identity", account.identity ?: JSONObject.NULL)
                            .put("spendingCapMinor", account.spendingCapMinor ?: JSONObject.NULL)
                            .put(
                                "linkedCreditCardAccountId",
                                account.linkedCreditCardAccountId ?: JSONObject.NULL,
                            )
                            .put(
                                "statementDayOfMonth",
                                account.statementDayOfMonth ?: JSONObject.NULL,
                            )
                            .put(
                                "paymentDayOfMonth",
                                account.paymentDayOfMonth ?: JSONObject.NULL,
                            ),
                    )
                }
            },
        )

        root.put(
            "transactions",
            JSONArray().also { arr ->
                transactions.forEach { tx ->
                    arr.put(
                        JSONObject()
                            .put("accountId", tx.accountId)
                            .put("transactionId", tx.transactionId)
                            .put("bookingDate", tx.bookingDate)
                            .put("valueDate", tx.valueDate ?: JSONObject.NULL)
                            .put("amountMinor", tx.amountMinor)
                            .put("currency", tx.currency)
                            .put("payee", tx.payee)
                            .put("description", tx.description ?: JSONObject.NULL)
                            .put("isPending", tx.isPending)
                            .put("isInternalTransfer", tx.isInternalTransfer)
                            .put("isRecurring", tx.isRecurring)
                            .put("isCardPayment", tx.isCardPayment)
                            .put("transferOverridden", tx.transferOverridden)
                            .put("categoryOverride", tx.categoryOverride ?: JSONObject.NULL)
                            // rawJson is the untouched payload from the bank. It is the only
                            // way to recover a field this app does not model yet, so a backup
                            // that dropped it would not really be a backup.
                            .put("rawJson", tx.rawJson ?: JSONObject.NULL),
                    )
                }
            },
        )

        root.put(
            "manualRules",
            JSONArray().also { arr ->
                manualRules.forEach { rule ->
                    arr.put(
                        JSONObject()
                            .put("id", rule.id)
                            .put("payee", rule.payee)
                            .put("direction", rule.direction)
                            .put("amountMinor", rule.amountMinor)
                            .put("currency", rule.currency)
                            .put("cadence", rule.cadence)
                            .put("anchorDay", rule.anchorDay)
                            .put("startDate", rule.startDate)
                            .put("isActive", rule.isActive),
                    )
                }
            },
        )

        root.put(
            "budgetGoals",
            JSONArray().also { arr ->
                budgetGoals.forEach { goal ->
                    arr.put(
                        JSONObject()
                            .put("category", goal.category)
                            .put("limitMinor", goal.limitMinor)
                            .put("currency", goal.currency),
                    )
                }
            },
        )

        root.put(
            "categoryRules",
            JSONArray().also { arr ->
                categoryRules.forEach { rule ->
                    arr.put(
                        JSONObject()
                            .put("pattern", rule.pattern)
                            .put("category", rule.category)
                            .put("createdAt", rule.createdAt),
                    )
                }
            },
        )

        root.put("ignoredRules", JSONArray(ignoredRules.toList()))
        root.put("transferGroups", JSONArray(transferGroups.toList()))

        // Corrections to a detected rule's day are the user's own knowledge of their pay
        // and bills, and exist nowhere else.
        root.put(
            "ruleOverrides",
            JSONArray().also { arr ->
                ruleOverrides.forEach { o ->
                    arr.put(
                        JSONObject()
                            .put("ruleKey", o.ruleKey)
                            .put("anchorDay", o.anchorDay ?: JSONObject.NULL)
                            .put("shift", o.shift ?: JSONObject.NULL)
                            .put("decemberAnchorDay", o.decemberAnchorDay ?: JSONObject.NULL),
                    )
                }
            },
        )
        root.put(
            "settings",
            JSONObject()
                .put("primaryIncomeKey", primaryIncomeKey ?: JSONObject.NULL)
                .put("budgetModel", budgetModel ?: JSONObject.NULL)
                .put("cardTiming", cardTiming ?: JSONObject.NULL),
        )

        return root.toString(2)
    }
}
