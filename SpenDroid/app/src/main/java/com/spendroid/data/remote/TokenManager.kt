package com.spendroid.data.remote

import com.spendroid.data.SecretsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TokenManager(
    private val authApi: GcAuthApi,
    private val secrets: SecretsStore,
) {

    private var accessToken: String? = null
    private var expiresAtMs = 0L

    /** Several requests can find the token expired at once; one refresh serves them all. */
    private val lock = Mutex()

    suspend fun get(): String = lock.withLock {
        if (accessToken == null || expiresAtMs - 60_000 < System.currentTimeMillis()) {
            refresh()
        }
        accessToken ?: error("GoCardless credentials not configured")
    }

    /**
     * Drops the cached token. [get] only refreshes on expiry, so it cannot notice that the
     * underlying credentials changed - without this, a token minted for the old account stays
     * in use until its TTL elapses.
     */
    fun clear() {
        accessToken = null
        expiresAtMs = 0L
    }

    private suspend fun refresh() {
        val storedRefresh = secrets.refreshToken.first()
        if (storedRefresh != null) {
            // Use stored refresh token to get new access token
            try {
                val resp = authApi.refreshToken(mapOf("refresh" to storedRefresh))
                accessToken = resp.access
                expiresAtMs = System.currentTimeMillis() + resp.accessExpires * 1000L
                // Update refresh token if a new one was issued
                resp.refresh?.let { secrets.saveRefreshToken(it) }
                return
            } catch (e: Exception) {
                // Refresh token expired or invalid — fall through to re-authenticate
            }
        }

        // No refresh token or refresh failed — authenticate with secrets
        val id = secrets.secretId.first() ?: error("Missing GoCardless secret_id")
        val key = secrets.secretKey.first() ?: error("Missing GoCardless secret_key")
        val resp = authApi.newToken(mapOf("secret_id" to id, "secret_key" to key))
        accessToken = resp.access
        expiresAtMs = System.currentTimeMillis() + resp.accessExpires * 1000L
        // Store the refresh token for future use
        resp.refresh?.let { secrets.saveRefreshToken(it) }
    }
}