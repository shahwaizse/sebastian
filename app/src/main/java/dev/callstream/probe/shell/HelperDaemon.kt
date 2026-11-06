package dev.callstream.probe.shell

import android.app.Application
import android.content.AttributionSource
import android.content.Context
import android.net.LocalServerSocket
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import dev.callstream.probe.BuildConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket

/** Shell-side helper process: owns [ProbeService] and hands its binder to the app. */
object HelperDaemon {
    private const val TAG = "CallStreamHelper"
    private const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.helper"
    @Volatile private var service: ProbeService? = null

    @JvmStatic
    fun start(pkg: Context, activityThread: Any) {
        // Claim the socket first: it doubles as the single-instance lock. Losing the race to another
        // helper means two controllers would auto-answer the same call, so exit instead.
        val server = runCatching { LocalServerSocket(HelperMain.SOCKET) }.getOrElse {
            Log.w(TAG, "another helper owns the socket; exiting"); System.exit(0); return
        }
        val app = makeApplication(pkg, activityThread)
        val probe = ProbeService(app)
        service = probe
        Thread({ listen(server) }, "callstream-helper-socket").apply { isDaemon = true }.start()
        Thread(::listenLoopback, "callstream-helper-reattach").apply { isDaemon = true }.start()
        deliver(probe)
        Log.i(TAG, "helper started version=${BuildConfig.VERSION_CODE}")
        // Grants the app's permissions (and its restart after a reboot) without a laptop; the app's checklist shows the result.
        Thread({ runCatching { PhoneSetup.ensure(app) }.onFailure { Log.w(TAG, "setup grants failed", it) } }, "callstream-setup").start()
        Thread(PhoneSetup::keepUsbDebugging, "callstream-usb-debugging").apply { isDaemon = true }.start()
        // After a reboot nobody opens the app: resume the user's last ON/OFF choice here.
        Thread({ probe.restoreCallStream()?.let { Log.i(TAG, "restore: ${it.lineSequence().take(2).joinToString(" | ")}") } },
            "callstream-restore").start()
    }


    /** What LoadedApk.makeApplication() provides, without the vendor hook that crashes in shell. */
    private fun makeApplication(pkg: Context, activityThread: Any): Application {
        val app = Application()
        Application::class.java.getDeclaredMethod("attach", Context::class.java)
            .apply { isAccessible = true }.invoke(app, pkg)
        // ActivityThread.currentApplication() and Context.getApplicationContext() must both work:
        // AudioTools.shellContext() relies on them.
        activityThread.javaClass.getDeclaredField("mInitialApplication")
            .apply { isAccessible = true }.set(activityThread, app)
        val loadedApk = pkg.javaClass.getDeclaredField("mPackageInfo").apply { isAccessible = true }.get(pkg)
        loadedApk.javaClass.getDeclaredField("mApplication").apply { isAccessible = true }.set(loadedApk, app)
        return app
    }

    /** A new HelperMain asks a running daemon to reconnect (same version) or to exit (older). */
    private fun listen(server: LocalServerSocket) {
        while (true) {
            runCatching {
                server.accept().use { socket ->
                    val version = DataInputStream(socket.inputStream).readInt()
                    val out = DataOutputStream(socket.outputStream)
                    if (version == BuildConfig.VERSION_CODE) {
                        service?.let(::deliver)
                        out.writeInt(HelperMain.REPLY_REATTACHED); out.flush()
                    } else {
                        Log.i(TAG, "replaced by version $version; exiting")
                        out.writeInt(HelperMain.REPLY_EXITING); out.flush()
                        service?.destroy() // stops audio and exits the process
                    }
                }
            }.onFailure { Log.w(TAG, "helper socket request failed: ${it.message}") }
        }
    }

    /**
     * The restarted app asks here for the binder again (HelperConnector.askRunningHelper), with no ADB and so no Wi-Fi.
     * Any app on the phone can reach a loopback port, so this only ever re-delivers to our own app's provider: another
     * version gets REPLY_OTHER_VERSION and the app replaces the helper over ADB (HelperMain's socket), as before.
     */
    private fun listenLoopback() {
        val server = runCatching { ServerSocket(HelperMain.REATTACH_PORT, 4, InetAddress.getLoopbackAddress()) }.getOrElse {
            Log.w(TAG, "reattach port ${HelperMain.REATTACH_PORT} unavailable (${it.message}); the app will relaunch over ADB"); return
        }
        while (true) {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val version = DataInputStream(socket.getInputStream()).readInt()
                    val same = version == BuildConfig.VERSION_CODE
                    DataOutputStream(socket.getOutputStream()).apply {
                        writeInt(if (same) HelperMain.REPLY_REATTACHED else HelperMain.REPLY_OTHER_VERSION); flush()
                    }
                    if (same) service?.let(::deliver)
                }
            }.onFailure { Log.w(TAG, "reattach request failed: ${it.message}") }
        }
    }

    /**
     * Hands the binder to the app's HelperProvider. ContentResolver can't be used here: this process
     * isn't registered with ActivityManager, so it goes through getContentProviderExternal like
     * Shizuku's starter did.
     */
    private fun deliver(binder: IBinder) {
        val token = Binder()
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        try {
            val holder = am.javaClass.getMethod("getContentProviderExternal", String::class.java,
                Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                .invoke(am, AUTHORITY, 0, token, TAG) ?: error("app provider $AUTHORITY unavailable")
            val provider = holder.javaClass.getField("provider").get(holder)
            val shell = AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
            provider.javaClass.getMethod("call", AttributionSource::class.java, String::class.java,
                String::class.java, String::class.java, Bundle::class.java)
                .invoke(provider, shell, AUTHORITY, "attach", null, Bundle().apply { putBinder("binder", binder) })
            Log.i(TAG, "binder delivered to app")
        } catch (t: Throwable) {
            Log.e(TAG, "binder delivery failed", t)
        } finally {
            runCatching {
                am.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java,
                    IBinder::class.java, Int::class.javaPrimitiveType).invoke(am, AUTHORITY, token, 0)
            }
        }
    }
}
