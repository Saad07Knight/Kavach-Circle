package com.kavachcircle.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var live: TextView? = null
    private var chips: List<TextView> = emptyList()

    private val permReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { build() }

    private val liveLoop = object : Runnable {
        override fun run() {
            live?.text = liveText()
            updateChips()
            handler.postDelayed(this, 2000)
        }
    }

    private data class Perm(val label: String, val granted: () -> Boolean, val request: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 28), Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 40))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        build()
        handler.removeCallbacks(liveLoop)
        handler.post(liveLoop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(liveLoop)
    }

    // ------------------------------------------------------------------ screens

    private fun build() {
        root.removeAllViews()
        live = null
        chips = emptyList()
        root.addView(Ui.tag(this, "KAVACH CIRCLE"))
        when (Prefs.role(this)) {
            Prefs.ROLE_VICTIM -> victimScreen()
            Prefs.ROLE_GUARDIAN -> guardianScreen()
            else -> roleScreen()
        }
    }

    private fun roleScreen() {
        root.addView(Ui.ring(this, Ui.CYAN, 84).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(this@MainActivity, 18)
        })
        root.addView(Ui.title(this, "Stop scam calls before the money moves"))
        root.addView(Ui.text(this, "Kavach Circle spots the pattern of a digital arrest scam on this phone and pulls your family in while the call is still live.", 15f, Ui.MUTED))

        val a = Ui.card(this, Ui.CYAN)
        a.addView(Ui.text(this, "Protect this phone", 18f, Ui.INK, true))
        a.addView(Ui.text(this, "For the person who could be targeted, such as a parent or grandparent.", 13f, Ui.MUTED))
        a.addView(Ui.button(this, "Protect me", Ui.CYAN, Ui.DARK) { Prefs.setRole(this, Prefs.ROLE_VICTIM); build() })
        root.addView(a)

        val b = Ui.card(this)
        b.addView(Ui.text(this, "I'm a guardian", 18f, Ui.INK, true))
        b.addView(Ui.text(this, "Optional. Guardians already get SMS alerts without the app. Install it for full-screen alerts and one-tap approve or block.", 13f, Ui.MUTED))
        b.addView(Ui.button(this, "Set up as guardian") { Prefs.setRole(this, Prefs.ROLE_GUARDIAN); build() })
        root.addView(b)
    }

    private fun identityCard(prompt: String): Boolean {
        if (Prefs.name(this).isNotEmpty() && Prefs.phone(this).length == 10) return true
        val card = Ui.card(this, Ui.AMBER)
        card.addView(Ui.text(this, "About you", 17f, Ui.INK, true))
        card.addView(Ui.text(this, prompt, 13f, Ui.MUTED))
        val name = Ui.edit(this, "Your name, e.g. Sunita")
        val phone = Ui.edit(this, "Your 10-digit mobile number", Ui.PHONE_INPUT)
        card.addView(name)
        card.addView(phone)
        card.addView(Ui.button(this, "Save", Ui.AMBER, Ui.DARK) {
            if (name.text.isBlank() || Phone.norm(phone.text.toString()).length != 10) {
                toast("Enter your name and a 10-digit number")
            } else {
                Prefs.setIdentity(this, name.text.toString(), phone.text.toString()); build()
            }
        })
        root.addView(card)
        return false
    }

    private fun victimScreen() {
        if (!identityCard("Your guardians will see this name and number in every alert, so they can call you back.")) return

        val on = Prefs.enabled(this) && !Prefs.fakeOff(this)
        val gs = Prefs.guardians(this)
        val perms = victimPerms()

        // ---- status hero
        val status = Ui.card(this, if (on) Ui.GREEN else Ui.LINE)
        val head = Ui.row(this, 0)
        head.addView(Ui.ring(this, if (on) Ui.GREEN else Ui.MUTED))
        val headText = Ui.col(this)
        headText.addView(Ui.text(this, "Hello, ${Prefs.name(this)}", 13f, Ui.MUTED))
        headText.addView(Ui.text(this, if (on) "You are protected" else "Protection is off", 21f, if (on) Ui.GREEN else Ui.INK, true))
        val lv = Ui.text(this, liveText(), 13f, Ui.MUTED)
        live = lv
        headText.addView(lv)
        head.addView(headText)
        status.addView(head)
        if (on) {
            val chipRow = Ui.row(this, 14)
            val list = listOf(Ui.chip(this, "Long call"), Ui.chip(this, "Payment app"), Ui.chip(this, "Bank OTP"), Ui.chip(this, "Screen share"))
            list.forEach { chipRow.addView(it) }
            chips = list
            updateChips()
            status.addView(chipRow)
            status.addView(Ui.text(this, "These light up during a call. Three in a row, or a screen-sharing app, alerts your family.", 12f, Ui.MUTED))
            status.addView(Ui.button(this, "Turn off protection (needs PIN)") { pinDialog() })
        } else {
            status.addView(Ui.button(this, "Turn on protection", Ui.CYAN, Ui.DARK) { turnOn() })
        }
        root.addView(status)

        // ---- one-tap help
        root.addView(Ui.button(this, "SOS: I think this call is a scam\nText and call my family now", Ui.RED, Ui.DARK) { sos() })

        // ---- setup progress
        val steps = listOf(
            "Allow permissions" to perms.all { it.granted() },
            "Add a guardian" to gs.isNotEmpty(),
            "Set PIN and duress PIN" to Prefs.hasPins(this),
            "Turn on protection" to on
        )
        val done = steps.count { it.second }
        if (done < steps.size) {
            val sc = Ui.card(this, Ui.AMBER)
            sc.addView(Ui.text(this, "Finish setup · $done of ${steps.size}", 17f, Ui.INK, true))
            sc.addView(Ui.bar(this, done.toFloat() / steps.size, Ui.AMBER))
            steps.forEach { (label, ok) ->
                sc.addView(Ui.text(this, (if (ok) "✓  " else "○  ") + label, 14f, if (ok) Ui.GREEN else Ui.INK).apply {
                    setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0)
                })
            }
            root.addView(sc)
        }

        permissionCard(perms)

        // ---- guardians
        val gc = Ui.card(this)
        gc.addView(Ui.text(this, "Guardians", 17f, Ui.INK, true))
        gc.addView(Ui.text(this, "Family members who get an SMS if a call looks like a scam. They do not need the app, and can reply YES or NO to approve or block a payment.", 13f, Ui.MUTED))
        gs.forEach { g ->
            val pending = g.activeAt > System.currentTimeMillis()
            val r = Ui.row(this, 12)
            r.addView(Ui.avatar(this, g.name.take(1).uppercase().ifBlank { "G" }, if (pending) Ui.AMBER else Ui.CYAN))
            val t = Ui.col(this)
            t.addView(Ui.text(this, g.name, 15f, Ui.INK, true))
            t.addView(Ui.text(this, g.phone + if (pending) " · active in 24 h" else "", 13f, if (pending) Ui.AMBER else Ui.MUTED))
            r.addView(t)
            r.addView(Ui.small(this, "Remove") { removeGuardianDialog(g) })
            gc.addView(r)
        }
        gc.addView(Ui.button(this, "Add a guardian", Ui.CYAN, Ui.DARK) { addGuardianDialog() })
        if (gs.isNotEmpty()) gc.addView(Ui.button(this, "Send a test SMS to my guardians") {
            if (!granted(Manifest.permission.SEND_SMS)) toast("Allow SMS first")
            else { Messenger.toGuardians(this, "TEST"); toast("Test SMS sent") }
        })
        root.addView(gc)

        // ---- PINs
        val pc = Ui.card(this)
        pc.addView(Ui.text(this, "PIN and duress PIN", 17f, Ui.INK, true))
        pc.addView(Ui.text(this,
            if (Prefs.hasPins(this)) "Both are set. If a caller forces you to switch Kavach off, enter the duress PIN: the app looks switched off but quietly alerts your family."
            else "Set a PIN to switch protection off, and a second duress PIN to use if someone is forcing you.", 13f, Ui.MUTED))
        pc.addView(Ui.button(this, if (Prefs.hasPins(this)) "Change PINs" else "Set PINs") { setPinsDialog() })
        root.addView(pc)

        // ---- demo mode
        val dc = Ui.card(this)
        dc.addView(Ui.text(this, "Demo mode", 17f, Ui.INK, true))
        dc.addView(Ui.text(this, "For the hackathon: the pause screen appears after a 1-minute call instead of 30, and new guardians are active at once.", 13f, Ui.MUTED))
        @Suppress("DEPRECATION")
        val sw = Switch(this).apply {
            text = "Demo mode"
            setTextColor(Ui.INK)
            isChecked = Prefs.demo(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setDemo(this@MainActivity, v) }
        }
        dc.addView(sw)
        root.addView(dc)

        footer()
    }

    private fun updateChips() {
        if (chips.size < 4) return
        val call = Engine.onCall()
        Ui.setChip(chips[0], call && Engine.minutes() >= Prefs.thresholdMinutes(this))
        Ui.setChip(chips[1], call && Engine.paymentDuringCall)
        Ui.setChip(chips[2], call && Engine.otpDuringCall)
        Ui.setChip(chips[3], call && Engine.remoteDuringCall)
    }

    /** One-tap help: texts every guardian and calls the first one. */
    private fun sos() {
        val gs = Prefs.activeGuardians(this)
        if (gs.isEmpty()) { toast("Add a guardian first"); return }
        AlertDialog.Builder(this).setTitle("Alert your family now?")
            .setMessage("Kavach will text your guardians and call ${gs[0].name}.")
            .setPositiveButton("Yes, alert them") { _, _ ->
                if (granted(Manifest.permission.SEND_SMS)) Messenger.toGuardians(this, "SOS")
                val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
                open(Intent(action, Uri.parse("tel:${gs[0].phone}")))
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun guardianScreen() {
        root.addView(Ui.title(this, "Guardian"))
        root.addView(Ui.text(this, "Optional. You already get SMS alerts without this app. With it, alerts open full screen, call them automatically, and you can approve or block a payment in one tap.", 13f, Ui.MUTED))

        val sc = Ui.card(this, if (Prefs.victimPhone(this).isNotEmpty()) Ui.GREEN else Ui.AMBER)
        if (Prefs.victimPhone(this).isEmpty()) {
            sc.addView(Ui.text(this, "Who are you protecting?", 18f, Ui.INK, true))
            sc.addView(Ui.text(this, "Enter the name and number of the person who added you as their guardian.", 13f, Ui.MUTED))
            val n = Ui.edit(this, "Their name, e.g. Mom")
            val ph = Ui.edit(this, "Their 10-digit mobile number", Ui.PHONE_INPUT)
            sc.addView(n)
            sc.addView(ph)
            sc.addView(Ui.button(this, "Save", Ui.CYAN, Ui.DARK) {
                if (n.text.isBlank() || Phone.norm(ph.text.toString()).length != 10) toast("Enter their name and a 10-digit number")
                else { Prefs.setVictim(this, n.text.toString(), ph.text.toString()); build() }
            })
        } else {
            sc.addView(Ui.text(this, "Watching over ${Prefs.victimName(this)}", 18f, Ui.GREEN, true))
            sc.addView(Ui.text(this, "Kavach SMS from ${Prefs.victimPhone(this)} will open full screen on this phone.", 13f, Ui.MUTED))
            sc.addView(Ui.button(this, "Change person") { Prefs.setVictim(this, "", ""); build() })
        }
        root.addView(sc)

        permissionCard(guardianPerms())
        footer()
    }

    private fun footer() {
        root.addView(Ui.text(this, "Kavach Circle never records calls and never reads message text. It only checks whether a call is on, which app is in front, and whether an SMS looks like a bank OTP.", 12f, Ui.MUTED))
        root.addView(Ui.button(this, "Reset this phone") {
            if (Prefs.role(this) == Prefs.ROLE_VICTIM && Prefs.hasPins(this)) {
                askPin("Enter your PIN to reset") { r ->
                    if (r == 1) resetAll() else if (r == 2) duressOff() else toast("Wrong PIN")
                }
            } else resetAll()
        })
    }

    // ------------------------------------------------------------------ permissions

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun smsPerm() = Perm("SMS: spot bank OTPs and send signed family alerts",
        { granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.SEND_SMS) },
        { permReq.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)) })

    private fun callPerm() = Perm("Phone: call family in one tap",
        { granted(Manifest.permission.CALL_PHONE) },
        { permReq.launch(arrayOf(Manifest.permission.CALL_PHONE)) })

    private fun notifPerm() = Perm("Notifications",
        { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) },
        { if (Build.VERSION.SDK_INT >= 33) permReq.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) })

    private fun overlayPerm(label: String) = Perm(label,
        { Settings.canDrawOverlays(this) },
        { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })

    @SuppressLint("BatteryLife")
    private fun batteryPerm() = Perm("Keep running in the background",
        { (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) },
        { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) })

    private fun victimPerms(): List<Perm> {
        val admin = ComponentName(this, AdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return listOf(
            smsPerm(),
            Perm("Usage access: see when Paytm, Google Pay or PhonePe opens", { hasUsageAccess() },
                { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }),
            overlayPerm("Display over other apps: show the pause screen over payment apps"),
            callPerm(),
            notifPerm(),
            Perm("Tamper alarm: tell family if someone tries to remove Kavach", { dpm.isAdminActive(admin) }, {
                open(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets Kavach Circle warn your family before it can be uninstalled."))
            }),
            batteryPerm()
        )
    }

    private fun guardianPerms(): List<Perm> = listOf(
        smsPerm(), callPerm(), notifPerm(),
        overlayPerm("Display over other apps: show alerts on screen instantly"),
        batteryPerm()
    )

    private fun permissionCard(perms: List<Perm>) {
        val missing = perms.count { !it.granted() }
        val card = Ui.card(this, if (missing == 0) Ui.GREEN else Ui.AMBER)
        card.addView(Ui.text(this, if (missing == 0) "All permissions granted" else "Permissions needed ($missing left)", 17f, Ui.INK, true))
        perms.forEach { p ->
            val ok = p.granted()
            if (ok) {
                if (missing > 0) card.addView(Ui.text(this, "✓  ${p.label}", 13f, Ui.GREEN))
            } else {
                card.addView(Ui.button(this, "Allow: ${p.label}") { p.request() })
            }
        }
        card.addView(Ui.text(this,
            "On Xiaomi, Redmi, POCO, Vivo, Oppo, Realme or OnePlus phones, also turn on Autostart for Kavach Circle (Settings › Apps › Kavach Circle › Autostart). Without it, replies and OTPs can be missed when the app is closed.", 12f, Ui.AMBER))
        if (missing > 0) card.addView(Ui.text(this,
            "If Android says \"Restricted setting\", open Settings › Apps › Kavach Circle › ⋮ › Allow restricted settings, then try again.", 12f, Ui.MUTED))
        root.addView(card)
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { toast("Open Settings and allow this for Kavach Circle") }
    }

    // ------------------------------------------------------------------ actions

    private fun liveText(): String {
        if (Prefs.role(this) != Prefs.ROLE_VICTIM) return ""
        if (!Prefs.enabled(this)) return "Turn protection on to start watching."
        if (Prefs.fakeOff(this)) return "Turn protection on to start watching."
        if (!Engine.onCall()) return "No call right now. Kavach is watching quietly."
        val m = Engine.minutes()
        return "On a call · ${m.toInt()} min ${((m - m.toInt()) * 60).toInt()} s" +
            (if (Engine.alertSent || Engine.remoteDuringCall) "\nYour family has been alerted." else "")
    }

    private fun turnOn() {
        if (!Prefs.hasPins(this)) { toast("Set your PINs first"); setPinsDialog(); return }
        if (Prefs.guardians(this).isEmpty()) toast("Tip: add a guardian so someone is alerted")
        Prefs.setEnabled(this, true)
        Prefs.setFakeOff(this, false)
        MonitorService.start(this)
        build()
    }

    private fun pinDialog() {
        askPin("Enter your PIN to turn off protection") { r ->
            when (r) {
                1 -> {
                    Prefs.setEnabled(this, false)
                    MonitorService.stop(this)
                    Messenger.toGuardians(this, "DISABLED")
                    build()
                }
                2 -> duressOff()
                else -> toast("Wrong PIN")
            }
        }
    }

    /** Looks switched off to whoever is watching. Keeps protecting and alerts the family. */
    private fun duressOff() {
        Prefs.setFakeOff(this, true)
        Messenger.toGuardians(this, "DURESS")
        MonitorService.start(this) // refreshes the notification to a neutral one
        build()
    }

    private fun askPin(title: String, onResult: (Int) -> Unit) {
        val e = Ui.edit(this, "4-digit PIN", Ui.PIN_INPUT)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 20), 0)
            addView(e)
        }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> onResult(Prefs.checkPin(this, e.text.toString())) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun setPinsDialog() {
        val real = Ui.edit(this, "PIN (4 digits)", Ui.PIN_INPUT)
        val duress = Ui.edit(this, "Duress PIN (4 digits, different)", Ui.PIN_INPUT)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 20), 0)
            addView(real); addView(duress)
        }
        AlertDialog.Builder(this).setTitle("Set your PINs").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val a = real.text.toString(); val b = duress.text.toString()
                if (a.length != 4 || b.length != 4 || a == b) toast("Use two different 4-digit PINs")
                else { Prefs.setPins(this, a, b); toast("PINs saved"); build() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addGuardianDialog() {
        if (!granted(Manifest.permission.SEND_SMS)) { toast("Allow SMS first, so Kavach can text your guardian"); return }
        val n = Ui.edit(this, "Guardian's name, e.g. Rahul")
        val ph = Ui.edit(this, "Guardian's 10-digit mobile number", Ui.PHONE_INPUT)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 20), 0)
            addView(n); addView(ph)
        }
        AlertDialog.Builder(this).setTitle("Add a guardian").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val number = Phone.norm(ph.text.toString())
                when {
                    n.text.isBlank() || number.length != 10 -> toast("Enter a name and a 10-digit number")
                    number == Prefs.phone(this) -> toast("That is your own number")
                    else -> {
                        val before = Prefs.activeGuardians(this)
                        val g = Prefs.addGuardian(this, n.text.toString(), number)
                        Messenger.sms(this, g.phone, Messenger.text(this, "ADDED"))
                        if (g.activeAt > System.currentTimeMillis()) {
                            val warn = "${Messenger.PREFIX}: ${g.name} (${g.phone}) is being added as ${Prefs.name(this)}'s guardian and will be active in 24 hours. If you did not expect this, call ${Prefs.phone(this)}."
                            before.forEach { Messenger.sms(this, it.phone, warn) }
                            toast("${g.name} will be active in 24 hours. Your other guardians were told.")
                        } else {
                            toast("${g.name} added and sent a welcome SMS")
                        }
                        build()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun removeGuardianDialog(g: Guardian) {
        val doRemove = {
            Prefs.removeGuardian(this, g.phone)
            Messenger.sms(this, g.phone, Messenger.text(this, "REMOVED"))
            toast("${g.name} removed")
            build()
        }
        if (Prefs.hasPins(this)) {
            askPin("Enter your PIN to remove ${g.name}") { r ->
                when (r) {
                    1 -> doRemove()
                    2 -> duressOff()
                    else -> toast("Wrong PIN")
                }
            }
        } else {
            AlertDialog.Builder(this).setTitle("Remove ${g.name}?")
                .setPositiveButton("Remove") { _, _ -> doRemove() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun resetAll() {
        MonitorService.stop(this)
        Prefs.resetAll(this)
        build()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
