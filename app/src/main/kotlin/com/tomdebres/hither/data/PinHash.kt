package com.tomdebres.hither.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable

/**
 * The admin's PIN as it is kept: never the digits, only PBKDF2-HMAC-SHA256 of them with a random
 * 16-byte salt, slow on purpose (DECISIONS 92). It is kept inside the admin's sealed record besides,
 * so trying the 10,000 PINs against it would first need the TV's Keystore key, which never leaves the
 * TV's hardware; the slowness is for the case where that is ever not so.
 */
@Serializable
data class PinHash(
    /** Base64 of the salt. */
    val salt: String,
    val iterations: Int,
    /** Base64 of the derived 32 bytes. */
    val hash: String,
) {
    /** Whether [pin] is the PIN this was made from; compared in constant time. */
    fun matches(pin: String): Boolean {
        val again = derive(pin, decode(salt), iterations)
        return MessageDigest.isEqual(again, decode(hash))
    }

    override fun toString(): String = "PinHash(…)"

    companion object {
        /**
         * Rounds of PBKDF2. 120,000 took 335 ms a check on `tv31`, so about a second on the Shield's
         * older cores, after every PIN typed; 50,000 is about 140 ms there, under half a second on the
         * Shield. No number of rounds saves 10,000 PINs from an attacker who holds the hash: what keeps
         * them is the Keystore seal around it and the lock-out on wrong tries (DECISIONS 92). It is
         * kept with each hash, so a later build can change it without losing the PIN.
         */
        const val ITERATIONS = 50_000

        fun of(pin: String, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): PinHash {
            val salt = ByteArray(16).also(random::nextBytes)
            return PinHash(encode(salt), iterations, encode(derive(pin, salt, iterations)))
        }

        private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
        private fun decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
    }
}
