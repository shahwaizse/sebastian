package dev.callstream.probe

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import dev.callstream.probe.adb.AdbProblem
import dev.callstream.probe.adb.LocalAdb

/**
 * Starts the helper again without anyone opening the app. Starting it takes the app's ADB connection, so Wi-Fi; a
 * helper that's already running just re-delivers its binder (HelperConnector). The helper restores the last ON/OFF
 * state itself. Three triggers:
 *  - boot (a reboot kills the helper), with retries every 30 s for ~10 minutes;
 *  - Wi-Fi coming up, after a start failed for want of it ([watchWifi]);
 *  - every 15 minutes ([HelperKeepAlive]), for a helper that died while the app was closed (adbd restarted, say).
 * On phones that don't start apps at boot (some makers' auto-launch settings) nothing runs until the app is opened.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> HelperRevive.scheduleKeepAlive(app)
            HelperRevive.ACTION_RETRY, HelperRevive.ACTION_WIFI -> {}
            else -> return
        }
        val pending = goAsync()
        HelperRevive.attempt(app, intent.action.orEmpty(), intent.getIntExtra(EXTRA_ATTEMPT, 0)) { pending.finish() }
    }

    companion object {
        const val EXTRA_ATTEMPT = "attempt"
    }
}

/** Runs [HelperRevive.attempt] every 15 minutes (the shortest period Android allows). */
class HelperKeepAlive : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        HelperRevive.attempt(applicationContext, "keep-alive", HelperRevive.NO_RETRY) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}

object HelperRevive {
    private const val TAG = "CallStreamBoot"
    const val ACTION_RETRY = "dev.rawaiz.sebastian.BOOT_RETRY"
    const val ACTION_WIFI = "dev.rawaiz.sebastian.WIFI_UP"
    /** [attempt]'s attempt number that never schedules retries (the keep-alive has its own period). */
    const val NO_RETRY = Int.MAX_VALUE
    private const val RETRY_MS = 30_000L
    private const val MAX_ATTEMPTS = 20 // ~10 minutes
    private const val KEEP_ALIVE_JOB = 7_002

    /** Off the main thread; [done] always runs. */
    fun attempt(context: Context, reason: String, attempt: Int, done: () -> Unit) {
        if (!LocalAdb.isPaired(context) || HelperConnector.current() != null) { done(); return }
        Thread({
            try {
                runCatching { HelperConnector.obtain(context, 15_000) }
                    .onSuccess { Log.i(TAG, "helper up ($reason, attempt $attempt)") }
                    .onFailure { e ->
                        Log.w(TAG, "helper start failed ($reason): ${e.message}")
                        when ((e as? AdbProblem)?.kind) {
                            AdbProblem.Kind.NOT_PAIRED -> {} // only the person can fix this, in the app
                            AdbProblem.Kind.NO_WIFI -> watchWifi(context) // a timer can't help; Wi-Fi will
                            else -> retry(context, attempt)
                        }
                    }
            } finally {
                done()
            }
        }, "callstream-revive").start()
    }

    private fun retry(context: Context, attempt: Int) {
        if (attempt == NO_RETRY) return
        if (attempt >= MAX_ATTEMPTS) { Log.w(TAG, "helper not started after $MAX_ATTEMPTS tries; the keep-alive will try again"); return }
        val next = Intent(context, BootReceiver::class.java).setAction(ACTION_RETRY).putExtra(BootReceiver.EXTRA_ATTEMPT, attempt + 1)
        val pi = PendingIntent.getBroadcast(context, 0, next, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java)
            .set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + RETRY_MS, pi)
        Log.i(TAG, "retry ${attempt + 1}/$MAX_ATTEMPTS in ${RETRY_MS / 1000} s")
    }

    /**
     * One shot: the system holds the callback with the app process dead, sends the PendingIntent once Wi-Fi is joined,
     * and then drops it (ConnectivityService releases a PendingIntent request 5 s after sending it; seen on the Pixel,
     * Android 17). Registering again replaces it.
     */
    fun watchWifi(context: Context) = runCatching {
        val intent = Intent(context, BootReceiver::class.java).setAction(ACTION_WIFI)
        // Mutable: the system adds the network to the intent's extras.
        val pi = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build() // Wi-Fi without internet counts
        context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, pi)
        Log.i(TAG, "waiting for Wi-Fi to start the helper")
    }.onFailure { Log.w(TAG, "can't watch Wi-Fi: ${it.message}") }

    /** Idempotent: an already scheduled job is left alone. */
    fun scheduleKeepAlive(context: Context) = runCatching {
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (jobs.getPendingJob(KEEP_ALIVE_JOB) != null) return@runCatching
        jobs.schedule(JobInfo.Builder(KEEP_ALIVE_JOB, ComponentName(context, HelperKeepAlive::class.java))
            .setPeriodic(15 * 60_000L).setPersisted(true).build())
    }.onFailure { Log.w(TAG, "can't schedule the keep-alive: ${it.message}") }
}
