package dev.callstream.probe.shell

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import dev.callstream.probe.IProbeService
import dev.callstream.probe.leads.LeadStore
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The lab probes from the platform investigation (2026-10-02 onwards): uplink and downlink tests, tones, capture taps,
 * the oracle. Only adb uses them (ProbeReceiver broadcasts: oracle, uplink_check, tx_tone, …), mostly to check a new
 * phone model; the call path never does. Kept apart from ProbeService so the helper's real work reads on its own.
 * [pauseCalls] turns Call Stream off for a test without changing the saved ON/OFF; [callsBusy]: Call Stream is on or an
 * AI call is live.
 */
internal class LabProbes(private val appContext: Context?, private val pauseCalls: () -> Unit, private val callsBusy: () -> Boolean) {
    // Shell attribution, like the working carrier-call track: with the app context the injector came back null on
    // WhatsApp (2026-10-02), the same identity mismatch that blocked the official helpers.
    private val hidden: HiddenAudioPolicy? = appContext?.let { runCatching { HiddenAudioPolicy(AudioTools.shellContext()) }.getOrElse { _ -> HiddenAudioPolicy(appContext) } }
    private var injector: HiddenAudioPolicy.InjectorSession? = null
    @Volatile private var toneThread: Thread? = null
    @Volatile private var toneRunning = false
    private var officialTrack: android.media.AudioTrack? = null
    @Volatile private var officialToneRunning = false
    private var officialToneThread: Thread? = null

    // Capture-tap probe (2026-10-18): VOICE_DOWNLINK at 16 kHz sounded muffled to the user while the Pixel's earpiece
    // sounded clear on the same kind of call. During one live call (Call Stream OFF) this records each capture point in
    // turn to /data/local/tmp/call-stream-taps/<time>/<tap>.wav, for comparing spectra and transcriptions (bridge/tap_probe.py).
    @Volatile private var tapStatus = "tap probe: not run"
    @Volatile private var tapThread: Thread? = null

    fun startTapProbe(secondsEach: Int): String {
        if (tapThread?.isAlive == true) return tapStatus
        if (callsBusy()) return "tap probe: Call Stream is ON (the agent would take the call audio); turn it OFF first"
        val ms = secondsEach.coerceIn(3, 20) * 1000
        tapThread = Thread({ runTapProbe(ms) }, "tap-probe").apply { isDaemon = true; start() }
        return "tap probe started: waits up to 90 s for an active call, then records ${ms / 1000} s from each of ${TAPS.size} taps"
    }

    fun tapProbeStatus(): String = tapStatus

    private fun runTapProbe(ms: Int) {
        val ctx = appContext ?: run { tapStatus = "tap probe: no context"; return }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val deadline = SystemClock.elapsedRealtime() + 90_000
        tapStatus = "tap probe: waiting for an active call"
        while (am.mode != AudioManager.MODE_IN_CALL && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
        if (am.mode != AudioManager.MODE_IN_CALL) { tapStatus = "tap probe: no active call within 90 s"; return }
        val dir = File("/data/local/tmp/call-stream-taps/${System.currentTimeMillis()}").apply { mkdirs() }
        val summary = org.json.JSONArray()
        for ((i, tap) in TAPS.withIndex()) {
            if (am.mode != AudioManager.MODE_IN_CALL) break
            tapStatus = "tap probe: recording ${tap.first} (${i + 1}/${TAPS.size})"
            val entry = org.json.JSONObject().put("tap", tap.first).put("start_wall_ms", System.currentTimeMillis())
            runCatching {
                val record = when (tap.first) {
                    "redirection_16k" -> attemptOfficialDownlink(am, callFormat(playback = false)).record
                    else -> AudioTools.openRecord(tap.second, tap.third)
                } ?: error("recorder did not open")
                check(record.state == AudioRecord.STATE_INITIALIZED) { "recorder not initialized" }
                val rate = record.sampleRate
                val cap = AudioTools.readPcm(record, ms, rate)
                runCatching { record.release() }
                entry.put("state", cap.state).put("rate", rate).put("seconds", cap.samples.size / rate.toDouble())
                    .put("rms", cap.rms).put("peak", cap.peak).put("open", AudioTools.lastOpenNote)
                if (cap.samples.isNotEmpty()) File(dir, "${tap.first}.wav").writeBytes(AudioTools.wavBytes(cap.samples, rate))
            }.onFailure { entry.put("state", "failed: ${it.javaClass.simpleName}: ${it.message}".take(300)) }
            summary.put(entry)
        }
        if (am.mode != AudioManager.MODE_IN_CALL && summary.length() < TAPS.size) summary.put(org.json.JSONObject().put("note", "call ended early"))
        File(dir, "summary.json").writeText(summary.toString(1))
        tapStatus = "tap probe: done -> ${dir.path}\n" + summary.toString(1)
    }

    fun classifyPath(): String {
        val policy = exec("dumpsys", "media.audio_policy", maxChars = 400_000)
        val flinger = exec("dumpsys", "media.audio_flinger", maxChars = 400_000)
        val telecom = exec("dumpsys", "telecom", maxChars = 120_000)
        val mode = audioMode()
        val result = DumpClassifier.classify(policy, flinger, telecom, mode)
        saveText("classify.txt", result.render() + "\n\n--- audio mode ---\n$mode")
        return result.render()
    }

    fun probeCaptureSources(durationMs: Int): String {
        val ms = durationMs.coerceIn(250, 4_000)
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            MediaRecorder.AudioSource.VOICE_UPLINK,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.REMOTE_SUBMIX,
            HiddenCallAudio.ECHO_REFERENCE_SOURCE,
        )
        val out = StringBuilder()
        out.appendLine("window=${ms}ms  rate=16000  mono  pcm16")
        out.appendLine(audioMode())
        out.appendLine("source                         state            rms    peak    n")
        out.appendLine("------------------------------ ---------------- ------ ------- --------")
        for (src in sources) {
            val name = HiddenAudioPolicy.presetLabel(src).padEnd(30)
            val line = try {
                val sample = capture(src, ms)
                val silent = sample.rms < SILENCE_RMS
                val flag = when {
                    sample.state != "ok" -> sample.state
                    silent -> "SILENT"
                    else -> "SIGNAL"
                }
                "%s %-16s %6.1f %7d %8d  %s".format(
                    name, flag, sample.rms, sample.peak, sample.samples, sample.opener,
                )
            } catch (t: Throwable) {
                "%s FAIL %s".format(name, t.message?.replace('\n', ' '))
            }
            out.appendLine(line)
        }
        out.appendLine()
        out.appendLine("SIGNAL = energy above silence floor ($SILENCE_RMS rms).")
        out.appendLine("SILENT = opened but near-zero. FAIL = source rejected for this uid.")
        out.appendLine("Run this during a live cellular call. Idle numbers are not the experiment.")
        val text = out.toString()
        saveText("capture_${System.currentTimeMillis()}.txt", text)
        return text
    }

