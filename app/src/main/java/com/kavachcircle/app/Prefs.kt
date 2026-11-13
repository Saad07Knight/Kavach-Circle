package com.kavachcircle.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Guardian(val name: String, val phone: String, val activeAt: Long)

object Phone {
    /** Last 10 digits, so +91 98xxxxxxxx and 98xxxxxxxx match. */
    fun norm(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }
}

object Prefs {
    const val ROLE_VICTIM = "victim"
    const val ROLE_GUARDIAN = "guardian"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("kavach", Context.MODE_PRIVATE)

    fun role(c: Context): String = p(c).getString("role", "") ?: ""
    fun setRole(c: Context, r: String) = p(c).edit().putString("role", r).apply()

    fun name(c: Context): String = p(c).getString("name", "") ?: ""
    fun phone(c: Context): String = p(c).getString("phone", "") ?: ""
    fun setIdentity(c: Context, n: String, ph: String) =
        p(c).edit().putString("name", n.trim()).putString("phone", Phone.norm(ph)).apply()

    fun enabled(c: Context): Boolean = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()

    /** Duress mode: the app looks switched off but keeps protecting. */
    fun fakeOff(c: Context): Boolean = p(c).getBoolean("fakeOff", false)
    fun setFakeOff(c: Context, v: Boolean) = p(c).edit().putBoolean("fakeOff", v).apply()

    fun demo(c: Context): Boolean = p(c).getBoolean("demo", true)
    fun setDemo(c: Context, v: Boolean) = p(c).edit().putBoolean("demo", v).apply()
    fun thresholdMinutes(c: Context): Double = if (demo(c)) 1.0 else 30.0
    fun continueWaitSec(c: Context): Int = if (demo(c)) 30 else 120
    fun newGuardianDelayMs(c: Context): Long = if (demo(c)) 0L else DAY_MS

    // ---- guardians (stored on the protected person's phone) ----
    fun guardians(c: Context): List<Guardian> {
        val arr = try { JSONArray(p(c).getString("guardians", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Guardian>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Guardian(o.optString("name"), o.optString("phone"), o.optLong("activeAt", 0L)))
        }
        return out
    }

    private fun saveGuardians(c: Context, list: List<Guardian>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("phone", it.phone).put("activeAt", it.activeAt)) }
        p(c).edit().putString("guardians", arr.toString()).apply()
    }

    /** Adds a guardian. The first one is active at once; later ones wait 24 hours (not in demo mode). */
    fun addGuardian(c: Context, name: String, phone: String): Guardian {
        val existing = guardians(c).filter { it.phone != Phone.norm(phone) }
        val delay = if (existing.isEmpty()) 0L else newGuardianDelayMs(c)
        val g = Guardian(name.trim(), Phone.norm(phone), System.currentTimeMillis() + delay)
        saveGuardians(c, existing + g)
        return g
    }

    fun removeGuardian(c: Context, phone: String) = saveGuardians(c, guardians(c).filter { it.phone != phone })

    fun activeGuardians(c: Context): List<Guardian> =
        guardians(c).filter { it.activeAt <= System.currentTimeMillis() }

    fun guardianByPhone(c: Context, phone: String): Guardian? =
        guardians(c).firstOrNull { it.phone == Phone.norm(phone) }

    // ---- the protected person (stored on a guardian's phone, only if the guardian installs the app) ----
    fun victimName(c: Context): String = p(c).getString("victimName", "") ?: ""
    fun victimPhone(c: Context): String = p(c).getString("victimPhone", "") ?: ""
    fun setVictim(c: Context, name: String, phone: String) =
        p(c).edit().putString("victimName", name.trim()).putString("victimPhone", Phone.norm(phone)).apply()

    // ---- PINs (salted SHA-256) ----
    private fun salt(c: Context): String {
        var s = p(c).getString("salt", "") ?: ""
        if (s.isEmpty()) {
            s = Crypto.randomHex(16)
            p(c).edit().putString("salt", s).apply()
        }
        return s
    }
    fun hasPins(c: Context): Boolean = (p(c).getString("pinReal", "") ?: "").isNotEmpty()
    fun setPins(c: Context, real: String, duress: String) {
        val s = salt(c)
        p(c).edit().putString("pinReal", Crypto.sha256(s + real)).putString("pinDuress", Crypto.sha256(s + duress)).apply()
    }
    /** 0 = wrong, 1 = real PIN, 2 = duress PIN */
    fun checkPin(c: Context, pin: String): Int {
        val h = Crypto.sha256(salt(c) + pin)
        return when (h) {
            p(c).getString("pinReal", null) -> 1
            p(c).getString("pinDuress", null) -> 2
            else -> 0
        }
    }

    // ---- payment approval, kept on disk so it survives the call ending or Android closing the app ----
    private const val APPROVAL_TTL_MS = 30L * 60 * 1000

    fun approvalState(c: Context): Int {
        val st = p(c).getInt("apState", Engine.APPROVAL_NONE)
        val age = System.currentTimeMillis() - p(c).getLong("apAt", 0L)
        return if (st == Engine.APPROVAL_WAITING && age > APPROVAL_TTL_MS) Engine.APPROVAL_NONE else st
    }
    fun approvalCode(c: Context): String = p(c).getString("apCode", "") ?: ""
    fun approverName(c: Context): String = p(c).getString("apBy", "") ?: ""
    fun startApproval(c: Context, code: String) = p(c).edit()
        .putInt("apState", Engine.APPROVAL_WAITING).putString("apCode", code)
        .putLong("apAt", System.currentTimeMillis()).putString("apBy", "").commit()
    fun setApprovalResult(c: Context, yes: Boolean, by: String) = p(c).edit()
        .putInt("apState", if (yes) Engine.APPROVAL_YES else Engine.APPROVAL_NO).putString("apBy", by).commit()
    fun clearApproval(c: Context) = p(c).edit()
        .remove("apState").remove("apCode").remove("apAt").remove("apBy").apply()

    fun resetAll(c: Context) = p(c).edit().clear().apply()
}
