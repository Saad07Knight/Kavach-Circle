package com.kavachcircle.app

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import kotlin.math.abs

/**
 * Family link over SMS. Every message is:
 *   KVC1|TYPE|nonce|unixTime|payload|hmac
 * The receiver checks the HMAC with the pair's Keystore key, rejects anything older than
 * 15 minutes, and rejects any nonce it has already seen (replay protection).
 */
object Messenger {
    const val PFX = "KVC1"
    private const val MAX_AGE_SEC = 15 * 60

    fun send(c: Context, toPhone: String, alias: String, type: String, payload: String) {
        val clean = payload.replace("|", " ").replace("\n", " ").take(70)
        val base = "$PFX|$type|${Crypto.randomHex(4)}|${System.currentTimeMillis() / 1000}|$clean"
        val tag = Crypto.mac(c, alias, base) ?: run {
            Log.w("Kavach", "No key for $alias, message not sent")
            return
        }
        sms(c, toPhone, "$base|$tag")
    }

    @Suppress("DEPRECATION")
    private fun sms(c: Context, to: String, text: String) {
        try {
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                c.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            sm.sendTextMessage(to, null, text, null, null)
        } catch (e: Exception) {
            Log.e("Kavach", "SMS send failed", e)
        }
    }

    fun toGuardians(c: Context, type: String, payload: String) {
        Prefs.activeGuardians(c).forEach { send(c, it.phone, it.alias, type, payload) }
    }

    fun toVictim(c: Context, type: String, payload: String) {
        val ph = Prefs.victimPhone(c)
        val alias = Prefs.victimAlias(c)
        if (ph.isNotEmpty() && alias.isNotEmpty()) send(c, ph, alias, type, payload)
    }

    fun handle(c: Context, fromRaw: String, body: String) {
        val parts = body.trim().split("|")
        if (parts.size != 6 || parts[0] != PFX) return
        val type = parts[1]
        val nonce = parts[2]
        val ts = parts[3].toLongOrNull() ?: return
        val payload = parts[4]
        val tag = parts[5]
        val from = Phone.norm(fromRaw)
        val role = Prefs.role(c)

        val alias = when (role) {
            Prefs.ROLE_GUARDIAN -> if (from == Prefs.victimPhone(c)) Prefs.victimAlias(c) else ""
            Prefs.ROLE_VICTIM -> Prefs.guardianByPhone(c, from)?.alias
                ?: if (type == "PAIR") Prefs.pendingAlias(c) else ""
            else -> ""
        }
        if (alias.isEmpty()) return

        val expected = Crypto.mac(c, alias, parts.take(5).joinToString("|")) ?: return
        if (!Crypto.sameTag(expected, tag)) {
            Log.w("Kavach", "Bad signature from $from, dropped")
            return
        }
        if (abs(System.currentTimeMillis() / 1000 - ts) > MAX_AGE_SEC) return
        if (Prefs.seenOrRemember(c, nonce)) {
            Log.w("Kavach", "Replay blocked from $from")
            return
        }

        if (role == Prefs.ROLE_VICTIM) onVictimSide(c, type, from, alias, payload)
        else onGuardianSide(c, type, payload)
    }

    private fun onVictimSide(c: Context, type: String, from: String, alias: String, payload: String) {
        when (type) {
            "PAIR" -> {
                val existing = Prefs.guardians(c)
                val delay = if (existing.isEmpty()) 0L else Prefs.newGuardianDelayMs(c)
                val name = payload.ifBlank { "Guardian" }
                Prefs.upsertGuardian(c, Guardian(name, from, alias, System.currentTimeMillis() + delay))
                Prefs.setPendingAlias(c, "")
                send(c, from, alias, "PAIROK", Prefs.name(c))
                if (delay > 0) {
                    existing.filter { it.activeAt <= System.currentTimeMillis() }
                        .forEach { send(c, it.phone, it.alias, "NEWG", name) }
                    Notify.info(c, "$name will become a guardian in 24 hours", "Your existing guardians have been told.")
                } else {
                    Notify.info(c, "$name is now your guardian", "They will be alerted if a call looks like a scam.")
                }
            }
            "APPROVE" -> Engine.onApproval(c, true, Prefs.guardianByPhone(c, from)?.name ?: "Your guardian")
            "DENY" -> Engine.onApproval(c, false, Prefs.guardianByPhone(c, from)?.name ?: "Your guardian")
        }
    }

    private fun onGuardianSide(c: Context, type: String, payload: String) {
        if (type == "PAIROK") {
            Prefs.setVictimConfirmed(c)
            Notify.info(c, "Linked with ${Prefs.victimName(c)}", "You will be alerted if they look to be in trouble.")
            return
        }
        AlertActivity.launch(c, type, payload)
    }
}

object Otp {
    private val digits = Regex("\\b\\d{4,8}\\b")
    private val words = listOf("otp", "one time", "one-time", "verification code", "debited", "debit", "upi", "transfer", "transaction")

    /** Checks only whether a message looks like a bank OTP or debit alert. Nothing is stored. */
    fun looksLikeOtp(body: String): Boolean {
        val b = body.lowercase()
        return digits.containsMatchIn(b) && words.any { b.contains(it) }
    }
}
