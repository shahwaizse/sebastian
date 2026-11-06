package dev.callstream.probe.shell

internal object DumpClassifier {

    data class Result(
        val verdict: String,
        val reasons: List<String>,
        val flags: Map<String, Boolean>,
    ) {
        fun render(): String = buildString {
            appendLine("VERDICT: $verdict")
            appendLine()
            appendLine("Flags")
            flags.forEach { (k, v) -> appendLine("  ${if (v) "YES" else "no "}  $k") }
            appendLine()
            appendLine("Why")
            reasons.forEach { appendLine("  - $it") }
        }
    }

    fun classify(policy: String, flinger: String, telecom: String, audioMode: String): Result {
        val blob = "$policy\n$flinger\n$telecom\n$audioMode"
        val lower = blob.lowercase()

        val inCall = audioMode.contains("IN_CALL") ||
            audioMode.contains("CALL_SCREENING") ||
            audioMode.contains("CALL_REDIRECT") ||
            lower.contains("mode: in_call") ||
            lower.contains("mode = 2") ||
            Regex("mode:\\s*2\\b").containsMatchIn(lower)
        val inComm = audioMode.contains("IN_COMMUNICATION") ||
            audioMode.contains("COMMUNICATION_REDIRECT") ||
            lower.contains("mode: in_communication")

        val telephonyTx = containsAny(
            lower,
            "telephony_tx",
            "audio_device_out_telephony_tx",
            "out_telephony_tx",
        )
        val telephonyRx = containsAny(
            lower,
            "telephony_rx",
            "audio_device_in_telephony_rx",
            "in_telephony_rx",
        )
        val incallMusic = containsAny(
            lower,
            "incall_music",
            "in_call_music",
            "audio_output_flag_incall_music",
        )
        val voiceCallSource = containsAny(
            lower,
            "audio_source_voice_call",
            "source: voice_call",
            "source voice_call",
            "voicesource: voice_call",
        )
        val voiceCommSource = containsAny(
            lower,
            "audio_source_voice_communication",
            "source: voice_communication",
            "voice_communication",
        )
        val phoneUid = containsAny(
            lower,
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.google.android.dialer",
            "com.android.ims",
        )
        val recordThread = containsAny(
            lower,
            "recordthread",
            "record tracks",
            "recordtracks",
            "input thread",
        )
        val remoteSubmix = containsAny(lower, "remote_submix", "r_submix")

        val flags = linkedMapOf(
            "audio_mode_in_call" to inCall,
            "audio_mode_in_communication" to inComm,
            "telephony_tx_device" to telephonyTx,
            "telephony_rx_device" to telephonyRx,
            "incall_music" to incallMusic,
            "voice_call_source_visible" to voiceCallSource,
            "voice_communication_visible" to voiceCommSource,
            "telephony_process_in_graph" to phoneUid,
            "record_thread_present" to recordThread,
            "remote_submix_present" to remoteSubmix,
        )

        val reasons = mutableListOf<String>()
        val verdict: String

        when {
            inCall && voiceCallSource && recordThread -> {
                verdict = "SOFTWARE_PATH"
                reasons += "IN_CALL mode with a VOICE_CALL record source and an AudioFlinger record thread."
                reasons += "MIX_ROLE_INJECTOR is worth trying against MIC / VOICE_COMMUNICATION / VOICE_UPLINK."
            }
            inCall && telephonyTx && !recordThread -> {
                verdict = "HAL_VOICE_SESSION"
                reasons += "IN_CALL with TELEPHONY_TX and no visible AudioFlinger record thread."
                reasons += "The vocoder is likely patched at the HAL. Injector may be a no-op."
                if (incallMusic) {
                    reasons += "incall_music / TELEPHONY_TX is present — next fallback after injector."
                }
            }
            inCall && telephonyTx && recordThread -> {
                verdict = "HYBRID"
                reasons += "IN_CALL, TELEPHONY_TX, and an AudioFlinger record thread all exist."
                reasons += "Capture is likely to work. Injection needs both injector and TELEPHONY_TX tests."
            }
            inComm -> {
                verdict = "VOIP_SOFTWARE"
                reasons += "MODE_IN_COMMUNICATION — a VoIP app owns the path, not IMS."
                reasons += "MIX_ROLE_INJECTOR should replace that app's microphone."
            }
            !inCall && !inComm -> {
                verdict = "IDLE"
                reasons += "Audio mode is not IN_CALL or IN_COMMUNICATION."
                reasons += "Place a real cellular call, then run Classify and Probe capture again."
                if (telephonyTx) reasons += "TELEPHONY_TX exists on this device even idle — incall-music candidate."
            }
            else -> {
                verdict = "UNKNOWN"
                reasons += "Could not place this dump on a known path. Read the raw dumps."
            }
        }

        if (telephonyTx) reasons += "TELEPHONY_TX device is advertised."
        if (incallMusic) reasons += "incall_music flag/device is advertised."
        if (phoneUid) reasons += "Telephony / dialer uid appears in the audio graph."

        return Result(verdict, reasons.distinct(), flags)
    }

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }
}
