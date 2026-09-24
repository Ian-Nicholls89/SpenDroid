package com.spendroid.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reminder to reauthorise depends on knowing when access was granted. That date was never
 * written down, so every read called it "now" and the reminder could never fire.
 */
class ConnectionExpiryTest {

    private val granted = LocalDate.of(2026, 7, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private val connection = Connection(
        institutionId = "NATWEST_NWBKGB2L",
        institutionName = "NatWest",
        requisitionId = "req-1",
        accountIds = listOf("a", "b"),
        createdAt = granted,
    )

    @Test
    fun `the grant date survives being saved and read back`() {
        val json = SecretsStore.connectionsToJson(listOf(connection))
        assertEquals(listOf(connection), SecretsStore.parseConnections(json))
    }

    @Test
    fun `a connection saved without a date reads as unknown, not as today`() {
        val old = """[{"institutionId":"X","institutionName":"X","requisitionId":"r","accountIds":[]}]"""
        val parsed = SecretsStore.parseConnections(old).single()
        assertEquals(0L, parsed.createdAt)
        assertNull(parsed.daysUntilExpiry())
    }

    @Test
    fun `access runs ninety days from the grant`() {
        assertEquals(14, connection.daysUntilExpiry(LocalDate.of(2026, 9, 15), ZoneOffset.UTC))
        assertEquals(-1, connection.daysUntilExpiry(LocalDate.of(2026, 9, 30), ZoneOffset.UTC))
    }
}
