package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.BudgetGoalEntity
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.ManualRecurringRuleEntity
import com.spendroid.data.db.RuleOverrideEntity
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
        val ruleOverrides: List<RuleOverrideEntity> = emptyList(),
        val seenSpends: List<com.spendroid.data.db.SeenSpendEntity> = emptyList(),
        /** For [BackupPrefs.restore]; null in a backup from before format 5. */
        val preferences: JSONObject? = null,
        val transferGroups: Set<String> = emptySet(),
        /** Null when the backup predates settings being kept, so nothing is overwritten. */
        val primaryIncomeKey: String? = null,
        val budgetModel: String? = null,
        val cardTiming: String? = null,
        val duplicateChecks: List<com.spendroid.data.db.DuplicateCheckEntity> = emptyList(),
        val notificationSamples: List<com.spendroid.data.db.NotificationSampleEntity> = emptyList(),
        /** For [SecretsStore.restoreStore]; null in a backup from before format 6. */
        val store: JSONObject? = null,
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
                rawBalancesJson = o.nullableString("rawBalancesJson"),
                identity = o.nullableString("identity"),
                spendingCapMinor = if (o.has("spendingCapMinor") && !o.isNull("spendingCapMinor")) {
                    o.getLong("spendingCapMinor")
                } else {
                    null
                },
                statementDayOfMonth = o.nullableInt("statementDayOfMonth"),
                paymentDayOfMonth = o.nullableInt("paymentDayOfMonth"),
                walletLinked = o.optBoolean("walletLinked", false),
                cardLastFour = o.nullableString("cardLastFour"),
                manual = o.optBoolean("manual", false),
                anchorMinor = if (o.has("anchorMinor") && !o.isNull("anchorMinor")) o.getLong("anchorMinor") else null,
                anchorDate = o.nullableString("anchorDate"),
                checkedAt = if (o.has("checkedAt") && !o.isNull("checkedAt")) o.getLong("checkedAt") else null,
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
                isCardPayment = o.optBoolean("isCardPayment", false),
                transferOverridden = o.optBoolean("transferOverridden", false),
                categoryOverride = o.nullableString("categoryOverride"),
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
                accountId = o.nullableString("accountId"),
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

        val ruleOverrides = root.optJSONArray("ruleOverrides").mapObjects { o ->
            RuleOverrideEntity(
                ruleKey = o.getString("ruleKey"),
                anchorDay = o.nullableInt("anchorDay"),
                shift = o.nullableString("shift"),
                decemberAnchorDay = o.nullableInt("decemberAnchorDay"),
            )
        }
        val seenSpends = root.optJSONArray("seenSpends").mapObjects { o ->
            com.spendroid.data.db.SeenSpendEntity(
                id = o.getString("id"),
                source = o.getString("source"),
                seenAt = o.getLong("seenAt"),
                amountMinor = o.getLong("amountMinor"),
                currency = o.getString("currency"),
                merchant = o.getString("merchant"),
                cardDigits = o.nullableString("cardDigits"),
                accountId = o.nullableString("accountId"),
                matchedTransactionId = o.nullableString("matchedTransactionId"),
                dismissed = o.optBoolean("dismissed", false),
                keptByUser = o.optBoolean("keptByUser", false),
                categoryOverride = o.nullableString("categoryOverride"),
                accountGuessed = o.optBoolean("accountGuessed", false),
                provisionalAccountId = o.nullableString("provisionalAccountId"),
                pairedWithId = o.nullableString("pairedWithId"),
                confirmedSeparate = o.optBoolean("confirmedSeparate", false),
            )
        }
        val duplicateChecks = root.optJSONArray("duplicateChecks").mapObjects { o ->
            com.spendroid.data.db.DuplicateCheckEntity(
                accountId = o.getString("accountId"),
                transactionId = o.getString("transactionId"),
                missedSyncs = o.getInt("missedSyncs"),
                candidates = o.getString("candidates"),
                state = o.getString("state"),
            )
        }
        val notificationSamples = root.optJSONArray("notificationSamples").mapObjects { o ->
            com.spendroid.data.db.NotificationSampleEntity(
                id = o.getLong("id"),
                source = o.getString("source"),
                postedAt = o.getLong("postedAt"),
                title = o.nullableString("title"),
                text = o.nullableString("text"),
                parsed = o.optBoolean("parsed", false),
                outcome = o.nullableString("outcome"),
            )
        }
        val settings = root.optJSONObject("settings")
        val transferGroups = mutableSetOf<String>()
        root.optJSONArray("transferGroups")?.let { arr ->
            for (i in 0 until arr.length()) transferGroups.add(arr.getString(i))
        }

        return Restored(
            accounts = accounts,
            transactions = transactions,
            manualRules = manualRules,
            budgetGoals = budgetGoals,
            categoryRules = categoryRules,
            ignoredRules = ignored,
            ruleOverrides = ruleOverrides,
            seenSpends = seenSpends,
            preferences = root.optJSONObject("preferences"),
            transferGroups = transferGroups,
            primaryIncomeKey = settings?.nullableString("primaryIncomeKey"),
            budgetModel = settings?.nullableString("budgetModel"),
            cardTiming = settings?.nullableString("cardTiming"),
            duplicateChecks = duplicateChecks,
            notificationSamples = notificationSamples,
            store = root.optJSONObject("store"),
        )
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

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key)) null else optInt(key).takeIf { has(key) }
}
