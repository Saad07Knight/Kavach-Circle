package com.kavachcircle.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notify {
    private const val CH_MON = "monitor"
    private const val CH_ALERT = "alerts"
    private var nextId = 100

    fun channels(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_MON, "Protection status", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Family alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun monitor(c: Context, disguised: Boolean): Notification {
        channels(c)
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(c, CH_MON)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (disguised) "Device care" else "Kavach Circle is protecting you")
            .setContentText(if (disguised) "Running" else "Watching for scam call patterns. Nothing is recorded.")
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun allowed(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun info(c: Context, title: String, text: String) {
        channels(c)
        if (!allowed(c)) return
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(c).notify(nextId++, n) } catch (e: SecurityException) { }
    }

    /** Used when Android blocks opening a screen from the background: a full-screen notification instead. */
    @SuppressLint("MissingPermission")
    fun fullScreen(c: Context, target: Intent, title: String, text: String) {
        channels(c)
        if (!allowed(c)) return
        val pi = PendingIntent.getActivity(c, nextId, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(c).notify(nextId++, n) } catch (e: SecurityException) { }
    }
}

/** Foreground service that runs the rule engine every 3 seconds on the protected phone. */
class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() {
            try { Engine.tick(this@MonitorService) } catch (e: Exception) { Log.e("Kavach", "tick failed", e) }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notify.monitor(this, Prefs.fakeOff(this))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, MonitorService::class.java))
            } catch (e: Exception) {
                Log.e("Kavach", "Could not start monitor", e)
            }
        }
        fun stop(c: Context) {
            c.stopService(Intent(c, MonitorService::class.java))
        }
    }
}

/** Receives bank OTP SMS (only checks if it looks like one), guardian YES/NO replies, and alerts on a guardian phone. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val from = msgs[0].originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        when (Prefs.role(c)) {
            Prefs.ROLE_VICTIM -> {
                if (Messenger.onVictimSms(c, from, body)) return
                if (Otp.looksLikeOtp(body)) Engine.onOtp(c)
            }
            Prefs.ROLE_GUARDIAN -> Messenger.onGuardianSms(c, from, body)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Prefs.role(c) == Prefs.ROLE_VICTIM && Prefs.enabled(c)
        ) {
            MonitorService.start(c)
        }
    }
}

/** Tamper alarm: switching off device admin is the step before uninstalling. Guardians hear about it first. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        if (Prefs.role(context) == Prefs.ROLE_VICTIM) Messenger.toGuardians(context, "TAMPER")
        return "Kavach Circle will tell your family that protection is being removed."
    }
}

/**
 * Screen-share watch. Android gives no direct way to ask "is another app sharing my screen?", but
 * calling apps must show an ongoing notification while they do. With notification access, Kavach
 * looks only at ongoing notifications from calling apps and the system, and only for screen-share
 * wording. Chat messages are never read.
 */
class ShareWatch : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    companion object {
        @Volatile private var instance: ShareWatch? = null

        private val APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.apps.tachyon" to "Google Meet",
            "com.google.android.apps.meetings" to "Google Meet",
            "us.zoom.videomeetings" to "Zoom",
            "com.microsoft.teams" to "Microsoft Teams",
            "com.skype.raider" to "Skype",
            "org.thoughtcrime.securesms" to "Signal",
            "com.android.systemui" to "this phone",
            "android" to "this phone"
        )
        private val WORDS = Regex(
            "screen\\s*-?\\s*(shar|cast|record|mirror)|(shar|cast|present|record|mirror)\\w*\\s+(your |the |entire |my )?screen"
        )

        /** Name of the app that is sharing the screen right now, or null. */
        fun sharing(): String? {
            val svc = instance ?: return null
            try {
                val list = svc.activeNotifications ?: return null
                for (sbn in list) {
                    val label = APPS[sbn.packageName] ?: continue
                    if (!sbn.isOngoing) continue
                    val ex = sbn.notification.extras
                    val text = listOf(
                        ex.getCharSequence(Notification.EXTRA_TITLE),
                        ex.getCharSequence(Notification.EXTRA_TEXT),
                        ex.getCharSequence(Notification.EXTRA_SUB_TEXT),
                        sbn.notification.tickerText
                    ).joinToString(" ") { it?.toString() ?: "" }.lowercase()
                    val hindi = text.contains("स्क्रीन") && (text.contains("शेयर") || text.contains("साझा"))
                    if (WORDS.containsMatchIn(text) || hindi) return label
                }
            } catch (e: Exception) {
                Log.w("Kavach", "screen-share check failed", e)
            }
            return null
        }
    }
}
