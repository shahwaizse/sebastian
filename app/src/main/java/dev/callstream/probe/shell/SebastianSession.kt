package dev.callstream.probe.shell

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sebastian inside one live call. He listens to both sides (two AssemblyAI streaming transcripts, so each line
 * knows who said it), stays silent, and answers only when someone says his name. His answers come from the
 * AssemblyAI LLM Gateway and are spoken by AssemblyAI TTS into the call: into the carrier uplink so the other
 * person hears him, and onto the phone's earpiece so Rayyan does too.
 *
 * Audio is 16 kHz mono PCM16 end to end: what the carrier capture gives, what streaming STT takes and what TTS
 * returns at sample_rate=16000. No resampling anywhere.
 *
 * Threads: two capture threads (other side, Rayyan's side), one playback thread, one brain thread (LLM and tools),
 * plus OkHttp's socket readers.
 */
internal class SebastianSession(
    private val cfg: JSONObject,
    /** The other person's name if known ("Shahwaiz"), else "Them". */
    private val otherName: String,
    private val log: (String) -> Unit,
    /** A WhatsApp call: Sebastian relays both directions through these hooks. Null: a SIM call. */
    private val tap: WhatsAppTap? = null,
    /** Whether the call is still up (for "Sebastian, cut the call"). */
    private val inCall: () -> Boolean = { false },
    /** The other person's number, when Sebastian dialed them: meeting links go to it on WhatsApp. */
    private val otherNumber: String? = null,
) {
    data class Line(val atMs: Long, val who: String, val text: String)

    private val running = AtomicBoolean(false)
    private val mcp = SebastianMcp(cfg, CLIENT)
    @Volatile var state = "IDLE"; private set
    val startedAt = System.currentTimeMillis()
    private val lines = ArrayList<Line>()
    private val key = cfg.optString("assemblyai_api_key").ifBlank { error("No AssemblyAI key in Sebastian's config") }
    private val ownerName = cfg.optString("owner_name", "Rayyan")
    /** Sebastian's TTS is quieter than a person on a call: his voice is scaled up by this much (clipped at full scale). */
    private val voiceGain = cfg.optDouble("voice_gain", 1.0)

    // ---- audio ----
    private var downlink: AudioRecord? = null
    private var selfRecord: AudioRecord? = null
    @Volatile private var uplinkTrack: AudioTrack? = null
    private var localTrack: AudioTrack? = null
    private val out = LinkedBlockingDeque<ShortArray>()
    /** Sebastian's voice is (or was just) in the call: both transcripts are fed silence so he doesn't hear himself. */
    @Volatile private var speakingUntilNs = 0L
    private var threads = mutableListOf<Thread>()
    private var uplinkRoute = "call_assistant"
    private var trackRebuilds = 0
    /** WhatsApp: Sebastian's frames, also for Rayyan's earpiece (the uplink thread hands them to the relay thread). */
    private val localVoice = LinkedBlockingDeque<ShortArray>()
    private val upsampler = Upsampler16To48()
    /** The join line waits for the other side to speak first (they've picked up). */
    @Volatile private var joinPending = false
    private var startedNs = 0L
    /** [sum of RMS, frames, peak RMS] since the last status read, per direction. */
    private val themMeter = DoubleArray(3)
    private val micMeter = DoubleArray(3)
    @Volatile private var upFrames = 0L
    @Volatile private var upWriteMsMax = 0.0
    // ---- relay delay (WhatsApp): what waits in each hop, and trimming of queues that never empty (see HopGauge)
    private val latTarget = cfg.optDouble("latency_target_ms", 30.0)
    private val latTrim = cfg.optBoolean("latency_trim", true)
    private val echoDuck = cfg.optBoolean("echo_duck", true)
    /** Diagnostic: the call's audio saved on the phone at four points of the relay (see CallTape). WhatsApp only. */
    private val tape: CallTape? = if (tap != null && cfg.optBoolean("record_call", false))
        runCatching { CallTape(System.currentTimeMillis()) }.getOrNull() else null
    /** Rayyan is recorded on the phone's own voice-call path, which cancels the earpiece echo itself (openVoiceCommMic). */
    @Volatile private var hwEchoPath = false
    /** WebRTC AEC3: removes what the earpiece plays from Rayyan's mic (see EchoCanceller). WhatsApp only. */
    /** Observer mode: WhatsApp keeps the real mic and its echo canceller; Sebastian only listens and speaks to the earpiece. */
    private val observer = tap?.observer == true
    private val aec: EchoCanceller? = if (tap != null && !observer && cfg.optBoolean("aec", true))
        runCatching { EchoCanceller.create() }
            .onFailure { log("echo canceller unavailable: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull() else null
    // ---- observer: the other person hears Sebastian only while the mic hook is on (see MicSwap) ----
    private val swap: MicSwap? = if (observer && cfg.optBoolean("mic_swap", false)) runCatching { MicSwap(log) }
        .onFailure { log("mic swap unavailable: ${it.message}") }.getOrNull() else null
    private val swapVoice = LinkedBlockingDeque<ShortArray>()
    private val swapUp = Upsampler16To48()
    /** The hook stays on until this time (rolling: Sebastian's last sound + a tail). */
    @Volatile private var swapHoldUntilNs = 0L
    /** Rayyan's voice waiting in the track WhatsApp reads as its mic. */
    private val gUp =HopGauge("up", WhatsAppTap.TRACK_RATE, latTarget, latTrim)
    /** Rayyan's mic captured but not yet read. */
    private val gMic = HopGauge("mic", 16_000, latTarget, latTrim)
    /** The other side's voice captured from WhatsApp but not yet read. */
    private val gDown = HopGauge("down", 16_000, latTarget, latTrim)
    /** The other side's voice waiting in Rayyan's earpiece track. */
    private val gEar = HopGauge("ear", 16_000, latTarget, latTrim)
    private var upWritten = 0L
    private var upRebases = 0
    private var earWritten = 0L
    private val micBacklog = RecordBacklog(16_000)
    private val downBacklog = RecordBacklog(16_000)
    private fun latencyText() = listOf(gMic, gUp, gDown, gEar).joinToString(" ") { it.report() }

    // ---- AssemblyAI sockets ----
    @Volatile private var sttThem: WebSocket? = null
    @Volatile private var sttSelf: WebSocket? = null
    @Volatile private var tts: WebSocket? = null
    @Volatile private var ttsOpen = false
    /** After a Cancel, audio still in flight belongs to the cancelled reply until the server says Cancelled. */
    @Volatile private var dropTts = false
    private var ttsCarry = ByteArray(0)

    // ---- brain ----
    private val brain = Executors.newSingleThreadExecutor { Thread(it, "sebastian-brain").apply { isDaemon = true } }
    private val thinking = AtomicBoolean(false)
    /** "Hey Sebastian" said alone: the question comes in that speaker's next turn. */
    private data class Waiting(val who: String, val text: String, val untilMs: Long)
    @Volatile private var waiting: Waiting? = null
    @Volatile private var bargeFrames = 0
    @Volatile private var bargeFramesSelf = 0
    /** The other side's loudness, held for ~300 ms: while they talk, the mic mostly hears them on Rayyan's speaker. */
    @Volatile private var farLevel = 0.0
    /** The other side's loudness per 10 ms frame, last 600 ms: their echo reaches the mic 100-400 ms late. */
    private val farHistory = DoubleArray(60)
    private var farIdx = 0
    private var micGain = 1.0
    @Volatile private var duckedFrames = 0L
    /** Who Sebastian last answered, whether one follow-up without his name is still open, and when he was last asked. */
    @Volatile private var lastAsker = ""
    @Volatile private var followUpOpen = false
    @Volatile private var lastAskMs = 0L
    @Volatile var spokenReplies = 0; private set

    fun start() {
        check(running.compareAndSet(false, true)) { "already running" }
        state = "CONNECTING"
        Thread({ mcp.connect(log) }, "sebastian-mcp").apply { isDaemon = true }.start()
        startedNs = SystemClock.elapsedRealtimeNanos()
        if (tap != null && observer) {
            // WhatsApp keeps the real mic and plays the caller on the earpiece itself. Sebastian gets a copy of the
            // caller's audio, tries Rayyan's mic beside WhatsApp, and speaks on the earpiece only.
            downlink = tap.record
            localTrack = openLocal()
            selfRecord = openSelfVoip()
            if (selfRecord == null) log("Rayyan's mic isn't available beside the call: only the caller is transcribed")
            val stale = HopGauge.drain(downlink!!, ShortArray(FRAME_SAMPLES))
            localTrack!!.play(); selfRecord?.startRecording()
            log("observer start: dropped ${stale / 16}ms of old audio self=$selfLabel")
            uplinkRoute = "observer"
            threads += Thread({ capture(true) }, "sebastian-them").apply { start() }
            if (selfRecord != null) threads += Thread({ capture(false) }, "sebastian-self").apply { start() }
            threads += Thread(::playbackToEarOnly, "sebastian-voice").apply { start() }
            if (swap != null) {
                threads += Thread(::swapKeeper, "sebastian-swap").apply { isDaemon = true; start() }
                if (cfg.optBoolean("swap_probe", false)) threads += Thread(::swapProbe, "sebastian-swap-probe").apply { isDaemon = true; start() }
            }
        } else if (tap != null) {
            // The relay first: until it runs, neither side of the WhatsApp call hears the other.
            downlink = tap.record
            uplinkTrack = tap.track
            localTrack = openLocal()
            selfRecord = openSelfVoip() ?: error("can't record Rayyan's microphone for WhatsApp")
            // The tap outlives calls and nobody reads or writes it between them: leftover audio would sit at the front of
            // both directions as delay for the whole call. Start this call with both empty.
            runCatching { uplinkTrack!!.pause(); uplinkTrack!!.flush(); uplinkTrack!!.play() }
                .onFailure { log("uplink flush failed: ${it.message}") }
            // Its play position counts from before this call: start the written count there, so queued = 0 now.
            upWritten = HopGauge.head(uplinkTrack!!)
            val stale = HopGauge.drain(downlink!!, ShortArray(FRAME_SAMPLES))
            localTrack!!.play(); selfRecord!!.startRecording()
            log("relay start: dropped ${stale / 16}ms of old audio, trim=${latTrim} target=${latTarget.toInt()}ms" +
                " echoDuck=$echoDuck aec=${aec != null} hwEcho=$hwEchoPath self=$selfLabel earLowLatency=${localTrack!!.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY}")
            uplinkRoute = "whatsapp"
            threads += Thread({ capture(true) }, "sebastian-them").apply { start() }
            threads += Thread(::relayUp, "sebastian-up").apply { start() }
            joinPending = true
        } else {
            downlink = openDownlink()
            selfRecord = openSelf()
            uplinkTrack = openUplink()
            localTrack = if (cfg.optBoolean("local_playback", true)) runCatching { openLocal() }
                .onFailure { log("local playback unavailable: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull() else null
            downlink?.startRecording(); selfRecord?.startRecording(); uplinkTrack?.play(); localTrack?.play()
            threads += Thread({ capture(true) }, "sebastian-them").apply { start() }
            if (selfRecord != null) threads += Thread({ capture(false) }, "sebastian-self").apply { start() }
            threads += Thread(::playback, "sebastian-voice").apply { start() }
        }
        connectTts()
        sttThem = connectStt(otherName)
        sttSelf = connectStt(ownerName)
        threads += Thread(::keepAlive, "sebastian-keepalive").apply { isDaemon = true; start() }
        state = "LISTENING"
        if (!joinPending) sayJoinLine()
        log("session live: self=${selfLabel} route=$uplinkRoute local=${localTrack != null}")
    }

    /** Ends the session and returns what was said, oldest first. */
    fun stop(): List<Line> {
        if (!running.getAndSet(false)) return synchronized(lines) { lines.toList() }
        state = "STOPPING"
        runCatching { sttThem?.send("""{"type":"Terminate"}""") }; runCatching { sttSelf?.send("""{"type":"Terminate"}""") }
        runCatching { tts?.send("""{"type":"Terminate"}""") }
        // WhatsApp: the tap's recorder and track belong to the tap (they must outlive the call): left running.
        if (tap == null) { runCatching { downlink?.stop() }; runCatching { uplinkTrack?.pause(); uplinkTrack?.flush() } }
        runCatching { selfRecord?.stop() }
        runCatching { localTrack?.pause(); localTrack?.flush() }
        threads.forEach { it.join(1500) }
        tape?.let { t -> runCatching { log("call recorded: ${t.close()}") } }
        aec?.let { a -> log("echo canceller: ${a.metrics()} frames=${a.frames}"); a.close() }
        swap?.release()
        // Give the last transcript a moment to arrive after Terminate.
        SystemClock.sleep(800)
        listOf(sttThem, sttSelf, tts).forEach { runCatching { it?.close(1000, null) } }
        if (tap == null) { runCatching { downlink?.release() }; runCatching { uplinkTrack?.release() } }
        runCatching { selfRecord?.release() }; runCatching { localTrack?.release() }
        brain.shutdownNow()
        state = "ENDED"
        return synchronized(lines) { lines.toList() }
    }

    fun isLive() = running.get()

    fun status() = "$state lines=${synchronized(lines) { lines.size }} replies=$spokenReplies route=$uplinkRoute self=$selfLabel queuedMs=${out.size * 10}" +
        " them=${meterText(themMeter)} mic=${meterText(micMeter)} upFrames=$upFrames ducked=$duckedFrames far=${"%.0f".format(farLevel)} upWriteMaxMs=${"%.0f".format(upWriteMsMax)}" +
        " track=${uplinkTrack?.playState ?: -1}/${uplinkTrack?.underrunCount ?: -1} joinPending=$joinPending" +
        (if (tap != null) " lat[${latencyText()}]" else "")

    private fun meter(m: DoubleArray, level: Double) = synchronized(m) { m[0] += level; m[1] += 1; if (level > m[2]) m[2] = level }
    private fun meterText(m: DoubleArray) = synchronized(m) {
        (if (m[1] > 0) "avg%.0f/peak%.0f/n%.0f".format(m[0] / m[1], m[2], m[1]) else "none").also { m.fill(0.0) }
    }

    // =================================================================== audio

    private var selfLabel = "none"

    private fun openDownlink(): AudioRecord {
        val r = AudioTools.openRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, 16_000)
        check(r?.state == AudioRecord.STATE_INITIALIZED) { "can't record the other side of the call" }
        return r!!
    }

    /** Rayyan's own voice: the call's uplink if this phone allows recording it, else the microphone. */
    private fun openSelf(): AudioRecord? {
        val order = when (cfg.optString("self_source", "auto")) {
            "uplink" -> listOf(MediaRecorder.AudioSource.VOICE_UPLINK)
            "mic" -> listOf(MediaRecorder.AudioSource.MIC)
            "none" -> emptyList()
            else -> listOf(MediaRecorder.AudioSource.VOICE_UPLINK, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }
        for (src in order) {
            val r = runCatching { AudioTools.openRecord(src, 16_000) }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                selfLabel = when (src) { MediaRecorder.AudioSource.VOICE_UPLINK -> "uplink"; MediaRecorder.AudioSource.MIC -> "mic"; else -> "voice_comm" }
                return r
            }
            runCatching { r?.release() }
        }
        log("can't record Rayyan's side; Sebastian hears only the other person")
        return null
    }

    /** HyperOS (the Redmi) kills the call-assistant track a moment after the call starts; a voice-communication
     * track aimed at the telephony output works there. "auto" starts from whatever worked last time. */
    private fun openUplink(): AudioTrack {
        uplinkRoute = when (val c = cfg.optString("uplink_route", "auto")) {
            "call_assistant", "telephony_tx" -> c
            else -> runCatching { File(SebastianConfig.ROUTE_PATH).readText().trim() }.getOrNull()
                ?.takeIf { it == "call_assistant" || it == "telephony_tx" } ?: "call_assistant"
        }
        return buildUplink(uplinkRoute)
    }

    private fun buildUplink(route: String): AudioTrack = if (route == "call_assistant") {
        val fmt = AudioFormat.Builder().setSampleRate(16_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        HiddenCallAudio.shellUplink(fmt, HiddenCallAudio.pstnRedirection(), false)
    } else {
        val am = AudioTools.shellContext().getSystemService(AudioManager::class.java)
        val telephony = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY && it.isSink }
            ?: error("no telephony output device")
        AudioTools.openShellTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION).also {
            check(it.setPreferredDevice(telephony)) { "telephony output refused the track" }
        }
    }.also { check(it.state == AudioTrack.STATE_INITIALIZED) { "uplink track not initialized ($route)" } }

    /** Rayyan's copy of Sebastian's voice, on whatever the call is using here (earpiece, speaker or headset). */
    private fun openLocal(): AudioTrack {
        // Low-latency path (smaller buffer, Android's fast mixer) where the phone allows it; the normal track otherwise.
        val t = runCatching { AudioTools.openShellTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION, lowLatency = true) }
            .getOrNull()?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
            ?: AudioTools.openShellTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        // WhatsApp: follow the call's own routing, so its speaker button still works.
        if (tap != null) return t
        val am = AudioTools.shellContext().getSystemService(AudioManager::class.java)
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val want = if (am.isSpeakerphoneOn) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        outs.firstOrNull { it.type == want }?.let { t.setPreferredDevice(it) }
        return t
    }

    private fun capture(them: Boolean) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val chunk = ShortArray(CHUNK_SAMPLES)
        val frame = ShortArray(FRAME_SAMPLES)
        var filled = 0
        var rebuilds = 0
        try {
            while (running.get()) {
                val rec = (if (them) downlink else selfRecord) ?: return
                val n = rec.read(frame, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING)
                if (n == AudioRecord.ERROR_DEAD_OBJECT && tap == null && rebuilds++ < 5) {
                    runCatching { rec.release() }
                    val fresh = if (them) openDownlink() else openSelf() ?: return
                    fresh.startRecording()
                    if (them) downlink = fresh else selfRecord = fresh
                    log("${if (them) "downlink" else "self"} recorder rebuilt")
                    continue
                }
                if (n <= 0) { if (!running.get()) return; error("record read failed: $n") }
                val level = rms(frame, n)
                if (!them && observer) {
                    tape?.mic?.write(frame, n)
                    meter(micMeter, level)
                    // While the hook is on, WhatsApp reads this track as its mic: Rayyan's mic plus Sebastian's voice.
                    swap?.takeIf { it.engaged }?.let { relaySwap(it, frame, n) }
                }
                if (them && tap != null) {
                    tape?.incoming?.write(frame, n)
                    downBacklog.read += n
                    downBacklog.frames(rec)?.let { gDown.observe(it) }
                    // A standing backlog here is delay before Rayyan hears them: skip a block (to STT too).
                    if (gDown.shouldDrop(n, level < QUIET_RMS)) continue
                    relayDown(frame, n, level)
                }
                val speaking = SystemClock.elapsedRealtimeNanos() < speakingUntilNs
                if (speaking && (them || observer)) bargeIn(level, them)
                // While Sebastian talks, his own voice comes back on both captures: send silence instead.
                if (speaking) java.util.Arrays.fill(frame, 0, n, 0)
                System.arraycopy(frame, 0, chunk, filled, n)
                filled += n
                if (filled >= CHUNK_SAMPLES) {
                    (if (them) sttThem else sttSelf)?.send(pcmBytes(chunk, filled).toByteString())
                    filled = 0
                }
            }
        } catch (t: Throwable) {
            if (running.get()) log("capture ${if (them) "them" else "self"} stopped: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Someone talks over Sebastian for ~300 ms: he stops at once and listens. The other person is judged at
     * barge_rms; Rayyan's own mic also hears Sebastian on the earpiece, so it needs the louder barge_rms_self.
     */
    private fun bargeIn(level: Double, them: Boolean) {
        if (!cfg.optBoolean("barge_in", true) || out.isEmpty()) { bargeFrames = 0; bargeFramesSelf = 0; return }
        val loud = level >= if (them) cfg.optDouble("barge_rms", 1200.0) else cfg.optDouble("barge_rms_self", 3000.0)
        val frames = if (loud) (if (them) bargeFrames else bargeFramesSelf) + 1 else 0
        if (them) bargeFrames = frames else bargeFramesSelf = frames
        // 10 ms frames: they have to talk over him this long before he stops.
        if (frames >= cfg.optInt("barge_frames", 30)) {
            bargeFrames = 0; bargeFramesSelf = 0
            cancelSpeech(if (them) "barge-in by $otherName" else "barge-in by $ownerName")
        }
    }

    private fun cancelSpeech(why: String) {
        speakingUntilNs = 0L
        out.clear()
        dropTts = true
        runCatching { tts?.send("""{"type":"Cancel"}""") }
        log("speech cancelled ($why)")
    }

    private fun playback() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val silence = ShortArray(FRAME_SAMPLES)
        try {
            while (running.get()) {
                val next = out.pollFirst() ?: silence
                if (next !== silence) speakingUntilNs = SystemClock.elapsedRealtimeNanos() + ECHO_TAIL_NANOS
                // The uplink write blocks: it paces this loop at real time.
                var offset = 0
                while (offset < next.size && running.get()) {
                    val t = uplinkTrack ?: return
                    val n = t.write(next, offset, next.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (n == AudioTrack.ERROR_DEAD_OBJECT && trackRebuilds < 5) { rebuildUplink(); continue }
                    check(n > 0) { "uplink write failed: $n" }
                    offset += n
                }
                if (next !== silence) localTrack?.write(next, 0, next.size, AudioTrack.WRITE_NON_BLOCKING)
            }
        } catch (t: Throwable) {
            if (running.get()) log("playback stopped: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Observer: Sebastian's voice goes to Rayyan's earpiece only; the caller never hears him. */
    private fun playbackToEarOnly() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        try {
            while (running.get()) {
                val next = out.pollFirst()
                if (next == null) { SystemClock.sleep(10); continue }
                speakingUntilNs = SystemClock.elapsedRealtimeNanos() + ECHO_TAIL_NANOS
                tape?.botVoice(next, SystemClock.elapsedRealtimeNanos())
                if (!earFirstLogged) { earFirstLogged = true; log("voice at your ear ${System.currentTimeMillis() - turnHeardMs} ms after the question") }
                if (swap?.engaged == true) {
                    swapVoice.offerLast(next); while (swapVoice.size > 300) swapVoice.pollFirst()
                    swapHoldUntilNs = maxOf(swapHoldUntilNs, SystemClock.elapsedRealtimeNanos() + SWAP_TAIL_NANOS)
                }
                var offset = 0
                while (offset < next.size && running.get()) {
                    val t = localTrack ?: return
                    val n = t.write(next, offset, next.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(n > 0) { "earpiece write failed: $n" }
                    offset += n
                }
            }
        } catch (t: Throwable) {
            if (running.get()) log("earpiece voice stopped: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---- timing of one reply, logged so the next call shows where the seconds go ----
    @Volatile private var turnHeardMs = 0L
    @Volatile private var lastSayMs = 0L
    @Volatile private var ttsFirstLogged = true
    @Volatile private var earFirstLogged = true

    /** Turns the mic hook on in the background (it takes a moment) for a reply that is about to be spoken. */
    private fun ensureSwap() {
        val s = swap ?: return
        swapHoldUntilNs = maxOf(swapHoldUntilNs, SystemClock.elapsedRealtimeNanos() + SWAP_LEAD_NANOS)
        if (s.engaged) return
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            if (s.engage()) log("mic swap: hook on after ${SystemClock.elapsedRealtime() - t0} ms; WhatsApp mic=${MicSwap.whatsAppMicRoute()}")
        }, "sebastian-swap-on").apply { isDaemon = true; start() }
    }

    /** Mixes Sebastian's voice into Rayyan's mic frame and writes it to the hook's track. Runs on the mic capture thread. */
    private fun relaySwap(s: MicSwap, frame: ShortArray, n: Int) {
        val t = s.track ?: return
        val mixed = frame.copyOf(n)
        // His voice waits (queued) until WhatsApp's recorder has followed the hook, so the first words aren't cut.
        if (SystemClock.elapsedRealtimeNanos() - s.engagedAtNs >= SWAP_SETTLE_NANOS)
            swapVoice.pollFirst()?.let { v -> for (i in 0 until minOf(n, v.size)) mixed[i] = clip(mixed[i] + v[i]) }
        val up = swapUp.process(mixed)
        var off = 0
        while (off < up.size && running.get() && s.track === t) {
            val w = t.write(up, off, up.size - off, AudioTrack.WRITE_BLOCKING)
            if (w <= 0) break
            off += w
        }
    }

    /** Drops the hook once Sebastian has been quiet for a while, so WhatsApp has the real mic again. */
    private fun swapKeeper() {
        val s = swap ?: return
        var released = false
        while (running.get()) {
            SystemClock.sleep(250)
            if (s.engaged && SystemClock.elapsedRealtimeNanos() > swapHoldUntilNs) {
                s.release(); swapVoice.clear(); released = true
                log("mic swap: hook off")
            }
            if (released && !s.engaged) {
                released = false
                Thread({ SystemClock.sleep(1500); log("mic swap: 1.5 s after hook off, WhatsApp mic=${MicSwap.whatsAppMicRoute()}") }, "swap-after").apply { isDaemon = true; start() }
            }
        }
        s.release()
    }

    /**
     * One swap on a timer, to learn whether WhatsApp's recorder follows a hook that is registered mid-call and dropped
     * again. Logs WhatsApp's real input before, during and after. config "swap_probe": true.
     */
    private fun swapProbe() {
        val s = swap ?: return
        SystemClock.sleep(12_000)
        if (!running.get()) return
        log("swap probe: before=${MicSwap.whatsAppMicRoute()}")
        swapHoldUntilNs = SystemClock.elapsedRealtimeNanos() + 6_000_000_000L
        val t0 = SystemClock.elapsedRealtime()
        val ok = s.engage()
        log("swap probe: hook ${if (ok) "on" else "FAILED"} after ${SystemClock.elapsedRealtime() - t0} ms")
        for (i in 1..8) {
            SystemClock.sleep(700)
            log("swap probe: +${i * 700} ms engaged=${s.engaged} WhatsApp mic=${MicSwap.whatsAppMicRoute()}")
            if (!running.get()) return
        }
        for (i in 1..6) {
            SystemClock.sleep(1000)
            log("swap probe: after release +${i}s engaged=${s.engaged} WhatsApp mic=${MicSwap.whatsAppMicRoute()}")
            if (!running.get()) return
        }
    }

    private fun sayJoinLine() {
        if (observer) return
        cfg.optString("join_line", DEFAULT_JOIN_LINE).takeIf { it.isNotBlank() }?.let { say(it.replace("{owner}", ownerName)) }
    }

    /** WhatsApp, other side to Rayyan's earpiece, with Sebastian's voice mixed in. Runs on the "them" capture thread. */
    private fun relayDown(frame: ShortArray, n: Int, level: Double) {
        val mixed = frame.copyOf(n)
        localVoice.pollFirst()?.let { v -> for (i in 0 until minOf(n, v.size)) mixed[i] = clip(mixed[i] + v[i]) }
        tape?.ear?.write(mixed, n)
        // Observer: WhatsApp already plays the caller on the earpiece; Sebastian's own track carries only his voice.
        if (!observer) localTrack?.let { t ->
            HopGauge.trackQueued(t, earWritten)?.let { gEar.observe(it) } ?: run { earWritten = HopGauge.head(t) }
            if (!gEar.shouldDrop(n, level < QUIET_RMS)) {
                val w = t.write(mixed, 0, n, AudioTrack.WRITE_BLOCKING)
                if (w > 0) { earWritten += w; aec?.render(mixed, n) }
            }
        }
        meter(themMeter, level)
        synchronized(farHistory) {
            farHistory[farIdx] = level; farIdx = (farIdx + 1) % farHistory.size
            farLevel = farHistory.max()
        }
    }

    /** The other side's first real words ("Hello?"): they've picked up. WhatsApp's ring-back tone is never transcribed. */
    private fun joinNow() {
        if (!joinPending) return
        joinPending = false
        brain.execute { SystemClock.sleep(600); sayJoinLine() }
    }

    /** WhatsApp, Rayyan's mic (plus Sebastian) to WhatsApp's microphone hook at 48 kHz. The mic read paces this loop. */
    private fun relayUp() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val frame = ShortArray(FRAME_SAMPLES)
        val chunk = ShortArray(CHUNK_SAMPLES)
        var filled = 0
        try {
            while (running.get()) {
                val rec = selfRecord ?: return
                val n = rec.read(frame, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING)
                if (n <= 0) { if (!running.get()) return; error("mic read failed: $n") }
                tape?.mic?.write(frame, n)
                // Every frame, before any trimming: AEC3 tracks the echo timing frame by frame. On the phone's own
                // voice-call path the phone cancels the echo already ("soft_aec_with_voice_comm" stacks AEC3 on top).
                if (!hwEchoPath || cfg.optBoolean("soft_aec_with_voice_comm", false)) aec?.let { runCatching { it.process(frame, n) }.onFailure { e -> log("echo canceller failed: ${e.message}") } }
                val micLevel = rms(frame, n)
                micBacklog.read += n
                micBacklog.frames(rec)?.let { gMic.observe(it) }
                // Mic audio piling up unread is delay before the other side hears Rayyan: skip a block.
                if (gMic.shouldDrop(n, micLevel < QUIET_RMS)) continue
                if (SystemClock.elapsedRealtimeNanos() < speakingUntilNs) bargeIn(micLevel, false)
                val voice = out.pollFirst()
                if (voice != null) { speakingUntilNs = SystemClock.elapsedRealtimeNanos() + ECHO_TAIL_NANOS; localVoice.offerLast(voice) }
                meter(micMeter, micLevel)
                // Echo suppressor: the other side is talking and the mic isn't clearly louder than them, so it's their
                // voice coming back off the speaker. Duck it hard (fast down, slower back up) so they don't hear themselves.
                val far = farLevel
                // "echo_duck": false turns this off, to test whether WhatsApp's own echo canceller copes now the relay is fast.
                val echo = echoDuck && far > cfg.optDouble("echo_min_rms", 250.0) && micLevel < far * cfg.optDouble("echo_ratio", 1.0)
                micGain = if (echo) maxOf(0.02, micGain * 0.3) else minOf(1.0, micGain + 0.07)
                if (micGain < 0.999) for (i in 0 until n) frame[i] = (frame[i] * micGain).toInt().toShort()
                if (micGain < 0.3) duckedFrames++
                val mixed = frame.copyOf(n)
                if (voice != null) for (i in 0 until minOf(n, voice.size)) mixed[i] = clip(mixed[i] + voice[i])
                tape?.outgoing?.write(mixed, n)
                val up = upsampler.process(mixed)
                val w0 = SystemClock.elapsedRealtimeNanos()
                val track = uplinkTrack!!
                HopGauge.trackQueued(track, upWritten)?.let { gUp.observe(it) } ?: run {
                    upWritten = HopGauge.head(track); upRebases++
                }
                // A standing queue in WhatsApp's mic track is delay before the other side hears Rayyan: skip this block.
                if (!gUp.shouldDrop(up.size, rms(mixed, mixed.size) < QUIET_RMS)) {
                    var off = 0
                    while (off < up.size && running.get()) {
                        val w = track.write(up, off, up.size - off, AudioTrack.WRITE_BLOCKING)
                        check(w > 0) { "WhatsApp mic write failed: $w" }
                        off += w
                    }
                    upWritten += off
                }
                upFrames++
                if (upFrames % 1000 == 0L) log("latency ${latencyText()} underruns=${track.underrunCount} rebases=$upRebases" +
                    " aec=${aec?.metrics() ?: "off"}")
                upWriteMsMax = maxOf(upWriteMsMax, (SystemClock.elapsedRealtimeNanos() - w0) / 1e6)
                // Rayyan's words to his transcript (silence while Sebastian talks: the mic hears him on the earpiece).
                if (SystemClock.elapsedRealtimeNanos() < speakingUntilNs || micGain < 0.3) java.util.Arrays.fill(frame, 0, n, 0)
                System.arraycopy(frame, 0, chunk, filled, n)
                filled += n
                if (filled >= CHUNK_SAMPLES) { sttSelf?.send(pcmBytes(chunk, filled).toByteString()); filled = 0 }
            }
        } catch (t: Throwable) {
            if (running.get()) log("relay up stopped: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Rayyan's mic for a WhatsApp call. First choice: the real voice-call mic (VOICE_COMMUNICATION), when the WhatsApp
     * hook leaves Sebastian's uid out (WhatsAppTap.selfExcluded). That's the phone's own call path, whose echo
     * canceller uses Sebastian's voice-call earpiece track as its reference: what WhatsApp itself gets on a normal call.
     * Checked: it must be routed to the built-in mic, not the hook (that would loop Sebastian's own output back).
     * Otherwise the plain mic, with the effects attached where the phone has them. */
    private fun openVoiceCommMic(): AudioRecord? {
        val r = runCatching { AudioTools.openRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000) }
            .onFailure { log("voice-call mic failed to open: ${it.message}") }.getOrNull() ?: return null
        if (r.state != AudioRecord.STATE_INITIALIZED) { runCatching { r.release() }; return null }
        val aecFx = runCatching { android.media.audiofx.AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        val nsFx = runCatching { android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        // The route only exists once it records: read a few frames, then look at where it really comes from.
        val probe = ShortArray(FRAME_SAMPLES)
        val routed = runCatching {
            r.startRecording()
            var dev: AudioDeviceInfo? = null
            for (i in 0 until 30) {
                r.read(probe, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING)
                dev = r.routedDevice
                if (dev != null && i >= 5) break
            }
            dev
        }.getOrNull()
        val type = routed?.type ?: -1
        if (type != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            log("voice-call mic not used: routed to device type $type (hook didn't leave Sebastian out)")
            runCatching { r.stop() }; runCatching { r.release() }
            return null
        }
        hwEchoPath = true
        selfLabel = "voice_comm" + (if (aecFx?.enabled == true) "+aec" else "") + (if (nsFx?.enabled == true) "+ns" else "")
        return r
    }

    private fun openSelfVoip(): AudioRecord? {
        // Observer: "observer_hear_me": false leaves WhatsApp the only user of Rayyan's mic (test for a quiet voice).
        if (observer && !cfg.optBoolean("observer_hear_me", true)) { log("observer: Sebastian doesn't record Rayyan's mic (config)"); return null }
        if (cfg.optBoolean("self_voice_comm", true) && WhatsAppTap.selfExcluded) openVoiceCommMic()?.let { return it }
        WhatsAppTap.exclusionError?.let { log("mic hook exclusion unavailable: $it") }
        for (src in listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
            val r = runCatching { AudioTools.openRecord(src, 16_000) }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                // Beside WhatsApp's own mic use (observer), the phone's echo canceller / noise suppressor on this second
                // recorder can pull the shared microphone down: Shahwaiz heard Rayyan quiet and muffled (2026-11-04).
                val effects = cfg.optBoolean("self_effects", !observer)
                val aec = if (!effects) null else runCatching { android.media.audiofx.AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
                val ns = if (!effects) null else runCatching { android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
                selfLabel = (if (src == MediaRecorder.AudioSource.MIC) "mic" else "voice_rec") +
                    (if (aec?.enabled == true) "+aec" else "") + (if (ns?.enabled == true) "+ns" else "")
                return r
            }
            runCatching { r?.release() }
        }
        return null
    }

    private fun rebuildUplink() {
        trackRebuilds++
        runCatching { uplinkTrack?.release() }
        if (uplinkRoute == "call_assistant" && cfg.optString("uplink_route", "auto") == "auto") {
            uplinkRoute = "telephony_tx"
            runCatching { File(SebastianConfig.ROUTE_PATH).writeText(uplinkRoute) }
        }
        uplinkTrack = buildUplink(uplinkRoute).also { it.play() }
        log("uplink track rebuilt on $uplinkRoute")
    }

    // =================================================================== listening (streaming STT)

    private fun connectStt(who: String): WebSocket {
        val q = linkedMapOf(
            "sample_rate" to "16000", "encoding" to "pcm_s16le",
            "speech_model" to cfg.optString("stt_model", "universal-streaming-english"),
            "format_turns" to "true",
            "keyterms_prompt" to JSONArray(listOf("Sebastian") + keyterms()).toString(),
        )
        // How fast the end of a turn is called (docs: min_turn_silence default 400 ms, end_of_turn_confidence_threshold 0.4).
        cfg.optString("stt_min_turn_silence").takeIf { it.isNotBlank() }?.let { q["min_turn_silence"] = it }
        cfg.optString("stt_eot_confidence").takeIf { it.isNotBlank() }?.let { q["end_of_turn_confidence_threshold"] = it }
        val url = "wss://streaming.assemblyai.com/v3/ws?" + q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val req = Request.Builder().url(url).header("Authorization", key).build()
        return CLIENT.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (sttFailures.getAndSet(0) > 0) log("STT ($who) reconnected")
            }
            override fun onMessage(webSocket: WebSocket, text: String) = onStt(who, text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (running.get()) log("STT ($who) failed: ${t.message} http=${response?.code} ${response?.body?.string()?.take(200)}")
                reconnect("STT ($who)") { replaceStt(who, webSocket) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (running.get()) log("STT ($who) closed $code $reason")
                reconnect("STT ($who)") { replaceStt(who, webSocket) }
            }
        })
    }

    private val sttFailures = java.util.concurrent.atomic.AtomicInteger()

    /** Swaps in a fresh socket for [who], unless the one that died is no longer the current one. */
    private fun replaceStt(who: String, dead: WebSocket) {
        val isThem = who == otherName
        if ((if (isThem) sttThem else sttSelf) !== dead) return
        val fresh = connectStt(who)
        if (isThem) sttThem = fresh else sttSelf = fresh
    }

    /**
     * A listening or voice connection that dropped (or never opened: the phone's DNS can fail as a call connects) comes
     * back after a growing pause, for as long as the call lasts. Without it Sebastian stayed deaf and mute.
     */
    private fun reconnect(what: String, again: () -> Unit) {
        if (!running.get()) return
        val k = sttFailures.incrementAndGet().coerceAtMost(5)
        Thread({
            SystemClock.sleep(minOf(8_000L, 500L shl k))
            if (running.get()) runCatching(again).onFailure { log("$what reconnect failed: ${it.message}") }
        }, "sebastian-reconnect").apply { isDaemon = true; start() }
    }

    private fun keyterms(): List<String> {
        val terms = mutableListOf(ownerName)
        if (otherName != "Them") terms += otherName
        cfg.optJSONArray("keyterms")?.let { for (i in 0 until it.length()) terms += it.getString(i) }
        return terms.distinct().take(99)
    }

    private fun onStt(who: String, text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (m.optString("type") != "Turn" || !m.optBoolean("end_of_turn") || !m.optBoolean("turn_is_formatted")) return
        val said = m.optString("transcript").trim()
        if (said.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lines) { lines += Line(now - startedAt, who, said) }
        log("heard $who: ${said.take(80)}")
        if (who == otherName) joinNow()
        heard(who, said, now)
    }

    /** A finished line from someone. Answers only when it's addressed to Sebastian. */
    private fun heard(who: String, said: String, now: Long) {
        waiting?.let { w ->
            waiting = null
            if (w.who == who && now < w.untilMs) { ask(who, "${w.text} $said"); return }
        }
        val m = NAME.find(said) ?: run {
            // Right after he answered, the same person may carry on without his name ("and what about Friday?").
            // Only a question, only from them, only once.
            val sinceVoice = SystemClock.elapsedRealtimeNanos() - speakingUntilNs
            if (followUpOpen && who == lastAsker && said.endsWith("?") &&
                sinceVoice in 0..cfg.optLong("follow_up_ms", 10_000) * 1_000_000) {
                followUpOpen = false
                ask(who, said, followUp = true)
            }
            return
        }
        if (STOP.containsMatchIn(said)) {
            // "Sebastian, shut up": stop talking, no reply.
            waiting = null; followUpOpen = false
            if (out.isNotEmpty() || SystemClock.elapsedRealtimeNanos() < speakingUntilNs) cancelSpeech("told to stop")
            log("told to stop by $who")
            return
        }
        if (HANG_UP.containsMatchIn(said)) {
            // "Sebastian, cut the call": say so, then press WhatsApp's end-call button.
            waiting = null; followUpOpen = false
            cancelSpeech("hanging up")
            log("asked to end the call by $who")
            say("Okay, ending the call.")
            Thread {
                SystemClock.sleep(2_500)
                log(if (tap != null) WhatsAppHangup.hangUp(inCall) else "hang-up only works on WhatsApp calls")
            }.start()
            return
        }
        // The same mention heard twice (both sides of the call, or an echo): answer once.
        if (now - lastAskMs < 3_000) { log("duplicate mention skipped"); return }
        val after = said.substring(m.range.last + 1).trim().trimStart(',', '.', '!', ' ')
        if (after.split(Regex("\\s+")).count { it.isNotBlank() } < 2 && !said.endsWith("?")) {
            // "Hey Sebastian." He says he's listening; the task follows in their next line.
            lastAskMs = System.currentTimeMillis()
            ensureSwap()
            waiting = Waiting(who, said, now + cfg.optLong("listen_window_ms", 15_000))
            val line = cfg.optString("listening_line", "Yeah, I'm listening.")
            if (line.isNotBlank()) brain.execute { say(line) }
            return
        }
        ask(who, said)
    }

    // =================================================================== thinking (LLM Gateway + tools)

    private fun ask(who: String, said: String, followUp: Boolean = false) {
        if (!thinking.compareAndSet(false, true)) { log("already answering; skipped a mention"); return }
        lastAskMs = System.currentTimeMillis(); lastAsker = who; followUpOpen = !followUp
        turnHeardMs = lastAskMs
        // His name was heard: bring the mic hook up now, in parallel with thinking, so it's ready when he speaks.
        if (!followUp) ensureSwap()
        brain.execute {
            // Heard right away, before the LLM: the call knows he's on it while he thinks.
            val acked = !followUp && ack()
            try { answer(who, said, followUp, acked) } catch (t: Throwable) {
                log("brain failed: ${t.javaClass.simpleName}: ${t.message}")
                say("Sorry, I couldn't get that one.")
            } finally { thinking.set(false) }
        }
    }

    private var lastAck = -1

    /** Says one of the short acknowledgements (never the same twice in a row). False when none are set. */
    private fun ack(): Boolean {
        val lines = cfg.optJSONArray("ack_lines")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: DEFAULT_ACK_LINES
        val pool = lines.filter { it.isNotBlank() }
        if (pool.isEmpty()) return false
        val i = if (pool.size == 1) 0 else (0 until pool.size).filter { it != lastAck }.random()
        lastAck = i
        say(pool[i])
        return true
    }

    private fun answer(who: String, said: String, followUp: Boolean = false, acked: Boolean = false) {
        val t0 = SystemClock.elapsedRealtime()
        val line = if (followUp) "Right after your last answer, $who said (without your name): \"$said\". If it's a follow-up for you, answer it. " +
            "If they're talking to each other, reply with exactly SILENT." else "$who just said: \"$said\""
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt()))
            .put(JSONObject().put("role", "user").put("content", line))
        var saidOnIt = acked
        repeat(4) {
            val tLlm = SystemClock.elapsedRealtime()
            val reply = llm(messages)
            log("llm ${SystemClock.elapsedRealtime() - tLlm} ms")
            val calls = reply.second
            if (calls.length() == 0) {
                val text = clean(reply.first)
                log("brain ${SystemClock.elapsedRealtime() - t0} ms")
                if (text.isEmpty() || text.equals("SILENT", ignoreCase = true) || text.startsWith("SILENT")) return
                say(text)
                return
            }
            if (!saidOnIt) { saidOnIt = true; say(cfg.optString("on_it_line", "On it.")) }
            messages.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls))
            var searchAnswer: String? = null
            var onlySearch = true
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.getJSONObject("function")
                val tTool = SystemClock.elapsedRealtime()
                val result = runCatching { SebastianTools.run(cfg, fn.getString("name"), JSONObject(fn.optString("arguments", "{}")), otherName, otherNumber, mcp) }
                    .getOrElse { JSONObject().put("error", "${it.javaClass.simpleName}: ${it.message}") }
                log("tool ${fn.getString("name")} ${SystemClock.elapsedRealtime() - tTool} ms -> ${result.toString().take(160)}")
                if (fn.getString("name") == "web_search" && !result.has("error")) searchAnswer = result.optString("answer").takeIf { it.isNotBlank() }
                else onlySearch = false
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", result.toString()))
            }
            // The search already comes back as a spoken-style answer: say it, instead of a second AI call to rephrase it.
            if (onlySearch && searchAnswer != null && cfg.optBoolean("search_direct", true)) {
                log("brain ${SystemClock.elapsedRealtime() - t0} ms (search answer spoken as is)")
                say(clean(searchAnswer))
                return
            }
        }
    }

    /** One LLM Gateway call: the reply text and any tool calls (each may come back in its own choice). */
    private fun llm(messages: JSONArray): Pair<String, JSONArray> {
        val body = JSONObject()
            .put("model", cfg.optString("llm_model", "gemma-4-31b"))
            .put("messages", messages)
            .put("max_tokens", cfg.optInt("llm_max_tokens", 300))
            .put("tools", SebastianTools.schema(mcp))
            .put("tool_choice", "auto")
        cfg.optJSONObject("llm_extra")?.let { extra -> extra.keys().forEach { k -> body.put(k, extra.get(k)) } }
        val req = Request.Builder().url(cfg.optString("llm_url", "https://llm-gateway.assemblyai.com/v1/chat/completions"))
            .header("authorization", key)
            .post(body.toString().toRequestBody(JSON))
            .build()
        CLIENT.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            check(r.isSuccessful) { "LLM Gateway HTTP ${r.code}: ${raw.take(300)}" }
            val choices = JSONObject(raw).optJSONArray("choices") ?: JSONArray()
            val text = StringBuilder()
            val calls = JSONArray()
            for (i in 0 until choices.length()) {
                val msg = choices.getJSONObject(i).optJSONObject("message") ?: continue
                if (!msg.isNull("content")) text.append(msg.optString("content"))
                msg.optJSONArray("tool_calls")?.let { tc -> for (j in 0 until tc.length()) calls.put(tc.get(j)) }
            }
            return text.toString() to calls
        }
    }

    private fun systemPrompt(): String {
        val zone = ZoneId.of(cfg.optString("timezone", "Asia/Karachi"))
        val today = ZonedDateTime.now(zone)
        // Models slip on weekday arithmetic ("Friday" booked as Saturday): spell out the coming week.
        val week = (1..7).joinToString(", ") { today.plusDays(it.toLong()).format(DateTimeFormatter.ofPattern("EEEE d MMMM = yyyy-MM-dd")) }
        val now = today.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a")) + ". Coming days: $week"
        val transcript = synchronized(lines) {
            lines.joinToString("\n") { "[${it.atMs / 60000}:${"%02d".format(it.atMs / 1000 % 60)}] ${it.who}: ${it.text}" }
        }.takeLast(cfg.optInt("context_chars", 12_000))
        val base = cfg.optString("system_prompt").ifBlank { DEFAULT_PROMPT }
        return base.replace("{owner}", ownerName).replace("{other}", otherName).replace("{now}", now)
            .replace("{timezone}", zone.id).replace("{location}", cfg.optString("location", "Lahore, Pakistan")) + "\n\nThe call so far:\n" + transcript.ifEmpty { "(nothing yet)" }
    }

    // =================================================================== speaking (streaming TTS)

    private fun connectTts() {
        val q = "voice_id=${URLEncoder.encode(cfg.optString("voice_id", "aai-peter"), "UTF-8")}" +
            "&sample_rate=16000&encoding=pcm_s16le&language=english&word_boundaries=false&inactivity_timeout=120"
        val req = Request.Builder().url("wss://tts.us.assemblyai.com/v1/ws?$q").header("Authorization", key).build()
        tts = CLIENT.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { ttsOpen = true }
            override fun onMessage(webSocket: WebSocket, text: String) = onTts(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onTts(bytes.utf8())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ttsOpen = false
                if (running.get()) log("TTS failed: ${t.message} http=${response?.code} ${response?.body?.string()?.take(200)}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ttsOpen = false }
        })
    }

    private fun onTts(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (m.optString("type")) {
            "Audio" -> {
                if (dropTts) return
                if (!ttsFirstLogged) { ttsFirstLogged = true; log("tts first audio ${System.currentTimeMillis() - lastSayMs} ms after say") }
                val bytes = ttsCarry + Base64.decode(m.optString("audio"), Base64.DEFAULT)
                val whole = bytes.size / (FRAME_SAMPLES * 2) * (FRAME_SAMPLES * 2)
                var i = 0
                while (i < whole) {
                    val f = ShortArray(FRAME_SAMPLES) { k -> clip((((bytes[i + 2 * k].toInt() and 255) or (bytes[i + 2 * k + 1].toInt() shl 8)).toShort() * voiceGain).toInt()) }
                    out.offerLast(f); i += FRAME_SAMPLES * 2
                }
                ttsCarry = bytes.copyOfRange(whole, bytes.size)
            }
            "Cancelled" -> { dropTts = false; ttsCarry = ByteArray(0) }
            "FlushDone" -> {
                // Pad the last partial frame so the reply's final syllable isn't lost.
                if (ttsCarry.isNotEmpty() && !dropTts) {
                    val f = ShortArray(FRAME_SAMPLES)
                    for (k in 0 until ttsCarry.size / 2) f[k] = ((ttsCarry[2 * k].toInt() and 255) or (ttsCarry[2 * k + 1].toInt() shl 8)).toShort()
                    out.offerLast(f)
                }
                ttsCarry = ByteArray(0)
            }
            "Error" -> log("TTS error: ${m.optString("error_code")} ${m.optString("error").take(200)}")
        }
    }

    /** Speaks [text] into the call (and Rayyan's earpiece), and notes it in the transcript. */
    fun say(text: String) {
        val clean = clean(text)
        if (clean.isEmpty() || !running.get()) return
        ensureSwap()
        lastSayMs = System.currentTimeMillis(); ttsFirstLogged = false; earFirstLogged = false
        log("say +${lastSayMs - turnHeardMs} ms after the question: ${clean.take(40)}")
        if (!ttsOpen) {
            connectTts()
            val until = SystemClock.elapsedRealtime() + 6_000
            while (!ttsOpen && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20)
        }
        dropTts = false
        clean.chunked(1_900).forEach { tts?.send(JSONObject().put("type", "Generate").put("text", it).toString()) }
        tts?.send("""{"type":"Flush"}""")
        spokenReplies++
        synchronized(lines) { lines += Line(System.currentTimeMillis() - startedAt, "Sebastian", clean) }
    }

    private fun keepAlive() {
        while (running.get()) {
            SystemClock.sleep(30_000)
            if (running.get() && ttsOpen) runCatching { tts?.send("""{"type":"KeepAlive"}""") }
        }
    }

    companion object {
        private const val TAG = "Sebastian"
        const val FRAME_SAMPLES = 160          // 10 ms at 16 kHz
        const val CHUNK_SAMPLES = 800          // 50 ms per STT message (the API takes 50-1000 ms)
        private const val ECHO_TAIL_NANOS = 400_000_000L
        /** Mic hook: stays on this long after Sebastian's last sound, and at least this long from a reply being started. */
        private const val SWAP_TAIL_NANOS = 1_500_000_000L
        private const val SWAP_LEAD_NANOS = 8_000_000_000L
        /** After the hook comes up, WhatsApp's recorder follows within ~0.7 s (measured); his voice waits this long. */
        private const val SWAP_SETTLE_NANOS = 900_000_000L
        /** Below this a 10 ms block is near silence: the best place to drop audio unheard. */
        private const val QUIET_RMS = 300.0
        private fun clip(v: Int): Short = v.coerceIn(-32_767, 32_767).toShort()
        private val JSON = "application/json".toMediaType()
        /** "Sebastian" and the ways speech-to-text tends to spell it. */
        val NAME = Regex("\\b(sebastian|sebastien|sebastián|sabastian|sebastion|bastian)\\b", RegexOption.IGNORE_CASE)
        /** "Sebastian, shut up / stop / be quiet": he goes silent. */
        val STOP = Regex("\\b(shut up|stop( talking)?|be quiet|quiet|enough|not now|keep quiet)\\b", RegexOption.IGNORE_CASE)
        val HANG_UP = Regex("\\b((cut|end|drop|disconnect|finish|close) (the |this )?call|hang up|hang the call)\\b", RegexOption.IGNORE_CASE)

        val DEFAULT_ACK_LINES = listOf("One sec.", "Sure, one moment.", "Got it, give me a second.")

        const val DEFAULT_JOIN_LINE = "Hi, Sebastian here, taking notes for {owner}. Say my name if you need me."

        const val DEFAULT_PROMPT = """You are Sebastian, an AI teammate sitting silently in a live phone call between {owner} and {other}. You take notes and speak only when someone addresses you by name.
It is {now} ({timezone}). {owner} is in {location}: when a question needs a place and none is named (weather, nearby, local time), use {location}.
Rules for every reply:
- You are heard on a phone line, so reply in one to three short spoken sentences. No lists, no markdown, no emojis, no dashes.
- Answer from the call so far when the question is about the call ("what did we say the price was?"). Quote numbers exactly as they were said. If it wasn't said, say so.
- Use a tool when they ask you to book, schedule, search or look something up: web_search for anything on the internet, read_notion_doc for the team's own docs. Never invent a tool result.
- Meetings: if they agree an exact day and time, use book_calendar_event (it adds a Google Meet link and WhatsApps it to {other}). If they want to meet but no time is fixed, use send_booking_link (Rayyan's Calendly). Then say in one sentence what you sent and to whom, exactly as the tool result says.
- If they ask for something you can't do from a phone call, say so in one sentence.
- If the latest line only mentions you in passing and doesn't ask or tell you anything ("Sebastian was useful yesterday"), reply with exactly SILENT.
- Never repeat the question back. Be warm, calm and direct."""

        fun clean(s: String) = s.replace("—", ", ").replace("–", ", ").replace(Regex("[*_#`]"), "").replace(Regex("\\s+"), " ").trim()

        private fun rms(pcm: ShortArray, n: Int): Double {
            if (n <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) sum += pcm[i].toDouble() * pcm[i]
            return Math.sqrt(sum / n)
        }

        private fun pcmBytes(pcm: ShortArray, n: Int): ByteArray {
            val b = ByteArray(n * 2)
            for (i in 0 until n) { b[2 * i] = pcm[i].toInt().toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
            return b
        }

        val CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .dns(RetryDns).pingInterval(20, TimeUnit.SECONDS).build()
        }
    }
}
