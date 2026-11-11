package com.kavachcircle.app

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Base64
import android.util.Log
import java.security.Key
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * No static API keys and no home-made crypto.
 * Each guardian link gets its own random 256-bit HMAC-SHA256 key, exchanged only by an
 * in-person QR scan and then sealed inside Android Keystore (it cannot be read back out).
 */
object Crypto {
    private const val KS = "AndroidKeyStore"
    private const val ALG = "HmacSHA256"
    private val rng = SecureRandom()

    fun newSecret(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun importKey(c: Context, alias: String, raw: ByteArray) {
        try {
            val ks = KeyStore.getInstance(KS)
            ks.load(null)
            ks.setEntry(
                alias,
                KeyStore.SecretKeyEntry(SecretKeySpec(raw, ALG)),
                KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).build()
            )
        } catch (e: Exception) {
            Log.w("Kavach", "Keystore import failed, using app-private fallback", e)
            Prefs.setKeyFallback(c, alias, Base64.encodeToString(raw, Base64.NO_WRAP))
        }
    }

    private fun loadKey(c: Context, alias: String): Key? {
        try {
            val ks = KeyStore.getInstance(KS)
            ks.load(null)
            val k = ks.getKey(alias, null)
            if (k != null) return k
        } catch (e: Exception) {
            Log.w("Kavach", "Keystore read failed", e)
        }
        val fb = Prefs.keyFallback(c, alias) ?: return null
        return SecretKeySpec(Base64.decode(fb, Base64.NO_WRAP), ALG)
    }

    /** 80-bit truncated HMAC tag, hex. Enough to stop forgery over SMS, short enough to fit one SMS. */
    fun mac(c: Context, alias: String, msg: String): String? {
        val key = loadKey(c, alias) ?: return null
        return try {
            val m = Mac.getInstance(ALG)
            m.init(key)
            m.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        } catch (e: Exception) {
            Log.w("Kavach", "MAC failed", e)
            null
        }
    }

    fun sameTag(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
