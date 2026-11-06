package dev.callstream.probe.shell

import android.util.Log
import dev.callstream.probe.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * App updates without "install unknown apps": the helper runs as the shell user, so it can install the new APK itself,
 * as `adb install` would. The app finds the update (AppUpdates, version.json on get.shahwaiz.dev) and the person taps
 * Update; the helper downloads it, checks its SHA-256, installs it and reopens the app. Android still checks that the new
 * APK is signed with the same key as the installed one, so only our releases can replace the app.
 *
 * Without the helper (setup not finished, helper stopped) or when it can't install (Xiaomi refuses until "Install via
 * USB" is on), the app downloads the same way ([download]) and hands the file to Android's installer instead
 * (MainActivity.installUpdate): one "allow installs from Call Stream" prompt, then the usual Install button.
 */
internal object Updater {
    private const val TAG = "CallStreamUpdate"
    /** Updates come only from here (release.py uploads to it). */
    const val HOST = "https://get.shahwaiz.dev/"
    private const val APK = "/data/local/tmp/call-stream-update.apk"
    private const val MAX_BYTES = 200L shl 20
    private val lock = Any()

    /** Throws with a readable message when [url] or [sha256] can't be an update of ours. */
    fun check(url: String, sha256: String) {
        require(url.startsWith(HOST) && url.endsWith(".apk") && ".." !in url) { "Updates only come from $HOST" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "The update's checksum is missing." }
    }

    /**
     * Downloads, checks and installs. [callActive]: an AI call is running now; the install would end it, so it waits.
     * Returns only on failure: on success the package manager stops the app (and this binder call) and the helper
     * reopens it.
     */
    fun install(url: String, sha256: String, callActive: Boolean): String = synchronized(lock) {
        check(url, sha256)
        check(!callActive) { "A call is in progress. Update after it ends." }
        val file = File(APK)
        try {
            download(url, file, sha256)
            val (code, out) = run("pm", "install", "-r", file.path)
            check(code == 0 && "Success" in out) {
                if ("UPDATE_INCOMPATIBLE" in out) "This update is signed with a different key than the installed app. Reinstall Call Stream from get.shahwaiz.dev."
                else "Install failed: ${out.lines().lastOrNull { it.isNotBlank() } ?: "exit $code"}"
            }
            Log.i(TAG, "installed $url over ${BuildConfig.VERSION_NAME}")
            run("am", "start", "-n", "${BuildConfig.APPLICATION_ID}/.MainActivity")
            restartHelper()
            "Installed"
        } finally {
            file.delete()
        }
    }

    /**
     * Starts the new version's helper from this one: it runs as shell already, so no ADB (and no Wi-Fi) is needed, as
     * the app's own start would. The new HelperMain asks this helper to exit (another version) and takes over.
     */
    private fun restartHelper() {
        val apk = run("pm", "path", BuildConfig.APPLICATION_ID).second.lineSequence()
            .firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim() ?: return
        run("sh", "-c", "CLASSPATH='$apk' setsid /system/bin/app_process /system/bin --nice-name=${BuildConfig.APPLICATION_ID}:probe " +
            "dev.callstream.probe.shell.HelperMain </dev/null >/dev/null 2>&1 &")
        Log.i(TAG, "new helper starting from $apk")
    }

    /** Our host only, checked against [sha256]; throws with a readable message. Used by the helper and the app. */
    fun download(url: String, to: File, sha256: String) {
        check(url, sha256)
        val digest = MessageDigest.getInstance("SHA-256")
        HTTP.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "Couldn't download the update (HTTP ${r.code})." }
            val body = r.body ?: error("Empty download")
            to.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 shl 10)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        check(total <= MAX_BYTES) { "The download is too large to be an update." }
                        digest.update(buf, 0, n); out.write(buf, 0, n)
                    }
                }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        check(got == sha256) { "The download was damaged (checksum mismatch). Try again." }
    }

    private fun run(vararg cmd: String): Pair<Int, String> = runCatching {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
        proc.waitFor() to out
    }.getOrElse { -1 to (it.message ?: it.javaClass.simpleName) }

    private val HTTP by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
}
