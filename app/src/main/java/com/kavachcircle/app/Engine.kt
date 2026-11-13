package com.kavachcircle.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager

/**
 * The on-device rule engine. No AI: three signals, one fixed rule.
 *  1. A voice or video call has been running for the threshold (30 min, 1 min in demo mode)
 *  2. A payment or banking app was opened during that call
 *  3. A bank OTP or debit SMS arrived during that call
 * Signals 1 + 2 show the pause screen over the payment app. All three send an SMS alert to the guardians.
 */
object Engine {
    val PAYMENT_APPS: Map<String, String> = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "net.one97.paytm" to "Paytm",
        "com.phonepe.app" to "PhonePe",
        "in.org.npci.upiapp" to "BHIM",
        "com.sbi.lotusintouch" to "YONO SBI",
        "com.snapwork.hdfc" to "HDFC Bank",
        "com.csam.icici.bank.imobile" to "ICICI iMobile",
        "com.axis.mobile" to "Axis Mobile",
        "com.msf.kbank.mobile" to "Kotak",
        "com.dreamplug.androidapp" to "CRED",
        "com.mobikwik_new" to "MobiKwik",
        "com.freecharge.android" to "Freecharge",
        "com.bankofbaroda.mconnect" to "bob World",
        "com.amazon.mShop.android.shopping" to "Amazon Pay"
    )

    /** Screen-sharing and remote-control apps that scammers ask victims to install. */
    val REMOTE_APPS: Map<String, String> = mapOf(
        "com.anydesk.anydeskandroid" to "AnyDesk",
        "com.teamviewer.quicksupport.market" to "TeamViewer QuickSupport",
        "com.teamviewer.host.market" to "TeamViewer Host",
        "com.teamviewer.teamviewer.market.mobile" to "TeamViewer",
        "com.carriez.flutter_hbb" to "RustDesk",
        "com.sand.airdroid" to "AirDroid",
        "com.sand.airmirror" to "AirMirror",
        "com.zoho.assist.agent" to "Zoho Assist"
    )

    // approval states for the two-person rule
    const val APPROVAL_NONE = 0
    const val APPROVAL_WAITING = 1
    const val APPROVAL_YES = 2
    const val APPROVAL_NO = 3

    @Volatile var callStart = 0L
    @Volatile private var lastInCall = 0L
    @Volatile var paymentDuringCall = false
    @Volatile var otpDuringCall = false
    @Volatile var alertSent = false
    @Volatile var pauseCleared = false
    @Volatile var remoteDuringCall = false
    @Volatile var remoteCleared = false
    @Volatile var shareDuringCall = false
    @Volatile var lastApp = ""
    @Volatile private var lastFg = ""

    fun onCall(): Boolean = callStart != 0L
    fun minutes(): Double = if (callStart == 0L) 0.0 else (System.currentTimeMillis() - callStart) / 60000.0

    private fun resetCall() {
        paymentDuringCall = false
        otpDuringCall = false
        alertSent = false
        pauseCleared = false
        remoteDuringCall = false
        remoteCleared = false
        shareDuringCall = false
    }

    fun inCall(c: Context): Boolean {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL
    }

    @Suppress("DEPRECATION")
    fun foreground(c: Context): String {
        try {
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 15_000, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastFg = e.packageName ?: lastFg
            }
        } catch (ex: Exception) {
            // usage access not granted yet
        }
        return lastFg
    }

    fun tick(c: Context) {
        if (Prefs.role(c) != Prefs.ROLE_VICTIM || !Prefs.enabled(c)) return
        val now = System.currentTimeMillis()
        if (inCall(c)) {
            if (callStart == 0L) {
                callStart = now
                resetCall()
                Prefs.clearApproval(c)
            }
            lastInCall = now
        } else if (callStart != 0L && now - lastInCall > 20_000) {
            callStart = 0L
            resetCall()
        }

        val fg = foreground(c)
        val app = PAYMENT_APPS[fg]

        // Remote-access shield: a screen-sharing app opened during ANY call is treated as an attack at once.
        val remote = REMOTE_APPS[fg]
        if (onCall() && remote != null) {
            if (!remoteDuringCall) {
                remoteDuringCall = true
                Messenger.toGuardians(c, "REMOTE", minutes().toInt(), remote)
                Notify.info(c, "Kavach Circle alerted your family", "You opened $remote during a call. Scammers use it to control your phone.")
            }
            if (!remoteCleared && !InterventionActivity.visible) InterventionActivity.launch(c, remote, 1)
        }

        // Screen-share watch: WhatsApp, Meet, Zoom and others show an ongoing notification while the
        // screen is being shared. Seeing one during a call is treated the same way.
        val share = ShareWatch.sharing()
        if (onCall() && share != null) {
            if (!shareDuringCall) {
                shareDuringCall = true
                Messenger.toGuardians(c, "SHARE", minutes().toInt(), share)
                Notify.info(c, "Kavach Circle alerted your family", "Your screen is being shared in $share during a call.")
            }
            if (!remoteCleared && !InterventionActivity.visible) InterventionActivity.launch(c, share, 2)
        }
        if (onCall() && app != null) {
            paymentDuringCall = true
            lastApp = app
        }
        val longCall = onCall() && minutes() >= Prefs.thresholdMinutes(c)

        if (longCall && app != null && !pauseCleared && !InterventionActivity.visible) {
            InterventionActivity.launch(c, app)
        }
        if (longCall && paymentDuringCall && otpDuringCall && !alertSent) {
            alertSent = true
            Messenger.toGuardians(c, "ALERT", minutes().toInt(), lastApp.ifBlank { "a payment app" })
            Notify.info(c, "Kavach Circle alerted your family", "A long call, a payment app and a bank OTP together look like a scam.")
        }
    }

    fun onOtp(c: Context) {
        if (onCall() || inCall(c)) {
            otpDuringCall = true
            tick(c)
        }
    }

    fun onApproval(c: Context, yes: Boolean, who: String) {
        Prefs.setApprovalResult(c, yes, who)
        Notify.info(c, if (yes) "$who approved the payment" else "$who said do not pay",
            if (yes) "You can continue to the payment." else "Do not pay. Hang up the call.")
        // Bring the pause screen to the front so the answer shows on the main phone straight away.
        InterventionActivity.launch(c, lastApp.ifBlank { "a payment app" })
    }
}
