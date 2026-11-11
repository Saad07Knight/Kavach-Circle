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
import android.util.Base64
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var live: TextView? = null

    private val permReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { build() }
    private val scan = registerForActivityResult(ScanContract()) { r -> r.contents?.let { onScanned(it) } }

    private val liveLoop = object : Runnable {
        override fun run() {
            live?.text = liveText()
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
        root.addView(Ui.tag(this, "KAVACH CIRCLE"))
        when (Prefs.role(this)) {
            Prefs.ROLE_VICTIM -> victimScreen()
            Prefs.ROLE_GUARDIAN -> guardianScreen()
            else -> roleScreen()
        }
    }

    private fun roleScreen() {
        root.addView(Ui.title(this, "Who will use this phone?"))
        root.addView(Ui.text(this, "Install Kavach Circle on two phones: the person to protect, and a family member who will be their guardian.", 15f, Ui.MUTED))
        root.addView(Ui.button(this, "Protect me\nI could be targeted by a scam call", Ui.CYAN, Ui.DARK) {
            Prefs.setRole(this, Prefs.ROLE_VICTIM); build()
        })
        root.addView(Ui.button(this, "I'm a guardian\nI watch over a family member") {
            Prefs.setRole(this, Prefs.ROLE_GUARDIAN); build()
        })
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
        root.addView(Ui.title(this, "Protection for ${Prefs.name(this).ifBlank { "you" }}"))
        if (!identityCard("Your guardians will see this name. Your number is used to link their phone to yours.")) return

        val on = Prefs.enabled(this) && !Prefs.fakeOff(this)
        val status = Ui.card(this, if (on) Ui.GREEN else Ui.LINE)
        status.addView(Ui.text(this, if (on) "Protection is ON" else "Protection is OFF", 18f, if (on) Ui.GREEN else Ui.MUTED, true))
        val lv = Ui.text(this, liveText(), 13f, Ui.MUTED)
        live = lv
        status.addView(lv)
        if (on) {
            status.addView(Ui.button(this, "Turn off protection (needs PIN)") { pinDialog() })
        } else {
            status.addView(Ui.button(this, "Turn on protection", Ui.CYAN, Ui.DARK) { turnOn() })
        }
        root.addView(status)

        permissionCard(victimPerms())

        val gc = Ui.card(this)
        gc.addView(Ui.text(this, "Guardians", 17f, Ui.INK, true))
        val gs = Prefs.guardians(this)
        if (gs.isEmpty()) gc.addView(Ui.text(this, "No guardians yet. Add a family member who will be alerted.", 13f, Ui.MUTED))
        gs.forEach {
            val pending = it.activeAt > System.currentTimeMillis()
            gc.addView(Ui.text(this, "${it.name} · ${it.phone}" + if (pending) " · active in 24 h" else "", 14f, if (pending) Ui.AMBER else Ui.INK))
        }
        gc.addView(Ui.button(this, "Add a guardian (show QR code)", Ui.CYAN, Ui.DARK) { showPairQr() })
        if (gs.isNotEmpty()) gc.addView(Ui.button(this, "Send a test alert") {
            Messenger.toGuardians(this, "TEST", ""); toast("Test alert sent by SMS")
        })
        root.addView(gc)

        val pc = Ui.card(this)
        pc.addView(Ui.text(this, "PIN and duress PIN", 17f, Ui.INK, true))
        pc.addView(Ui.text(this,
            if (Prefs.hasPins(this)) "Both are set. If a caller forces you to switch Kavach off, enter the duress PIN: the app looks switched off but quietly alerts your family."
            else "Set a PIN to switch protection off, and a second duress PIN to use if someone is forcing you.", 13f, Ui.MUTED))
        pc.addView(Ui.button(this, if (Prefs.hasPins(this)) "Change PINs" else "Set PINs") { setPinsDialog() })
        root.addView(pc)

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

    private fun guardianScreen() {
        root.addView(Ui.title(this, "Guardian"))
        if (!identityCard("The person you protect will see this name on their phone.")) return

        val sc = Ui.card(this, if (Prefs.victimConfirmed(this)) Ui.GREEN else Ui.LINE)
        if (Prefs.victimPhone(this).isEmpty()) {
            sc.addView(Ui.text(this, "Not linked yet", 18f, Ui.MUTED, true))
            sc.addView(Ui.text(this, "On their phone, open Kavach Circle and tap \"Add a guardian\". Then scan the QR code it shows. You must be together in person.", 13f, Ui.MUTED))
        } else {
            val ok = Prefs.victimConfirmed(this)
            sc.addView(Ui.text(this, if (ok) "Watching over ${Prefs.victimName(this)}" else "Waiting for ${Prefs.victimName(this)}'s phone to confirm…", 18f, if (ok) Ui.GREEN else Ui.AMBER, true))
            sc.addView(Ui.text(this, "Their number: ${Prefs.victimPhone(this)}. Alerts arrive by SMS, signed with a key only your two phones hold.", 13f, Ui.MUTED))
        }
        sc.addView(Ui.button(this, if (Prefs.victimPhone(this).isEmpty()) "Scan their QR code" else "Scan again", Ui.CYAN, Ui.DARK) {
            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the QR code on their phone").setBeepEnabled(false).setOrientationLocked(false))
        })
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
                card.addView(Ui.text(this, "✓  ${p.label}", 13f, Ui.GREEN))
            } else {
                card.addView(Ui.button(this, "Allow: ${p.label}") { p.request() })
            }
        }
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
        if (!Engine.onCall()) return "No call right now. Rule: call over ${Prefs.thresholdMinutes(this).toInt()} min + payment app + bank OTP."
        val m = Engine.minutes()
        return "On a call · ${m.toInt()} min ${((m - m.toInt()) * 60).toInt()} s\n" +
            (if (m >= Prefs.thresholdMinutes(this)) "✓" else "○") + " Long call   " +
            (if (Engine.paymentDuringCall) "✓" else "○") + " Payment app   " +
            (if (Engine.otpDuringCall) "✓" else "○") + " Bank OTP" +
            (if (Engine.alertSent) "\nFamily alerted." else "")
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
                    Messenger.toGuardians(this, "DISABLED", "")
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
        Messenger.toGuardians(this, "DURESS", "")
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

    private fun showPairQr() {
        val alias = "kvc-g-" + System.currentTimeMillis()
        val raw = Crypto.newSecret()
        Crypto.importKey(this, alias, raw)
        Prefs.setPendingAlias(this, alias)
        val safeName = Prefs.name(this).replace("|", " ")
        val content = "KAVACH1|${Prefs.phone(this)}|$safeName|${Base64.encodeToString(raw, Base64.NO_WRAP)}"
        raw.fill(0)
        val bmp = try { BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, 720, 720) } catch (e: Exception) { null }
        if (bmp == null) { toast("Could not draw the QR code"); return }
        val img = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(img)
            addView(Ui.text(this@MainActivity, "Only show this to a family member who is with you in person. Never send a photo of it.", 13f, Ui.MUTED).apply {
                setPadding(Ui.dp(this@MainActivity, 20), 0, Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 8))
            })
        }
        AlertDialog.Builder(this).setTitle("Guardian: scan this").setView(box)
            .setPositiveButton("Done") { _, _ -> build() }.show()
    }

    private fun onScanned(text: String) {
        val p = text.split("|")
        if (p.size != 4 || p[0] != "KAVACH1" || p[1].length != 10) { toast("That is not a Kavach Circle code"); return }
        val raw = try { Base64.decode(p[3], Base64.NO_WRAP) } catch (e: Exception) { null }
        if (raw == null || raw.size != 32) { toast("That code is damaged. Try again"); return }
        val alias = "kvc-v-" + System.currentTimeMillis()
        Crypto.importKey(this, alias, raw)
        raw.fill(0)
        Prefs.setVictim(this, p[2], p[1], alias)
        if (!granted(Manifest.permission.SEND_SMS)) {
            toast("Allow SMS first, then scan again")
        } else {
            Messenger.send(this, p[1], alias, "PAIR", Prefs.name(this))
            toast("Linking by SMS. Their phone will confirm in a few seconds")
        }
        build()
    }

    private fun resetAll() {
        MonitorService.stop(this)
        Prefs.resetAll(this)
        build()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
