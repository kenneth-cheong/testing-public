package io.github.kennethcheong.clauderemote.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the bridge is and this watch's device token.
 *
 * The token is encrypted with an AES key that lives in the Android Keystore
 * (hardware-backed on current watches) and never leaves it, so the stored
 * value is useless if copied off the device.
 */
class BridgeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    var url: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)?.let(::decrypt)
        set(value) = prefs.edit().putString(KEY_TOKEN, value?.let(::encrypt)).apply()

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        }
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        // Key was wiped (e.g. device reset); the watch simply has to pair again.
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bridge-token-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
