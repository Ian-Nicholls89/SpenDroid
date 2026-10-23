package com.spendroid.data

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup encrypted with a passphrase the user chose: for one that leaves the phone - to a Google
 * Drive folder - and has to open on another phone, where this phone's keystore isn't. AES-256-GCM,
 * its key drawn from the passphrase slowly (PBKDF2, many rounds), so guessing it is slow too.
 *
 * The file: [MAGIC], then the salt, the IV, and the encrypted backup (its tag at the end). Opened
 * with the wrong passphrase - or altered at all - it fails rather than reading as something else.
 */
object BackupCrypto {

    private val MAGIC = "SPENDROID-BACKUP-1\n".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    private const val IV = 12
    private const val ROUNDS = 210_000

    class WrongPassphrase : Exception("That passphrase doesn't open this backup.")

    fun isEncrypted(bytes: ByteArray): Boolean = bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(json: String, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv)) }
        return MAGIC + salt + iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(bytes: ByteArray, passphrase: CharArray): String {
        require(isEncrypted(bytes)) { "Not an encrypted SpenDroid backup" }
        var at = MAGIC.size
        val salt = bytes.copyOfRange(at, at + SALT).also { at += SALT }
        val iv = bytes.copyOfRange(at, at + IV).also { at += IV }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv)) }
        return try {
            String(cipher.doFinal(bytes, at, bytes.size - at), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw WrongPassphrase()
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ROUNDS, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
