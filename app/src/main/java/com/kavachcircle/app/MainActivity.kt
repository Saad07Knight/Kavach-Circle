package com.kavachcircle.app

import android.Manifest
import android.animation.Animator
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One activity, several pages:
 *   welcome -> signup -> signup2 (PIN)      first run: create the account on this phone
 *   login                                   PIN lock every time the app is opened
 *   home + sub-pages                        a different dashboard for the protected person and for a guardian
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val LOCK_AFTER_MS = 5 * 60 * 1000L
        private var unlocked = false
        private var lastActive = 0L
        private var fails = 0
        private var lockedUntil = 0L
        private val AUTH_PAGES = setOf("welcome", "signup", "signup2", "login")
    }

    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val anims = ArrayList<Animator>()
    private var page = "home"
    private var animate = false
    private var index = 0

    // live widgets on the protected person's home page
    private var live: TextView? = null
    private var chips: List<TextView> = emptyList()

    // login page state
    private var pin = ""
    private var dots: List<View> = emptyList()
    private var loginMsg: TextView? = null

    // signup state
    private var suRole = Prefs.ROLE_VICTIM

    private val permReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { build(false) }

    private val liveLoop = object : Runnable {
        override fun run() {
            live?.text = liveText()
            updateChips()
            handler.postDelayed(this, 2000)
        }
    }

    private data class Perm(val label: String, val granted: () -> Boolean, val request: () -> Unit)

    private fun isVictim() = Prefs.role(this) == Prefs.ROLE_VICTIM

    private fun accountReady() = Prefs.role(this).isNotEmpty() && Prefs.name(this).isNotEmpty() &&
        Prefs.phone(this).length == 10 && Prefs.hasPins(this)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 26), Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 40))
        }
        scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) }
        setContentView(scroll)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    page == "signup" -> nav("welcome")
                    page in AUTH_PAGES || page == "home" -> moveTaskToBack(true)
                    else -> nav("home")
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (!accountReady()) {
            if (page !in AUTH_PAGES || page == "login") {
                val half = Prefs.role(this).isNotEmpty() && Prefs.name(this).isNotEmpty() && Prefs.phone(this).length == 10
                page = if (half) "signup2" else "welcome"
            }
        } else if (!unlocked || now - lastActive > LOCK_AFTER_MS) {
            unlocked = false
            pin = ""
            page = "login"
        } else if (page in AUTH_PAGES) {
            page = "home"
        }
        build(true)
        handler.removeCallbacks(liveLoop)
        handler.post(liveLoop)
    }

    override fun onPause() {
        super.onPause()
        lastActive = System.currentTimeMillis()
        handler.removeCallbacks(liveLoop)
    }

    override fun onDestroy() {
        stopAnims()
        super.onDestroy()
    }

    private fun stopAnims() {
        anims.forEach { it.cancel() }
        anims.clear()
    }

    // ------------------------------------------------------------------ page plumbing

    private fun nav(p: String) {
        page = p
        build(true)
        scroll.scrollTo(0, 0)
    }

    /** Adds a view to the page; on a fresh page each view swings in after the one before it. */
    private fun add(v: View) {
        root.addView(v)
        if (animate) Ui.enter(v, index++)
    }

    private fun build(withAnimation: Boolean) {
        stopAnims()
        root.removeAllViews()
        animate = withAnimation
        index = 0
        live = null
        chips = emptyList()
        dots = emptyList()
        loginMsg = null
        when (page) {
            "welcome" -> welcomePage()
            "signup" -> signupPage()
            "signup2" -> signupPinPage()
            "login" -> loginPage()
            "profile" -> profilePage()
            "perms" -> permsPage()
            "how" -> howPage()
            "guardians" -> if (isVictim()) guardiansPage() else guardianHome()
            "pins" -> if (isVictim()) pinsPage() else guardianHome()
            "shield" -> if (isVictim()) shieldPage() else guardianHome()
            "settings" -> if (isVictim()) settingsPage() else guardianHome()
            "person" -> if (isVictim()) victimHome() else personPage()
            else -> if (isVictim()) victimHome() else guardianHome()
        }
    }

    private fun centered(v: View, top: Int = 12): View {
        val lp = v.layoutParams as LinearLayout.LayoutParams
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.marginEnd = 0
        lp.topMargin = Ui.dp(this, top)
        return v
    }

    private fun centerText(t: TextView): TextView {
        t.gravity = Gravity.CENTER_HORIZONTAL
        return t
    }

    /** Top bar. Home: menu, title, profile. Sub-page: back arrow and title. */
    private fun topBar(title: String, sub: Boolean) {
        val bar = Ui.row(this, 0)
        bar.addView(Ui.iconBtn(this, if (sub) R.drawable.ic_back else R.drawable.ic_menu) { v ->
            if (sub) nav("home") else showMenu(v)
        })
        if (!sub) bar.addView(Ui.logo(this, 40).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(this@MainActivity, 10)
        })
        val t = Ui.col(this)
        t.addView(Ui.tag(this, if (sub) "S.P.D" else if (isVictim()) "S.P.D · PROTECTED" else "S.P.D · GUARDIAN"))
        t.addView(Ui.text(this, title, 20f, Ui.INK, true))
        bar.addView(t)
        if (!sub) {
            val av = Ui.avatar(this, Prefs.name(this).take(1).uppercase().ifBlank { "K" }, Ui.CYAN)
            (av.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
            av.setOnClickListener { nav("profile") }
            bar.addView(av)
        }
        add(bar)
    }

    private fun showMenu(anchor: View) {
        val items = if (isVictim()) listOf(
            "Profile" to "profile", "Guardians" to "guardians", "PIN and duress PIN" to "pins",
            "Screen-share shield" to "shield", "Permissions" to "perms", "Settings" to "settings",
            "How S.P.D works" to "how", "Log out" to "logout"
        ) else listOf(
            "Profile" to "profile", "Person I protect" to "person", "Permissions" to "perms",
            "How S.P.D works" to "how", "Log out" to "logout"
        )
        val m = PopupMenu(this, anchor)
        items.forEachIndexed { i, e -> m.menu.add(0, i, i, e.first) }
        m.setOnMenuItemClickListener { item ->
            val target = items[item.itemId].second
            if (target == "logout") logout() else nav(target)
            true
        }
        m.show()
    }

    /** Lays tiles out two per row. */
    private fun grid(tiles: List<View>) {
        tiles.chunked(2).forEach { pair ->
            val r = Ui.row(this, 12)
            r.gravity = Gravity.TOP
            pair.forEachIndexed { i, t ->
                t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i == 0) marginEnd = Ui.dp(this@MainActivity, 6) else marginStart = Ui.dp(this@MainActivity, 6)
                }
                r.addView(t)
            }
            if (pair.size == 1) r.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = Ui.dp(this@MainActivity, 6) }
            })
            add(r)
        }
    }

    // ------------------------------------------------------------------ account: welcome, sign up, log in

    private fun welcomePage() {
        val badge = Ui.logo(this, 140)
        add(centered(badge, 40))
        anims.add(Ui.spin(badge))
        add(centerText(Ui.text(this, "S.P.D", 34f, Ui.INK, true)).apply { setPadding(0, Ui.dp(this@MainActivity, 18), 0, 0) })
        add(centerText(Ui.tag(this, "SCAM PROTECTION & DETECTION")))
        add(centerText(Ui.title(this, "Stop scam calls before the money moves")))
        add(centerText(Ui.text(this, "S.P.D spots the pattern of a digital arrest scam on this phone and pulls your family in while the call is still live.", 15f, Ui.MUTED)))

        val c = Ui.card(this)
        listOf(
            "Pauses payments made during a long call",
            "Texts your family and asks them to approve",
            "Warns you if your screen is being shared",
            "Never records calls or reads your chats"
        ).forEach { c.addView(Ui.text(this, "✓  $it", 14f, Ui.INK).apply { setPadding(0, Ui.dp(this@MainActivity, 5), 0, 0) }) }
        add(c)

        add(Ui.button(this, "Create my account", Ui.CYAN, Ui.DARK) { nav("signup") })
        add(centerText(Ui.text(this, "Your account stays on this phone. There is no server and no password to steal.", 12f, Ui.MUTED)))
    }

    private fun signupPage() {
        add(Ui.tag(this, "STEP 1 OF 2"))
        add(Ui.title(this, "Create your account"))
        add(Ui.bar(this, 0.5f, Ui.CYAN))

        add(Ui.text(this, "Who will use this phone?", 15f, Ui.INK, true).apply { setPadding(0, Ui.dp(this@MainActivity, 18), 0, 0) })
        val a = Ui.tile(this, R.drawable.ic_stat, "Protect me", "I could be targeted by a scam call", Ui.CYAN) {}
        val b = Ui.tile(this, R.drawable.ic_users, "I'm a guardian", "I watch over a family member", Ui.AMBER) {}
        fun mark() {
            a.background = Ui.round(this, if (suRole == Prefs.ROLE_VICTIM) Ui.PANEL2 else Ui.PANEL, if (suRole == Prefs.ROLE_VICTIM) Ui.CYAN else Ui.LINE)
            b.background = Ui.round(this, if (suRole == Prefs.ROLE_GUARDIAN) Ui.PANEL2 else Ui.PANEL, if (suRole == Prefs.ROLE_GUARDIAN) Ui.AMBER else Ui.LINE)
        }
        a.setOnClickListener { suRole = Prefs.ROLE_VICTIM; mark() }
        b.setOnClickListener { suRole = Prefs.ROLE_GUARDIAN; mark() }
        mark()
        grid(listOf(a, b))

        val name = Ui.edit(this, "Your name, e.g. Sunita")
        val phone = Ui.edit(this, "Your 10-digit mobile number", Ui.PHONE_INPUT)
        name.setText(Prefs.name(this))
        phone.setText(Prefs.phone(this))
        add(name)
        add(phone)
        add(Ui.button(this, "Next", Ui.CYAN, Ui.DARK) {
            if (name.text.isBlank() || Phone.norm(phone.text.toString()).length != 10) {
                toast("Enter your name and a 10-digit number")
            } else {
                Prefs.setIdentity(this, name.text.toString(), phone.text.toString())
                Prefs.setRole(this, suRole)
                nav("signup2")
            }
        })
    }

    private fun signupPinPage() {
        val victim = isVictim()
        add(Ui.tag(this, "STEP 2 OF 2"))
        add(Ui.title(this, "Choose your PIN"))
        add(Ui.bar(this, 1f, Ui.CYAN))
        add(Ui.text(this, "You will enter this 4-digit PIN each time you open S.P.D.", 14f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 14), 0, 0)
        })
        val real = Ui.edit(this, "PIN (4 digits)", Ui.PIN_INPUT)
        val second = Ui.edit(this, if (victim) "Duress PIN (4 digits, different)" else "Enter the PIN again", Ui.PIN_INPUT)
        add(real)
        add(second)
        if (victim) {
            val c = Ui.card(this, Ui.AMBER)
            c.addView(Ui.text(this, "What is a duress PIN?", 15f, Ui.AMBER, true))
            c.addView(Ui.text(this, "If a caller ever forces you to open S.P.D and switch it off, enter this second PIN instead. The app will look switched off, but it keeps protecting you and quietly alerts your family.", 13f, Ui.MUTED))
            add(c)
        }
        add(Ui.button(this, "Finish", Ui.CYAN, Ui.DARK) {
            val x = real.text.toString()
            val y = second.text.toString()
            when {
                x.length != 4 || y.length != 4 -> toast("Both must be 4 digits")
                victim && x == y -> toast("The duress PIN must be different")
                !victim && x != y -> toast("The two PINs do not match")
                else -> {
                    if (victim) Prefs.setPins(this, x, y) else Prefs.setPin(this, x)
                    unlocked = true
                    lastActive = System.currentTimeMillis()
                    nav("home")
                }
            }
        })
        add(Ui.button(this, "Back") { nav("signup") })
    }

    private fun loginPage() {
        val badge = Ui.logo(this, 110)
        add(centered(badge, 30))
        anims.add(Ui.spin(badge))
        add(centerText(Ui.text(this, "Welcome back, ${Prefs.name(this)}", 22f, Ui.INK, true)).apply {
            setPadding(0, Ui.dp(this@MainActivity, 18), 0, 0)
        })
        val msg = centerText(Ui.text(this, "Enter your PIN", 14f, Ui.MUTED))
        loginMsg = msg
        add(msg)

        val dotRow = Ui.row(this, 18)
        dotRow.gravity = Gravity.CENTER
        val list = ArrayList<View>()
        repeat(4) {
            val d = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16)).apply {
                    marginStart = Ui.dp(this@MainActivity, 9)
                    marginEnd = Ui.dp(this@MainActivity, 9)
                }
            }
            list.add(d)
            dotRow.addView(d)
        }
        dots = list
        add(dotRow)
        refreshDots()

        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { keys ->
            val r = Ui.row(this, 14)
            r.gravity = Gravity.CENTER
            keys.forEach { k ->
                if (k.isEmpty()) {
                    r.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 68), Ui.dp(this@MainActivity, 68)).apply {
                            marginStart = Ui.dp(this@MainActivity, 10)
                            marginEnd = Ui.dp(this@MainActivity, 10)
                        }
                    })
                } else {
                    r.addView(Ui.key(this, k) { onKey(k, dotRow) })
                }
            }
            add(r)
        }

        add(centerText(Ui.text(this, "Forgot PIN?", 14f, Ui.CYAN)).apply {
            setPadding(0, Ui.dp(this@MainActivity, 20), 0, Ui.dp(this@MainActivity, 6))
            setOnClickListener { forgotPin() }
        })
        if (isVictim() && Prefs.activeGuardians(this).isNotEmpty()) {
            add(Ui.button(this, "SOS: text and call my family now", Ui.RED, Ui.DARK) { sos() })
        }
    }

    private fun refreshDots() {
        dots.forEachIndexed { i, d ->
            d.background = Ui.oval(this, if (i < pin.length) Ui.CYAN else Ui.BG, if (i < pin.length) Ui.CYAN else Ui.MUTED)
        }
    }

    private fun onKey(k: String, dotRow: View) {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) {
            loginMsg?.text = "Too many wrong tries. Wait ${(lockedUntil - now) / 1000 + 1} seconds."
            Ui.shake(dotRow)
            return
        }
        if (k == "⌫") {
            pin = pin.dropLast(1)
            refreshDots()
            return
        }
        if (pin.length >= 4) return
        pin += k
        refreshDots()
        if (pin.length < 4) return
        val entered = pin
        handler.postDelayed({
            when (Prefs.checkPin(this, entered)) {
                1 -> openApp()
                2 -> {
                    // Duress PIN at the lock screen: open as normal, look switched off, alert the family.
                    Prefs.setFakeOff(this, true)
                    Messenger.toGuardians(this, "DURESS")
                    MonitorService.start(this)
                    openApp()
                }
                else -> {
                    fails++
                    if (fails >= 5) {
                        lockedUntil = System.currentTimeMillis() + 30_000
                        fails = 0
                    }
                    pin = ""
                    refreshDots()
                    loginMsg?.text = "Wrong PIN. Try again."
                    loginMsg?.setTextColor(Ui.RED)
                    Ui.shake(dotRow)
                }
            }
        }, 140)
    }

    private fun openApp() {
        fails = 0
        pin = ""
        unlocked = true
        lastActive = System.currentTimeMillis()
        nav("home")
    }

    private fun logout() {
        unlocked = false
        pin = ""
        nav("login")
    }

    private fun forgotPin() {
        AlertDialog.Builder(this).setTitle("Reset S.P.D?")
            .setMessage("The PIN cannot be recovered. Resetting erases your account on this phone, and you set it up again." +
                if (isVictim()) " Your guardians will be told by SMS that protection was reset." else "")
            .setPositiveButton("Reset") { _, _ ->
                if (isVictim() && granted(Manifest.permission.SEND_SMS)) Messenger.toGuardians(this, "RESET")
                resetAll()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ protected person: home

    private fun victimHome() {
        val on = Prefs.enabled(this) && !Prefs.fakeOff(this)
        val gs = Prefs.guardians(this)
        val perms = victimPerms()
        val missing = perms.count { !it.granted() }
        topBar("Hello, ${Prefs.name(this)}", false)

        val status = Ui.card(this, if (on) Ui.GREEN else Ui.LINE)
        val head = Ui.row(this, 0)
        val badge = Ui.ring(this, if (on) Ui.GREEN else Ui.MUTED)
        if (on) anims.add(Ui.pulse(badge))
        head.addView(badge)
        val headText = Ui.col(this)
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
            status.addView(Ui.text(this, "These light up during a call. The first three together, or screen sharing alone, alert your family.", 12f, Ui.MUTED))
            status.addView(Ui.button(this, "Turn off protection (needs PIN)") { pinDialog() })
        } else {
            status.addView(Ui.button(this, "Turn on protection", Ui.CYAN, Ui.DARK) { turnOn() })
        }
        Ui.tilt(status)
        add(status)

        add(Ui.button(this, "SOS: I think this call is a scam\nText and call my family now", Ui.RED, Ui.DARK) { sos() })

        val steps = listOf(
            Triple("Allow permissions", missing == 0, "perms"),
            Triple("Add a guardian", gs.isNotEmpty(), "guardians"),
            Triple("Turn on protection", on, "")
        )
        val done = steps.count { it.second }
        if (done < steps.size) {
            val sc = Ui.card(this, Ui.AMBER)
            sc.addView(Ui.text(this, "Finish setup · $done of ${steps.size}", 17f, Ui.INK, true))
            sc.addView(Ui.bar(this, done.toFloat() / steps.size, Ui.AMBER))
            steps.forEach { s ->
                sc.addView(Ui.text(this, (if (s.second) "✓  " else "○  ") + s.first, 14f, if (s.second) Ui.GREEN else Ui.INK).apply {
                    setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
                    if (!s.second && s.third.isNotEmpty()) setOnClickListener { nav(s.third) }
                })
            }
            add(sc)
        }

        add(Ui.text(this, "Everything in one place", 16f, Ui.INK, true).apply { setPadding(0, Ui.dp(this@MainActivity, 18), 0, 0) })
        val shareOn = notifAccess()
        grid(listOf(
            Ui.tile(this, R.drawable.ic_users, "Guardians", if (gs.isEmpty()) "Add a family member" else "${gs.size} added", Ui.CYAN) { nav("guardians") },
            Ui.tile(this, R.drawable.ic_cast, "Screen-share shield", if (shareOn) "Watching video calls" else "Needs one permission", if (shareOn) Ui.GREEN else Ui.AMBER) { nav("shield") },
            Ui.tile(this, R.drawable.ic_lock, "PIN and duress PIN", "Change your PINs", Ui.CYAN) { nav("pins") },
            Ui.tile(this, R.drawable.ic_check, "Permissions", if (missing == 0) "All granted" else "$missing still needed", if (missing == 0) Ui.GREEN else Ui.AMBER) { nav("perms") },
            Ui.tile(this, R.drawable.ic_msg, "Test alert", "Send a test SMS", Ui.CYAN) { testSms() },
            Ui.tile(this, R.drawable.ic_clock, "Demo mode", if (Prefs.demo(this)) "On: 1-minute calls" else "Off: 30-minute calls", if (Prefs.demo(this)) Ui.AMBER else Ui.CYAN) { nav("settings") },
            Ui.tile(this, R.drawable.ic_info, "How it works", "The rule in plain words", Ui.CYAN) { nav("how") },
            Ui.tile(this, R.drawable.ic_user, "Profile", "Your account", Ui.CYAN) { nav("profile") }
        ))
        add(Ui.text(this, "S.P.D never records calls and never reads your chats.", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 16), 0, 0)
        })
    }

    private fun updateChips() {
        if (chips.size < 4) return
        val call = Engine.onCall()
        Ui.setChip(chips[0], call && Engine.minutes() >= Prefs.thresholdMinutes(this))
        Ui.setChip(chips[1], call && Engine.paymentDuringCall)
        Ui.setChip(chips[2], call && Engine.otpDuringCall)
        Ui.setChip(chips[3], call && (Engine.remoteDuringCall || Engine.shareDuringCall))
    }

    private fun liveText(): String {
        if (!isVictim()) return ""
        if (!Prefs.enabled(this) || Prefs.fakeOff(this)) return "Turn protection on to start watching."
        if (!Engine.onCall()) return "No call right now. S.P.D is watching quietly."
        val m = Engine.minutes()
        return "On a call · ${m.toInt()} min ${((m - m.toInt()) * 60).toInt()} s" +
            (if (Engine.alertSent || Engine.remoteDuringCall || Engine.shareDuringCall) "\nYour family has been alerted." else "")
    }

    // ------------------------------------------------------------------ protected person: sub-pages

    private fun guardiansPage() {
        topBar("Guardians", true)
        add(Ui.text(this, "Family members who get an SMS if a call looks like a scam. They do not need the app, and can reply YES or NO to approve or block a payment.", 14f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0)
        })
        val gs = Prefs.guardians(this)
        gs.forEach { g ->
            val pending = g.activeAt > System.currentTimeMillis()
            val c = Ui.card(this, if (pending) Ui.AMBER else Ui.LINE)
            val r = Ui.row(this, 0)
            r.addView(Ui.avatar(this, g.name.take(1).uppercase().ifBlank { "G" }, if (pending) Ui.AMBER else Ui.CYAN))
            val t = Ui.col(this)
            t.addView(Ui.text(this, g.name, 16f, Ui.INK, true))
            t.addView(Ui.text(this, g.phone + if (pending) " · active in 24 h" else "", 13f, if (pending) Ui.AMBER else Ui.MUTED))
            r.addView(t)
            r.addView(Ui.small(this, "Remove") { removeGuardianDialog(g) })
            c.addView(r)
            add(c)
        }
        if (gs.isEmpty()) add(Ui.card(this).apply { addView(Ui.text(this@MainActivity, "No guardians yet.", 14f, Ui.MUTED)) })
        add(Ui.button(this, "Add a guardian", Ui.CYAN, Ui.DARK) { addGuardianDialog() })
        if (gs.isNotEmpty()) add(Ui.button(this, "Send a test SMS to my guardians") { testSms() })
    }

    private fun pinsPage() {
        topBar("PIN and duress PIN", true)
        val c = Ui.card(this)
        c.addView(Ui.text(this, "Your PIN", 16f, Ui.INK, true))
        c.addView(Ui.text(this, "Opens the app, switches protection off and removes guardians.", 13f, Ui.MUTED))
        add(c)
        val d = Ui.card(this, Ui.AMBER)
        d.addView(Ui.text(this, "Your duress PIN", 16f, Ui.AMBER, true))
        d.addView(Ui.text(this, "Use it if someone is forcing you. The app looks switched off, but keeps protecting you and quietly alerts your family.", 13f, Ui.MUTED))
        add(d)
        add(Ui.button(this, "Change PINs", Ui.CYAN, Ui.DARK) {
            askPin("Enter your current PIN") { r ->
                when (r) {
                    1 -> setPinsDialog()
                    2 -> duressOff()
                    else -> toast("Wrong PIN")
                }
            }
        })
    }

    private fun shieldPage() {
        topBar("Screen-share shield", true)
        val ok = notifAccess()
        val c = Ui.card(this, if (ok) Ui.GREEN else Ui.AMBER)
        val r = Ui.row(this, 0)
        val badge = Ui.ring(this, if (ok) Ui.GREEN else Ui.AMBER, 60, R.drawable.ic_cast)
        if (ok) anims.add(Ui.pulse(badge))
        r.addView(badge)
        val t = Ui.col(this)
        t.addView(Ui.text(this, if (ok) "Shield is on" else "One permission needed", 18f, if (ok) Ui.GREEN else Ui.AMBER, true))
        t.addView(Ui.text(this, if (ok) "S.P.D is watching for screen sharing during calls." else "Allow notification access so S.P.D can tell when a video call starts sharing your screen.", 13f, Ui.MUTED))
        r.addView(t)
        c.addView(r)
        if (!ok) c.addView(Ui.button(this, "Allow notification access", Ui.AMBER, Ui.DARK) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        add(c)

        val a = Ui.card(this)
        a.addView(Ui.text(this, "Screen sharing inside a video call", 16f, Ui.INK, true))
        a.addView(Ui.text(this, "Scammers on WhatsApp video calls ask you to tap \"Share screen\" so they can watch your bank app and OTPs. If sharing starts in WhatsApp, Google Meet, Zoom, Telegram, Teams, Skype or Signal during a call, S.P.D shows a red stop screen and texts your guardians. The caller sees only a black screen in place of the warning.", 13f, Ui.MUTED))
        add(a)
        val b = Ui.card(this)
        b.addView(Ui.text(this, "Remote-control apps", 16f, Ui.INK, true))
        b.addView(Ui.text(this, "Opening AnyDesk, TeamViewer, RustDesk, AirDroid or Zoho Assist during any call triggers the same stop screen and alert.", 13f, Ui.MUTED))
        add(b)
        add(Ui.text(this, "Privacy: S.P.D looks only at the ongoing call notification from these apps, and only for screen-share wording. It never reads your messages.", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0)
        })
    }

    private fun settingsPage() {
        topBar("Settings", true)
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
        add(dc)
        add(Ui.button(this, "Log out") { logout() })
        add(Ui.button(this, "Reset this phone (needs PIN)") { resetWithPin() })
    }

    // ------------------------------------------------------------------ guardian: home and sub-pages

    private fun guardianHome() {
        topBar("Hello, ${Prefs.name(this)}", false)
        val linked = Prefs.victimPhone(this).isNotEmpty()
        val who = Prefs.victimName(this).ifBlank { "them" }

        val hero = Ui.card(this, if (linked) Ui.CYAN else Ui.AMBER)
        val head = Ui.row(this, 0)
        val badge = Ui.ring(this, if (linked) Ui.CYAN else Ui.AMBER, 68, R.drawable.ic_users)
        if (linked) anims.add(Ui.pulse(badge))
        head.addView(badge)
        val t = Ui.col(this)
        if (linked) {
            t.addView(Ui.text(this, "Watching over $who", 20f, Ui.CYAN, true))
            t.addView(Ui.text(this, "S.P.D alerts from ${Prefs.victimPhone(this)} open full screen on this phone.", 13f, Ui.MUTED))
        } else {
            t.addView(Ui.text(this, "Who are you protecting?", 20f, Ui.AMBER, true))
            t.addView(Ui.text(this, "Add the person who made you their guardian.", 13f, Ui.MUTED))
        }
        head.addView(t)
        hero.addView(head)
        if (linked) {
            hero.addView(Ui.button(this, "Call $who now", Ui.CYAN, Ui.DARK) { call(Prefs.victimPhone(this)) })
        } else {
            hero.addView(Ui.button(this, "Add the person I protect", Ui.AMBER, Ui.DARK) { nav("person") })
        }
        Ui.tilt(hero)
        add(hero)

        add(Ui.text(this, "Recent alerts", 16f, Ui.INK, true).apply { setPadding(0, Ui.dp(this@MainActivity, 18), 0, 0) })
        val alerts = Prefs.alerts(this)
        if (alerts.isEmpty()) {
            add(Ui.card(this).apply {
                addView(Ui.text(this@MainActivity, "No alerts yet. You will only hear from S.P.D if $who looks to be in trouble.", 14f, Ui.MUTED))
            })
        }
        val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
        alerts.take(8).forEach { a ->
            val color = when (a.second) {
                "DURESS", "TAMPER" -> Ui.RED
                "ALERT", "HELP", "APPROVE_REQ" -> Ui.AMBER
                else -> Ui.LINE
            }
            val c = Ui.card(this, color)
            val r = Ui.row(this, 0)
            r.addView(Ui.text(this, AlertActivity.titleFor(a.second), 14f, if (color == Ui.LINE) Ui.INK else color, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            r.addView(Ui.text(this, fmt.format(Date(a.first)), 12f, Ui.MUTED).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            c.addView(r)
            c.addView(Ui.text(this, a.third, 13f, Ui.MUTED))
            add(c)
        }

        val missing = guardianPerms().count { !it.granted() }
        grid(listOf(
            Ui.tile(this, R.drawable.ic_users, "Person I protect", if (linked) who else "Not added yet", if (linked) Ui.CYAN else Ui.AMBER) { nav("person") },
            Ui.tile(this, R.drawable.ic_check, "Permissions", if (missing == 0) "All granted" else "$missing still needed", if (missing == 0) Ui.GREEN else Ui.AMBER) { nav("perms") },
            Ui.tile(this, R.drawable.ic_info, "How it works", "What each alert means", Ui.CYAN) { nav("how") },
            Ui.tile(this, R.drawable.ic_user, "Profile", "Your account", Ui.CYAN) { nav("profile") }
        ))
    }

    private fun personPage() {
        topBar("Person I protect", true)
        val c = Ui.card(this, Ui.CYAN)
        c.addView(Ui.text(this, "Enter the name and number of the person who added you as their guardian. S.P.D SMS from that number will open full screen here.", 13f, Ui.MUTED))
        val n = Ui.edit(this, "Their name, e.g. Mom")
        val ph = Ui.edit(this, "Their 10-digit mobile number", Ui.PHONE_INPUT)
        n.setText(Prefs.victimName(this))
        ph.setText(Prefs.victimPhone(this))
        c.addView(n)
        c.addView(ph)
        c.addView(Ui.button(this, "Save", Ui.CYAN, Ui.DARK) {
            if (n.text.isBlank() || Phone.norm(ph.text.toString()).length != 10) toast("Enter their name and a 10-digit number")
            else { Prefs.setVictim(this, n.text.toString(), ph.text.toString()); toast("Saved"); nav("home") }
        })
        add(c)
    }

    // ------------------------------------------------------------------ shared pages

    private fun profilePage() {
        topBar("Profile", true)
        val av = Ui.avatar(this, Prefs.name(this).take(1).uppercase().ifBlank { "K" }, Ui.CYAN)
        av.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84))
        av.textSize = 34f
        add(centered(av, 22))
        anims.add(Ui.spin(av))
        add(centerText(Ui.text(this, Prefs.name(this), 22f, Ui.INK, true)).apply { setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0) })
        add(centerText(Ui.text(this, if (isVictim()) "Protected account" else "Guardian account", 13f, Ui.AMBER)))

        val c = Ui.card(this)
        c.addView(Ui.text(this, "Your details", 16f, Ui.INK, true))
        val name = Ui.edit(this, "Your name")
        val phone = Ui.edit(this, "Your 10-digit mobile number", Ui.PHONE_INPUT)
        name.setText(Prefs.name(this))
        phone.setText(Prefs.phone(this))
        c.addView(name)
        c.addView(phone)
        c.addView(Ui.button(this, "Save changes", Ui.CYAN, Ui.DARK) {
            if (name.text.isBlank() || Phone.norm(phone.text.toString()).length != 10) toast("Enter your name and a 10-digit number")
            else { Prefs.setIdentity(this, name.text.toString(), phone.text.toString()); toast("Saved"); build(false) }
        })
        add(c)
        add(Ui.button(this, "Log out") { logout() })
        add(Ui.button(this, if (isVictim()) "Reset this phone (needs PIN)" else "Reset this phone") { resetWithPin() })
        add(centerText(Ui.text(this, "Your account is stored only on this phone.", 12f, Ui.MUTED)))
    }

    private fun permsPage() {
        topBar("Permissions", true)
        permissionCard(if (isVictim()) victimPerms() else guardianPerms())
    }

    private fun howPage() {
        topBar("How S.P.D works", true)
        val steps = if (isVictim()) listOf(
            "S.P.D watches three signals" to "A call running 30 minutes or more, a payment or bank app opened during it, and a bank OTP arriving.",
            "It pauses the payment" to "When you open a payment app during a long call, a pause screen appears on top. You can call your family or ask them to approve.",
            "Your family decides with you" to "Guardians get an SMS and reply YES or NO. Only replies from saved guardians count.",
            "Screen sharing sets it off at once" to "If your screen is shared in a video call, or a remote-control app opens during a call, S.P.D stops you and alerts your family.",
            "A duress PIN for the worst case" to "Forced to switch S.P.D off? Enter the duress PIN. It looks off, stays on, and tells your family."
        ) else listOf(
            "COERCION ALERT" to "A long call, a payment app and a bank OTP happened together. Call them straight away.",
            "Payment approval needed" to "They want to pay during a long call. Reply NO unless you know exactly who they are paying.",
            "Screen sharing or remote control" to "A scammer may be watching their phone. Call them and tell them to hang up.",
            "DURESS PIN USED" to "Someone is forcing them to switch S.P.D off. Call them, and consider calling 1930 or the police.",
            "S.P.D is being removed" to "Someone is taking protection off their phone. Check in with them."
        )
        steps.forEachIndexed { i, s ->
            val c = Ui.card(this)
            val r = Ui.row(this, 0)
            r.addView(Ui.avatar(this, "${i + 1}", if (i == 0) Ui.AMBER else Ui.CYAN))
            val t = Ui.col(this)
            t.addView(Ui.text(this, s.first, 15f, Ui.INK, true))
            t.addView(Ui.text(this, s.second, 13f, Ui.MUTED))
            r.addView(t)
            c.addView(r)
            Ui.tilt(c)
            add(c)
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun notifAccess() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun smsPerm() = Perm("SMS: spot bank OTPs and text your guardians",
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
            Perm("Notification access: spot screen sharing in WhatsApp and other video calls", { notifAccess() },
                { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }),
            callPerm(),
            notifPerm(),
            Perm("Tamper alarm: tell family if someone tries to remove S.P.D", { dpm.isAdminActive(admin) }, {
                open(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets S.P.D warn your family before it can be uninstalled."))
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
        card.addView(Ui.text(this, if (missing == 0) "All permissions granted" else "$missing still needed", 17f, Ui.INK, true))
        card.addView(Ui.bar(this, (perms.size - missing).toFloat() / perms.size, if (missing == 0) Ui.GREEN else Ui.AMBER))
        perms.forEach { p ->
            if (p.granted()) {
                card.addView(Ui.text(this, "✓  ${p.label}", 13f, Ui.GREEN).apply { setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0) })
            } else {
                card.addView(Ui.button(this, "Allow: ${p.label}") { p.request() })
            }
        }
        add(card)
        add(Ui.text(this,
            "On Xiaomi, Redmi, POCO, Vivo, Oppo, Realme or OnePlus phones, also turn on Autostart for S.P.D (Settings › Apps › S.P.D › Autostart). Without it, replies and OTPs can be missed when the app is closed.", 12f, Ui.AMBER).apply {
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0)
        })
        if (missing > 0) add(Ui.text(this,
            "If Android says \"Restricted setting\", open Settings › Apps › S.P.D › ⋮ › Allow restricted settings, then try again.", 12f, Ui.MUTED))
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
        try { startActivity(i) } catch (e: Exception) { toast("Open Settings and allow this for S.P.D") }
    }

    // ------------------------------------------------------------------ actions

    private fun call(number: String) {
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        open(Intent(action, Uri.parse("tel:$number")))
    }

    private fun testSms() {
        when {
            Prefs.activeGuardians(this).isEmpty() -> { toast("Add a guardian first"); nav("guardians") }
            !granted(Manifest.permission.SEND_SMS) -> toast("Allow SMS first")
            else -> { Messenger.toGuardians(this, "TEST"); toast("Test SMS sent") }
        }
    }

    /** One-tap help: texts every guardian and calls the first one. */
    private fun sos() {
        val gs = Prefs.activeGuardians(this)
        if (gs.isEmpty()) { toast("Add a guardian first"); return }
        AlertDialog.Builder(this).setTitle("Alert your family now?")
            .setMessage("S.P.D will text your guardians and call ${gs[0].name}.")
            .setPositiveButton("Yes, alert them") { _, _ ->
                if (granted(Manifest.permission.SEND_SMS)) Messenger.toGuardians(this, "SOS")
                call(gs[0].phone)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun turnOn() {
        if (Prefs.guardians(this).isEmpty()) toast("Tip: add a guardian so someone is alerted")
        Prefs.setEnabled(this, true)
        Prefs.setFakeOff(this, false)
        MonitorService.start(this)
        build(true)
    }

    private fun pinDialog() {
        askPin("Enter your PIN to turn off protection") { r ->
            when (r) {
                1 -> {
                    Prefs.setEnabled(this, false)
                    MonitorService.stop(this)
                    Messenger.toGuardians(this, "DISABLED")
                    build(true)
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
        nav("home")
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
        val real = Ui.edit(this, "New PIN (4 digits)", Ui.PIN_INPUT)
        val duress = Ui.edit(this, "New duress PIN (4 digits, different)", Ui.PIN_INPUT)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 20), 0)
            addView(real); addView(duress)
        }
        AlertDialog.Builder(this).setTitle("Set your PINs").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val a = real.text.toString(); val b = duress.text.toString()
                if (a.length != 4 || b.length != 4 || a == b) toast("Use two different 4-digit PINs")
                else { Prefs.setPins(this, a, b); toast("PINs saved") }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addGuardianDialog() {
        if (!granted(Manifest.permission.SEND_SMS)) { toast("Allow SMS first, so S.P.D can text your guardian"); nav("perms"); return }
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
                        build(true)
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun removeGuardianDialog(g: Guardian) {
        askPin("Enter your PIN to remove ${g.name}") { r ->
            when (r) {
                1 -> {
                    Prefs.removeGuardian(this, g.phone)
                    Messenger.sms(this, g.phone, Messenger.text(this, "REMOVED"))
                    toast("${g.name} removed")
                    build(true)
                }
                2 -> duressOff()
                else -> toast("Wrong PIN")
            }
        }
    }

    private fun resetWithPin() {
        askPin("Enter your PIN to reset") { r ->
            when (r) {
                1 -> {
                    if (isVictim() && granted(Manifest.permission.SEND_SMS)) Messenger.toGuardians(this, "RESET")
                    resetAll()
                }
                2 -> duressOff()
                else -> toast("Wrong PIN")
            }
        }
    }

    private fun resetAll() {
        MonitorService.stop(this)
        Prefs.resetAll(this)
        unlocked = false
        pin = ""
        suRole = Prefs.ROLE_VICTIM
        nav("welcome")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
