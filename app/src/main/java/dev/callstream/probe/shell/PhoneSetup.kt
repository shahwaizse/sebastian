package dev.callstream.probe.shell

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.callstream.probe.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * The setup steps that used to be `adb shell pm grant` from the laptop. The helper runs as the shell user, so it can
 * grant them itself: the app's runtime permissions, and WRITE_SECURE_SETTINGS for the app, which lets it switch Wireless
 * debugging back on to restart the helper after a reboot (adb/LocalAdb). It also keeps USB debugging on: without it,
 * adbd restarts when Wi-Fi drops and takes the helper with it. Granting is idempotent, so [ensure] runs on every helper
 * start and whenever the app's setup checklist asks.
 */
internal object PhoneSetup {
    private const val TAG = "CallStreamSetup"
    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /** The app's runtime permissions the call path needs, for this Android version. */
    fun appPermissions(sdk: Int): List<String> = listOfNotNull(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.POST_NOTIFICATIONS.takeIf { sdk >= 33 },
    )

    /**
     * Grants what's missing, then reports. [granted] and [grant] take (permission, package); [grant] returns an error
     * or null. [usbDebugging] and [wirelessDebugging] are the switches as the shell reads them (an app can't read USB
     * debugging on Android 17).
     */
    fun ensure(sdk: Int, usbDebugging: Boolean, wirelessDebugging: Boolean,
               granted: (String, String) -> Boolean, grant: (String, String) -> String?): JSONObject {
        val errors = JSONArray()
        fun need(permission: String, pkg: String): Boolean {
            if (granted(permission, pkg)) return true
            grant(permission, pkg)?.let { errors.put("$permission: $it") }
            return granted(permission, pkg)
        }
        val missing = JSONArray()
        appPermissions(sdk).forEach { if (!need(it, BuildConfig.APPLICATION_ID)) missing.put(it.substringAfterLast('.')) }
        val boot = if (need(WRITE_SECURE_SETTINGS, BuildConfig.APPLICATION_ID)) "ready" else "missing"
        return JSONObject().put("missing_permissions", missing).put("boot_start", boot)
            .put("usb_debugging", usbDebugging).put("wireless_debugging", wirelessDebugging).put("errors", errors)
    }

    /** On the phone, through the helper (shell uid). */
    fun ensure(ctx: Context): String {
        val pm = ctx.packageManager
        // Shell commands, not the context: the settings provider rejects this package name under the shell uid.
        val usb = usbDebuggingOn() || setUsbDebugging()
        val wireless = run("settings", "get", "global", "adb_wifi_enabled").second == "1"
        val status = ensure(Build.VERSION.SDK_INT, usb, wireless,
            granted = { permission, pkg -> pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED },
            grant = ::pmGrant)
        status.put("manufacturer", Build.MANUFACTURER.lowercase()).put("sdk", Build.VERSION.SDK_INT)
        // Xiaomi keeps its two adb switches in system properties (set by its Security Center): "USB debugging (Security
        // settings)", without which adb's grants are refused, and "Install via USB". The shell user can always read them.
        if (dev.callstream.probe.phoneFamily(Build.MANUFACTURER, Build.BRAND) == dev.callstream.probe.PhoneFamily.XIAOMI) {
            status.put("xiaomi_adb_input", run("getprop", XIAOMI_ADB_INPUT).second == "1")
                .put("xiaomi_adb_install", run("getprop", XIAOMI_ADB_INSTALL).second == "1")
        }
        if (status.getJSONArray("errors").length() > 0) Log.w(TAG, "setup: $status") else Log.i(TAG, "setup: $status")
        return status.toString()
    }

    /**
     * USB debugging is what keeps adbd, and so this helper, alive when Wi-Fi drops (JemRec measured it). Some phones'
     * Settings switch it off by themselves (Honor's does whenever Developer options opens), so it's checked every 10 s
     * for the helper's whole life. Where turning it off stops adbd at once, the helper is gone before this can help.
     */
    fun keepUsbDebugging() {
        while (true) {
            runCatching { if (!usbDebuggingOn()) Log.i(TAG, "USB debugging was off; on again: ${setUsbDebugging()}") }
            Thread.sleep(10_000)
        }
    }

    private fun usbDebuggingOn() = run("settings", "get", "global", "adb_enabled").second == "1"

    private fun setUsbDebugging(): Boolean = run("settings", "put", "global", "adb_enabled", "1").first == 0 && usbDebuggingOn()

    const val XIAOMI_ADB_INPUT = "persist.security.adbinput"
    const val XIAOMI_ADB_INSTALL = "persist.security.adbinstall"

    private fun pmGrant(permission: String, pkg: String): String? =
        run("pm", "grant", pkg, permission).let { (code, out) -> if (code == 0) null else out.ifEmpty { "pm grant exited $code" } }

    /** (exit code, trimmed output); -1 if it couldn't run. */
    private fun run(vararg cmd: String): Pair<Int, String> = runCatching {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
        proc.waitFor() to out
    }.getOrElse { -1 to (it.message ?: it.javaClass.simpleName) }
}
