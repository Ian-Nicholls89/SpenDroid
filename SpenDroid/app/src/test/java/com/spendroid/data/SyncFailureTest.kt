package com.spendroid.data

import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.AccountType
import com.spendroid.widget.updatedLabel
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * The reported case: First Direct's accounts failed to sync at 14:00 and kept their 09:29
 * figures, while the widget said "Updated 14:08" on the strength of a credit card.
 */
class SyncFailureTest {

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test
    fun `a refusal says why`() {
        assertEquals(SyncFailure.Reason.LIMIT, GoCardlessRepository.failureReason(http(429)))
        assertEquals(SyncFailure.Reason.REAUTH, GoCardlessRepository.failureReason(http(401)))
        assertEquals(SyncFailure.Reason.OFFLINE, GoCardlessRepository.failureReason(IOException()))
        assertEquals(SyncFailure.Reason.ERROR, GoCardlessRepository.failureReason(http(500)))
    }

    private fun account(id: String, type: AccountType, hour: Int, minute: Int) = AccountEntity(
        id = id,
        institutionName = "Bank",
        label = id,
        currency = "GBP",
        balanceMinor = 0,
        lastSynced = LocalDate.now(ZoneOffset.UTC).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli(),
        accountType = type,
    )

    @Test
    fun `updated is the current account's time, not the freshest card's`() {
        val accounts = listOf(
            account("personal", AccountType.PERSONAL, 9, 29),
            account("card", AccountType.CREDIT_CARD, 14, 8),
        )
        assertEquals("Updated 09:29", updatedLabel(accounts, ZoneOffset.UTC))
    }
}
