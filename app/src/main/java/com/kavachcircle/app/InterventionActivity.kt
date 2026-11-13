package com.kavachcircle.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The pause screen. It opens on top of Paytm / Google Pay / PhonePe when a payment app is opened
 * during a long call. It is a full screen of its own rather than a floating overlay, because many
 * UPI apps hide floating overlays to stop overlay attacks.
 */
class InterventionActivity : AppCompatActivity() {

    companion object {
        @Volatile var visible = false

        fun launch(c: Context, app: String, kind: Int = 0) {
            val i = Intent(c, InterventionActivity::class.java)
                .putExtra("app", app)
                .putExtra("kind", kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // "Display over other apps" also lets us open this screen from the background.
            if (Settings.canDrawOverlays(c)) {
                try {
                    c.startActivity(i)
                    return
                } catch (e: Exception) { }
            }
            Notify.fullScreen(c, i, if (kind != 0) "Stop. Do not share your screen" else "Pause before you pay",
                "You are on a call and opened $app. Tap to read this first.")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var openedAt = 0L
    private lateinit var status: TextView
    private lateinit var continueBtn: Button
    private lateinit var askBtn: Button
    private var app = "a payment app"
    private var remote = false
    private var kind = 0
    private var pulse: android.animation.Animator? = null

    private val refresh = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        app = intent.getStringExtra("app") ?: app
        kind = intent.getIntExtra("kind", 0)
        remote = kind != 0
        openedAt = System.currentTimeMillis()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@InterventionActivity, 22), Ui.dp(this@InterventionActivity, 36), Ui.dp(this@InterventionActivity, 22), Ui.dp(this@InterventionActivity, 28))
        }
        val badge = Ui.ring(this, if (remote) Ui.RED else Ui.AMBER, 76)
        root.addView(badge)
        pulse = Ui.pulse(badge)
        root.addView(Ui.tag(this, "S.P.D"))
        if (kind == 2) {
            root.addView(Ui.title(this, "Stop sharing your screen", Ui.RED))
            root.addView(Ui.text(this,
                "Your screen is being shared in $app while you are on a call.\n\n" +
                    "The caller can see everything on your phone, including your bank apps, passwords and OTPs. " +
                    "No bank or police officer needs to see your screen. Stop sharing now. " +
                    "The caller cannot see this warning, and your family has been told.", 16f))
        } else if (remote) {
            root.addView(Ui.title(this, "Stop. Do not share your screen", Ui.RED))
            root.addView(Ui.text(this,
                "You are on a call and just opened $app.\n\n" +
                    "$app lets another person see and control your phone, including your bank apps and OTPs. " +
                    "No bank, police officer or company will ever ask you to install it on a call. Your family has been told.", 16f))
        } else {
            root.addView(Ui.title(this, "Pause before you pay", Ui.AMBER))
            root.addView(Ui.text(this,
                "You have been on a call for ${Engine.minutes().toInt()} minutes and just opened $app.\n\n" +
                    "Real CBI, police, customs or RBI officers never ask you to pay, move money or \"verify\" your savings on a call. " +
                    "There is no such thing as a digital arrest.", 16f))
        }

        status = Ui.text(this, "", 14f, Ui.CYAN, true)
        root.addView(status)

        root.addView(Ui.button(this, "Call my family now", Ui.RED, Ui.DARK) { callFamily() })
        askBtn = Ui.button(this, "Ask my family to approve this payment", Ui.CYAN, Ui.DARK) { askApproval() }
        if (!remote) root.addView(askBtn)
        continueBtn = Ui.button(this, "Continue without approval") { continueAnyway() }
        root.addView(continueBtn)
        root.addView(Ui.button(this, "Go to home screen") { goHome() })
        root.addView(Ui.text(this, "This screen is hidden from screen sharing, so the caller cannot see it.", 12f, Ui.MUTED))

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goHome() }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A different warning (payment vs screen-sharing) replaces the one on screen.
        if (intent.getIntExtra("kind", 0) != kind) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onDestroy() {
        pulse?.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        visible = false
        handler.removeCallbacks(refresh)
    }

    private fun update() {
        val wait = Prefs.continueWaitSec(this)
        val left = wait - ((System.currentTimeMillis() - openedAt) / 1000).toInt()
        val by = Prefs.approverName(this).ifBlank { "Your family" }
        when (if (remote) Engine.APPROVAL_NONE else Prefs.approvalState(this)) {
            Engine.APPROVAL_WAITING -> {
                status.text = "Waiting for your family to reply YES or NO by SMS…"
                status.setTextColor(Ui.CYAN)
                askBtn.isEnabled = false
            }
            Engine.APPROVAL_YES -> {
                status.text = "$by replied YES. You can continue to the payment."
                status.setTextColor(Ui.GREEN)
                continueBtn.text = "Continue to payment"
                continueBtn.isEnabled = true
                askBtn.isEnabled = false
                return
            }
            Engine.APPROVAL_NO -> {
                status.text = "$by replied NO. Do not pay. Hang up the call."
                status.setTextColor(Ui.RED)
                continueBtn.isEnabled = false
                continueBtn.text = "Payment blocked by your family"
                askBtn.isEnabled = false
                return
            }
            else -> status.text = ""
        }
        if (left > 0) {
            continueBtn.isEnabled = false
            continueBtn.text = "Continue without approval (in ${left}s)"
        } else {
            continueBtn.isEnabled = true
            continueBtn.text = "Continue without approval"
        }
    }

    private fun callFamily() {
        Messenger.toGuardians(this, "HELP", Engine.minutes().toInt(), app)
        val g = Prefs.activeGuardians(this).firstOrNull() ?: return
        val canCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${g.phone}"))
        try { startActivity(i) } catch (e: Exception) { }
    }

    private fun askApproval() {
        if (Prefs.activeGuardians(this).isEmpty()) {
            status.text = "Add a guardian in S.P.D first."
            status.setTextColor(Ui.RED)
            return
        }
        val code = Crypto.approvalCode()
        Prefs.startApproval(this, code)
        Messenger.toGuardians(this, "APPROVE_REQ", Engine.minutes().toInt(), app, code)
        update()
    }

    private fun continueAnyway() {
        if (remote || Prefs.approvalState(this) != Engine.APPROVAL_YES) {
            Messenger.toGuardians(this, "CONTINUED", app = app)
        }
        if (remote) {
            Engine.remoteCleared = true
        } else {
            Engine.pauseCleared = true
            Prefs.clearApproval(this)
        }
        finish()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