    fun listDevices(): String {
        val ctx = appContext ?: return "no context"
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return buildString {
            appendLine(audioMode())
            appendLine("outputs")
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
                appendLine("  ${typeName(it.type)}  ${it.productName}  ${it.address} id=${it.id} sink=${it.isSink}")
            }
            appendLine("inputs")
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach {
                appendLine("  ${typeName(it.type)}  ${it.productName}  ${it.address} id=${it.id} source=${it.isSource}")
            }
        }.trim()
    }

    private fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        28 -> "ECHO_REFERENCE"
        else -> "type_$t"
    }

    fun startInjectorTone(hz: Int): String {
        val helper = hidden ?: return "no context in UserService — cannot register AudioPolicy"
        stopInjectorInternal()
        val freq = if (hz <= 0) 440 else hz
        val errors = mutableListOf<String>()
        // VoIP apps (WhatsApp) record with VOICE_COMMUNICATION; MIC registers too, so it would win if first.
        val presets = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_UPLINK,
        )
        var last: HiddenAudioPolicy.InjectorSession? = null
        for (preset in presets) {
            try {
                last = helper.registerInjector(preset)
                injector = last
                startTone(last, freq)
                return buildString {
                    appendLine("injector registered")
                    appendLine("preset=${last.presetName}")
                    appendLine("tone=${freq}Hz")
                    appendLine("track.state=${last.track.state} play=${last.track.playState}")
                    appendLine(audioMode())
                    appendLine()
                    appendLine("If this is a VoIP call, the remote party should hear the tone.")
                    appendLine("If this is a cellular call and they do not, we are on the HAL voice path.")
                    if (errors.isNotEmpty()) {
                        appendLine()
                        appendLine("earlier presets failed:")
                        errors.forEach { appendLine("  $it") }
                    }
                }.trim()
            } catch (t: Throwable) {
                errors += "${HiddenAudioPolicy.presetLabel(preset)}: ${t.message}"
                runCatching { last?.let { helper.unregister(it) } }
                last = null
                injector = null
            }
        }
        return buildString {
            appendLine("injector failed on all presets")
            errors.forEach { appendLine("  $it") }
        }.trim()
    }

    /** Lab stop / preflight / helper shutdown: pauses calls (without changing the user's saved ON/OFF) and every tone. */
    fun stopAll(): String {
        pauseCalls()
        stopTones()
        return "injector stopped (all tones off)"
    }

    fun stopTones() {
        stopInjectorInternal()
        stopOfficialToneInternal()
    }

    fun audioMode(): String {
        val ctx = appContext
        val mode = if (ctx != null) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            modeName(am.mode)
        } else {
            "unknown (no context)"
        }
        return "audio_mode=$mode"
    }

    fun oracle(): String {
        val ctx = appContext ?: return "oracle: no context in UserService"
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val out = StringBuilder()
        out.appendLine("oracle — RESEARCH.md 5.0/5.4")
        out.appendLine(audioMode())
        val devices = am.getDevices(AudioManager.GET_DEVICES_ALL)
        val telIn = devices.filter { it.type == AudioDeviceInfo.TYPE_TELEPHONY && it.isSource }
        val telOut = devices.filter { it.type == AudioDeviceInfo.TYPE_TELEPHONY && it.isSink }
        out.appendLine("TYPE_TELEPHONY input  (TELEPHONY_RX, capture): " +
            if (telIn.isEmpty()) "ABSENT" else "PRESENT x${telIn.size} ids=${telIn.joinToString { it.id.toString() }}")
        out.appendLine("TYPE_TELEPHONY output (TELEPHONY_TX, inject): " +
            if (telOut.isEmpty()) "ABSENT" else "PRESENT x${telOut.size} ids=${telOut.joinToString { it.id.toString() }}")
        val official: String = if (Build.VERSION.SDK_INT < 33) {
            "SKIPPED (sdk=${Build.VERSION.SDK_INT}, needs 13+)"
        } else {
            try {
                if (HiddenCallAudio.isPstnCallAudioInterceptable(am)) "true" else "false"
            } catch (t: Throwable) {
                HiddenCallAudio.describeFailure(t)
            }
        }
        out.appendLine("isPstnCallAudioInterceptable: $official")
        out.appendLine(
            when {
                official == "true" && telIn.isNotEmpty() && telOut.isNotEmpty() ->
                    "VERDICT: OFFICIAL_API_EXPECTED — HAL declares the telephony devices"
                official == "false" && telIn.isEmpty() && telOut.isEmpty() ->
                    "VERDICT: LEGACY_VOICE_SESSION_LIKELY — official API will throw PSTN-NOT-ACCESSIBLE; use injector fallbacks"
                official == "true" || (telIn.isNotEmpty() && telOut.isNotEmpty()) ->
                    "VERDICT: MISMATCH — device scan and oracle disagree; capture dumpsys media.audio_policy"
                else ->
                    "VERDICT: INCONCLUSIVE — see raw lines above"
            },
        )
        val text = out.toString().trim()
        saveText("oracle_${System.currentTimeMillis()}.txt", text)
        return text
    }

    fun officialUplinkTone(hz: Int): String {
        val ctx = appContext ?: return "official tone: no context in UserService"
        if (Build.VERSION.SDK_INT < 33) {
            return "official tone: API-MISSING (sdk=${Build.VERSION.SDK_INT}, needs 13+)"
        }
        stopInjectorInternal()
        stopOfficialToneInternal()
        val freq = if (hz <= 0) 440 else hz
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val format = callFormat(playback = true)
            val attempt = attemptOfficialUplink(am, format)
            val track = attempt.track
            if (track == null) {
                return "official uplink injection track:\n${attempt.report}".trim()
            }
            officialTrack = track
            startOfficialTone(track, freq)
            SystemClock.sleep(300)
            buildString {
                appendLine("official uplink injection track: OPEN")
                appendLine(attempt.report)
                appendLine("tone=${freq}Hz  track.rate=${track.sampleRate} state=${track.state} ${playbackLine(track)}")
                appendLine(audioMode())
                appendLine()
                appendLine("During a live call: run uplinkCheck() or ask the remote party.")
                appendLine("Idle: this opening is not expected (no call) — WRONG-MODE is normal.")
            }.trim()
        } catch (t: Throwable) {
            stopOfficialToneInternal()
            "official uplink injection track: ${HiddenCallAudio.describeFailure(t)}"
        }
    }

    fun officialDownlinkCapture(durationMs: Int): String {
        val ctx = appContext ?: return "official capture: no context in UserService"
        if (Build.VERSION.SDK_INT < 33) {
            return "official capture: API-MISSING (sdk=${Build.VERSION.SDK_INT}, needs 13+)"
        }
        val ms = durationMs.coerceIn(250, 4_000)
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val format = callFormat(playback = false)
            val attempt = attemptOfficialDownlink(am, format)
            val record = attempt.record
            if (record == null) {
                return "official downlink extraction record:\n${attempt.report}".trim()
            }
            val cap = AudioTools.readPcm(record, ms)
            runCatching { record.release() }
            val wavPath = cap.samples.takeIf { it.isNotEmpty() }?.let { samples ->
                val dir = dumpsDir()
                if (dir != null) {
                    val f = File(dir, "official_downlink_${System.currentTimeMillis()}.wav")
                    f.writeBytes(AudioTools.wavBytes(samples, cap.sampleRate))
                    f.absolutePath
                } else {
                    "(no dump dir)"
                }
            } ?: "(no samples)"
            buildString {
                appendLine("official downlink extraction record: OPEN")
                appendLine(attempt.report)
                appendLine("state=${cap.state} rms=%.1f peak=${cap.peak} n=${cap.samples.size}".format(cap.rms))
                appendLine("wav=$wavPath")
                appendLine(audioMode())
            }.trim()
        } catch (t: Throwable) {
            "official downlink extraction record: ${HiddenCallAudio.describeFailure(t)}"
        }
    }

    fun uplinkCheck(hz: Int, durationMs: Int): String {
        val ctx = appContext ?: return "uplink check: no context in UserService"
        val freq = if (hz <= 0) 440 else hz
        val ms = durationMs.coerceIn(500, 4_000)
        val out = StringBuilder()
        out.appendLine("uplink check — tone $freq Hz, window ${ms}ms")
        out.appendLine(audioMode())
        var toneOpen = false
        try {
            stopInjectorInternal()
            stopOfficialToneInternal()
            // 1) official mechanism first. Below API 33 the method does not exist;
            // the injector fallback below is still worth running.
            if (Build.VERSION.SDK_INT < 33) {
                out.appendLine("tone: official track SKIPPED (sdk=${Build.VERSION.SDK_INT}, needs 13+)")
            } else {
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val attempt = attemptOfficialUplink(am, callFormat(playback = true))
                    out.appendLine(attempt.report)
                    val track = attempt.track
                    if (track != null) {
                        officialTrack = track
                        startOfficialTone(track, freq)
                        toneOpen = true
                        out.appendLine("tone: official track OPEN (shell call-redirection preferred)")
                    } else {
                        out.appendLine("tone: official track FAILED")
                    }
                } catch (t: Throwable) {
                    stopOfficialToneInternal()
                    out.appendLine("tone: official track FAILED — ${HiddenCallAudio.describeFailure(t)}")
                }
            }
            // 2) injector fallback (same presets as startInjectorTone)
            if (!toneOpen) {
                val helper = hidden
                val presets = intArrayOf(
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.VOICE_UPLINK,
                )
                for (preset in presets) {
                    try {
                        val session = helper?.registerInjector(preset) ?: error("no context")
                        injector = session
                        try {
                            startTone(session, freq)
                        } catch (t: Throwable) {
                            stopInjectorInternal()
                            throw t
                        }
                        toneOpen = true
                        out.appendLine("tone: injector fallback OPEN (preset=${HiddenAudioPolicy.presetLabel(preset)})")
                        break
                    } catch (t: Throwable) {
                        out.appendLine("tone: injector preset ${HiddenAudioPolicy.presetLabel(preset)} failed: ${t.message}")
                        if (injector != null) stopInjectorInternal()
                    }
                }
                if (!toneOpen) out.appendLine("tone: injector fallback FAILED on all presets")
            }
            // 3) let the tone reach the uplink, then capture the uplink mirror
            if (toneOpen) {
                SystemClock.sleep(300)
                officialTrack?.let { out.appendLine(playbackLine(it)) }
                val uplink = try {
                    val rec = AudioTools.openRecord(MediaRecorder.AudioSource.VOICE_UPLINK)
                    val cap = try {
                        AudioTools.readPcm(rec, ms)
                    } finally {
                        runCatching { rec.release() }
                    }
                    cap
                } catch (t: Throwable) {
                    null
                }
                if (uplink == null) {
                    out.appendLine("capture: VOICE_UPLINK FAILED to open (expected on some OEMs)")
                } else {
                    out.appendLine(
                        "capture: VOICE_UPLINK state=${uplink.state} rms=%.1f peak=${uplink.peak} n=${uplink.samples.size}"
                            .format(uplink.rms),
                    )
                    out.appendLine(AudioTools.toneReport(uplink.samples, uplink.sampleRate, freq))
                    if (uplink.samples.isNotEmpty()) {
                        val dir = dumpsDir()
                        if (dir != null) {
                            val f = File(dir, "uplink_check_${System.currentTimeMillis()}.wav")
                            f.writeBytes(AudioTools.wavBytes(uplink.samples, uplink.sampleRate))
                            out.appendLine("wav=${f.absolutePath}")
                        }
                    }
                }
                val evidence = out.toString()
                val onTelephony = evidence.contains("routed=TELEPHONY")
                out.appendLine(
                    when {
                        onTelephony && evidence.contains("TONE-DETECTED") ->
                            "VERDICT: TONE-REACHED-UPLINK — routed to telephony; the remote party should hear it"
                        onTelephony ->
                            "VERDICT: ROUTED-TELEPHONY-NO-TONE — far-end ears still decide"
                        else ->
                            "VERDICT: LOCAL-OR-UNROUTED — routed device is not telephony; do not treat this as uplink injection"
                    },
                )
            } else {
                out.appendLine("capture: SKIPPED (no tone mechanism opened)")
            }
            return out.toString().trim()
        } finally {
            stopInjectorInternal()
            stopOfficialToneInternal()
        }
    }

    private data class UplinkAttempt(val track: AudioTrack?, val report: String)

    private data class DownlinkAttempt(val record: AudioRecord?, val report: String)

    private fun callFormat(playback: Boolean): android.media.AudioFormat {
        val channel = if (playback) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_IN_MONO
        return android.media.AudioFormat.Builder()
            .setSampleRate(16_000)
            .setChannelMask(channel)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
    }

    private fun attemptOfficialUplink(am: AudioManager, format: android.media.AudioFormat): UplinkAttempt {
        val modeResult = runCatching { HiddenCallAudio.redirectionForCurrentMode(am) }
        val wrapper = runCatching { HiddenCallAudio.uplinkInjectionTrack(am, format) }
        if (modeResult.isFailure) {
            wrapper.getOrNull()?.let { releaseTrack(it) }
            val report = buildString {
                appendLine("wrapper: ${attemptLine(wrapper)}")
                appendLine(probeShellUplink(format, HiddenCallAudio.pstnRedirection(), "PSTN"))
                appendLine(probeShellUplink(format, HiddenCallAudio.voipRedirection(), "VOIP"))
            }.trim()
            return UplinkAttempt(null, report)
        }
        val mode = modeResult.getOrThrow()
        val kind = HiddenCallAudio.redirectionName(mode)
        val shell = runCatching { HiddenCallAudio.shellUplink(format, mode) }
        val chosen = shell.getOrNull()
        if (chosen != null) wrapper.getOrNull()?.let { releaseTrack(it) }
        val report = buildString {
            appendLine("wrapper: ${attemptLine(wrapper)}")
            appendLine("shell-redirect $kind: ${attemptLine(shell)}")
        }.trim()
        return UplinkAttempt(chosen ?: wrapper.getOrNull(), report)
    }

    private fun attemptOfficialDownlink(am: AudioManager, format: android.media.AudioFormat): DownlinkAttempt {
        val modeResult = runCatching { HiddenCallAudio.redirectionForCurrentMode(am) }
        val wrapper = runCatching { HiddenCallAudio.downlinkExtractionRecord(am, format) }
        if (modeResult.isFailure) {
            wrapper.getOrNull()?.let { runCatching { it.release() } }
            val report = buildString {
                appendLine("wrapper: ${attemptLine(wrapper)}")
                appendLine(probeShellDownlink(format, HiddenCallAudio.pstnRedirection(), "PSTN"))
                appendLine(probeShellDownlink(format, HiddenCallAudio.voipRedirection(), "VOIP"))
            }.trim()
            return DownlinkAttempt(null, report)
        }
        val mode = modeResult.getOrThrow()
        val kind = HiddenCallAudio.redirectionName(mode)
        val shell = runCatching { HiddenCallAudio.shellDownlink(format, mode) }
        val chosen = shell.getOrNull()
        if (chosen != null) wrapper.getOrNull()?.let { runCatching { it.release() } }
        val report = buildString {
            appendLine("wrapper: ${attemptLine(wrapper)}")
            appendLine("shell-redirect $kind: ${attemptLine(shell)}")
        }.trim()
        return DownlinkAttempt(chosen ?: wrapper.getOrNull(), report)
    }

    private fun probeShellUplink(format: android.media.AudioFormat, mode: Int, name: String): String {
        return try {
            val track = HiddenCallAudio.shellUplink(format, mode)
            val state = track.state
            releaseTrack(track)
            "shell-redirect $name: BUILT state=$state (released, not played)"
        } catch (t: Throwable) {
            "shell-redirect $name: ${HiddenCallAudio.describeFailure(t)}"
        }
    }

    private fun probeShellDownlink(format: android.media.AudioFormat, mode: Int, name: String): String {
        return try {
            val record = HiddenCallAudio.shellDownlink(format, mode)
            val state = record.state
            runCatching { record.release() }
            "shell-redirect $name: BUILT state=$state (released, not read)"
        } catch (t: Throwable) {
            "shell-redirect $name: ${HiddenCallAudio.describeFailure(t)}"
        }
    }

    private fun <T> attemptLine(result: Result<T>): String {
        return result.fold(
            onSuccess = { "BUILT" },
            onFailure = { HiddenCallAudio.describeFailure(it) },
        )
    }

    private fun releaseTrack(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    fun telephonyTxTone(hz: Int, durationMs: Int): String {
        val ctx = appContext ?: return "tx tone: no context in UserService"
        val freq = if (hz <= 0) 440 else hz
        val ms = durationMs.coerceIn(500, 4_000)
        val modeLine = audioMode()
        val out = StringBuilder()
        out.appendLine("telephony tx tone — $freq Hz, window ${ms}ms")
        out.appendLine(modeLine)
        val armed = modeLine.contains("IN_CALL") ||
            modeLine.contains("CALL_SCREENING") ||
            modeLine.contains("CALL_REDIRECT")
        if (!armed) {
            out.appendLine("SKIPPED: in-call music arms only for IN_CALL / CALL_SCREENING / CALL_REDIRECT")
            return out.toString().trim()
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val telephony = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY && it.isSink }
        if (telephony == null) {
            out.appendLine("NO TELEPHONY sink")
            return out.toString().trim()
        }
        out.appendLine("device: TELEPHONY id=${telephony.id}")
        var paramSet = false
        var track: AudioTrack? = null
        try {
            val voice = AudioAttributes.USAGE_VOICE_COMMUNICATION
            val media = AudioAttributes.USAGE_MEDIA
            var usage = voice
            track = openTxTrack(voice)
            var preferred = track.setPreferredDevice(telephony)
            out.appendLine("usage=VOICE_COMMUNICATION setPreferredDevice=$preferred state=${track.state}")
            if (!preferred) {
                track.release()
                track = openTxTrack(media)
                usage = media
                preferred = track.setPreferredDevice(telephony)
                out.appendLine("usage=MEDIA setPreferredDevice=$preferred state=${track.state}")
            }
            if (!preferred) {
                val param = setIncallMusic(true)
                paramSet = param.startsWith("ok")
                out.appendLine("setParameters incall_music_enabled=true: $param")
                track.release()
                track = openTxTrack(voice)
                usage = voice
                preferred = track.setPreferredDevice(telephony)
                out.appendLine("after parameter usage=VOICE_COMMUNICATION setPreferredDevice=$preferred")
                if (!preferred) {
                    track.release()
                    track = openTxTrack(media)
                    usage = media
                    preferred = track.setPreferredDevice(telephony)
                    out.appendLine("after parameter usage=MEDIA setPreferredDevice=$preferred")
                }
            }
            if (!preferred) {
                out.appendLine("ROUTE REJECTED — tone not played")
                return out.toString().trim()
            }
            val rate = if (track.sampleRate > 0) track.sampleRate else 16_000
            val chunk = sine(freq, rate, rate / 10)
            track.play()
            val playing = track
            val writer = Thread({
                val deadline = SystemClock.elapsedRealtime() + ms + 800L
                try {
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val wrote = playing.write(chunk, 0, chunk.size)
                        if (wrote < 0) {
                            Log.w(TAG, "tx write $wrote")
                            break
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "tx writer died", t)
                }
            }, "tx-tone")
            writer.isDaemon = true
            writer.start()
            Thread.sleep(300)
            val routed = playing.routedDevice
            out.appendLine(
                "playing usage=${if (usage == voice) "VOICE_COMMUNICATION" else "MEDIA"} " +
                    "playState=${playing.playState} routed=" +
                    (routed?.let { "${typeName(it.type)} id=${it.id}" } ?: "none"),
            )
            val captures = arrayOfNulls<String>(2)
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_UPLINK,
                MediaRecorder.AudioSource.VOICE_CALL,
            )
            val readers = sources.mapIndexed { index, source ->
                Thread({
                    val record = AudioTools.openRecord(source)
                    val cap = try {
                        AudioTools.readPcm(record, ms)
                    } finally {
                        runCatching { record.release() }
                    }
                    val name = HiddenAudioPolicy.presetLabel(source)
                    captures[index] = "$name state=${cap.state} rms=%.1f peak=${cap.peak} n=${cap.samples.size} opener=${AudioTools.lastOpenNote}\n${AudioTools.toneReport(cap.samples, cap.sampleRate, freq)}"
                        .format(cap.rms)
                }, "tx-cap-$index")
            }
            readers.forEach { it.start() }
            readers.forEach { it.join((ms + 2000).toLong()) }
            writer.join(1000)
            captures.forEach { line ->
                if (line != null) out.appendLine(line)
            }
            val heard = captures.any { it?.contains("TONE-DETECTED") == true }
            out.appendLine(
                if (heard) {
                    "VERDICT: TONE-IN-CAPTURE — far-end ears still decide"
                } else {
                    "VERDICT: NO-TONE-IN-CAPTURE — routed device above is the route evidence; far-end ears still decide"
                },
            )
            return out.toString().trim()
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            out.appendLine("FAILED ${cause.javaClass.simpleName}: ${cause.message?.replace('\n', ' ')}")
            return out.toString().trim()
        } finally {
            track?.let {
                runCatching { it.pause() }
                runCatching { it.flush() }
                runCatching { it.release() }
            }
            if (paramSet) {
                Log.i(TAG, "clear incall_music: ${setIncallMusic(false)}")
            }
        }
    }

    private fun openTxTrack(usage: Int): AudioTrack {
        val track = AudioTools.openShellTrack(usage)
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack uninitialized for usage=$usage")
        }
        return track
    }

    private fun setIncallMusic(enabled: Boolean): String {
        return try {
            val method = Class.forName("android.media.AudioSystem")
                .getMethod("setParameters", String::class.java)
            val ret = method.invoke(null, "incall_music_enabled=$enabled")
            "ok ret=$ret"
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            "FAILED ${cause.javaClass.simpleName}: ${cause.message?.replace('\n', ' ')}"
        }
    }

    private fun playbackLine(track: AudioTrack): String {
        val routed = track.routedDevice
        val route = routed?.let { "${typeName(it.type)} id=${it.id}" } ?: "none"
        val attrs = track.audioAttributes
        val systemUsage = runCatching {
            AudioAttributes::class.java.getMethod("getSystemUsage").invoke(attrs) as Int
        }.getOrDefault(-1)
        val flags = runCatching {
            AudioAttributes::class.java.getMethod("getAllFlags").invoke(attrs) as Int
        }.getOrDefault(attrs.flags)
        return "playState=${track.playState} routed=$route systemUsage=$systemUsage flags=0x${flags.toString(16)}"
    }

    private fun startOfficialTone(track: android.media.AudioTrack, hz: Int) {
        val rate = if (track.sampleRate > 0) track.sampleRate else 16_000
        val chunk = sine(hz, rate, rate / 10)
        officialToneRunning = true
        track.play()
        officialToneThread = Thread({
            try {
                while (officialToneRunning) {
                    track.write(chunk, 0, chunk.size)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "official tone thread died", t)
            }
        }, "official-tone").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopOfficialToneInternal() {
        officialToneRunning = false
        officialToneThread?.join(500)
        officialToneThread = null
        val track = officialTrack
        officialTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    /**
     * Injector loopback, no call needed (2026-10-25, the Redmi's choppy WhatsApp audio): registers the injector at
     * [mixRate], plays a 440 Hz tone into it for [durationMs], and records it back through a VOICE_COMMUNICATION
     * recorder at [recordRate] (the injector serves any such recorder, as it does WhatsApp's). Reports write stalls,
     * how much audio arrived against real time, and the gaps in it.
     */
    fun injectorLoopback(mixRate: Int, recordRate: Int, durationMs: Int): String {
        check(!callsBusy()) { "Call Stream is on or in a call: turn it off first" }
        val policy = HiddenAudioPolicy(AudioTools.shellContext())
        val session = policy.registerInjector(MediaRecorder.AudioSource.VOICE_COMMUNICATION, mixRate)
        val record = AudioTools.openRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, recordRate)
        try {
            val track = session.track
            val chunk = sine(440, mixRate, mixRate / 100)
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            var writes = 0; var stalls = 0; var maxWriteMs = 0.0; var written = 0L
            val writer = Thread {
                track.play()
                while (!stop.get()) {
                    val t0 = System.nanoTime()
                    val n = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    val ms = (System.nanoTime() - t0) / 1e6
                    if (n > 0) written += n
                    writes++; if (ms >= 100) stalls++; if (ms > maxWriteMs) maxWriteMs = ms
                }
            }
            record.startRecording()
            val start = System.nanoTime()
            writer.start()
            val frame = ShortArray(recordRate / 100)
            var read = 0L; var frames = 0; var silent = 0; var started = false; var longestGap = 0; var gap = 0
            while ((System.nanoTime() - start) / 1_000_000 < durationMs) {
                val n = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                read += n
                var sum = 0.0; for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                val loud = kotlin.math.sqrt(sum / n) >= SILENCE_RMS
                if (loud) started = true
                if (started) { frames++; if (!loud) { silent++; gap++; if (gap > longestGap) longestGap = gap } else gap = 0 }
            }
            val elapsedMs = (System.nanoTime() - start) / 1e6
            stop.set(true); writer.join(1000)
            return buildString {
                appendLine("mix ${mixRate} Hz, record ${recordRate} Hz, ${"%.0f".format(elapsedMs)} ms")
                appendLine("track: buffer ${track.bufferSizeInFrames}/${track.bufferCapacityInFrames} frames, underruns ${track.underrunCount}")
                appendLine("writes $writes, stalls >=100 ms $stalls, longest ${"%.0f".format(maxWriteMs)} ms")
                appendLine("written ${"%.0f".format(written * 1000.0 / mixRate)} ms of audio; recorded ${"%.0f".format(read * 1000.0 / recordRate)} ms in ${"%.0f".format(elapsedMs)} ms")
                appendLine("after the tone arrived: $frames frames of 10 ms, $silent silent, longest gap ${longestGap * 10} ms")
                append("recorder routed type ${record.routedDevice?.type ?: -1}")
            }
        } finally {
            runCatching { record.stop() }; runCatching { record.release() }
            policy.unregister(session)
        }
    }

    private fun startTone(session: HiddenAudioPolicy.InjectorSession, hz: Int) {
        val track = session.track
        val sampleRate = if (track.sampleRate > 0) track.sampleRate else 16_000
        // hz=1: loop a speech clip instead (16 kHz mono s16le). WhatsApp's echo canceller and noise suppressor
        // remove a steady sine, so the tone alone can't show whether VoIP injection is heard (2026-10-25).
        val clip = java.io.File(INJECT_CLIP)
        val chunk = if (hz == 1 && clip.canRead()) {
            val bytes = clip.readBytes()
            ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
        } else {
            sine(hz, sampleRate, sampleRate / 10)
        }
        toneRunning = true
        track.play()
        toneThread = Thread({
            try {
                while (toneRunning) {
                    track.write(chunk, 0, chunk.size)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "tone thread died", t)
            }
        }, "injector-tone").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopInjectorInternal() {
        toneRunning = false
        toneThread?.join(500)
        toneThread = null
        val session = injector
        injector = null
        if (session != null) {
            hidden?.unregister(session)
        }
    }

    private data class CaptureSample(
        val state: String,
        val rms: Double,
        val peak: Int,
        val samples: Int,
        val opener: String = "",
    )

    private fun capture(source: Int, durationMs: Int): CaptureSample {
        val rate = 16_000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val min = AudioRecord.getMinBufferSize(rate, channel, encoding)
        if (min <= 0) return CaptureSample("bad_buffer($min)", 0.0, 0, 0)
        val record = AudioTools.openRecord(source)
        val opener = AudioTools.lastOpenNote
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return CaptureSample("uninit", 0.0, 0, 0, opener)
        }
        return try {
            record.startRecording()
            val buf = ShortArray(min.coerceAtLeast(320))
            var sumSq = 0.0
            var n = 0
            var peak = 0
            val deadline = SystemClock.elapsedRealtime() + durationMs
            while (SystemClock.elapsedRealtime() < deadline) {
                val read = record.read(buf, 0, buf.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    val s = buf[i].toInt()
                    sumSq += s.toDouble() * s
                    val a = abs(s)
                    if (a > peak) peak = a
                    n++
                }
            }
            val rms = if (n == 0) 0.0 else sqrt(sumSq / n)
            CaptureSample("ok", rms, peak, n, opener)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun saveText(name: String, text: String): String {
        return try {
            val dir = dumpsDir() ?: return "(no dump dir)"
            val file = File(dir, name)
            file.writeText(text)
            file.absolutePath
        } catch (t: Throwable) {
            "(save failed: ${t.message?.replace('\n', ' ')})"
        }
    }

    private fun dumpsDir(): File? {
        // The UserService runs as shell uid with the app Context. getExternalFilesDir
        // checks the context package against that uid and throws SecurityException
        // ("callingPackage does not match UID"). A throw must not skip the later dirs.
        val candidates = listOfNotNull(
            runCatching { appContext?.getExternalFilesDir("dumps") }.getOrNull(),
            File("/storage/emulated/0/Android/data/dev.callstream.probe/files/dumps"),
            File("/data/local/tmp/call-stream"),
        )
        for (dir in candidates) {
            val ok = runCatching { dir.exists() || dir.mkdirs() }.getOrDefault(false)
            if (ok) return dir
        }
        return null
    }

    fun exec(vararg cmd: String, maxChars: Int = 180_000): String {
        return try {
            val proc = Runtime.getRuntime().exec(cmd)
            val stdout = proc.inputStream.bufferedReader().use { it.readText() }
            val stderr = proc.errorStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            val text = if (stderr.isBlank()) stdout else "$stdout\n[stderr]\n$stderr"
            if (text.length <= maxChars) text else text.take(maxChars) + "\n… truncated ${text.length - maxChars} chars …\n"
        } catch (t: Throwable) {
            "exec failed: ${t.message}"
        }
    }

    private companion object {
        const val TAG = "CallStreamLab"
        const val INJECT_CLIP = "/data/local/tmp/callstream-inject.pcm"
        /** Capture points the tap probe records: name, source, requested rate (the redirection recorder picks its own). */
        private val TAPS = listOf(
            Triple("downlink_8k", MediaRecorder.AudioSource.VOICE_DOWNLINK, 8_000),
            Triple("downlink_16k", MediaRecorder.AudioSource.VOICE_DOWNLINK, 16_000),
            Triple("downlink_48k", MediaRecorder.AudioSource.VOICE_DOWNLINK, 48_000),
            Triple("voice_call_16k", MediaRecorder.AudioSource.VOICE_CALL, 16_000),
            Triple("redirection_16k", MediaRecorder.AudioSource.VOICE_DOWNLINK, 16_000),
        )
        private const val SILENCE_RMS = 40.0

        private fun modeName(mode: Int): String = when (mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
            AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
            AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMMUNICATION_REDIRECT"
            else -> "MODE_$mode"
        }

        private fun sine(hz: Int, sampleRate: Int, samples: Int): ShortArray {
            val out = ShortArray(samples)
            val w = 2.0 * Math.PI * hz / sampleRate
            val amp = 0.35 * Short.MAX_VALUE
            for (i in 0 until samples) {
                out[i] = (sin(w * i) * amp).toInt().toShort()
            }
            return out
        }
    }
}
