package dev.callstream.probe

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.callstream.probe.shell.Updater
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Finds updates: https://get.shahwaiz.dev/version.json lists the newest build per channel, `{"stable": {...}, "beta": {...}}`.
 * Client phones follow stable; testing phones can follow beta (Settings), which also takes a newer stable. The helper
 * installs what this finds (shell/Updater).
 */
object AppUpdates {
    const val VERSION_URL = "https://get.shahwaiz.dev/version.json"

    /** The newest release for [beta] or stable, or null if the file has none. */
    fun pick(json: String, beta: Boolean): Release? {
        val o = JSONObject(json)
        fun read(channel: String) = o.optJSONObject(channel)?.let {
            Release(it.getInt("version_code"), it.getString("version_name"), it.getString("url"), it.getString("sha256"),
                it.optString("notes"), it.optLong("size"))
        }
        val stable = read("stable")
        val candidates = listOfNotNull(stable, if (beta) read("beta") else null)
        return candidates.maxByOrNull { it.versionCode }
    }

    /** Newer than [installed], or null. Blocking; throws on network errors. */
    fun check(beta: Boolean, installed: Int): Release? {
        val request = Request.Builder().url(VERSION_URL).cacheControl(CacheControl.FORCE_NETWORK).build()
        val text = HTTP.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "Couldn't check for updates (HTTP ${r.code})" }
            r.body?.string().orEmpty()
        }
        return pick(text, beta)?.takeIf { it.versionCode > installed }
    }

    /** Without the helper: downloads [release] (shell/Updater checks host and checksum) and returns Android's installer
     * for it. Blocking; throws with a readable message. */
    fun installerIntent(context: Context, release: Release): Intent {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val apk = File(dir, "callstream-${release.versionName}.apk").also { Updater.download(release.url, it, release.sha256) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val HTTP by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }
}
