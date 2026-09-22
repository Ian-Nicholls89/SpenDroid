package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
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
    const val FORMAT_VERSION = 1

    fun toJson(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        manualRules: List<ManualRecurringRuleEntity>,
        ignoredRules: Set<String>,
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
                            .put(
                                "linkedCreditCardAccountId",
                                account.linkedCreditCardAccountId ?: JSONObject.NULL,
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

        root.put("ignoredRules", JSONArray(ignoredRules.toList()))

        return root.toString(2)
    }
}
