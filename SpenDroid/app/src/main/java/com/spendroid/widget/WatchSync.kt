package com.spendroid.widget

import android.content.Context
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.spendroid.BudgetApplication
import androidx.compose.ui.graphics.toArgb
import com.spendroid.domain.BudgetModel
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.RecurringAnalyzer
import com.spendroid.ui.visual
import com.spendroid.ui.tidyPayee
import kotlinx.coroutines.flow.first
import com.spendroid.domain.BudgetPace
import com.spendroid.domain.CARD_BILL_KEY_PREFIX
import com.spendroid.domain.CreditCardEngine
import com.spendroid.ui.cardPaceLine
import com.spendroid.ui.formatMoney
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.tasks.await

/**
 * Sends the watch what the phone's home screen shows, whenever the widget redraws: the
 * complication's figures, and everything the watch app's screens show when it is tapped.
 *
 * Everything is formatted here, so the two apps word things the same way. The keys are shared
 * with the watch app's BudgetReading by value - the two are separate apps - so a key renamed
 * here must be renamed there. With no watch, or no Play services, this does nothing.
 */
// The data layer only tells the watch when the item changes, so nothing in it changes by itself
// (no timestamp): an hourly push with nothing new costs the watch nothing.
internal object WatchSync {

    private const val PATH = "/spendroid/budget"
    /** Between the account and the transaction in a recent payment's id. */
    const val SEP = "\u001F"
    private const val RECENT = 8
    /** Money in, and money moving between accounts: not categories anyone spends in. */
    private val NOT_SPENDING = setOf(Category.SALARY, Category.TRANSFERS, Category.CARD_BILL)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
    private val SHORT_DAY = DateTimeFormatter.ofPattern("d MMM")

