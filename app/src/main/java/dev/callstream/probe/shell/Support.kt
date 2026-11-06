package dev.callstream.probe.shell

import android.os.Build
import dev.callstream.probe.BuildConfig
import org.json.JSONObject
import java.io.File

/**
 * The helper's half of Settings → Send report: what support needs to see a phone's problem without a visit. It holds
 * the setup status, the helper's current state, its recent log and the newest call traces. Never the lead sheet,
 * prompts, the key or call audio (traces carry none of those; see CallLatencyTrace). Sizes are capped so the reply
 * fits one binder call; the app adds its own side and zips it.
 */
internal object Support {
    /** Where CallLatencyTrace writes each call's trace. */
    private const val TRACE_DIR = "/data/local/tmp/call-stream-metrics"
    private const val TRACES = 3
    private const val TRACE_CHARS = 60_000
    private const val LOG_CHARS = 40_000
    private val LOG_TAGS = listOf("CallStreamHelper", "CallStreamControl", "CallStreamGemini", "CallStreamProbe", "CallStreamSetup",
        "CallStreamUpdate", "CallStreamLab", "CallStreamBoot", "OutboundRun")

    fun report(setup: String, status: String, outbound: String, options: String, calls: String = ""): String {
        val files = JSONObject()
        files.put("helper-status.txt", "$status\n\noutbound: $outbound")
        // What the helper saw around calls (CallStreamController.events): rings, modes, WhatsApp notices.
        files.put("helper-calls.txt", tail(calls, LOG_CHARS))
        files.put("helper-setup.json", setup)
        files.put("agent-options.json", runCatching { JSONObject(options).apply { remove("voices") }.toString(1) }.getOrDefault(options))
        files.put("helper-log.txt", tail(run("logcat", "-d", "-v", "time", "-t", "3000", "-s", *LOG_TAGS.toTypedArray()), LOG_CHARS))
        File(TRACE_DIR).listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
            .sortedByDescending { it.lastModified() }.take(TRACES)
            .forEach { files.put("traces/${it.name}", tail(it.readText(), TRACE_CHARS)) }
        val phone = JSONObject().put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER).put("device", Build.DEVICE)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT).put("helper_version", BuildConfig.VERSION_NAME)
        return JSONObject().put("phone", phone).put("files", files).toString()
    }

    /** The end of [text] (the newest lines), at most [max] characters. */
    fun tail(text: String, max: Int): String =
        if (text.length <= max) text else "… (earlier lines cut)\n" + text.substring(text.indexOf('\n', text.length - max).coerceAtLeast(text.length - max) + 1)

    private fun run(vararg cmd: String): String = runCatching {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(); out
    }.getOrElse { "(${it.message})" }
}
