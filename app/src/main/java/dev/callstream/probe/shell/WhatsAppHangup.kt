package dev.callstream.probe.shell

import android.os.SystemClock

/**
 * Ends the WhatsApp call in progress. WhatsApp calls don't go through Android's Telecom, so there's no API to hang
 * them up: this presses WhatsApp's own end-call button, on the call screen or, failing that, in its ongoing-call
 * notification. Runs as the shell user (uiautomator + input).
 */
internal object WhatsAppHangup {
    private const val DUMP = "/data/local/tmp/sebastian/ui.xml"
    private val END = Regex("^(end call|leave call|hang up|end)$", RegexOption.IGNORE_CASE)
    private val NODE = Regex("<node [^>]*>")
    private val ATTR = { name: String -> Regex("\\b$name=\"([^\"]*)\"") }
    private val BOUNDS = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")

    /** Returns what it did, for the log. [inCall] says whether the call is still up. */
    fun hangUp(inCall: () -> Boolean): String {
        // First the "Hang up" button on WhatsApp's call notification, through the app's notification listener:
        // works with the screen off (earpiece calls turn it off).
        val out = shell("am", "broadcast", "-n", "dev.rawaiz.sebastian/dev.callstream.probe.HangupReceiver")
        if (out.contains("result=1,") || out.lines().any { it.trim().endsWith("result=1") }) return waitEnded(inCall, "notification button")
        val why = Regex("data=\"([^\"]*)\"").find(out)?.groupValues?.get(1) ?: out.trim().take(120)
        if (!inCall()) return "call already ended"
        // Fallback: the on-screen button.
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        if (tap()) return waitEnded(inCall, "call screen")
        // Not on the call screen: use the notification's button.
        shell("cmd", "statusbar", "expand-notifications")
        SystemClock.sleep(700)
        val tapped = tap()
        if (!tapped) { shell("cmd", "statusbar", "collapse"); return "couldn't end the call: $why; no end-call button on screen either" }
        SystemClock.sleep(300)
        shell("cmd", "statusbar", "collapse")
        return waitEnded(inCall, "notification")
    }

    private fun waitEnded(inCall: () -> Boolean, where: String): String {
        val until = SystemClock.elapsedRealtime() + 3_000
        while (inCall() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(200)
        return if (inCall()) "pressed end call ($where) but the call is still up" else "call ended ($where)"
    }

    /** Finds a WhatsApp node labelled "End call" (or similar) on screen and taps its centre. */
    private fun tap(): Boolean {
        shell("uiautomator", "dump", DUMP)
        val xml = runCatching { java.io.File(DUMP).readText() }.getOrDefault("")
        for (node in NODE.findAll(xml).map { it.value }) {
            val label = (ATTR("content-desc").find(node)?.groupValues?.get(1)).orEmpty().ifBlank {
                ATTR("text").find(node)?.groupValues?.get(1).orEmpty()
            }.trim()
            if (!END.matches(label)) continue
            if (!node.contains("package=\"com.whatsapp\"") && !node.contains("package=\"com.android.systemui\"")) continue
            val b = BOUNDS.find(ATTR("bounds").find(node)?.groupValues?.get(1).orEmpty())?.groupValues ?: continue
            val x = (b[1].toInt() + b[3].toInt()) / 2
            val y = (b[2].toInt() + b[4].toInt()) / 2
            shell("input", "tap", "$x", "$y")
            return true
        }
        return false
    }

    private fun shell(vararg cmd: String): String = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }.getOrElse { "Error: ${it.message}" }
}
