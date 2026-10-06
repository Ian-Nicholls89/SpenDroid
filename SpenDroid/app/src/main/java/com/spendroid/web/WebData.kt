package com.spendroid.web

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.spendroid.BudgetApplication
import com.spendroid.domain.BudgetPace
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.Direction
import com.spendroid.domain.PeriodRows
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.domain.toRecurringRule
import com.spendroid.ui.accountColour
import com.spendroid.ui.payeeColour
import com.spendroid.ui.theme.Accent
import com.spendroid.ui.tidyPayee
import com.spendroid.ui.visual
import com.spendroid.widget.updatedLabel
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the page shows, as one JSON document: the same budget, accounts, transactions and
 * regular payments the app shows, worked out by the same code. Amounts are in pence; the page
 * formats them. Read-only: nothing here changes anything.
 */
object WebData {

    private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

    suspend fun json(context: Context): String {
        val app = context.applicationContext as BudgetApplication
        val repo = app.repository
        val s = repo.budgetSnapshot()
        val accounts = repo.accounts()
        val transactions = repo.transactions()
        val accent = Accent.from(repo.accent.first()).colour
        val own = repo.accountColours.first()
        val rules = repo.categoryRules.first()
        val colourOf = { id: String? -> accountColour(accounts.firstOrNull { it.id == id }, accounts, own, accent).hex() }
        val root = JSONObject()
        root.put("accent", accent.hex())
        root.put("updated", updatedLabel(accounts).orEmpty())
        root.put("accounts", JSONArray(accounts.map { a ->
            val owed = s?.cardBills?.firstOrNull { it.cardAccountId == a.id }?.outstandingMinor
            JSONObject()
                .put("id", a.id).put("label", a.label).put("institution", a.institutionName)
                .put("type", a.accountType.name).put("colour", colourOf(a.id))
                .put("balance", owed?.let { -it } ?: a.balanceMinor ?: JSONObject.NULL)
        }))
        root.put("categories", JSONArray(Category.entries.map { c ->
            JSONObject().put("name", c.name).put("label", c.label).put("colour", c.visual.color.hex())
        }))

        if (s != null) {
            val pace = BudgetPace.of(s)
            root.put("budget", JSONObject()
                .put("available", s.availableToSpend)
                .put("pace", pace.name)
                .put("nextIncome", s.nextIncomeDate?.toString() ?: JSONObject.NULL)
                .put("days", s.daysUntilNextIncome ?: JSONObject.NULL)
                .put("cycleStart", s.cycleStart.toString())
                .put("cycleEnd", s.cycleEnd?.toString() ?: JSONObject.NULL)
                .put("asOf", s.asOf.toString())
                .put("used", BudgetPace.usedFraction(s) ?: JSONObject.NULL)
                .put("elapsed", BudgetPace.elapsedFraction(s) ?: JSONObject.NULL)
                .put("usedMinor", s.usedThisCycle).put("spendable", s.spendableThisCycle)
                .put("spentThisCycle", s.spentThisCycle)
                .put("spentToday", s.spentTodayFromAccountsMinor + s.spentTodayOnCardsMinor)
                .put("model", s.budgetModel.name)
                .put("setAside", s.setAsideMinor)
                .put("income", s.averageMonthlyIncome).put("bills", s.fixedMonthlyOutgoings).put("toSpend", s.variableMonthlyBudget))
            root.put("upcoming", JSONArray(s.upcomingFixed.map { p ->
                val card = p.rule.key.startsWith(CARD_BILL_KEY_PREFIX)
                JSONObject().put("name", p.rule.payee.tidyPayee()).put("date", p.dueDate.toString()).put("amount", p.amountMinor)
                    .put("card", card).put("variable", p.rule.isVariable)
                    .put("colour", if (card) colourOf(s.cardBills.firstOrNull { p.rule.payee.contains(it.cardLabel) }?.cardAccountId) else payeeColour(p.rule.payee).hex())
            }))
            val others = accounts.filter { it.id != s.potAccountId }.map { it.id }
            root.put("others", JSONArray(PeriodRows.rows(others, s, accounts, transactions).filter { it.kind != PeriodRows.Kind.BUDGET }.map { r ->
                JSONObject().put("accountId", r.accountId).put("label", r.label).put("kind", r.kind.name).put("colour", colourOf(r.accountId))
                    .put("spent", r.spentMinor).put("against", r.againstMinor ?: JSONObject.NULL).put("used", r.used ?: JSONObject.NULL)
                    .put("gone", r.gone ?: JSONObject.NULL).put("toCome", r.toComeMinor)
                    .put("against_label", if (r.against == PeriodRows.Against.LIMIT) "limit" else "usual")
            }))
            // Where each pay cycle began, for the Insights page to group spending by.
            val starts = s.primaryIncomeRule?.let { rule ->
                transactions.filter { RecurringAnalyzer.matches(rule, it) }.mapNotNull { RecurringAnalyzer.parseBookingDate(it.bookingDate) }.distinct().sorted()
            }.orEmpty()
            root.put("cycleStarts", JSONArray(starts.map { it.toString() }))
        }

        val settled = s?.confirmedSettlementKeys.orEmpty()
        root.put("transactions", JSONArray(transactions.filter { "${it.accountId}|${it.transactionId}" !in settled }.map { tx ->
            val c = CategoryEngine.classify(tx, rules, s?.cardPaymentKeys.orEmpty(), s?.creditCardAccountIds.orEmpty())
            JSONObject()
                .put("accountId", tx.accountId).put("date", tx.bookingDate).put("payee", tx.payee.tidyPayee())
                .put("description", tx.description ?: "").put("amount", tx.amountMinor).put("pending", tx.isPending)
                .put("category", c.name).put("transfer", tx.isInternalTransfer).put("recurring", tx.isRecurring)
                .put("seen", tx.transactionId.startsWith(com.spendroid.domain.NotificationSpend.SEEN_PREFIX))
        }))

        val ignored = repo.ignoredRules.first()
        val all = RecurringAnalyzer.analyze(transactions) + repo.manualRules.first().mapNotNull { it.toRecurringRule() }
        root.put("rules", JSONArray(all.map { r ->
            JSONObject().put("payee", r.payee.tidyPayee()).put("amount", r.amountMinor).put("cadence", r.cadence.name)
                .put("direction", r.direction.name).put("ignored", r.key in ignored).put("manual", r.isManual)
                .put("variable", r.isVariable).put("last", r.lastOccurrence.toString())
                .put("paidFrom", r.paidFrom ?: JSONObject.NULL)
                .put("income", r.direction == Direction.IN)
        }))
        return root.toString()
    }
}
