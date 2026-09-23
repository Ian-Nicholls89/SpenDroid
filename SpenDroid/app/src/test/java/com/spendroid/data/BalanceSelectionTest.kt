package com.spendroid.data

import com.spendroid.data.db.AccountType
import com.spendroid.data.remote.AmountDto
import com.spendroid.data.remote.BalanceDto
import com.spendroid.data.remote.BalancesDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Banks return different sets of balance types, which is why one credit card looked right
 * and another showed its credit limit. These pin the choice down per account type.
 */
class BalanceSelectionTest {

    private fun bal(type: String, amount: String, creditLimitIncluded: Boolean? = null) =
        BalanceDto(type, AmountDto(amount, "GBP"), creditLimitIncluded)

    private fun pick(balances: List<BalanceDto>, type: AccountType) =
        GoCardlessRepository.selectBalance(BalancesDto(balances), type)?.first

    @Test
    fun `a credit card reporting available credit shows what is owed, not the headroom`() {
        // Tesco: returns both. interimAvailable is the unused limit.
        val owed = pick(
            listOf(
                bal("interimAvailable", "7190.54", creditLimitIncluded = true),
                bal("closingBooked", "-855.80"),
            ),
            AccountType.CREDIT_CARD,
        )
        assertEquals(-85580L, owed)
    }

    @Test
    fun `a credit card that omits available still works`() {
        // NatWest: no interimAvailable at all, which is why it looked fine already.
        val owed = pick(listOf(bal("expected", "-855.80")), AccountType.CREDIT_CARD)
        assertEquals(-85580L, owed)
    }

    @Test
    fun `a balance including the credit limit is never chosen`() {
        val owed = pick(
            listOf(
                bal("closingBooked", "7190.54", creditLimitIncluded = true),
                bal("interimBooked", "-120.00"),
            ),
            AccountType.CREDIT_CARD,
        )
        assertEquals(-12000L, owed)
    }

    @Test
    fun `a current account still prefers what is available to spend`() {
        val available = pick(
            listOf(
                bal("closingBooked", "2548.65"),
                bal("interimAvailable", "2400.00"),
            ),
            AccountType.PERSONAL,
        )
        assertEquals(240000L, available)
    }

    @Test
    fun `a card falls back to any figure that is not the headroom`() {
        // A bank sending none of the preferred types still must not show the limit.
        val owed = pick(
            listOf(
                bal("interimAvailable", "7190.54", creditLimitIncluded = true),
                bal("nonInvoiced", "-42.00"),
            ),
            AccountType.CREDIT_CARD,
        )
        assertEquals(-4200L, owed)
    }

    @Test
    fun `only available balances yields null rather than a wrong number`() {
        assertEquals(null, pick(listOf(bal("forwardAvailable", "99.99")), AccountType.CREDIT_CARD))
    }
}
