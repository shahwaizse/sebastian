package dev.callstream.probe.shell

import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * Observer mode's way for the other person to hear Sebastian: the mic hook (the injector that replaces WhatsApp's
 * microphone) is registered only while Sebastian speaks, and dropped again afterwards, so WhatsApp has the real mic
 * and the phone's echo canceller the rest of the time.
 *
 * The hooks only catch recorders that open after they exist (Call Stream, 2026-10-25), so whether WhatsApp's recorder
 * follows a hook that comes and goes mid-call is what "swap_probe" measures: it logs WhatsApp's real input before, during
 * and after one swap. The Android API misreports that route on HyperOS, so it comes from the audio server's dump.
 */
internal class MicSwap(private val log: (String) -> Unit) {
    private val policy = HiddenAudioPolicy(AudioTools.shellContext())
    @Volatile private var session: HiddenAudioPolicy.InjectorSession? = null
    @Volatile var track: AudioTrack? = null; private set
    val engaged: Boolean get() = session != null
    /** When the hook came up; WhatsApp's recorder needs a moment to follow (the first seconds of a reply got cut). */
    @Volatile var engagedAtNs = 0L; private set

    /** Registers the mic hook and starts its track. True when it's up. */
    @Synchronized fun engage(): Boolean {
        if (session != null) return true
        val s = runCatching { policy.registerInjector(MediaRecorder.AudioSource.VOICE_COMMUNICATION, WhatsAppTap.TRACK_RATE) }
            .onFailure { log("mic swap: couldn't register the hook: ${it.javaClass.simpleName}: ${it.cause?.message ?: it.message}") }
            .getOrNull() ?: return false
        s.track.play()
        engagedAtNs = android.os.SystemClock.elapsedRealtimeNanos()
        session = s; track = s.track
        return true
    }

    /** Drops the hook: WhatsApp's mic is Android's again. */
    @Synchronized fun release() {
        val s = session ?: return
        session = null; track = null
        runCatching { policy.unregister(s) }
    }

    companion object {
        private fun sh(vararg cmd: String): String = runCatching {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrDefault("")

        /** Where WhatsApp's active recorder really reads from, e.g. "0x80000004 (AUDIO_DEVICE_IN_BUILTIN_MIC)", or "none". */
        fun whatsAppMicRoute(): String {
            val pids = sh("pidof", "com.whatsapp").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (pids.isEmpty()) return "whatsapp not running"
            var device = "?"
            var found = "none"
            for (line in sh("dumpsys", "media.audio_flinger").lineSequence()) {
                val trimmed = line.trim()
                when {
                    line.startsWith("Input thread") || line.startsWith("Output thread") -> device = "?"
                    trimmed.startsWith("Input device:") -> device = trimmed.removePrefix("Input device:").trim()
                    else -> {
                        val t = trimmed.split(Regex("\\s+"))
                        // An input track row: "yes <id> <client pid> <session> ...".
                        if (t.size >= 4 && t[0] == "yes" && t[2] in pids) found = device
                    }
                }
            }
            return found
        }
    }
}
