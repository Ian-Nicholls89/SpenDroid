package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.TransactionEntity
import org.json.JSONObject

/**
 * Reads a file written by [BackupExporter].
 *
 * Restoring is an upsert rather than a merge: every table here has a natural primary key, so
 * a row either replaces its existing twin or is new. That makes restoring onto a populated
 * database safe, which matters because the common case is not a bare reinstall but a device
 * that has already re-synced the last 90 days and is missing everything older.
 */
object BackupImporter {

    data class Restored(
        val accounts: List<AccountEntity>,
        val transactions: List<TransactionEntity>,
        val manualRules: List<ManualRecurringRuleEntity>,
        val budgetGoals: List<BudgetGoalEntity>,
        val categoryRules: List<CategoryRuleEntity>,
        val ignoredRules: Set<String>,
    ) {
        val isEmpty: Boolean
            get() = accounts.isEmpty() && transactions.isEmpty() && manualRules.isEmpty()
    }

    class IncompatibleBackup(message: String) : Exception(message)

    fun parse(json: String): Restored {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IncompatibleBackup("That file isn't a SpenDroid backup.")
        }

        val version = root.optInt("formatVersion", -1)
        if (version <= 0) {
            throw IncompatibleBackup("That file isn't a SpenDroid backup.")
        }
        if (version > BackupExporter.FORMAT_VERSION) {
            throw IncompatibleBackup(
                "That backup was written by a newer version of SpenDroid (format $version). " +
                    "Update the app and try again.",
            )
        }

        val accounts = root.optJSONArray("accounts").mapObjects { o ->
            AccountEntity(
                id = o.getString("id"),
                institutionName = o.optString("institutionName"),
                label = o.optString("label"),
                currency = o.optString("currency", "GBP"),
                balanceMinor = if (o.isNull("balanceMinor")) null else o.getLong("balanceMinor"),
                lastSynced = o.optLong("lastSynced", 0L),
                accountType = runCatching { AccountType.valueOf(o.optString("accountType")) }
                    .getOrDefault(AccountType.PERSONAL),
                linkedCreditCardAccountId = o.nullableString("linkedCreditCardAccountId"),
            )
        }

        val transactions = root.optJSONArray("transactions").mapObjects { o ->
            TransactionEntity(
                accountId = o.getString("accountId"),
                transactionId = o.getString("transactionId"),
                bookingDate = o.optString("bookingDate"),
                valueDate = o.nullableString("valueDate"),
                amountMinor = o.getLong("amountMinor"),
                currency = o.optString("currency", "GBP"),
                payee = o.optString("payee"),
                description = o.nullableString("description"),
                isPending = o.optBoolean("isPending", false),
                rawJson = o.nullableString("rawJson"),
                isInternalTransfer = o.optBoolean("isInternalTransfer", false),
                isRecurring = o.optBoolean("isRecurring", false),
            )
        }

        val manualRules = root.optJSONArray("manualRules").mapObjects { o ->
            ManualRecurringRuleEntity(
                id = o.getString("id"),
                payee = o.optString("payee"),
                direction = o.optString("direction", "OUT"),
                amountMinor = o.optLong("amountMinor", 0L),
                currency = o.optString("currency", "GBP"),
                cadence = o.optString("cadence", "MONTHLY"),
                anchorDay = o.optInt("anchorDay", 1),
                startDate = o.optString("startDate"),
                isActive = o.optBoolean("isActive", true),
            )
        }

        // Present only in backups written after budgets and custom rules existed.
        val budgetGoals = root.optJSONArray("budgetGoals").mapObjects { o ->
            BudgetGoalEntity(
                category = o.getString("category"),
                limitMinor = o.getLong("limitMinor"),
                currency = o.optString("currency", "GBP"),
            )
        }

        val categoryRules = root.optJSONArray("categoryRules").mapObjects { o ->
            CategoryRuleEntity(
                pattern = o.getString("pattern"),
                category = o.getString("category"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }

        val ignored = mutableSetOf<String>()
        root.optJSONArray("ignoredRules")?.let { arr ->
            for (i in 0 until arr.length()) ignored.add(arr.getString(i))
        }

        return Restored(accounts, transactions, manualRules, budgetGoals, categoryRules, ignored)
    }

    private fun <T> org.json.JSONArray?.mapObjects(block: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            // One malformed row should not cost the whole restore.
            runCatching { block(getJSONObject(i)) }.getOrNull()?.let(out::add)
        }
        return out
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
