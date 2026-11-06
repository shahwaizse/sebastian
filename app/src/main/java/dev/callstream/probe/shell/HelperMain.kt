package dev.callstream.probe.shell

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Looper
import dev.callstream.probe.BuildConfig
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Shell-side entry point, started by the app over its own ADB connection (adb/LocalAdb, HelperConnector) as
 * `CLASSPATH=<apk> app_process /system/bin dev.callstream.probe.shell.HelperMain`.
 *
 * Replaces Shizuku's UserService starter, which calls LoadedApk.makeApplication(). On MediaTek
 * Android 15 builds (e.g. HyperOS on the Redmi Note 14 Pro) that method compares
 * Application.getProcessName() — null in a shell app_process — and throws, so the helper never
 * starts (RikkaApps/Shizuku#1198). [HelperDaemon] builds the Application itself instead.
 */
object HelperMain {
    const val SOCKET = "dev.rawaiz.sebastian.helper"
    const val REPLY_REATTACHED = 1
    const val REPLY_EXITING = 2
    /** The loopback port's answer to another version: it never stops the helper (any app can reach that port). */
    const val REPLY_OTHER_VERSION = 3
    /** 127.0.0.1 port where a running helper re-delivers its binder to a restarted app, without ADB (HelperDaemon). */
    const val REATTACH_PORT = 47_829

    @JvmStatic
    fun main(args: Array<String>) {
        // A daemon from an earlier launch keeps its state (ON/OFF, active call): just reconnect it.
        if (askRunningHelper()) return
        Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val pkg = system.createPackageContext(BuildConfig.APPLICATION_ID,
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        // Load the daemon through the package class loader so native libraries (WebRTC) resolve.
        pkg.classLoader.loadClass("dev.callstream.probe.shell.HelperDaemon")
            .getMethod("start", Context::class.java, Any::class.java)
            .invoke(null, pkg, thread)
        Looper.loop()
    }

    /** True when a running daemon of this version re-delivered its binder to the app. */
    private fun askRunningHelper(): Boolean = try {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(SOCKET))
            DataOutputStream(socket.outputStream).apply { writeInt(BuildConfig.VERSION_CODE); flush() }
            when (DataInputStream(socket.inputStream).readInt()) {
                REPLY_REATTACHED -> true
                else -> { Thread.sleep(500); false } // older daemon is exiting; replace it
            }
        }
    } catch (_: Exception) {
        false // no daemon running
    }
}