    suspend fun push(context: Context) {
        runCatching {
            val app = context.applicationContext as? BudgetApplication ?: return
            val repo = app.repository
            val s = SharedSnapshot.get(context) ?: return
            val money = { minor: Long -> formatMoney(minor, s.baseCurrency) }
            val elapsed = BudgetPace.elapsedFraction(s)
            val days = s.daysUntilNextIncome

            val request = PutDataMapRequest.create(PATH).apply {
                dataMap.apply {
                    // The complication.
                    putString("available", poundsOnly(s.availableToSpend, s.baseCurrency))
                    putFloat("budgetLeft", BudgetPace.remainingFraction(s))
                    putFloat("cycleLeft", elapsed?.let { 1f - it } ?: -1f)
                    putString("pace", BudgetPace.of(s).name)

                    // The watch app published with this phone app, so the watch can update itself.
                    putInt("wearLatestCode", com.spendroid.BuildConfig.WEAR_VERSION_CODE)
                    putString("wearLatestName", com.spendroid.BuildConfig.WEAR_VERSION_NAME)
                    putString("wearApkUrl", com.spendroid.watch.WatchInstaller.apkUrl())

                    // Screen 1.
                    putString("availableFull", money(s.availableToSpend))
                    putString("daysLine", days?.let { d ->
                        "$d day${if (d == 1) "" else "s"}" + (if (d > 0) " · ${money(s.availableToSpend / d)} a day" else "")
                    }.orEmpty())
                    putString("incomeLine", s.nextIncomeDate?.let { "Income ${it.format(DAY)}" }.orEmpty())

                    // Screen 2.
                    putString("todayAccounts", money(s.spentTodayFromAccountsMinor))
                    putString("todayCards", money(s.spentTodayOnCardsMinor))
                    val cardsPending = s.spentTodayOnCardsPendingMinor
                    putString("todayCardsPending", if (cardsPending > 0L) "incl. ${money(cardsPending)} pending" else "")
                    putString("thisCycle", money(s.spentThisCycle))
                    val showBalance = s.budgetModel != BudgetModel.FRESH_START
                    putString("inAccount", s.potBalanceMinor?.takeIf { showBalance }?.let(money).orEmpty())

                    // Screen 3.
                    putLongArray("week", s.lastSevenDaysMinor.toLongArray())
                    putString("weekEnds", s.asOf.toString())
                    putString("updated", updatedLabel(repo.accounts()).orEmpty())

                    // Screens 4 on: one per card.
                    putDataMapArrayList("cards", ArrayList(s.cardBills.map { card(it, money) }))

                    // Recent spending, newest first, to recategorise from the wrist.
                    val rules = repo.categoryRules.first()
                    val all = repo.transactions()
                    putDataMapArrayList(
                        "recent",
                        ArrayList(
                            all.asSequence()
                                .filter { it.amountMinor < 0 && !it.isInternalTransfer }
                                .sortedWith(compareByDescending<com.spendroid.data.db.TransactionEntity> { it.bookingDate }.thenByDescending { it.isPending })
                                .take(RECENT)
                                .map { tx ->
                                    val category = CategoryEngine.classify(tx, rules, s.cardPaymentKeys, s.creditCardAccountIds)
                                    DataMap().apply {
                                        putString("id", tx.accountId + SEP + tx.transactionId)
                                        putString("payee", tx.payee.tidyPayee())
                                        putString("amount", money(-tx.amountMinor))
                                        putString("category", category.label)
                                        putInt("colour", category.visual.color.toArgb())
                                        putBoolean("pending", tx.isPending)
                                    }
                                }
                                .toList(),
                        ),
                    )
                    putDataMapArrayList(
                        "categoryOptions",
                        ArrayList(
                            Category.entries.filter { it !in NOT_SPENDING }.map {
                                DataMap().apply {
                                    putString("name", it.name)
                                    putString("label", it.label)
                                    putInt("colour", it.visual.color.toArgb())
                                }
                            },
                        ),
                    )

                    // This cycle by category, against the user's limits where set - the Insights
                    // tab's figures, as the balance widget draws them.
                    val cycle = all.filter { tx ->
                        RecurringAnalyzer.parseBookingDate(tx.bookingDate)?.let { !it.isBefore(s.cycleStart) } == true
                    }
                    val goals = repo.budgetGoals.first().associateBy { it.category }
                    putDataMapArrayList(
                        "categories",
                        ArrayList(
                            CategoryEngine.spendingBreakdown(cycle, rules, s.cardPaymentKeys, s.creditCardAccountIds)
                                .filter { it.category !in NOT_SPENDING }
                                .map { total ->
                                    val limit = goals[total.category.name]?.limitMinor?.takeIf { it > 0L }
                                    DataMap().apply {
                                        putString("label", total.category.label)
                                        putString("spent", money(total.amountMinor))
                                        putString("limit", limit?.let(money).orEmpty())
                                        putFloat("share", limit?.let { (total.amountMinor.toFloat() / it).coerceIn(0f, 1f) } ?: -1f)
                                        putBoolean("over", limit != null && total.amountMinor > limit)
                                        putInt("colour", total.category.visual.color.toArgb())
                                        putLong("minor", total.amountMinor)
                                    }
                                },
                        ),
                    )

                    // The last screen but one, and its summary.
                    putString("upcomingTotal", money(s.upcomingFixed.sumOf { it.amountMinor }))
                    putDataMapArrayList(
                        "upcoming",
                        ArrayList(
                            s.upcomingFixed.map { p ->
                                DataMap().apply {
                                    val isCard = p.rule.key.startsWith(CARD_BILL_KEY_PREFIX)
                                    putString("name", p.rule.payee)
                                    putString("date", (if (isCard) "~" else "") + p.dueDate.format(SHORT_DAY))
                                    putString("amount", money(p.amountMinor))
                                }
                            },
                        ),
                    )
                }
            }
            // Urgent, so the watch hears now rather than whenever the phone next batches.
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()).await()
        }
    }

    private fun card(bill: CreditCardEngine.CardBill, money: (Long) -> String): DataMap = DataMap().apply {
        val line = cardPaceLine(bill)
        val cap = bill.capMinor?.takeIf { it > 0L }
        putString("name", bill.cardLabel)
        putString("since", money(bill.unbilledMinor))
        putString("pending", if (bill.pendingMinor > 0L) "incl. ${money(bill.pendingMinor)} pending" else "")
        putString("pace", bill.projectedMinor?.let { "On pace for ~${money(it)}" }.orEmpty())
        putString(
            "cap",
            cap?.let { (if (bill.capSource == CreditCardEngine.CapSource.USER) "your limit " else "usual bill ") + money(it) }.orEmpty(),
        )
        putString(
            "billed",
            listOfNotNull(
                "Billed ${money(bill.billedMinor)}",
                bill.dueDate?.let { "due ~${it.format(SHORT_DAY)}" },
            ).joinToString(" · "),
        )
        putFloat("capShare", cap?.let { (bill.unbilledMinor.toFloat() / it).coerceIn(0f, 1f) } ?: -1f)
        val cycle = bill.statementClose?.let { close -> bill.nextStatementClose?.let { ChronoUnit.DAYS.between(close, it) } }
        putFloat(
            "statementGone",
            if (cycle != null && cycle > 0 && bill.statementDaysElapsed != null) {
                (bill.statementDaysElapsed.toFloat() / cycle).coerceIn(0f, 1f)
            } else {
                -1f
            },
        )
        putBoolean("over", line?.over == true)
    }

    /** "£430": a complication has room for a few characters, not pence. As the widget does it. */
    private fun poundsOnly(minor: Long, currency: String): String =
        formatMoney((minor / 100L) * 100L, currency).replace(Regex("[.,]00\\b"), "")
}
