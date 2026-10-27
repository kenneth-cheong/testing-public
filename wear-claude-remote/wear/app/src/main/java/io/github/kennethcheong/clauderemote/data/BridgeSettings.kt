package io.github.kennethcheong.clauderemote.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One paired computer. [name] is its short name, e.g. "MacBook Air"; blank until the bridge reports it. */
data class SavedBridge(val id: String, val url: String, val name: String, val token: String)

/**
 * The bridges this watch is paired with, each with its own device token.
 *
 * Tokens are encrypted with an AES key that lives in the Android Keystore
 * (hardware-backed on current watches) and never leaves it, so the stored
 * values are useless if copied off the device.
 */
class BridgeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    /** The address last typed on the setup screen, used to pre-fill it. */
    var url: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var bridges: List<SavedBridge>
        get() = load()
        set(value) = save(value)

    private fun load(): List<SavedBridge> {
        val stored = prefs.getString(KEY_BRIDGES, null) ?: return migrateSingleBridge()
        val entries = try {
            json.decodeFromString<List<StoredBridge>>(stored)
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
        // An entry whose token no longer decrypts (key wiped) is dropped; that computer has to be paired again.
        return entries.mapNotNull { e -> decrypt(e.token)?.let { SavedBridge(e.id, e.url, e.name, it) } }
    }

    private fun save(bridges: List<SavedBridge>) {
        val stored = bridges.map { StoredBridge(it.id, it.url, it.name, encrypt(it.token)) }
        prefs.edit().putString(KEY_BRIDGES, json.encodeToString(stored)).apply()
    }

    /** Version 0.2 kept one url and token; carry that pairing over so nobody has to pair again. */
    private fun migrateSingleBridge(): List<SavedBridge> {
        val url = prefs.getString(KEY_URL, null)
        val token = prefs.getString(KEY_TOKEN, null)?.let(::decrypt)
        val bridges = if (url != null && token != null) listOf(SavedBridge(UUID.randomUUID().toString(), url, "", token)) else emptyList()
        save(bridges)
        prefs.edit().remove(KEY_TOKEN).apply()
        return bridges
    }

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
        // Key was wiped (e.g. device reset or app reinstall); the watch simply has to pair again.
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

    /** What is written to disk: the same fields, with the token encrypted. */
    @Serializable
    private data class StoredBridge(val id: String, val url: String, val name: String, val token: String)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_BRIDGES = "bridges"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bridge-token-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
