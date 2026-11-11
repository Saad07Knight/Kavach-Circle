package com.kavachcircle.app

import java.security.MessageDigest
import java.security.SecureRandom

/** PIN hashing and one-time approval codes. No API keys anywhere in the app. */
object Crypto {
    private val rng = SecureRandom()

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 4-digit one-time code for a payment approval request. */
    fun approvalCode(): String = (1000 + rng.nextInt(9000)).toString()

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
