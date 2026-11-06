package dev.callstream.probe.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Why ADB couldn't be used; [message] is for people (the setup checklist shows it). */
class AdbProblem(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NOT_PAIRED, NO_WIFI, WIRELESS_OFF, NOT_FOUND, NO_PAIRING_DIALOG, WRONG_CODE, FAILED }
}

/**
 * The embedded ADB client that replaced Shizuku (build 92). The phone is its own computer: Sebastian pairs with the
 * phone's Wireless debugging over 127.0.0.1 once (the six-digit code, as a laptop would), and from then on connects
 * without asking and runs one shell command: the one that starts the helper (HelperConnector). The connection is closed
 * straight after; the helper outlives it (setsid). Approach and pitfalls from JemRec (github.com/jemcik/JemRec,
 * docs/ADB-NOTES.md); one command per connection sidesteps libadb's stream leak (a ninth stream on one connection fails).
 *
 * Wireless debugging only runs on Wi-Fi, so starting the helper needs Wi-Fi; running it doesn't, as long as USB
 * debugging stays on (without it adbd restarts when Wi-Fi drops and takes the helper with it; PhoneSetup keeps it on).
 */
object LocalAdb {
    private const val TAG = "CallStreamAdb"
    private const val LOOPBACK = "127.0.0.1"
    private const val PREFS = "callstream_adb"
    private const val PAIRED = "paired"
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val identity = AdbIdentity.get(context)
        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS) // the library's default waits forever on a peer that never answers
            setThrowOnUnauthorised(true)
        }
        override fun getPrivateKey(): PrivateKey = identity.privateKey
        override fun getCertificate(): Certificate = identity.certificate
        // Shown under Paired devices in Wireless debugging, which cuts the name at its first space ("Call").
        override fun getDeviceName(): String = "CallStream"
    }

    fun isPaired(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAIRED, false)

    private fun setPaired(context: Context, paired: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAIRED, paired).apply()

    fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // Any joined Wi-Fi counts, internet or not: Wireless debugging only asks whether Wi-Fi is joined.
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    fun wirelessDebuggingOn(context: Context) = Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    /** The helper grants this at its first start (PhoneSetup); from then on the app can switch Wireless debugging on. */
    fun canWriteSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Pairs with the "Pair device with pairing code" dialog, which must be on screen (its port is only advertised
     * while it is). Blocking, ~1-15 s.
     */
    fun pair(context: Context, code: String) {
        val port = discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 12_000)
            ?: throw AdbProblem(AdbProblem.Kind.NO_PAIRING_DIALOG,
                "Couldn't find the pairing code screen. Open Wireless debugging → Pair device with pairing code, then enter the new code.")
        val ok = Manager(context).use { manager ->
            runCatching { manager.pair(LOOPBACK, port, code) }.onFailure { Log.w(TAG, "pair failed", it) }.getOrDefault(false)
        }
        if (!ok) throw AdbProblem(AdbProblem.Kind.WRONG_CODE, "Pairing failed: the code was wrong or had changed. Try again with the code on screen now.")
        setPaired(context, true)
        Log.i(TAG, "paired")
    }

    /** Runs [command] as the shell user and returns what it printed. Blocking; needs Wi-Fi (see the class comment). */
    fun shell(context: Context, command: String, timeoutMs: Long = 20_000): String {
        if (!isPaired(context)) throw AdbProblem(AdbProblem.Kind.NOT_PAIRED, "Sebastian isn't paired with Wireless debugging yet.")
        val port = findAdbd(context)
        val manager = Manager(context)
        try {
            val connected = try {
                manager.connect(LOOPBACK, port) || manager.isConnected // false can also mean "already connected"
            } catch (e: AdbPairingRequiredException) {
                unpaired(context, e)
            } catch (e: AdbAuthenticationFailedException) {
                unpaired(context, e)
            }
            if (!connected) throw AdbProblem(AdbProblem.Kind.FAILED, "Wireless debugging didn't accept the connection. Try again.")
            // A watchdog: a stream that never ends is let go by closing the connection.
            val watchdog = Thread({ runCatching { Thread.sleep(timeoutMs); manager.close() } }, "callstream-adb-watchdog")
                .apply { isDaemon = true; start() }
            try {
                // shell:<cmd> runs without a terminal (no echo) and ends when the command does. libadb reports that
                // end as IOException("Stream closed") rather than -1, so it ends the read like EOF.
                return manager.openStream("shell:$command").use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val input = stream.openInputStream()
                    val buf = ByteArray(4096)
                    try {
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    } catch (e: java.io.IOException) {
                        if (!stream.isClosed) throw e
                    }
                    out.toString(Charsets.UTF_8.name()).trim()
                }
            } finally {
                watchdog.interrupt()
            }
        } finally {
            runCatching { manager.close() }
        }
    }

    private fun unpaired(context: Context, e: Exception): Nothing {
        Log.w(TAG, "adbd no longer accepts our key", e)
        setPaired(context, false)
        throw AdbProblem(AdbProblem.Kind.NOT_PAIRED, "Wireless debugging no longer knows Sebastian (it was forgotten or reset). Pair again.")
    }

    /**
     * The port adbd's Wireless debugging listens on (it changes every time it's switched on). Switches Wireless
     * debugging on when it's off and we may ([canWriteSettings]), and kicks it off and on when the switch says on but
     * adbd isn't running (seen after restarts in JemRec's measurements).
     */
    private fun findAdbd(context: Context): Int {
        val wifi = onWifi(context)
        val canWrite = canWriteSettings(context)
        if (wifi && canWrite) {
            if (!wirelessDebuggingOn(context)) setWireless(context, true)
            else if (property("init.svc.adbd").let { it.isNotEmpty() && it != "running" }) {
                Log.i(TAG, "Wireless debugging on but adbd stopped: switching it off and on")
                setWireless(context, false); Thread.sleep(800); setWireless(context, true)
            }
        }
        discover(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 8_000)?.let { return it }
        // adb tcpip from a computer leaves adbd on a fixed port too, Wi-Fi or not
        property("service.adb.tcp.port").toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        throw when {
            !wifi -> AdbProblem(AdbProblem.Kind.NO_WIFI, "Connect to Wi-Fi. Sebastian needs Wi-Fi to start (only to start).")
            !wirelessDebuggingOn(context) -> AdbProblem(AdbProblem.Kind.WIRELESS_OFF,
                "Wireless debugging is off. Turn it on in Developer options and allow it on this Wi-Fi.")
            else -> AdbProblem(AdbProblem.Kind.NOT_FOUND,
                "Wireless debugging didn't answer. Open it in Developer options, make sure it's on and allowed on this Wi-Fi, then try again.")
        }
    }

    private fun setWireless(context: Context, on: Boolean) = runCatching {
        Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, if (on) 1 else 0)
    }.onFailure { Log.w(TAG, "can't switch Wireless debugging: ${it.message}") }

    private fun discover(context: Context, type: String, timeoutMs: Long): Int? {
        val port = AtomicInteger(-1)
        val found = CountDownLatch(1)
        val mdns = AdbMdns(context.applicationContext, type) { _, p -> if (p > 0 && port.compareAndSet(-1, p)) found.countDown() }
        mdns.start()
        try {
            found.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        return port.get().takeIf { it > 0 }
    }

    fun property(name: String): String =
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String }
            .getOrDefault("")
}
