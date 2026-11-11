package com.kavachcircle.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Guardian(val name: String, val phone: String, val alias: String, val activeAt: Long)

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
            out.add(Guardian(o.getString("name"), o.getString("phone"), o.getString("alias"), o.optLong("activeAt", 0L)))
        }
        return out
    }

    fun saveGuardians(c: Context, list: List<Guardian>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("phone", it.phone).put("alias", it.alias).put("activeAt", it.activeAt))
        }
        p(c).edit().putString("guardians", arr.toString()).apply()
    }

    fun upsertGuardian(c: Context, g: Guardian) {
        val list = guardians(c).filter { it.phone != g.phone }.toMutableList()
        list.add(g)
        saveGuardians(c, list)
    }

    fun activeGuardians(c: Context): List<Guardian> =
        guardians(c).filter { it.activeAt <= System.currentTimeMillis() }

    fun guardianByPhone(c: Context, phone: String): Guardian? =
        guardians(c).firstOrNull { it.phone == Phone.norm(phone) }

    // ---- pairing session on the protected phone ----
    fun pendingAlias(c: Context): String = p(c).getString("pendingAlias", "") ?: ""
    fun setPendingAlias(c: Context, a: String) = p(c).edit().putString("pendingAlias", a).apply()

    // ---- the protected person (stored on the guardian's phone) ----
    fun victimName(c: Context): String = p(c).getString("victimName", "") ?: ""
    fun victimPhone(c: Context): String = p(c).getString("victimPhone", "") ?: ""
    fun victimAlias(c: Context): String = p(c).getString("victimAlias", "") ?: ""
    fun victimConfirmed(c: Context): Boolean = p(c).getBoolean("victimConfirmed", false)
    fun setVictim(c: Context, name: String, phone: String, alias: String) =
        p(c).edit().putString("victimName", name).putString("victimPhone", Phone.norm(phone))
            .putString("victimAlias", alias).putBoolean("victimConfirmed", false).apply()
    fun setVictimConfirmed(c: Context) = p(c).edit().putBoolean("victimConfirmed", true).apply()

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

    // ---- replay protection ----
    /** Returns true if this nonce was already seen (a replay). Remembers it otherwise. */
    fun seenOrRemember(c: Context, nonce: String): Boolean {
        val raw = p(c).getString("nonces", "") ?: ""
        val list = if (raw.isEmpty()) mutableListOf() else raw.split(",").toMutableList()
        if (list.contains(nonce)) return true
        list.add(nonce)
        while (list.size > 300) list.removeAt(0)
        p(c).edit().putString("nonces", list.joinToString(",")).apply()
        return false
    }

    // ---- fallback key store for phones whose Keystore rejects HMAC import ----
    fun keyFallback(c: Context, alias: String): String? = p(c).getString("kf_$alias", null)
    fun setKeyFallback(c: Context, alias: String, b64: String) = p(c).edit().putString("kf_$alias", b64).apply()

    fun resetAll(c: Context) = p(c).edit().clear().apply()
}
