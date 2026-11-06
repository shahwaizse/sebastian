package dev.callstream.probe

import android.content.Context
import android.os.IBinder
import android.util.Log
import dev.callstream.probe.adb.LocalAdb
import dev.callstream.probe.shell.HelperMain
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Gets the shell helper's binder to the app, which receives it through [HelperProvider]:
 *  1. A helper already running (the app process was restarted, the helper wasn't) is asked over its loopback port to
 *     deliver its binder again. No ADB, so no Wi-Fi needed.
 *  2. Otherwise the helper is started through the app's own ADB connection ([LocalAdb]), which needs Wi-Fi.
 */
object HelperConnector {
    private const val TAG = "CallStreamConnector"
    private val lock = Object()
    private val launchLock = Object() // one launch at a time per app process
    @Volatile private var binder: IBinder? = null
    /** Called on the attaching binder thread whenever a helper (re)attaches. */
    @Volatile var onAttached: ((IBinder) -> Unit)? = null

    /** pingBinder, not isBinderAlive: a proxy only learns of its death through a death recipient, so a helper killed
     * before anyone linked to it looked alive forever (seen on the Pixel, 2026-10-30). */
    fun current(): IBinder? = binder?.takeIf { it.pingBinder() }

    fun attach(helper: IBinder) {
        runCatching { helper.linkToDeath({ synchronized(lock) { if (binder === helper) binder = null } }, 0) }
            .onFailure { Log.w(TAG, "helper died while attaching"); return }
        synchronized(lock) { binder = helper; lock.notifyAll() }
        Log.i(TAG, "helper attached")
        onAttached?.invoke(helper)
    }

    /** Returns a live helper binder, reconnecting to or starting the helper if needed. Blocking. Throws
     * [dev.callstream.probe.adb.AdbProblem] when the helper isn't running and ADB can't start it. */
    fun obtain(context: Context, timeoutMs: Long = 15_000): IBinder = synchronized(launchLock) {
        current()?.let { return it }
        synchronized(lock) { binder = null }
        if (!askRunningHelper()) launch(context)
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (current() == null) {
                val left = deadline - System.currentTimeMillis()
                check(left > 0) { "HELPER-TIMEOUT after ${timeoutMs / 1000} s — check logcat tag CallStreamHelper" }
                lock.wait(left)
            }
            return binder!!
        }
    }

    /** True when a running helper of this version is re-delivering its binder. */
    private fun askRunningHelper(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), HelperMain.REATTACH_PORT), 500)
            socket.soTimeout = 2_000
            DataOutputStream(socket.getOutputStream()).apply { writeInt(BuildConfig.VERSION_CODE); flush() }
            DataInputStream(socket.getInputStream()).readInt() == HelperMain.REPLY_REATTACHED
        }
    }.getOrDefault(false).also { if (it) Log.i(TAG, "running helper re-delivering its binder") }

    private fun launch(context: Context) {
        val apk = context.applicationInfo.sourceDir
        // setsid + background: the helper outlives this short ADB shell and the app process. HelperMain itself hands
        // over to a running helper of the same version, and replaces an older one.
        // The sleep: the command returns at once otherwise, LocalAdb closes the connection, and adbd tears the shell
        // down before the backgrounded setsid has detached the helper (seen on the Pixel, 2026-10-30: no helper).
        val command = "CLASSPATH='$apk' setsid /system/bin/app_process /system/bin " +
            "--nice-name=${context.packageName}:probe ${HELPER_MAIN} </dev/null >/dev/null 2>&1 & sleep 1"
        val out = LocalAdb.shell(context, command)
        Log.i(TAG, "helper launched over ADB${if (out.isNotEmpty()) ": $out" else ""}")
    }

    private const val HELPER_MAIN = "dev.callstream.probe.shell.HelperMain"
}
