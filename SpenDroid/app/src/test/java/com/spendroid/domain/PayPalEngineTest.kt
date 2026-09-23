package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A PayPal purchase can appear on three rows across two accounts. These pin down which one
 * survives as spending in each funding shape, and that the merchant's name ends up on it.
 */
class PayPalEngineTest {

    private val accounts = listOf(
        AccountEntity(
            id = "bank",
            institutionName = "NatWest",
            label = "Everyday",
            currency = "GBP",
            balanceMinor = 120000,
            lastSynced = 0L,
            accountType = AccountType.PERSONAL,
        ),
        AccountEntity(
            id = "pp",
            institutionName = "PayPal",
            label = "PayPal",
            currency = "GBP",
            balanceMinor = 0,
            lastSynced = 0L,
            accountType = AccountType.PAYPAL,
        ),
    )

    private var seq = 0

    private fun tx(
        account: String,
        date: String,
        amountMinor: Long,
        payee: String,
        description: String? = null,
    ) = TransactionEntity(
        accountId = account,
        transactionId = "tx-${seq++}",
        bookingDate = date,
        valueDate = null,
        amountMinor = amountMinor,
        currency = "GBP",
        payee = payee,
        description = description,
        isPending = false,
        rawJson = null,
    )

    private fun List<TransactionEntity>.on(account: String, payeeStart: String) =
        first { it.accountId == account && it.payee.startsWith(payeeStart) }

