package com.tomdebres.hither.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the password and the session cookie before they are written to DataStore, with an
 * AES-256-GCM key that lives in the Android Keystore and never leaves it. (AndroidX's
 * EncryptedSharedPreferences did the same and is deprecated.) A value that will not open,
 * because the key is gone or the data was tampered with, reads as absent: the user signs in
 * again.
 */
open class SecretBox(private val alias: String = "hither.account") {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    /** The sealed form of [plain]: Base64 of a fresh 12-byte IV followed by the ciphertext. */
    open fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** What [sealed] held, or null when it cannot be opened. */
    fun open(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return null
        return try {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deletes the key itself: whatever it sealed can never be opened again, wherever a copy of it
     * lies. Removing the TV's admin account does it (DECISIONS 92); the next seal makes a new key.
     */
    open fun forget() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
