package dev.callstream.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.callstream.probe.adb.AdbProblem
import dev.callstream.probe.adb.LocalAdb

/**
 * Pairing with the phone's own Wireless debugging. The six-digit code is on the Settings dialog, which closes if the
 * person switches apps, so it's typed into a notification instead ([prompt], a reply box, as Shizuku and JemRec do).
 * Sending it starts this service, which pairs, starts the helper and says how it went in the same notification.
 *
 * A foreground service of the short kind (Android 14+), so the phone doesn't freeze it while Settings is in front.
 */
class PairingService : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, within the time allowed, under the pairing notification itself.
        val working = build(this, "Pairing…", "Keep the pairing code screen open.", reply = false, progress = true)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, working, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(ID, working)
        }.onFailure { Log.w(TAG, "foreground refused; pairing anyway", it); notifications(this).notify(ID, working) }
        if (running) return START_NOT_STICKY
        val code = intent?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(KEY_CODE)?.filter { it.isDigit() }?.toString()
        if (code?.length != 6) { finish(build(this, TITLE, "That wasn't six digits. Type the code shown in Settings.", reply = true)); return START_NOT_STICKY }
        running = true
        Thread({
            val result = runCatching {
                LocalAdb.pair(this, code)
                notifications(this).notify(ID, build(this, "Paired", "Starting Sebastian…", reply = false, progress = true))
                HelperConnector.obtain(this, 20_000)
            }
            running = false
            finish(result.fold(
                onSuccess = { build(this, "Sebastian is connected", "Pairing is done. You can close Settings and go back to Sebastian.", reply = false)
                    .also { HelperRevive.scheduleKeepAlive(this) } },
                onFailure = { e ->
                    Log.w(TAG, "pairing or start failed", e)
                    when {
                        e is AdbProblem && e.kind in setOf(AdbProblem.Kind.WRONG_CODE, AdbProblem.Kind.NO_PAIRING_DIALOG) -> build(this, TITLE, e.message.orEmpty(), reply = true)
                        // Paired, but the helper didn't start: Sebastian's checklist says why and has Reconnect.
                        LocalAdb.isPaired(this) -> build(this, "Paired, but Sebastian didn't start",
                            "${e.message.orEmpty()}\nOpen Sebastian and tap Reconnect.".trim(), reply = false)
                        else -> build(this, TITLE, "Pairing failed: ${e.message.orEmpty()}", reply = true)
                    }
                }))
        }, "callstream-pairing").start()
        return START_NOT_STICKY
    }

    /** The service's last word stays on screen as a plain notification. */
    private fun finish(notification: Notification) {
        stopForeground(STOP_FOREGROUND_DETACH)
        notifications(this).notify(ID, notification)
        stopSelf()
    }

    override fun onTimeout(startId: Int) { // Android 14+: a short service gets ~3 minutes
        finish(build(this, TITLE, "Pairing took too long. Try again with a new code.", reply = true))
    }

    companion object {
        private const val TAG = "CallStreamPairing"
        private const val CHANNEL = "pairing"
        private const val ID = 7_001
        private const val KEY_CODE = "code"
        private const val TITLE = "Pair Sebastian"
        const val HOW = "In Wireless debugging, tap Pair device with pairing code. Then type the 6-digit code here: tap Enter code."

        /** Posts the code box. False when notifications are blocked (the app asks for that permission first). */
        fun prompt(context: Context): Boolean {
            val nm = notifications(context)
            if (!nm.areNotificationsEnabled()) return false
            nm.notify(ID, build(context, TITLE, HOW, reply = true))
            return true
        }

        private fun notifications(context: Context): NotificationManager =
            context.getSystemService(NotificationManager::class.java).also {
                // High importance: it pops up over Settings, where the person is.
                it.createNotificationChannel(NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "The pairing code box used once during setup" })
            }

        private fun build(context: Context, title: String, text: String, reply: Boolean, progress: Boolean = false): Notification {
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_pair)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(!reply)
                .setAutoCancel(!reply && !progress)
            if (progress) b.setProgress(0, 0, true).setOngoing(true)
            if (reply) {
                val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
                // Mutable: the system writes the typed code into this intent.
                val pi = PendingIntent.getForegroundService(context, 0, Intent(context, PairingService::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                b.addAction(Notification.Action.Builder(null, "Enter code", pi).addRemoteInput(input).build())
            }
            return b.build()
        }
    }
}
