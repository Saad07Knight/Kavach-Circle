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

        fun launch(c: Context, app: String) {
            val i = Intent(c, InterventionActivity::class.java)
                .putExtra("app", app)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // "Display over other apps" also lets us open this screen from the background.
            if (Settings.canDrawOverlays(c)) {
                try {
                    c.startActivity(i)
                    return
                } catch (e: Exception) { }
            }
            Notify.fullScreen(c, i, "Pause before you pay", "You are on a long call and opened $app. Tap to read this first.")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var openedAt = 0L
    private lateinit var status: TextView
    private lateinit var continueBtn: Button
    private lateinit var askBtn: Button
    private var app = "a payment app"

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
        openedAt = System.currentTimeMillis()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@InterventionActivity, 22), Ui.dp(this@InterventionActivity, 36), Ui.dp(this@InterventionActivity, 22), Ui.dp(this@InterventionActivity, 28))
        }
        root.addView(Ui.tag(this, "KAVACH CIRCLE"))
        root.addView(Ui.title(this, "Pause before you pay", Ui.AMBER))
        root.addView(Ui.text(this,
            "You have been on a call for ${Engine.minutes().toInt()} minutes and just opened $app.\n\n" +
                "Real CBI, police, customs or RBI officers never ask you to pay, move money or \"verify\" your savings on a call. " +
                "There is no such thing as a digital arrest.", 16f))

        status = Ui.text(this, "", 14f, Ui.CYAN, true)
        root.addView(status)

        root.addView(Ui.button(this, "Call my family now", Ui.RED, Ui.DARK) { callFamily() })
        askBtn = Ui.button(this, "Ask my family to approve this payment", Ui.CYAN, Ui.DARK) { askApproval() }
        root.addView(askBtn)
        continueBtn = Ui.button(this, "Continue without approval") { continueAnyway() }
        root.addView(continueBtn)
        root.addView(Ui.button(this, "Go to home screen") { goHome() })
        root.addView(Ui.text(this, "This screen is hidden from screen sharing, so the caller cannot see it.", 12f, Ui.MUTED))

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goHome() }
        })
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
        when (Engine.approval) {
            Engine.APPROVAL_WAITING -> {
                status.text = "Waiting for your family to reply…"
                askBtn.isEnabled = false
            }
            Engine.APPROVAL_YES -> {
                status.text = "${Engine.approverName} approved this payment."
                status.setTextColor(Ui.GREEN)
                continueBtn.text = "Continue to payment"
                continueBtn.isEnabled = true
                askBtn.isEnabled = false
                return
            }
            Engine.APPROVAL_NO -> {
                status.text = "${Engine.approverName} said do not pay. Hang up the call."
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
        Messenger.toGuardians(this, "HELP", "${Engine.minutes().toInt()} min call, opened $app")
        val g = Prefs.activeGuardians(this).firstOrNull() ?: return
        val canCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${g.phone}"))
        try { startActivity(i) } catch (e: Exception) { }
    }

    private fun askApproval() {
        Engine.approval = Engine.APPROVAL_WAITING
        Messenger.toGuardians(this, "APPROVE_REQ", "${Engine.minutes().toInt()} min call, opened $app")
        update()
    }

    private fun continueAnyway() {
        if (Engine.approval != Engine.APPROVAL_YES) {
            Messenger.toGuardians(this, "CONTINUED", app)
        }
        Engine.pauseCleared = true
        finish()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
