package com.kavachcircle.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Full-screen alert on the guardian's phone. Serious alerts auto-call the protected person. */
class AlertActivity : AppCompatActivity() {

    companion object {
        private val AUTO_CALL = setOf("ALERT", "HELP", "DURESS", "TAMPER")

        fun launch(c: Context, type: String, payload: String) {
            val i = Intent(c, AlertActivity::class.java)
                .putExtra("type", type)
                .putExtra("payload", payload)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val (title, body) = describe(c, type, payload)
            if (Settings.canDrawOverlays(c)) {
                try {
                    c.startActivity(i)
                    Notify.info(c, title, body)
                    return
                } catch (e: Exception) { }
            }
            Notify.fullScreen(c, i, title, body)
        }

        fun describe(c: Context, type: String, payload: String): Pair<String, String> {
            val v = Prefs.victimName(c).ifBlank { "They" }
            return when (type) {
                "ALERT" -> "COERCION ALERT" to "$v has been on a call for $payload minutes, opened a payment app and just received a bank OTP. This matches a digital arrest scam."
                "HELP" -> "$v needs you now" to "$v tapped \"Call my family\" during a long call ($payload)."
                "APPROVE_REQ" -> "Payment approval needed" to "$v wants to pay during a long call ($payload). Approve only if you know exactly who they are paying."
                "DURESS" -> "DURESS PIN USED" to "$v entered the duress PIN. Someone may be forcing them to switch Kavach off. Protection is still running."
                "TAMPER" -> "Kavach is being removed" to "Someone is switching off Kavach's tamper protection on $v's phone. This is usually the step before uninstalling it."
                "DISABLED" -> "Protection turned off" to "$v turned Kavach off with the real PIN."
                "CONTINUED" -> "Payment continued without approval" to "$v waited out the pause and went on to $payload."
                "NEWG" -> "New guardian requested" to "$payload is being added as a guardian on $v's phone and becomes active in 24 hours. If you did not expect this, call $v."
                "TEST" -> "Test alert" to "This is a test from $v's phone. Your Kavach link works."
                else -> "Kavach Circle" to "Message from $v's phone."
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var countdown = -1
    private var countdownView: TextView? = null
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (countdown < 0) return
            if (countdown == 0) {
                countdown = -1
                callVictim()
                return
            }
            countdownView?.text = "Calling ${Prefs.victimName(this@AlertActivity)} automatically in ${countdown}s"
            countdown--
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        super.onDestroy()
    }

    private fun build() {
        handler.removeCallbacks(tickRunnable)
        val type = intent.getStringExtra("type") ?: ""
        val payload = intent.getStringExtra("payload") ?: ""
        val (title, body) = describe(this, type, payload)
        val serious = type in AUTO_CALL
        val color = when (type) {
            "DURESS", "TAMPER" -> Ui.RED
            "ALERT", "HELP", "APPROVE_REQ", "CONTINUED", "NEWG" -> Ui.AMBER
            else -> Ui.CYAN
        }
        vibrate(serious)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@AlertActivity, 22), Ui.dp(this@AlertActivity, 36), Ui.dp(this@AlertActivity, 22), Ui.dp(this@AlertActivity, 28))
        }
        root.addView(Ui.tag(this, "KAVACH CIRCLE · GUARDIAN"))
        root.addView(Ui.title(this, title, color))
        root.addView(Ui.text(this, body, 16f))

        if (serious) {
            val cd = Ui.text(this, "", 14f, Ui.AMBER, true)
            countdownView = cd
            root.addView(cd)
            countdown = 15
            handler.post(tickRunnable)
        }

        root.addView(Ui.button(this, "Call ${Prefs.victimName(this).ifBlank { "them" }} now", Ui.CYAN, Ui.DARK) { callVictim() })
        if (type == "APPROVE_REQ") {
            root.addView(Ui.button(this, "Block this payment", Ui.RED, Ui.DARK) {
                Messenger.toVictim(this, "DENY", "")
                done("You blocked the payment. They have been told not to pay.")
            })
            root.addView(Ui.button(this, "Approve this payment") {
                Messenger.toVictim(this, "APPROVE", "")
                done("You approved the payment.")
            })
        }
        if (serious) {
            root.addView(Ui.button(this, "Stop the automatic call") {
                countdown = -1
                handler.removeCallbacks(tickRunnable)
                countdownView?.text = "Automatic call stopped."
            })
        }
        root.addView(Ui.button(this, "Close") { finish() })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    private fun done(msg: String) {
        countdown = -1
        handler.removeCallbacks(tickRunnable)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@AlertActivity, 22), Ui.dp(this@AlertActivity, 48), Ui.dp(this@AlertActivity, 22), Ui.dp(this@AlertActivity, 28))
        }
        root.addView(Ui.title(this, msg, Ui.GREEN))
        root.addView(Ui.button(this, "Call ${Prefs.victimName(this).ifBlank { "them" }} now", Ui.CYAN, Ui.DARK) { callVictim() })
        root.addView(Ui.button(this, "Close") { finish() })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    private fun callVictim() {
        countdown = -1
        handler.removeCallbacks(tickRunnable)
        val ph = Prefs.victimPhone(this)
        if (ph.isEmpty()) return
        val canCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        try {
            startActivity(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$ph")))
        } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(strong: Boolean) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = if (strong) longArrayOf(0, 600, 300, 600, 300, 600) else longArrayOf(0, 300)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1)) else v.vibrate(pattern, -1)
        } catch (e: Exception) { }
    }
}
