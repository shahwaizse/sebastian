package dev.callstream.probe.shell

import android.content.pm.PackageManager

/**
 * WhatsApp calls as Telecom sees them. WhatsApp registers each call as a self-managed Telecom call
 * (`tpac=ComponentInfo{com.whatsapp/com.whatsapp}`, `prop=[ self_mng]`, `voip=true`, Pixel 2026-10-25), so a WhatsApp
 * ring puts the phone in MODE_RINGTONE with no SIM call ringing. Telecom has no public API naming the ringing call's
 * app; the helper runs as shell, so it reads `dumpsys telecom`, only while the phone rings.
 */
internal object WhatsAppCalls {
    /** WhatsApp and WhatsApp Business. */
    val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** The call ringing in Telecom: its app, and whether it's a self-managed VoIP call (WhatsApp, Discord…). */
    data class Ring(val app: String, val voip: Boolean) {
        val whatsApp get() = voip && app in PACKAGES
    }

    /** A Telecom dump that has no ringing call yet: look again. */
    val NOT_YET = Ring("", voip = false)

    /**
     * What's ringing now. Null when the dump can't be read (treat the ring as a SIM call, as before WhatsApp support);
     * [NOT_YET] when the dump has no ringing call yet (Telecom's isRinging can lead its CallsManager dump).
     */
    fun ringingCall(): Ring? = ringingCall(dumpTelecom())

    internal fun ringingCall(dump: String): Ring? {
        if (!dump.contains("CallsManager")) return null
        val line = callLines(dump).firstOrNull { it.contains("state=RINGING") } ?: return NOT_YET
        val app = Regex("""tpac=ComponentInfo\{([^/}]+)/""").find(line)?.groupValues?.get(1) ?: ""
        return Ring(app, voip = line.contains("self_mng"))
    }

    /** The WhatsApp package with a call ringing in [dump], or null (a SIM call, another VoIP app, or nothing). */
    internal fun ringingPackage(dump: String): String? = ringingCall(dump)?.takeIf { it.whatsApp }?.app

    /** The `[Call id=…]` lines under CallsManager's mCalls (the foreground-call copy repeats one of them). */
    private fun callLines(dump: String): List<String> =
        dump.lineSequence().takeWhile { !it.trimStart().startsWith("mCallAudioManager") }
            .filter { it.trimStart().startsWith("[Call id=") }.toList()

    /**
     * For Send report: WhatsApp's Telecom accounts and any call Telecom holds now (Telecom masks numbers as ***). Other
     * apps' accounts are left out. Whether WhatsApp registers with Telecom on a phone decides how its calls are seen.
     */
    fun telecomAccounts(): String = runCatching {
        val p = ProcessBuilder("dumpsys", "telecom").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().useLines { all ->
            all.filter { l -> (l.contains("PhoneAccount:") || l.trimStart().startsWith("[Call id=")) && PACKAGES.any { l.contains("{$it/") } }
                .map { it.trim() }.distinct().take(20).toList()
        }
        p.destroy()
        "telecom (WhatsApp):\n" + lines.joinToString("\n").ifEmpty { "(none: WhatsApp has no Telecom account here)" }
    }.getOrElse { "telecom: ${it.message}" }

    /** Uids of the installed WhatsApp apps, for the playback mix. */
    fun uids(pm: PackageManager): List<Int> = PACKAGES.mapNotNull { runCatching { pm.getPackageUid(it, 0) }.getOrNull() }

    private fun dumpTelecom(): String = runCatching {
        val p = ProcessBuilder("dumpsys", "telecom").redirectErrorStream(true).start()
        // CallsManager comes first; the rest of the dump (history, analytics) isn't needed.
        p.inputStream.bufferedReader().useLines { lines -> lines.take(60).joinToString("\n") }.also { p.destroy() }
    }.getOrDefault("")
}
