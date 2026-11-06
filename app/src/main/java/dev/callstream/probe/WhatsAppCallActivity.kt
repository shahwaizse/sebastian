package dev.callstream.probe

import android.app.Activity
import android.app.ActivityOptions
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Presses WhatsApp's Answer or Hang-up for the helper, then closes. WhatsApp's Answer opens its call screen, and
 * Android 17 blocks that when the helper fires it: a PendingIntent sent by a process with no window is a background
 * activity start, even with the opt-in and START_ACTIVITIES_FROM_BACKGROUND (BAL_BLOCK, Pixel 2026-10-25). The helper
 * starts this invisible screen with `am start` (a direct start, which the shell user may make), and a visible
 * activity may fire the intent. Only the shell user can start it (DUMP permission, as for ProbeReceiver).
 */
class WhatsAppCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val what = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_ANSWER
        // The intent comes from the helper (it reads WhatsApp's notification); the binder call stays off the main thread.
        Thread({
            val result = runCatching {
                val helper = IProbeService.Stub.asInterface(HelperConnector.obtain(applicationContext, timeoutMs = 3_000))
                val target = checkNotNull(helper.whatsAppIntent(what)) { "no $what intent from the WhatsApp notification" }
                val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
                target.send(this, 0, null, null, null, null, options)
            }
            result.onFailure { Log.w(TAG, "WhatsApp $what failed: ${it.javaClass.simpleName}: ${it.message}") }
                .onSuccess { Log.i(TAG, "WhatsApp $what sent") }
            runOnUiThread { finish(); overridePendingTransition(0, 0) }
        }, "callstream-wa-press").start()
    }

    companion object {
        private const val TAG = "CallStreamWaActivity"
        const val EXTRA_ACTION = "action"
        const val ACTION_ANSWER = "answer"
        const val ACTION_HANG_UP = "hangup"
    }
}
