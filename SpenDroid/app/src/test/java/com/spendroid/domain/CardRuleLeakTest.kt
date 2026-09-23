package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Nothing on a credit card is cash moving in or out of the current account. The bill is, and
 * it is computed separately - so a rule detected on the card itself must not reach the
 * budget, in either direction.
 */
class CardRuleLeakTest {

    private val now = LocalDateTime.of(2026, 9, 20, 12, 0)

    private val accounts = listOf(
        AccountEntity(
            id = "bank",
            institutionName = "NatWest",
            label = "Everyday",
            currency = "GBP",
            balanceMinor = 200000,
            lastSynced = 0L,
            accountType = AccountType.PERSONAL,
        ),
        AccountEntity(
            id = "card",
            institutionName = "Tesco Bank",
            label = "Tesco Credit Card",
            currency = "GBP",
            balanceMinor = -40000,
            lastSynced = 0L,
            accountType = AccountType.CREDIT_CARD,
        ),
    )

    private var seq = 0

    private fun tx(
        account: String,
        date: String,
        amountMinor: Long,
        payee: String,
        declaredPayment: Boolean = false,
    ) =
        TransactionEntity(
            accountId = account,
            transactionId = "tx-${seq++}",
            bookingDate = date,
            valueDate = null,
            amountMinor = amountMinor,
            currency = "GBP",
            payee = payee,
            description = null,
            isPending = false,
            rawJson = null,
            isCardPayment = declaredPayment,
        )

    /** Salary in, and a bill settled at the same amount every month on the card. */
    private fun history() = buildList {
        listOf("2026-06-25", "2026-07-25", "2026-08-25").forEach {
            add(tx("bank", it, 250000, "UKHSA SALARY"))
        }
        listOf("2026-06-15", "2026-07-15", "2026-08-15").forEach {
            add(tx("card", it, 30000, "PAYMENT RECEIVED THANK YOU", declaredPayment = true))
            add(tx("card", it, -1499, "NETFLIX.COM"))
        }
    }

    private fun snapshot(transactions: List<TransactionEntity>, accounts: List<AccountEntity>) =
        BudgetEngine.snapshot(
            transactions = transactions,
            rules = RecurringAnalyzer.analyze(transactions),
            accounts = accounts,
            referenceTime = now,
        )

    /**
     * The reported problem: settling the card posts a credit on it, and a steady payment
     * groups into a recurring income rule. Counted, it hands the budget money that was
     * never earned - money that in fact just left the current account.
     */
    @Test
    fun `paying a card bill is not income`() {
        val budget = snapshot(history(), accounts)

        assertEquals(250000L, budget.averageMonthlyIncome)
        assertEquals(
            emptyList<String>(),
            budget.incomeRules.map { it.payee }.filter { it.contains("PAYMENT", true) },
        )
    }

    /**
     * The other direction, and the same double count the card model exists to prevent: a
     * subscription charged to the card is already inside the bill.
     */
    @Test
    fun `a subscription charged to the card is not a fixed outgoing`() {
        val budget = snapshot(history(), accounts)

        assertEquals(0L, budget.fixedMonthlyOutgoings)
        assertEquals(emptyList<String>(), budget.fixedRules.map { it.payee })
    }

    /** The same rows on a current account are ordinary income and outgoings. */
    @Test
    fun `the same transactions on a current account still count`() {
        val onlyCurrent = accounts.map { it.copy(accountType = AccountType.PERSONAL) }
        val budget = snapshot(history(), onlyCurrent)

        assertEquals(280000L, budget.averageMonthlyIncome)
        assertEquals(1499L, budget.fixedMonthlyOutgoings)
    }

    /** A rule the user typed in has no account behind it and is always their own word. */
    @Test
    fun `a manual rule is never dropped`() {
        val manual = RecurringRule(
            key = "${MANUAL_KEY_PREFIX}gym",
            payee = "Gym",
            direction = Direction.OUT,
            amountMinor = -3000,
            currency = "GBP",
            cadence = Cadence.MONTHLY,
            anchorDay = 5,
            lastOccurrence = LocalDate.of(2026, 9, 5),
            occurrences = 3,
            score = 1f,
        )
        val budget = BudgetEngine.snapshot(
            transactions = history(),
            rules = RecurringAnalyzer.analyze(history()) + manual,
            accounts = accounts,
            referenceTime = now,
        )

        assertEquals(3000L, budget.fixedMonthlyOutgoings)
    }

    /**
     * The card side of the same payment is a positive amount, and every positive amount was
     * salary by default - so settling a bill appeared in the list as income received.
     */
    @Test
    fun `the credit on the card reads as a bill settlement, not salary`() {
        val transactions = history()
        val budget = snapshot(transactions, accounts)
        val settlement = transactions.first {
            it.accountId == "card" && it.amountMinor > 0
        }

        assertEquals(
            Category.SALARY,
            CategoryEngine.classify(settlement),
        )
        assertEquals(
            Category.CARD_BILL,
            CategoryEngine.classify(settlement, emptyList(), budget.cardPaymentKeys),
        )
    }
}