    /**
     * The everyday case, including the three-day settlement gap: PayPal pays the merchant on
     * the 12th and collects from the bank on the 15th.
     */
    @Test
    fun `a payment funded in full is renamed and counted once`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -2499, "Steam Games"),
            tx("pp", "2026-09-12", 2499, "Transfer from bank"),
            tx("bank", "2026-09-15", -2499, "PAYPAL *4KDJ2"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        // The bank debit survives as the spending, now with a name worth reading.
        val bankLeg = result.first { it.accountId == "bank" }
        assertEquals("Steam Games", bankLeg.payee)
        assertFalse(bankLeg.isInternalTransfer)
        // The bank's own reference is kept, because it is the audit trail.
        assertEquals("via PayPal · PAYPAL *4KDJ2", bankLeg.description)

        // Both PayPal rows drop out: one would double the spending, the other invent income.
        assertTrue(result.on("pp", "Steam").isInternalTransfer)
        assertTrue(result.on("pp", "Transfer").isInternalTransfer)
    }

    /**
     * The shape this account actually produces: two debits and nothing between them. No
     * top-up row is ever shown, so the pairing cannot rely on one being there.
     */
    @Test
    fun `a purchase with no top-up row is still paired and counted once`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -2499, "Steam Games"),
            tx("bank", "2026-09-15", -2499, "PAYPAL *4KDJ2"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        val bankLeg = result.first { it.accountId == "bank" }
        assertEquals("Steam Games", bankLeg.payee)
        assertFalse(bankLeg.isInternalTransfer)
        assertTrue(result.on("pp", "Steam").isInternalTransfer)

        assertEquals(-2499L, result.filter { !it.isInternalTransfer }.sumOf { it.amountMinor })
    }

    /**
     * Part-funded, with no top-up row to prove it. All that is left to go on is that PayPal
     * spent more than the bank was asked for, so the difference must have been a balance.
     */
    @Test
    fun `a part-funded purchase with no top-up row does not double count`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -2499, "Steam Games"),
            tx("bank", "2026-09-15", -1999, "PAYPAL *4KDJ2"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        // PayPal's row carries the purchase; the under-funded bank leg drops out.
        assertTrue(result.first { it.accountId == "bank" }.isInternalTransfer)
        assertFalse(result.on("pp", "Steam").isInternalTransfer)
        assertEquals(-2499L, result.filter { !it.isInternalTransfer }.sumOf { it.amountMinor })
    }

    /**
     * Two PayPal payments could each explain the same under-funded bank debit. Picking one
     * would move money on a coin toss, so neither is picked.
     */
    @Test
    fun `an ambiguous part-funded match is declined rather than guessed`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -2499, "Steam Games"),
            tx("pp", "2026-09-13", -3200, "Bandcamp"),
            tx("bank", "2026-09-15", -1999, "PAYPAL *4KDJ2"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        assertFalse(result.first { it.accountId == "bank" }.isInternalTransfer)
        assertFalse(result.on("pp", "Steam").isInternalTransfer)
        assertFalse(result.on("pp", "Bandcamp").isInternalTransfer)
    }

    /**
     * PayPal held £5, so the bank was only asked for £19.99 and no PayPal row matches it.
     * Kept for the setups that do show a top-up; harmless where none is ever sent.
     */
    @Test
    fun `a payment part-funded from a balance still totals the purchase`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -2499, "Steam Games"),
            tx("pp", "2026-09-14", 1999, "Transfer from bank"),
            tx("bank", "2026-09-14", -1999, "PAYPAL *4KDJ2"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        // Both legs of the top-up are transfers; PayPal's own rows carry the spending.
        assertTrue(result.first { it.accountId == "bank" }.isInternalTransfer)
        assertTrue(result.on("pp", "Transfer").isInternalTransfer)
        assertFalse(result.on("pp", "Steam").isInternalTransfer)

        // £24.99 spent, of which £19.99 came from the bank and £5 from the balance.
        val counted = result.filter { !it.isInternalTransfer }.sumOf { it.amountMinor }
        assertEquals(-2499L, counted)
    }

    @Test
    fun `a payment made entirely from a balance is left alone`() {
        val transactions = listOf(tx("pp", "2026-09-12", -2499, "Steam Games"))

        val result = PayPalEngine.reconcile(transactions, accounts)

        assertFalse(result.single().isInternalTransfer)
        assertEquals("Steam Games", result.single().payee)
    }

    @Test
    fun `a bank debit with no PayPal reference is never touched`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -2499, "Steam Games"),
            tx("bank", "2026-09-12", -2499, "TESCO STORES 3241"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        assertEquals("TESCO STORES 3241", result.first { it.accountId == "bank" }.payee)
        assertFalse(result.on("pp", "Steam").isInternalTransfer)
    }

    /** Two purchases of the same size in one week must not both claim the same PayPal row. */
    @Test
    fun `equal amounts pair off in the order they happened`() {
        val transactions = listOf(
            tx("pp", "2026-09-10", -1000, "Etsy Seller"),
            tx("pp", "2026-09-14", -1000, "Bandcamp"),
            tx("bank", "2026-09-12", -1000, "PAYPAL *AAA"),
            tx("bank", "2026-09-16", -1000, "PAYPAL *BBB"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)
        val bankLegs = result.filter { it.accountId == "bank" }.sortedBy { it.bookingDate }

        assertEquals("Etsy Seller", bankLegs[0].payee)
        assertEquals("Bandcamp", bankLegs[1].payee)
    }

    @Test
    fun `a debit outside the settlement window is not paired`() {
        val transactions = listOf(
            tx("pp", "2026-08-20", -2499, "Steam Games"),
            tx("bank", "2026-09-15", -2499, "PAYPAL *4KDJ2"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        assertEquals("PAYPAL *4KDJ2", result.first { it.accountId == "bank" }.payee)
    }

    /** PayPal's own boilerplate is no more use than the bank's reference. */
    @Test
    fun `a generic PayPal description does not replace the bank reference`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -2499, "Express Checkout Payment"),
            tx("bank", "2026-09-15", -2499, "PAYPAL *4KDJ2"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)
        val bankLeg = result.first { it.accountId == "bank" }

        assertEquals("PAYPAL *4KDJ2", bankLeg.payee)
        // Still deduplicated, though - the purchase is on the bank leg either way.
        assertTrue(result.on("pp", "Express").isInternalTransfer)
    }

    @Test
    fun `nothing happens without a PayPal account linked`() {
        val transactions = listOf(tx("bank", "2026-09-15", -2499, "PAYPAL *4KDJ2"))
        val bankOnly = accounts.filter { it.id == "bank" }

        assertEquals(transactions, PayPalEngine.reconcile(transactions, bankOnly))
    }

    /**
     * Real rows from a linked account, including the pair that no amount of reading the
     * reference would solve: "PAYPAL PAYMENT" names nothing, and the DisneyPlus payment it
     * belongs to sits three days earlier behind two unrelated transactions.
     *
     * Amount and date are the whole of the evidence, which is why they have to be enough.
     */
    @Test
    fun `the real feed collapses to one row per purchase`() {
        val transactions = listOf(
            tx("bank", "2026-09-17", -990, "PHARMACY2U LEEDS"),
            tx("bank", "2026-09-16", -4547, "PAYPAL *SCANCOMPUTE"),
            tx("bank", "2026-09-16", -1499, "PAYPAL PAYMENT"),
            tx("pp", "2026-09-15", -4547, "Scan Computers"),
            tx("bank", "2026-09-15", -1000, "NDCS"),
            tx("bank", "2026-09-14", -8765, "TESCO STORES 6294"),
            tx("bank", "2026-09-14", -1240, "SumUp *Cakes by"),
            tx("bank", "2026-09-14", -3860, "WILTON RESTAURANT"),
            tx("pp", "2026-09-13", -1499, "DisneyPlus"),
            tx("bank", "2026-09-11", -770, "TESCO STORES 3133"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        // The obvious pair: one day apart, and the reference even hints at it.
        val scan = result.on("bank", "Scan Computers")
        assertEquals("via PayPal · PAYPAL *SCANCOMPUTE", scan.description)
        assertTrue(result.on("pp", "Scan Computers").isInternalTransfer)

        // The pair that matters: three days apart, reference says nothing, still found.
        val disney = result.on("bank", "DisneyPlus")
        assertEquals(-1499L, disney.amountMinor)
        assertEquals("via PayPal · PAYPAL PAYMENT", disney.description)
        assertFalse(disney.isInternalTransfer)
        assertTrue(result.on("pp", "DisneyPlus").isInternalTransfer)

        // SumUp is another payment processor, not PayPal, and is left exactly as it was.
        assertEquals("SumUp *Cakes by", result.on("bank", "SumUp").payee)

        // Every purchase counted once: the feed's own total, with no PayPal row doubling it.
        val counted = result.filter { !it.isInternalTransfer }.sumOf { it.amountMinor }
        assertEquals(-(990 + 4547 + 1499 + 1000 + 8765 + 1240 + 3860 + 770).toLong(), counted)
    }

    /**
     * Generic transfer detection pairs opposite amounts across accounts, so a PayPal top-up
     * and the bank debit that funded it can already be stored as a transfer. If that flag
     * survived while PayPal's own rows were suppressed, the purchase would be counted zero
     * times - which is worse than counting it twice, because nothing looks wrong.
     */
    @Test
    fun `a bank leg already flagged as a transfer is still counted as the purchase`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -2499, "Steam Games"),
            tx("bank", "2026-09-15", -2499, "PAYPAL *4KDJ2").copy(isInternalTransfer = true),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        val bankLeg = result.first { it.accountId == "bank" }
        assertEquals("Steam Games", bankLeg.payee)
        assertFalse(bankLeg.isInternalTransfer)
        assertEquals(-2499L, result.filter { !it.isInternalTransfer }.sumOf { it.amountMinor })
    }

    /**
     * A purchase billed in dollars. No amount can match, because PayPal reports what the
     * merchant charged and the bank reports what it converted that into - which is also why
     * no exchange rate is needed: the bank already did the conversion, and its leg is the
     * one kept, so the sterling figure is exact rather than estimated.
     */
    @Test
    fun `a foreign purchase is paired without any exchange rate`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -2985, "Easynews Holdings, Inc").copy(currency = "USD"),
            tx("bank", "2026-09-15", -2231, "PAYPAL *EASYNEWS"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        val bankLeg = result.first { it.accountId == "bank" }
        assertEquals("Easynews Holdings, Inc", bankLeg.payee)
        assertEquals(-2231L, bankLeg.amountMinor)
        assertEquals("GBP", bankLeg.currency)
        assertTrue(result.on("pp", "Easynews").isInternalTransfer)

        // Counted once, in sterling, at the rate actually charged.
        assertEquals(-2231L, result.filter { !it.isInternalTransfer }.sumOf { it.amountMinor })
    }

    /** An implausible ratio is a coincidence, not a conversion. */
    @Test
    fun `a foreign candidate an order of magnitude away is not paired`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -29850, "Easynews Holdings, Inc").copy(currency = "USD"),
            tx("bank", "2026-09-15", -2231, "PAYPAL *SOMETHING"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        assertEquals("PAYPAL *SOMETHING", result.first { it.accountId == "bank" }.payee)
        assertFalse(result.on("pp", "Easynews").isInternalTransfer)
    }

    /** Two foreign purchases could each explain the debit, so neither is chosen. */
    @Test
    fun `an ambiguous foreign match is declined`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -2985, "Easynews Holdings, Inc").copy(currency = "USD"),
            tx("pp", "2026-09-13", -2900, "Backblaze").copy(currency = "USD"),
            tx("bank", "2026-09-15", -2231, "PAYPAL *SOMETHING"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        assertEquals("PAYPAL *SOMETHING", result.first { it.accountId == "bank" }.payee)
    }

    /**
     * Withdrawing a balance is the mirror of a purchase: money leaves PayPal and arrives in
     * a real account. Counting the arrival as income and the departure as spending would
     * invent money and then spend it.
     */
    @Test
    fun `a withdrawal to the bank is both halves of one movement`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -5000, "Withdraw to bank"),
            tx("bank", "2026-09-14", 5000, "PAYPAL TRANSFER"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        assertTrue(result.first { it.accountId == "bank" }.isInternalTransfer)
        assertTrue(result.on("pp", "Withdraw").isInternalTransfer)
        assertEquals(0L, result.filter { !it.isInternalTransfer }.sumOf { it.amountMinor })
    }

    /** A credit that names nothing to do with PayPal is ordinary income and stays. */
    @Test
    fun `an unrelated credit is not swept up as a withdrawal`() {
        val transactions = listOf(
            tx("pp", "2026-09-12", -5000, "Withdraw to bank"),
            tx("bank", "2026-09-14", 5000, "UKHSA SALARY"),
        )

        val result = PayPalEngine.reconcile(transactions, accounts)

        assertFalse(result.first { it.accountId == "bank" }.isInternalTransfer)
    }
}
