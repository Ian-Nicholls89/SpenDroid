package com.spendroid.data

import com.spendroid.data.db.AccountType
import com.spendroid.data.remote.AccountDetailsDto
import com.spendroid.data.remote.AccountInfoDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two credit cards were filed as current accounts because detection read one field that the
 * bank never sent. Everything downstream - the bill forecast, excluding card spending from
 * the budget, choosing the right balance - depends on getting this right.
 */
class AccountTypeDetectionTest {

    private fun detect(
        metadataName: String? = null,
        name: String? = null,
        product: String? = null,
        cashAccountType: String? = null,
    ) = GoCardlessRepository.detectAccountTypeFor(
        AccountDetailsDto(name = metadataName),
        AccountInfoDto(name = name, product = product, cashAccountType = cashAccountType),
    )

    @Test
    fun `the ISO cash account type settles it`() {
        assertEquals(AccountType.CREDIT_CARD, detect(cashAccountType = "CARD"))
        assertEquals(AccountType.CREDIT_CARD, detect(cashAccountType = "card"))
    }

    @Test
    fun `a card is found from product when the bank sends no name`() {
        // This is the case that failed: metadata.name null, everything in product.
        assertEquals(AccountType.CREDIT_CARD, detect(product = "Tesco Credit Card"))
        assertEquals(AccountType.CREDIT_CARD, detect(name = "Nectar Credit Card"))
    }

    @Test
    fun `current and joint accounts are unaffected`() {
        assertEquals(AccountType.PERSONAL, detect(product = "1st Account"))
        assertEquals(AccountType.JOINT, detect(product = "Joint Current Account"))
    }

    @Test
    fun `joint beats the weak card signal`() {
        assertEquals(AccountType.JOINT, detect(product = "Joint Account with debit card"))
    }

    @Test
    fun `savings are recognised`() {
        assertEquals(AccountType.SAVINGS, detect(product = "Online Savings Account"))
    }

    @Test
    fun `a credit union is not a credit card`() {
        assertEquals(AccountType.PERSONAL, detect(product = "Credit Union Current Account"))
    }
}
