package com.kavachcircle.app

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

/**
 * Family alerts as plain, readable SMS. Guardians do not need the app: any phone can read them.
 * Payment approvals are protected by a one-time 4-digit code that only the guardian's SMS contains,
 * and replies are accepted only from a saved guardian number.
 */
object Messenger {
    const val PREFIX = "Kavach Circle"
    const val APK_LINK = "https://github.com/Saad07Knight/Kavach-Circle/releases/latest/download/KavachCircle.apk"

    @Suppress("DEPRECATION")
    fun sms(c: Context, to: String, text: String) {
        try {
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                c.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = sm.divideMessage(text)
            if (parts.size > 1) sm.sendMultipartTextMessage(to, null, parts, null, null)
            else sm.sendTextMessage(to, null, text, null, null)
        } catch (e: Exception) {
            Log.e("Kavach", "SMS send failed", e)
        }
    }

    private fun me(c: Context) = Prefs.name(c).ifBlank { "Your family member" }
    private fun myPhone(c: Context) = Prefs.phone(c)

    /** Builds the readable message for each alert type. */
    fun text(c: Context, type: String, mins: Int = 0, app: String = "", code: String = ""): String {
        val n = me(c)
        val ph = myPhone(c)
        return when (type) {
            "ALERT" -> "$PREFIX ALERT: $n has been on a call for $mins min, opened $app and just got a bank OTP. This looks like a digital arrest scam. Call $n now on $ph."
            "HELP" -> "$PREFIX: $n needs you now. They tapped \"Call my family\" during a $mins-min call while opening $app. Call $ph."
            "APPROVE_REQ" -> "$PREFIX: $n wants to pay using $app during a $mins-min call. Reply YES $code to allow or NO $code to block. If unsure, call $ph first."
            "DURESS" -> "$PREFIX URGENT: $n entered the duress PIN. Someone may be forcing them to switch off protection. Call $ph now."
            "TAMPER" -> "$PREFIX: someone is removing Kavach Circle from $n's phone. Call $ph."
            "DISABLED" -> "$PREFIX: $n turned protection off with their PIN."
            "CONTINUED" -> "$PREFIX: $n waited out the pause and continued to $app without your approval. Call $ph."
            "TEST" -> "$PREFIX: test message. You are $n's guardian and will get an SMS like this if a call looks like a scam."
            "ADDED" -> "$PREFIX: $n added you as a guardian. You will get an SMS if a call looks like a scam. Optional, for full-screen alerts: $APK_LINK"
            "REMOVED" -> "$PREFIX: $n removed you as a guardian."
            else -> "$PREFIX: message from $n."
        }
    }

    fun toGuardians(c: Context, type: String, mins: Int = 0, app: String = "", code: String = "") {
        val msg = text(c, type, mins, app, code)
        Prefs.activeGuardians(c).forEach { sms(c, it.phone, msg) }
    }

    /** Called on the protected phone for every SMS that is not an OTP. */
    fun onVictimSms(c: Context, from: String, body: String): Boolean {
        val g = Prefs.guardianByPhone(c, from) ?: return false
        val m = Regex("^\\s*(YES|NO|Y|N)\\s*(\\d{4})", RegexOption.IGNORE_CASE).find(body) ?: return false
        if (Engine.approval != Engine.APPROVAL_WAITING) return true
        if (m.groupValues[2] != Engine.approvalCode) return true
        Engine.onApproval(c, m.groupValues[1].uppercase().startsWith("Y"), g.name)
        return true
    }

    /** Called on a guardian phone that has the app: turns the protected person's SMS into a full-screen alert. */
    fun onGuardianSms(c: Context, from: String, body: String) {
        if (Phone.norm(from) != Prefs.victimPhone(c) || !body.startsWith(PREFIX)) return
        val type = when {
            body.contains("URGENT") -> "DURESS"
            body.contains("ALERT:") -> "ALERT"
            body.contains("needs you now") -> "HELP"
            body.contains("Reply YES") -> "APPROVE_REQ"
            body.contains("removing Kavach") -> "TAMPER"
            else -> "INFO"
        }
        val code = Regex("YES (\\d{4})").find(body)?.groupValues?.get(1) ?: ""
        AlertActivity.launch(c, type, body, code)
    }
}

object Otp {
    private val digits = Regex("\\b\\d{4,8}\\b")
    private val words = listOf("otp", "one time", "one-time", "verification code", "debited", "debit", "upi", "transfer", "transaction")

    /** Checks only whether a message looks like a bank OTP or debit alert. Nothing is stored. */
    fun looksLikeOtp(body: String): Boolean {
        val b = body.lowercase()
        return !body.startsWith(Messenger.PREFIX) && digits.containsMatchIn(b) && words.any { b.contains(it) }
    }
}
