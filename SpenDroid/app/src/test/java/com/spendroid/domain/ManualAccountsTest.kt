package com.spendroid.domain

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.importer.StatementFile
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.9: accounts with no bank link - their balance, the balance check, and waiting for statements. */
class ManualAccountsTest {

    private val today = LocalDate.of(2026, 10, 18)
    private val account = AccountEntity(
        id = "m1", institutionName = "Barclays", label = "Everyday", currency = "GBP", balanceMinor = null, lastSynced = 0,
        accountType = AccountType.PERSONAL, manual = true, anchorMinor = 120_640, anchorDate = "2026-10-15",
    )
    private var seq = 0
    private fun tx(date: String, minor: Long, id: String = "${DuplicateCheck.IMPORTED_PREFIX}b:${seq++}") = TransactionEntity(
        accountId = "m1", transactionId = id, bookingDate = date, valueDate = null, amountMinor = minor,
        currency = "GBP", payee = "Shop", description = null, isPending = false, rawJson = null,
    )
    private fun seen(id: String, minor: Long, day: LocalDate, account: String? = "m1", provisional: String? = null, guessed: Boolean = false) =
        SeenSpendEntity(
            id = id, source = "bank", seenAt = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), amountMinor = -minor,
            currency = "GBP", merchant = "Tesco", accountId = account, provisionalAccountId = provisional, accountGuessed = guessed,
        )

    @Test
    fun `the balance is the anchor moved on by what came after it`() {
        val rows = listOf(tx("2026-10-14", -5_000), tx("2026-10-15", -2_000), tx("2026-10-16", -1_799), tx("2026-10-17", 10_000))
        // Rows on or before the anchor's day are already in it.
        assertEquals(120_640L - 1_799 + 10_000, ManualAccounts.balance(account, rows))
        assertNull(ManualAccounts.balance(account.copy(anchorMinor = null), rows))
    }

    @Test
    fun `payments read from notifications are left out, as a bank's balance leaves them out`() {
        val seenRow = tx("2026-10-17", -420, id = NotificationSpend.SEEN_PREFIX + "x")
        assertEquals(120_640L, ManualAccounts.balance(account, listOf(seenRow)))
    }

    @Test
    fun `a gap at the check is money gone that nothing accounts for`() {
        val coffee = seen("s1", 420, today)
        // Expected: the balance less what's been seen but not listed yet.
        val check = ManualAccounts.check(account, typedMinor = 114_360, transactions = emptyList(), seen = listOf(coffee))
        assertEquals(120_640L - 420, check.expectedMinor)
        assertEquals(5_860L, check.gapMinor)

        val (anchored, entry) = ManualAccounts.afterCheck(account, check, listOf(coffee), today, "c1")
        // The coffee is in what the bank shows; SpenDroid still counts it, so it goes back in the anchor.
        assertEquals(114_360L + 420, anchored.anchorMinor)
        assertEquals(today.toString(), anchored.anchorDate)
        assertEquals(-5_860L, entry!!.amountMinor)
        assertTrue(entry.transactionId.startsWith(ManualAccounts.CHECK_PREFIX))
        // After the check, what the budget sees - balance less counted coffee - is what the bank said.
        assertEquals(114_360L, ManualAccounts.balance(anchored, listOf(entry))!! - 420)
    }

    @Test
    fun `more than expected is no spending, just a new balance`() {
        val check = ManualAccounts.check(account, typedMinor = 125_000, transactions = emptyList(), seen = emptyList())
        assertEquals(-4_360L, check.gapMinor)
        assertNull(ManualAccounts.afterCheck(account, check, emptyList(), today, "c1").second)
    }

    @Test
    fun `a payment guessed onto the other account can explain the gap`() {
        val tesco = seen("s2", 3_855, today.minusDays(1), account = "joint", guessed = true)
        val check = ManualAccounts.check(account, typedMinor = 120_640 - 3_855 - 1_860, transactions = emptyList(), seen = listOf(tesco), elsewhere = listOf(tesco))
        assertEquals(tesco, check.misplaced)
        // Bigger than the gap: not it.
        val small = ManualAccounts.check(account, typedMinor = 120_640 - 1_000, transactions = emptyList(), seen = listOf(tesco), elsewhere = listOf(tesco))
        assertNull(small.misplaced)
    }

    @Test
    fun `a statement explains the balance-check entries in its days, and moves the anchor on`() {
        val entry = tx("2026-10-18", -5_860, id = ManualAccounts.CHECK_PREFIX + "c1")
        val older = tx("2026-10-01", -1_000, id = ManualAccounts.CHECK_PREFIX + "c0")
        assertEquals(listOf(entry), ManualAccounts.explained(listOf(entry, older), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20)))
        assertEquals(99_000L, ManualAccounts.anchorFrom(account, LocalDate.of(2026, 10, 20), 99_000)!!.anchorMinor)
        // An older statement never winds the balance back.
        assertNull(ManualAccounts.anchorFrom(account, LocalDate.of(2026, 9, 30), 99_000))
    }

    @Test
    fun `a payment seen on a manual account waits for the statement that covers it`() {
        val coffee = seen("s1", 420, LocalDate.of(2026, 10, 1))
        val longAfter = LocalDate.of(2026, 11, 20).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        // No statement yet: not "not reported", however long, and not forgotten.
        assertTrue(NotificationSpend.unconfirmed(listOf(coffee), longAfter, mapOf("m1" to null)).isEmpty())
        assertTrue(NotificationSpend.forgettable(listOf(coffee), longAfter, mapOf("m1" to null)).isEmpty())
        // A statement covering it, a few days past, that didn't list it: now it's worth asking.
        val covered = mapOf("m1" to LocalDate.of(2026, 10, 10))
        assertEquals(listOf(coffee), NotificationSpend.unconfirmed(listOf(coffee), longAfter, covered))
        assertEquals(listOf(coffee), NotificationSpend.forgettable(listOf(coffee), longAfter, covered))
        // Only just past it: the bank may not have posted it yet.
        assertTrue(NotificationSpend.unconfirmed(listOf(coffee), longAfter, mapOf("m1" to LocalDate.of(2026, 10, 3))).isEmpty())
        // A bank-linked account keeps the week.
        val linked = coffee.copy(accountId = "bank-1")
        assertEquals(listOf(linked), NotificationSpend.unconfirmed(listOf(linked), longAfter, mapOf("m1" to null)))
    }

    @Test
    fun `statements and checks fall due as the user set them`() {
        assertTrue(ManualAccounts.statementDue(null, 7, today))
        assertFalse(ManualAccounts.statementDue(today.minusDays(3), 7, today))
        assertTrue(ManualAccounts.statementDue(today.minusDays(7), 7, today))
        assertFalse(ManualAccounts.statementDue(null, 0, today))

        // Sunday 18 Oct: due unless checked today; on Saturday, checked last Sunday is enough.
        assertTrue(ManualAccounts.balanceDue(today.minusDays(1), ManualAccounts.BalanceWhen.SUNDAY, today, null))
        assertFalse(ManualAccounts.balanceDue(today, ManualAccounts.BalanceWhen.SUNDAY, today, null))
        assertFalse(ManualAccounts.balanceDue(LocalDate.of(2026, 10, 11), ManualAccounts.BalanceWhen.SUNDAY, LocalDate.of(2026, 10, 17), null))
        assertTrue(ManualAccounts.balanceDue(LocalDate.of(2026, 9, 29), ManualAccounts.BalanceWhen.PAYDAY, today, LocalDate.of(2026, 9, 30)))
        assertFalse(ManualAccounts.balanceDue(null, ManualAccounts.BalanceWhen.NEVER, today, null))
    }

    @Test
    fun `a CSV's closing balance is the newest day's last entry, whichever way round it's written`() {
        val oldestFirst = "Date,Description,Amount,Balance\n01/10/2026,A,-10.00,500.00\n02/10/2026,B,-5.00,495.00\n02/10/2026,C,-1.00,494.00\n"
        val p1 = StatementFile.parse(oldestFirst)
        assertEquals(LocalDate.of(2026, 10, 2) to 49_400L, StatementFile.closingBalance(p1, StatementFile.guess(p1, null, false)))
        val newestFirst = "Date,Description,Amount,Balance\n02/10/2026,C,-1.00,494.00\n02/10/2026,B,-5.00,495.00\n01/10/2026,A,-10.00,500.00\n"
        val p2 = StatementFile.parse(newestFirst)
        assertEquals(LocalDate.of(2026, 10, 2) to 49_400L, StatementFile.closingBalance(p2, StatementFile.guess(p2, null, false)))
        // No balance column, no closing balance.
        val bare = StatementFile.parse("Date,Description,Amount\n01/10/2026,A,-10.00\n")
        assertNull(StatementFile.closingBalance(bare, StatementFile.guess(bare, null, false)))
    }

    @Test
    fun `an OFX gives its own closing balance`() {
        val ofx = """
            OFXHEADER:100
            <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261014<TRNAMT>-12.40<NAME>CO-OP</STMTTRN>
            </BANKTRANLIST><LEDGERBAL><BALAMT>1206.40<DTASOF>20261015</LEDGERBAL></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
        """.trimIndent()
        val p = StatementFile.parse(ofx)
        assertEquals(LocalDate.of(2026, 10, 15) to 120_640L, StatementFile.closingBalance(p, StatementFile.Layout(emptyList(), emptyList())))
    }
}
