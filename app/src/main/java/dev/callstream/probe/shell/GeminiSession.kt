package dev.callstream.probe.shell

import android.content.Context
import android.media.*
import android.os.Process
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import javax.net.SocketFactory

/**
 * One AI call: the phone talks to Gemini Live directly over a WebSocket. No laptop, relay or server of
 * ours in between. Caller audio from the carrier downlink goes up as 16 kHz PCM the moment it's read;
 * Gemini's 24 kHz reply is resampled and written into the carrier uplink.
 *
 * Gemini decides turns and interruptions itself (server-side voice activity detection): on `interrupted`
 * the queued reply is dropped at once. Its audio arrives faster than real time, so the output queue is a
 * playout buffer, paced by the carrier track.
 */
internal class GeminiSession {
    private val running = AtomicBoolean(false)
    @Volatile private var state = "STOPPED"
    @Volatile private var worker: Thread? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var captured = 0L
    @Volatile private var played = 0L
    @Volatile private var captureRms = 0.0
    @Volatile private var playbackRms = 0.0
    @Volatile private var remotePeak = 0
    @Volatile private var sentFrames = 0L
    @Volatile private var metrics: CallLatencyTrace? = null
    /** [ack]: an acknowledgement clip played by the phone (AckPlayer), not Gemini audio. [synthetic]: silence the
     * uplink thread made up because nothing was queued. */
    private data class PcmFrame(val pcm: ShortArray, val queuedNs: Long = SystemClock.elapsedRealtimeNanos(), val turn: Int? = null,
                                val ack: Boolean = false, val ackFirst: Boolean = false, val rms: Double = rmsOf(pcm),
                                val synthetic: Boolean = false)
    /** Gemini's reply, resampled to 10 ms frames. Its head is edited only by the uplink thread. */
    private val outputFrames = LinkedBlockingDeque<PcmFrame>()
    private val outputDrops = AtomicLong()
    private val sendFailures = AtomicLong()
    private val echoGated = AtomicLong()
    private val interruptions = AtomicLong()
    /** Set by the WebSocket thread on `interrupted`; the uplink thread drops the queued reply. */
    private val pendingFlush = AtomicBoolean(false)
    /** Acknowledgement chosen on the capture thread, inserted by the uplink thread. */
    private val pendingAck = AtomicReference<ShortArray?>()
    private val ackCuts = AtomicLong()
    private val uplinkStalls = AtomicLong()
    @Volatile private var lastAgentWrittenNs = 0L
    /** Last energetic audio of any kind (agent or acknowledgement) written: the track isn't idle. */
    @Volatile private var lastEnergyWrittenNs = 0L
    @Volatile private var carrierStarted = false
    @Volatile private var carrierStartedNs = Long.MAX_VALUE
    @Volatile private var setupDone = false
    @Volatile private var agentSpoke = false
    @Volatile private var echo = EchoProbe()
    @Volatile private var quality = CaptureQuality()
    @Volatile private var onsetsRx = AgentOnsets()
    @Volatile private var ducking: DuckingCompensator? = null
    /** Boost ducked caller speech (config ducking_boost); [ducking] also exists for the echo gate alone. */
    @Volatile private var duckingBoost = false
    /** Config turn_detection "phone": the phone decides the caller's turns (capture thread only). */
    @Volatile private var phoneTurns: PhoneTurns? = null
    /** Config save_caller_audio (test calls): every caller frame sent to Gemini, saved (capture thread writes). */
    @Volatile private var callerAudio: CallerAudioRecorder? = null
    /** Config agent_gain_db: the agent's voice is scaled by this before it enters the call (0 = Gemini's own level).
     * Test of 2026-10-18: callers were muffled while talking over the agent, and echo suppressors clamp harder on a loud far end. */
    @Volatile private var agentGain = 1.0
    /** Capture thread: the last frames sent once a phone turn starts, so Gemini hears its beginning. */
    private val preroll = ArrayDeque<ShortArray>()
    private val phoneStarts = AtomicLong()
    /** Gemini is still sending the current reply's audio (WebSocket thread writes). */
    @Volatile private var generating = false
    /** The caller cut in (phone turns) mid-reply: audio of that reply still arriving is dropped until Gemini confirms. */
    @Volatile private var staleReply = false
    /** The agent's current reply: its transcript text and audio queued so far (for the interruption note), and how
     * many replies have finished (0: the greeting is playing). Text guarded by itself; WebSocket thread writes. */
    private val turnText = StringBuilder()
    @Volatile private var turnAudioMs = 0.0
    @Volatile private var turnEnded = false
    @Volatile private var agentTurnsDone = 0
    @Volatile private var echoGate = true
    @Volatile private var ack: AckPlayer? = null
    @Volatile private var lastAgentRxEnergyNs = 0L
    /** Gemini closed the session (or failed) mid-call. */
    @Volatile private var remoteEnded = false
    @Volatile private var remoteEndReason = ""
    /** Gemini asked to hang up (end_call); the session drains the goodbye and finishes. */
    @Volatile private var endRequested = false
    @Volatile private var endRequestedNs = 0L
    @Volatile private var endedByAgent = false
    @Volatile private var trackWritten = 0L
    private var trackRebuilds = 0
    private var recordRebuilds = 0
    /** How agent audio enters the carrier uplink. Pixel: call-assistant track. HyperOS/MediaTek kills that
     * track (ERROR_DEAD_OBJECT) but accepts a voice-communication track aimed at the telephony output. */
    private enum class UplinkRoute { CALL_ASSISTANT, TELEPHONY_TX }
    @Volatile private var uplinkRoute = UplinkRoute.CALL_ASSISTANT
    @Volatile private var uplinkRouteAuto = true
    /** Outbound calls: the controller says when the callee answered (audio mode is IN_CALL from dialing). */
    @Volatile private var carrierGate: (() -> Boolean)? = null
    /** A WhatsApp call: caller and agent audio go through its mixes instead of the carrier path. Owned by the controller. */
    @Volatile private var voip: VoipCallAudio? = null
    /** Outbound: the contact's name from the uploaded list, for the greeting (sent to Gemini, never logged). */
    @Volatile private var contactName: String? = null
    /** Outbound callbacks: what the earlier calls settled (OutboundRun.notes), for the opening. */
    @Volatile private var contactNotes: String? = null
    /** Both sides as Gemini transcribed them, in order ("Agent"/"Lead", text). Kept in memory for the post-call
     * extraction only; never logged or written to the trace. */
    private val transcript = ArrayList<Pair<String, String>>()
    /** When each [transcript] line started (trace ms), for the saved transcript (config save_transcripts). */
    private val transcriptAt = ArrayList<Double>()
    @Volatile private var config = JSONObject()
    @Volatile private var trackBufferFrames = TRACK_BUFFER_FRAMES
    /** The window's bounds: the carrier's, or VoIP's (the remote submix mixer pulls 512 frames a cycle). */
    @Volatile private var trackMinFrames = TRACK_BUFFER_MIN_FRAMES
    @Volatile private var trackMaxFrames = TRACK_BUFFER_MAX_FRAMES
    /**
     * Track frames per 16 kHz frame: 3 for WhatsApp's 48 kHz injector, else 1. Everything above the track (queue,
     * windows, pacing) counts 16 kHz frames; writes are upsampled and the track's own positions divided by this.
     */
    @Volatile private var trackScale = 1
    private val upsampler = Upsampler16To48()
    private var underrunBase = 0
    @Volatile private var uplinkLowLatency = false
    // WebSocket thread only: resampling, and leading silence of each reply.
    private val resampler = Resampler24To16()
    private var pendingSamples = ShortArray(0)
    private var replyStart = true
    // Usage and estimated cost, from Gemini's usageMetadata (numbers only).
    @Volatile private var costUsd = 0.0
    @Volatile private var promptTokens = 0L
    @Volatile private var responseTokens = 0L
    /** Preflight: when the synthetic caller line ended, and whether Gemini answered it. */
    @Volatile private var synthEndNs = 0L
    @Volatile private var answeredSynth = false
    /** The last preflight: ms from the synthetic caller's last word to Gemini's reply; -1 if it didn't answer. */
    @Volatile var preflightReplyMs = -1.0
        private set
    /** Called (on the WebSocket thread) once the session is set up and the greeting requested: answer now. */
    @Volatile var onReady: (() -> Unit)? = null

    /** prepare=true connects to Gemini immediately (while the phone rings) and starts carrier audio once the
     * call becomes active. Otherwise non-preflight sessions wait for an active call first. */
    @Synchronized fun arm(preflight: Boolean = false, trace: CallLatencyTrace? = null, prepare: Boolean = false,
                          carrierGate: (() -> Boolean)? = null, contactName: String? = null, contactNotes: String? = null,
                          voip: VoipCallAudio? = null): String {
        if (running.get() || worker?.isAlive == true) return status()
        check(File(CONFIG_PATH).canRead()) { "No agent profiles on this phone yet: open the app once" }
        captured = 0; played = 0; remotePeak = 0; sentFrames = 0; captureRms = 0.0; playbackRms = 0.0
        outputFrames.clear(); outputDrops.set(0); sendFailures.set(0); echoGated.set(0); interruptions.set(0)
        pendingFlush.set(false); pendingAck.set(null); ackCuts.set(0); uplinkStalls.set(0)
        phoneTurns = null; callerAudio = null; agentGain = 1.0; preroll.clear(); phoneStarts.set(0); generating = false; staleReply = false
        synchronized(turnText) { turnText.setLength(0) }; turnAudioMs = 0.0; turnEnded = false; agentTurnsDone = 0
        lastAgentWrittenNs = 0L; lastEnergyWrittenNs = 0L; lastAgentRxEnergyNs = 0L
        carrierStarted = false; carrierStartedNs = Long.MAX_VALUE; setupDone = false; agentSpoke = false
        echo = EchoProbe(); quality = CaptureQuality(); onsetsRx = AgentOnsets()
        remoteEnded = false; remoteEndReason = ""; endRequested = false; endedByAgent = false
        trackWritten = 0; trackRebuilds = 0; recordRebuilds = 0; trackBufferFrames = TRACK_BUFFER_FRAMES; underrunBase = 0
        resampler.reset(); upsampler.reset(); trackScale = 1; pendingSamples = ShortArray(0); replyStart = true
        costUsd = 0.0; promptTokens = 0; responseTokens = 0; synthEndNs = 0L; answeredSynth = false; preflightReplyMs = -1.0
        this.carrierGate = carrierGate
        this.voip = voip
        this.contactName = contactName
        this.contactNotes = contactNotes
        synchronized(transcript) { transcript.clear(); transcriptAt.clear() }
        metrics = trace ?: CallLatencyTrace()
        metrics!!.mark("session_armed", mapOf("preflight" to preflight, "prepare" to prepare))
        running.set(true)
        state = when {
            preflight -> "PREFLIGHT — direct Gemini connection, synthetic caller, no carrier audio"
            prepare -> "PREPARING — connecting Gemini before answer"
            else -> "ARMED — waiting for a carrier call"
        }
        worker = Thread({ runSession(preflight, prepare) }, "gemini-session").also { it.start() }
        return status()
    }
    /** The last call's transcript (empty unless the config turned transcription on); clears it. */
    fun takeTranscript(): List<Pair<String, String>> = synchronized(transcript) { transcript.toList().also { transcript.clear(); transcriptAt.clear() } }
    /** The settings the last call ran with (AgentConfig.resolve), including the key for the post-call extraction. */
    fun callConfig(): JSONObject = config
    /** Gemini ended the conversation (end_call), after its goodbye was played: the controller should hang up. */
    fun endedByAgent(): Boolean = endedByAgent
    /** True while the session is alive (connecting, waiting for carrier, or streaming). */
    fun isLive(): Boolean = running.get()
    /** Set up and greeting requested: its first audio arrives about when an answered call goes active. */
    fun readyToAnswer(): Boolean = running.get() && setupDone
    fun status(): String =
        "$state\ncaptureSamples=$captured playbackSamples=$played captureRms=%.1f playbackRms=%.1f remotePeak=$remotePeak sentFrames=$sentFrames cost≈$%.4f\n${metrics?.lastSummary ?: "latency: no session"}"
            .format(captureRms, playbackRms, costUsd)
    @Synchronized fun stop(): String {
        running.set(false)
        if (worker?.isAlive == true) metrics?.mark("stop_requested")
        runCatching { record?.stop() }
        runCatching { track?.pause() }; runCatching { track?.flush() }
        worker?.join(1500)
        state = "STOPPED"
        return status()
    }
    private fun active(am: AudioManager) = if (voip != null) am.mode == AudioManager.MODE_IN_COMMUNICATION else am.mode in intArrayOf(2, 4, 5)

    /** Opens the WebSocket and sends the setup. Messages are handled on OkHttp's reader thread. */
    private fun connect(timing: CallLatencyTrace, preflight: Boolean) {
        val key = config.optString("gemini_api_key").ifBlank { error("No Gemini API key on this phone: add one in the app's Settings") }
        val setup = GeminiProtocol.setup(config)
        timing.mark("ws_connect_start")
        val request = Request.Builder().url(GeminiProtocol.url(config.optString("api_version", "v1beta"))).header("x-goog-api-key", key).build()
        ws = CLIENT.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                timing.mark("ws_open", mapOf("protocol" to response.protocol.toString()))
                webSocket.send(setup)
                timing.mark("setup_sent", mapOf("bytes" to setup.length))
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text, timing, preflight)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8(), timing, preflight)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closed("closing $code ${reason.take(200)}", timing)
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed("closed $code ${reason.take(200)}", timing)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                closed("failure ${t.javaClass.simpleName}: ${t.message?.take(200)} http=${response?.code}", timing)
        })
    }

    private fun closed(reason: String, timing: CallLatencyTrace) {
        if (remoteEnded) return
        timing.mark("ws_closed", mapOf("reason" to reason, "after_end_call" to endRequested))
        remoteEndReason = reason
        if (!running.get()) return
        if (!endRequested) state = "FINISHED — Gemini closed the session ($reason)"
        // Mid-call: keep the playback thread running so the agent's last words drain.
        if (carrierStarted) remoteEnded = true else running.set(false)
    }

    private fun handle(webSocket: WebSocket, text: String, timing: CallLatencyTrace, preflight: Boolean) {
        val m = runCatching { GeminiProtocol.parse(text) }.getOrElse {
            timing.mark("gemini_parse_failed", mapOf("error_type" to it.javaClass.simpleName)); return
        }
        if (m.empty) return
        if (m.setupComplete) {
            timing.mark("setup_complete")
            // Who placed the call (the greeting differs), the contact's name, and Pakistan's date and time (visit days).
            val context = GeminiProtocol.callContext(config.optString("kickoff_text", GeminiProtocol.KICKOFF),
                outbound = carrierGate != null, contactName = contactName, now = java.time.ZonedDateTime.now(GeminiProtocol.PAKISTAN), notes = contactNotes)
            webSocket.send(if (config.optString("kickoff_mode") == "realtime_text") GeminiProtocol.kickoffRealtime(context) else GeminiProtocol.kickoff(context))
            timing.mark("greeting_requested")
            setupDone = true
            state = if (preflight) "PREFLIGHT — connected" else "READY — Gemini connected; greeting requested"
            onReady?.invoke()
        }
        m.voiceActivity?.let { timing.event("gemini_voice_activity", mapOf("type" to it, "audio_offset" to (m.voiceActivityOffset ?: ""))) }
        // Audio of a reply the caller already cut off (phone turns) until Gemini says it stopped.
        val stale = staleReply
        if (m.interrupted || m.turnComplete || m.generationComplete) { staleReply = false; generating = false }
        if (stale && m.audio.isNotEmpty()) {
            resampler.reset(); pendingSamples = ShortArray(0); replyStart = true
            timing.event("stale_reply_dropped", mapOf("ms" to m.audio.sumOf { it.size } * 1000.0 / m.audioRate))
        }
        if (!stale && m.audio.isNotEmpty() && !m.generationComplete && !m.turnComplete) generating = true
        if (m.interrupted) {
            interruptions.incrementAndGet()
            resampler.reset(); pendingSamples = ShortArray(0); replyStart = true
            pendingFlush.set(true)
            timing.event("gemini_interrupted", mapOf("output_queued_ms" to queuedMs()))
        }
        if (!stale) for (pcm in m.audio) {
            if (!timing.marked("first_gemini_audio")) timing.mark("first_gemini_audio", mapOf("rate" to m.audioRate, "samples" to pcm.size))
            val mono = if (m.audioRate == 24_000) resampler.process(pcm) else if (m.audioRate == 16_000) pcm else {
                timing.mark("gemini_unexpected_rate", mapOf("rate" to m.audioRate)); continue
            }
            var all = if (pendingSamples.isEmpty()) mono else pendingSamples + mono
            var offset = 0
            while (all.size - offset >= FRAME_SAMPLES) {
                agentFrame(all.copyOfRange(offset, offset + FRAME_SAMPLES), timing, preflight)
                offset += FRAME_SAMPLES
            }
            pendingSamples = all.copyOfRange(offset, all.size)
        }
        if (m.generationComplete) timing.event("gemini_generation_complete", mapOf("output_queued_ms" to queuedMs()))
        if (m.turnComplete) { replyStart = true; timing.event("gemini_turn_complete") }
        if (m.turnComplete || m.interrupted) { if (!turnEnded) agentTurnsDone++; turnEnded = true }
        m.usage?.let { u ->
            val turnCost = GeminiProtocol.costUsd(u)
            costUsd += turnCost; promptTokens += u.optLong("promptTokenCount"); responseTokens += u.optLong("responseTokenCount")
            timing.event("gemini_usage", mapOf("prompt_tokens" to u.optLong("promptTokenCount"), "response_tokens" to u.optLong("responseTokenCount"),
                "thoughts_tokens" to u.optLong("thoughtsTokenCount"), "turn_cost_usd" to turnCost, "call_cost_usd" to costUsd))
        }
        for ((id, name) in m.functionCalls) {
            timing.event("gemini_tool_call", mapOf("name" to name))
            if (name == GeminiProtocol.END_CALL) requestEnd(timing, "end_call") else webSocket.send(GeminiProtocol.toolResponse(id, name))
        }
        if (m.disconnectTag) requestEnd(timing, "transcript_disconnect_tag")
        m.callerText?.let { addTranscript("Lead", it, timing.nowMs()) }
        m.agentText?.let { piece ->
            val clean = piece.replace(Regex("<disconnect\\s*/?>"), "")
            newReplyIfEnded(); synchronized(turnText) { turnText.append(clean) }
            addTranscript("Agent", clean, timing.nowMs())
        }
        m.goAwayTimeLeft?.let { timing.mark("gemini_go_away", mapOf("time_left" to it)) }
    }

    /** Gemini sends transcription in pieces: consecutive pieces from one side join into one line. */
    private fun addTranscript(who: String, piece: String, atMs: Double) {
        if (piece.isBlank()) return
        synchronized(transcript) {
            val last = transcript.lastOrNull()
            if (last != null && last.first == who) transcript[transcript.size - 1] = who to (last.second + piece)
            else { transcript += who to piece; transcriptAt += atMs }
        }
    }

    /** Config save_transcripts (test calls): this call's transcript, with times matching its trace, beside the traces. */
    private fun saveTranscript(timing: CallLatencyTrace) {
        val lines = JSONArray()
        synchronized(transcript) {
            transcript.forEachIndexed { i, (who, text) -> lines.put(JSONObject().put("t_ms", transcriptAt.getOrNull(i) ?: 0.0).put("who", who).put("text", text.trim())) }
        }
        if (lines.length() == 0) return
        runCatching {
            val dir = File(TRANSCRIPT_DIR).apply { mkdirs() }
            File(dir, "${timing.id}.json").apply {
                writeText(JSONObject().put("trace", timing.id).put("profile", config.optString("profile")).put("lines", lines).toString(1))
                setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true)
            }
            timing.mark("transcript_saved", mapOf("lines" to lines.length()))
        }.onFailure { timing.mark("transcript_save_failed", mapOf("error" to "${it.javaClass.simpleName}: ${it.message}".take(200))) }
    }

    private fun requestEnd(timing: CallLatencyTrace, how: String) {
        if (endRequested) return
        endRequestedNs = SystemClock.elapsedRealtimeNanos()
        endRequested = true
        timing.mark("agent_end_requested", mapOf("how" to how, "output_queued_ms" to queuedMs()))
    }

    private fun runSession(preflight: Boolean, prepare: Boolean) {
        val timing = metrics!!
        var captureThread: Thread? = null
        var playbackThread: Thread? = null
        var qualityThread: Thread? = null
        try {
            val ctx = AudioTools.shellContext()
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (!preflight && !prepare) {
                // Legacy one-call arm: don't hold an agent session open while waiting for a call.
                val armDeadline = SystemClock.elapsedRealtime() + 15 * 60_000
                while (running.get() && !active(am)) {
                    check(SystemClock.elapsedRealtime() < armDeadline) { "arming expired after 15 minutes" }
                    Thread.sleep(100)
                }
            }
            if (!running.get()) return
            // The profile and voice picked in the app (AgentConfig), applied from this call on.
            config = AgentConfig.resolve(AgentConfig.readConfig(), AgentConfig.readSelection(), AgentConfig.readKey())
            state = "CONNECTING — phone WebSocket → Gemini"
            networkSnapshot()?.let { timing.event("network", it + ("phase" to "start")) }
            connect(timing, preflight)
            duckingBoost = !preflight && config.optBoolean("ducking_boost", true)
            // WhatsApp: the room mic and the earpiece are both out of the call, so there's no echo to gate.
            echoGate = !preflight && voip == null && config.optBoolean("echo_gate", true)
            ducking = if (duckingBoost || echoGate) DuckingCompensator() else null
            phoneTurns = if (GeminiProtocol.phoneTurns(config)) PhoneTurns(config.optDouble("turn_speech_rms", PhoneTurns.SPEECH_RMS),
                config.optDouble("turn_start_ms", PhoneTurns.START_MS), config.optDouble("vad_silence_ms", PhoneTurns.SILENCE_MS),
                config.optDouble("turn_end_rms", PhoneTurns.END_RMS)) else null
            agentGain = Math.pow(10.0, config.optDouble("agent_gain_db", 0.0).coerceIn(-30.0, 6.0) / 20.0)
            callerAudio = if (!preflight && config.optBoolean("save_caller_audio")) runCatching {
                val dir = File(AUDIO_DIR).apply { mkdirs() }
                CallerAudioRecorder(File(dir, "${timing.id}.wav"), File(dir, "${timing.id}.json"))
            }.onFailure { timing.mark("caller_audio_failed", mapOf("error" to "${it.javaClass.simpleName}: ${it.message}".take(200))) }.getOrNull() else null
            // Off by default: Gemini replies ~1 s after the caller stops, about when a clip would play.
            ack = if (!preflight && config.optBoolean("ack", false)) runCatching { AckPlayer(loadAckClips(ctx), config.optDouble("ack_silence_ms", AckPlayer.SILENCE_MS),
                config.optDouble("ack_agent_idle_ms", AckPlayer.AGENT_IDLE_MS)) }
                .onFailure { timing.mark("ack_clips_failed", mapOf("error" to "${it.javaClass.simpleName}: ${it.message}".take(200))) }
                .getOrNull() else null
            val urgentAudio = config.optBoolean("urgent_audio_priority", true)
            // WhatsApp: drained below one 32 ms submix pull, the track underruns as each reply starts.
            val idleTargetFrames = (config.optDouble("idle_track_ms", IDLE_TRACK_MS) * 16).toLong()
                .let { if (voip != null) maxOf(it, VOIP_IDLE_TRACK_FRAMES) else it }
            // WhatsApp: the remote submix mixer pulls 512 frames (32 ms) a cycle, so a 30-40 ms window underran all
            // through the agent's speech (1,332 underruns in 82 s, 2026-10-25); keep two cycles at least.
            trackMinFrames = if (voip != null) VOIP_TRACK_MIN_FRAMES else TRACK_BUFFER_MIN_FRAMES
            trackMaxFrames = if (voip != null) VOIP_TRACK_MAX_FRAMES else TRACK_BUFFER_MAX_FRAMES
            trackBufferFrames = (config.optDouble("track_buffer_ms", TRACK_BUFFER_MS) * 16).toInt().coerceIn(trackMinFrames, trackMaxFrames)
            val maxCallMs = config.optLong("max_call_ms", MAX_CALL_MS)
            // Every setting in effect, so a trace always says how the call was configured (never the key or prompt).
            timing.mark("audio_processing", mapOf("profile" to config.optString("profile", "none"), "model" to config.optString("gemini_model", GeminiProtocol.DEFAULT_MODEL),
                "voice" to config.optString("gemini_voice", GeminiProtocol.DEFAULT_VOICE),
                "thinking_budget" to config.optInt("thinking_budget", GeminiProtocol.DEFAULT_THINKING_BUDGET),
                "affective_dialog" to config.optBoolean("affective_dialog"),
                "api_version" to config.optString("api_version", "v1beta"), "kickoff_mode" to config.optString("kickoff_mode", "client_content"),
                "vad_prefix_padding_ms" to config.optInt("vad_prefix_padding_ms", -1),
                "vad_silence_ms" to config.optInt("vad_silence_ms", GeminiProtocol.DEFAULT_SILENCE_MS),
                "vad_start_sensitivity" to config.optString("vad_start_sensitivity", "default"),
                "vad_end_sensitivity" to config.optString("vad_end_sensitivity", "default"),
                "prompt_chars" to config.optString("system_prompt").length,
                "ducking_boost" to (ducking != null), "echo_gate" to echoGate, "ack" to (ack != null),
                "ack_silence_ms" to config.optDouble("ack_silence_ms", AckPlayer.SILENCE_MS),
                "urgent_audio_priority" to urgentAudio, "idle_track_ms" to idleTargetFrames / 16.0,
                "track_buffer_ms" to trackBufferFrames / 16.0, "uplink_low_latency" to config.optBoolean("uplink_low_latency", true),
                "max_call_ms" to maxCallMs, "preopen_carrier_audio" to config.optBoolean("preopen_carrier_audio", false),
                "turn_detection" to (if (phoneTurns != null) "phone" else "gemini"), "save_transcripts" to config.optBoolean("save_transcripts"),
                "save_caller_audio" to (callerAudio != null),
                "agent_gain_db" to config.optDouble("agent_gain_db", 0.0),
                "turn_speech_rms" to config.optDouble("turn_speech_rms", PhoneTurns.SPEECH_RMS), "turn_start_ms" to config.optDouble("turn_start_ms", PhoneTurns.START_MS),
                "turn_end_rms" to config.optDouble("turn_end_rms", PhoneTurns.END_RMS),
                "call_path" to if (voip != null) "whatsapp" else "carrier", "voip_extractor_error" to (voip?.extractorError ?: "")))
            chooseUplinkRoute(timing)
            if (!preflight) {
                val carrierDeadline = SystemClock.elapsedRealtime() + CARRIER_WAIT_MS
                val gate = carrierGate
                val preopen = config.optBoolean("preopen_carrier_audio", false)
                var preopenTried = false
                while (running.get() && !(gate?.invoke() ?: active(am))) {
                    check(SystemClock.elapsedRealtime() < carrierDeadline) { "carrier call did not become active" }
                    // Outbound: the mode is in-call from dialing, so carrier audio could be opened while the callee's
                    // phone rings (config preopen_carrier_audio). Off by default: a track opened before the modem's
                    // voice session exists stalled ~0.5 s every ~9 s for the whole call (2026-10-17: 7 stalls in 63 s
                    // outbound vs 1 in 69 s inbound, same 2G network); opening at answer takes ~16 ms.
                    if (gate != null && !preopenTried && preopen && active(am)) {
                        preopenTried = true
                        runCatching { record = openDownlink(); track = openUplink() }
                            .onSuccess { timing.mark("carrier_audio_preopened") }
                            .onFailure {
                                runCatching { record?.release() }; runCatching { track?.release() }; record = null; track = null
                                timing.mark("carrier_audio_preopen_failed", mapOf("error" to "${it.javaClass.simpleName}: ${it.message}".take(200)))
                            }
                    }
                    // Outbound reads volatile call state (cheap); inbound asks AudioManager (a binder call).
                    Thread.sleep(if (gate != null) 2 else 5)
                }
                if (!running.get()) return
                timing.mark("carrier_active_observed", mapOf("prepare" to prepare))
                timing.mark("carrier_audio_open_start")
                if (record == null) record = openDownlink()
                if (track == null) track = openUplink()
                timing.mark("carrier_audio_open_end", mapOf("uplink_route" to uplinkRoute.name, "record_buffer_frames" to record!!.bufferSizeInFrames,
                    "track_buffer_frames" to track!!.bufferSizeInFrames, "track_buffer_capacity_frames" to track!!.bufferCapacityInFrames,
                    "track_performance_mode" to track!!.performanceMode, "uplink_low_latency" to uplinkLowLatency,
                    "track_rate" to track!!.sampleRate))
            } else {
                // Preflight sends audio only once Gemini is set up.
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (running.get() && !setupDone) { check(SystemClock.elapsedRealtime() < deadline) { "Gemini setup timed out" }; Thread.sleep(5) }
            }
            carrierStartedNs = SystemClock.elapsedRealtimeNanos()
            carrierStarted = true
            record?.startRecording(); track?.play()
            underrunBase = runCatching { track?.underrunCount ?: 0 }.getOrDefault(0)
            timing.mark("carrier_audio_started", mapOf("track_routed_type" to (track?.routedDevice?.type ?: -1),
                "record_routed_type" to (record?.routedDevice?.type ?: -1),
                "voice_call_volume" to runCatching { am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(-1),
                "voice_call_volume_max" to runCatching { am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(-1)))
            val synthetic = if (preflight) ctx.assets.open("preflight-caller.pcm").use { it.readBytes() } else null
            // Caller-audio quality numbers (a 95-tap filter per sample) are diagnostics: computed off the capture thread.
            val qualityWork = java.util.concurrent.ArrayBlockingQueue<Pair<ShortArray, Boolean>>(64)
            if (synthetic == null) qualityThread = Thread({
                try {
                    while (true) {
                        val (pcm, speech) = qualityWork.take()
                        quality.frame(pcm, speech)?.let { timing.sample("caller_highband_db_frames", it) }
                    }
                } catch (_: InterruptedException) { }
            }, "gemini-quality").apply { isDaemon = true; start() }
            captureThread = Thread({
                try {
                    if (urgentAudio) raisePriority(timing, "capture")
                    val timestamp = AudioTimestamp()
                    var readFrom: AudioRecord? = null
                    var framesRead = 0L
                    var reads = 0
                    // Preflight: let the greeting play out (Gemini treats it as heard in real time) before the caller speaks.
                    var index = -PREFLIGHT_LEAD_FRAMES
                    val syntheticFrames = (synthetic?.size ?: 0) / 320
                    while (running.get()) {
                        val pcm = ShortArray(FRAME_SAMPLES)
                        if (synthetic != null) {
                            for (i in pcm.indices) {
                                val pos = (index * FRAME_SAMPLES + i) * 2
                                if (pos >= 0 && pos + 1 < synthetic.size) pcm[i] = ((synthetic[pos].toInt() and 255) or (synthetic[pos + 1].toInt() shl 8)).toShort()
                            }
                            if (index == syntheticFrames) { synthEndNs = SystemClock.elapsedRealtimeNanos(); timing.mark("preflight_caller_ended") }
                            index++; Thread.sleep(10)
                        } else {
                            val readStart = SystemClock.elapsedRealtimeNanos()
                            var offset = 0
                            while (offset < pcm.size && running.get()) {
                                val n = record!!.read(pcm, offset, pcm.size - offset, AudioRecord.READ_BLOCKING)
                                if (n == AudioRecord.ERROR_DEAD_OBJECT && recordRebuilds < MAX_REBUILDS) { rebuildDownlink(timing); continue }
                                check(n > 0) { "downlink read failed: $n" }; offset += n
                            }
                            timing.sample("record_read_ms", (SystemClock.elapsedRealtimeNanos() - readStart) / 1e6)
                            val current = record
                            if (current !== readFrom) { readFrom = current; framesRead = 0 }
                            framesRead += offset
                            // Audio the recorder holds that we haven't read yet: a backlog here delays everything.
                            if (++reads % 50 == 0 && current != null &&
                                runCatching { current.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) }.getOrDefault(-1) == AudioRecord.SUCCESS) {
                                val capturedNow = timestamp.framePosition + (System.nanoTime() - timestamp.nanoTime) * 16 / 1_000_000.0
                                timing.sample("record_unread_ms", (capturedNow - framesRead) / 16.0)
                            }
                        }
                        captureRms = rmsOf(pcm); captured += pcm.size
                        val now = timing.nowMs()
                        if (synthetic == null) {
                            echo.captured(captureRms, now)
                            qualityWork.offer(pcm.copyOf() to (captureRms >= TurnLatencyTracker.CALLER_RMS))
                        }
                        if (!timing.marked("first_captured_frame")) timing.mark("first_captured_frame")
                        timing.turns.callerAudio(captureRms, pcm.size / 16.0, now)
                        if (captureRms >= TurnLatencyTracker.CALLER_RMS && !timing.marked("first_caller_energy_captured")) timing.mark("first_caller_energy_captured")
                        // After all raw measurements: acknowledgement, echo gate and ducking boost edit pcm in place.
                        if (synthetic == null) ack?.let { a ->
                            val pending = SystemClock.elapsedRealtimeNanos() - lastAgentRxEnergyNs < AGENT_PENDING_NANOS
                            val decisionsBefore = a.decisions
                            a.caller(captureRms, now, pending)?.let { clip ->
                                pendingAck.set(clip)
                                timing.event("ack_played", mapOf("clip_ms" to clip.size / 16.0, "count" to a.played))
                            }
                            if (a.decisions != decisionsBefore && a.lastDecision != "played")
                                timing.event("ack_skipped", mapOf("reason" to a.lastDecision))
                            if (now < a.gateUntilMs && captureRms < AckPlayer.GATE_RMS) pcm.fill(0)
                        }
                        val rawRms = captureRms
                        var echoLike = false
                        ducking?.let { d ->
                            // No echo canceller on this path: agent echo (~15% of its level) must not reach Gemini as the caller.
                            if (echoGate && d.isEcho(captureRms, now)) { pcm.fill(0); echoGated.incrementAndGet(); echoLike = true }
                            else if (duckingBoost) d.caller(pcm, captureRms, now)
                        }
                        if (!remoteEnded && !endRequested) {
                            val turns = phoneTurns
                            if (turns == null) send(pcm, timing) else phoneTurn(turns, pcm, rawRms, now, echoLike, timing)
                        }
                    }
                } catch (t: Throwable) {
                    if (running.get()) {
                        state = "ERROR capture: ${t.message}"
                        timing.mark("capture_error", mapOf("error" to "${t.javaClass.simpleName}: ${t.message}".take(300)))
                        running.set(false)
                    }
                }
            }, "gemini-downlink").also { it.start() }
            if (!preflight) playbackThread = Thread({ playback(timing, urgentAudio, idleTargetFrames) }, "gemini-uplink").also { it.start() }
            state = if (preflight) "PREFLIGHT STREAMING — synthetic caller" else "STREAMING — Gemini ↔ carrier call, direct"
            val deadline = SystemClock.elapsedRealtime() + if (preflight) PREFLIGHT_MS else maxCallMs
            var nextStats = SystemClock.elapsedRealtime()
            while (running.get()) {
                if (SystemClock.elapsedRealtime() >= nextStats) {
                    nextStats = SystemClock.elapsedRealtime() + 5000
                    timing.flush(windowFields() + quality.snapshot())
                    echo.report()?.let { r ->
                        timing.event("echo_probe", r)
                        ducking?.echoEstimate((r["echo_lag_ms"] as Number).toDouble(), (r["echo_correlation"] as Number).toDouble(), (r["echo_gain"] as Number).toDouble())
                    }
                    networkSnapshot()?.let { timing.event("network", it) }
                }
                if (!preflight && !active(am)) { state = "FINISHED — carrier call ended"; break }
                if (endRequested && !preflight) { finishAfterAgentEnd(timing); break }
                if (remoteEnded) { finishAfterRemoteEnd(timing); break }
                if (preflight && answeredSynth) { state = "PREFLIGHT PASS — Gemini answered the synthetic caller"; break }
                if (SystemClock.elapsedRealtime() > deadline) {
                    state = if (preflight) "PREFLIGHT FAILED — no answer to the synthetic caller" else "FINISHED — call length limit"
                    break
                }
                Thread.sleep(20)
            }
        } catch (t: Throwable) {
            if (running.get()) {
                state = "ERROR: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, state)
            }
        } finally {
            timing.mark("cleanup_start")
            running.set(false)
            runCatching { ws?.close(1000, null) }; ws = null
            runCatching { record?.stop() }; runCatching { track?.pause() }; runCatching { track?.flush() }
            captureThread?.join(1500); playbackThread?.join(1500)
            qualityThread?.interrupt()
            runCatching { record?.release() }; runCatching { track?.release() }; record = null; track = null
            timing.flush(windowFields() + quality.snapshot())
            if (config.optBoolean("save_transcripts")) saveTranscript(timing)
            callerAudio?.let { a ->
                runCatching { timing.mark("caller_audio_saved", mapOf("seconds" to a.close(timing.id))) }
                    .onFailure { timing.mark("caller_audio_failed", mapOf("error" to "${it.javaClass.simpleName}: ${it.message}".take(200))) }
            }
            callerAudio = null
            timing.mark("gemini_session_summary", mapOf("cost_usd" to costUsd, "prompt_tokens" to promptTokens,
                "response_tokens" to responseTokens, "interruptions" to interruptions.get(), "ws_end" to remoteEndReason))
            timing.finish(state.lineSequence().first().take(300))
            Log.i(TAG, status())
        }
    }

    /**
     * Capture thread, phone turns: caller audio goes to Gemini only inside a turn the phone detected, between
     * activityStart and activityEnd. At the start the reply playing is dropped here at once (Gemini's own cut takes a
     * round trip), and the frames just before the trigger go first so the caller's first word isn't lost.
     */
    private fun phoneTurn(turns: PhoneTurns, pcm: ShortArray, rms: Double, now: Double, echo: Boolean, timing: CallLatencyTrace) {
        val socket = ws ?: return
        if (!setupDone) return
        preroll.addLast(pcm.copyOf())
        while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
        when (turns.frame(rms, now, echo)) {
            PhoneTurns.Event.START -> {
                val queued = queuedMs()
                val cut = generating || queued > 0
                if (generating) staleReply = true
                pendingFlush.set(true)
                if (cut) phoneStarts.incrementAndGet()
                socket.send(GeminiProtocol.activityStart())
                var heard = -1.0
                if (cut) {
                    // What the caller heard of the reply: the audio queued for it minus what was just dropped (approximate:
                    // the transcript may run a little behind the audio).
                    val total = turnAudioMs
                    heard = if (total > 0) ((total - queued) / total).coerceIn(0.0, 1.0) else 0.0
                    val text = synchronized(turnText) { turnText.toString() }
                    socket.send(GeminiProtocol.interruptionNote(text, heard, greeting = agentTurnsDone == 0))
                }
                preroll.forEach { send(it, timing) }
                preroll.clear()
                timing.event("phone_activity_start", mapOf("cut_reply" to cut, "output_queued_ms" to queued, "generating" to generating,
                    "heard_fraction" to heard, "greeting" to (cut && agentTurnsDone == 0)))
            }
            PhoneTurns.Event.END -> {
                socket.send(GeminiProtocol.activityEnd())
                staleReply = false
                timing.event("phone_activity_end", mapOf("duration_ms" to now - turns.startedAtMs, "last_voice_ms" to turns.lastVoiceMs))
            }
            PhoneTurns.Event.NONE -> if (turns.speaking) send(pcm, timing)
        }
    }

    /** Capture thread: one 10 ms frame up to Gemini, at once (OkHttp queues it; the socket has Nagle off). */
    private fun send(pcm: ShortArray, timing: CallLatencyTrace) {
        val socket = ws ?: return
        if (!setupDone) return
        if (socket.send(GeminiProtocol.audio(pcm))) {
            sentFrames++
            callerAudio?.write(pcm, timing.nowMs())
            if (rmsOf(pcm) >= TurnLatencyTracker.CALLER_RMS && !timing.marked("first_caller_energy_sent")) timing.mark("first_caller_energy_sent")
        } else sendFailures.incrementAndGet()
        // OkHttp buffers up to 16 MiB before failing; a growing queue means the network isn't keeping up.
        if (sentFrames % 100 == 0L) timing.sample("ws_send_queue_bytes", socket.queueSize().toDouble())
    }

    private fun windowFields() = mapOf("captured_samples" to captured, "sent_frames" to sentFrames, "played_samples" to played,
        "send_failures" to sendFailures.get(), "echo_gated_frames" to echoGated.get(), "interruptions" to interruptions.get(),
        "phone_cuts" to phoneStarts.get(),
        "output_dropped_frames" to outputDrops.get(), "uplink_write_stalls" to uplinkStalls.get(),
        "track_underruns" to (track?.underrunCount ?: 0), "track_buffer_frames" to trackBufferFrames,
        "output_queue_chunks" to outputFrames.size, "output_queued_audio_ms" to queuedMs(),
        "ack_played" to (ack?.played ?: 0), "ack_cuts" to ackCuts.get(),
        "ducking_boosted_frames" to (ducking?.boostedFrames ?: 0L),
        "ducking_mean_gain" to (ducking?.let { if (it.boostedFrames > 0) it.gainSum / it.boostedFrames else 0.0 } ?: 0.0),
        "cost_usd" to costUsd, "prompt_tokens" to promptTokens, "response_tokens" to responseTokens)

    /** The uplink thread: writes Gemini's reply (or silence) into the carrier track, paced by the track. */
    private fun playback(timing: CallLatencyTrace, urgentAudio: Boolean, idleTargetFrames: Long) {
        try {
            if (urgentAudio) raisePriority(timing, "uplink")
            fun writeFully(pcm16: ShortArray): Int {
                // WhatsApp's 48 kHz injector: written 3x upsampled; the count returned stays in 16 kHz frames.
                val pcm = if (trackScale == 1) pcm16 else upsampler.process(pcm16)
                var offset = 0
                while (offset < pcm.size && running.get()) {
                    val n = track!!.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (n == AudioTrack.ERROR_DEAD_OBJECT && trackRebuilds < MAX_REBUILDS) { rebuildUplink(timing); continue }
                    check(n > 0) { "uplink write failed: $n" }; offset += n
                }
                return offset / trackScale
            }
            fun pendingFrames() = trackWritten - (track!!.playbackHeadPosition.toLong() and 0xffffffffL) / trackScale
            val silence = ShortArray(FRAME_SAMPLES)
            val onsetsWritten = AgentOnsets()
            val presented = AudioTimestamp()
            var writes = 0
            var timestampFailures = 0
            var speakingWrites = 0
            var cleanSpeakingWrites = 0
            var heldDrained = false
            while (running.get()) {
                if (pendingFlush.getAndSet(false)) {
                    var dropped = 0
                    val kept = outputFrames.filter { it.ack }
                    dropped = outputFrames.size - kept.size
                    outputFrames.clear(); kept.forEach { outputFrames.offerLast(it) }
                    timing.event("reply_flushed", mapOf("dropped_ms" to dropped * 10.0))
                }
                pendingAck.getAndSet(null)?.let { insertAck(it) }
                // Agent idle: let the track run down to a sliver of silence before writing more, so the next words
                // (or an acknowledgement) aren't queued behind 20-30 ms of silence.
                val head = outputFrames.peekFirst()
                val idle = SystemClock.elapsedRealtimeNanos() - lastEnergyWrittenNs > IDLE_NANOS &&
                    (head == null || (!head.ack && head.rms < TurnLatencyTracker.AGENT_RMS))
                if (idle) {
                    val waitStart = SystemClock.elapsedRealtimeNanos()
                    while (running.get() && pendingFrames() > idleTargetFrames &&
                        SystemClock.elapsedRealtimeNanos() - waitStart < IDLE_WAIT_MAX_NANOS) LockSupport.parkNanos(1_000_000)
                }
                var next = outputFrames.pollFirst() ?: PcmFrame(silence, rms = 0.0, synthetic = true)
                if (next.ack && outputFrames.any { !it.ack && it.rms >= TurnLatencyTracker.AGENT_RMS }) {
                    // The reply has arrived while the acknowledgement plays: end the clip here (10 ms fade).
                    var cut = 0
                    while (outputFrames.peekFirst()?.ack == true) cut += outputFrames.pollFirst()!!.pcm.size
                    next = next.copy(pcm = fadeOut(next.pcm))
                    ackCuts.incrementAndGet()
                    timing.event("ack_cut", mapOf("cut_ms" to cut / 16.0))
                }
                val fromHold = next.queuedNs < carrierStartedNs
                if (!fromHold && !heldDrained && !next.synthetic) {
                    heldDrained = true
                    timing.mark("held_agent_audio_drained")
                }
                val writeStart = SystemClock.elapsedRealtimeNanos()
                val queuedMs = (writeStart - next.queuedNs) / 1e6
                val pendingBeforeMs = maxOf(0L, pendingFrames()) / 16.0
                val offset = writeFully(next.pcm)
                val writeMs = (SystemClock.elapsedRealtimeNanos() - writeStart) / 1e6
                if (writeMs >= STALL_MS) {
                    uplinkStalls.incrementAndGet()
                    timing.event("uplink_write_stall", mapOf("write_ms" to writeMs, "track_pending_before_ms" to pendingBeforeMs,
                        "since_carrier_start_ms" to (writeStart - carrierStartedNs) / 1e6, "output_queued_ms" to queuedMs()))
                }
                timing.sample("track_write_ms", writeMs)
                if (!timing.marked("first_track_write_return")) timing.mark("first_track_write_return")
                val nowMs = timing.nowMs()
                echo.agentWritten(next.rms, nowMs)
                ducking?.agent(next.rms, nowMs)
                if (!next.ack) ack?.agentWritten(next.rms, nowMs)
                if (next.ackFirst) ack?.let { a ->
                    timing.event("ack_written", mapOf("since_caller_speech_ms" to nowMs - a.answeredSpeechEndMs, "queued_ms" to queuedMs))
                }
                if (next.rms >= TurnLatencyTracker.AGENT_RMS) lastEnergyWrittenNs = SystemClock.elapsedRealtimeNanos()
                if (next.rms >= TurnLatencyTracker.AGENT_RMS && !next.ack) {
                    lastAgentWrittenNs = SystemClock.elapsedRealtimeNanos()
                    if (!timing.marked("first_agent_energy_written"))
                        timing.mark("first_agent_energy_written", mapOf("boundary" to "write_return_not_far_end_audibility"))
                    timing.turns.agentWritten(next.turn, nowMs, queuedMs, writeMs)
                }
                played += offset; trackWritten += offset
                val pendingMs = maxOf(0L, pendingFrames()) / 16.0
                timing.sample("track_pending_audio_ms", pendingMs)
                if (!next.ack && !next.synthetic) onsetsWritten.chunk(next.rms, nowMs)?.let { n ->
                    // queued_ms: from the frame's arrival from Gemini to being written (the reply arrives ahead of time).
                    timing.event("agent_onset_written", mapOf("onset" to n, "rx_t_ms" to (next.queuedNs - timing.originNs) / 1e6,
                        "queued_ms" to queuedMs, "track_pending_ms" to pendingMs, "from_hold" to fromHold))
                }
                // Track window: underruns while the agent speaks grow it; long clean stretches shrink it again.
                val t = track!!
                if (idle) underrunBase = t.underrunCount
                else if (++speakingWrites % 25 == 0) {
                    val underruns = t.underrunCount
                    if (underruns > underrunBase) {
                        underrunBase = underruns; cleanSpeakingWrites = 0
                        if (trackBufferFrames < trackMaxFrames) resizeTrack(t, trackBufferFrames + 160, timing, "underrun")
                    } else if ((cleanSpeakingWrites + 25).also { cleanSpeakingWrites = it } >= TRACK_SHRINK_AFTER_WRITES) {
                        cleanSpeakingWrites = 0
                        if (trackBufferFrames > trackMinFrames) resizeTrack(t, trackBufferFrames - 80, timing, "clean")
                    }
                }
                if (++writes % 50 == 0) {
                    val routed = t.routedDevice
                    check(routed == null || routed.type == AudioDeviceInfo.TYPE_TELEPHONY || (voip != null && routed.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX)) {
                        "uplink routed to non-telephony device type=${routed?.type} id=${routed?.id} name=${routed?.productName}"
                    }
                    if (timestampFailures < 10) {
                        val ok = runCatching { t.getTimestamp(presented) }.getOrDefault(false)
                        if (ok) {
                            timestampFailures = 0
                            val sinceNs = System.nanoTime() - presented.nanoTime
                            timing.sample("track_unpresented_ms", (trackWritten - presented.framePosition / trackScale) / 16.0 - sinceNs / 1e6)
                        } else if (++timestampFailures == 10) timing.mark("track_timestamp_unavailable", mapOf("route" to uplinkRoute.name))
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                state = "ERROR playback: ${t.message}"
                timing.mark("playback_error", mapOf("error" to "${t.javaClass.simpleName}: ${t.message}".take(300),
                    "routed_type" to (track?.routedDevice?.type ?: -1), "play_state" to (track?.playState ?: -1)))
                running.set(false)
            }
        }
    }

    /** One 10 ms chunk of Gemini audio at 16 kHz. WebSocket thread only. */
    private fun agentFrame(mono: ShortArray, timing: CallLatencyTrace, preflight: Boolean) {
        val gain = agentGain
        if (gain != 1.0) for (i in mono.indices) mono[i] = (mono[i] * gain).toInt().coerceIn(-32_767, 32_767).toShort()
        val level = rmsOf(mono)
        playbackRms = level
        val now = timing.nowMs()
        // Silence ahead of a reply only delays it.
        if (replyStart && level < TurnLatencyTracker.AGENT_RMS) return
        replyStart = false
        val turn = timing.turns.agentAudio(level, now)
        onsetsRx.chunk(level, now)?.let { timing.event("agent_onset_rx", mapOf("onset" to it)) }
        if (level >= TurnLatencyTracker.AGENT_RMS) {
            // The level the agent enters the call at (after agent_gain_db), for comparing against callers' muffling.
            timing.sample("agent_sent_rms", level)
            if (!timing.marked("first_agent_energy_received")) timing.mark("first_agent_energy_received")
            lastAgentRxEnergyNs = SystemClock.elapsedRealtimeNanos()
            agentSpoke = true
            if (preflight && synthEndNs != 0L && !answeredSynth) {
                answeredSynth = true
                preflightReplyMs = (lastAgentRxEnergyNs - synthEndNs) / 1e6
                timing.mark("preflight_answer", mapOf("caller_end_to_audio_ms" to preflightReplyMs))
            }
        }
        var peak = remotePeak
        for (s in mono) { val a = kotlin.math.abs(s.toInt()); if (a > peak) peak = a }
        remotePeak = peak
        if (preflight) { played += mono.size; return }
        newReplyIfEnded(); turnAudioMs += mono.size / 16.0
        outputFrames.offerLast(PcmFrame(mono, turn = turn, rms = level))
        // Last resort only: an interruption or the end of a reply normally empties it long before this.
        while (outputFrames.size > MAX_OUTPUT_CHUNKS && outputFrames.pollFirst() != null) outputDrops.incrementAndGet()
    }

    /** WebSocket thread: the first text or audio after a finished reply starts the next one. */
    private fun newReplyIfEnded() {
        if (!turnEnded) return
        turnEnded = false
        synchronized(turnText) { turnText.setLength(0) }; turnAudioMs = 0.0
    }

    /** Uplink thread: plays [clip] next. Queued silence goes (the agent is idle); the clip goes to the front. */
    private fun insertAck(clip: ShortArray) {
        while (true) {
            val head = outputFrames.peekFirst() ?: break
            if (head.ack || head.rms >= TurnLatencyTracker.AGENT_RMS) break
            outputFrames.pollFirst()
        }
        val chunks = (clip.indices step FRAME_SAMPLES).map { PcmFrame(clip.copyOfRange(it, minOf(it + FRAME_SAMPLES, clip.size)), ack = true, ackFirst = it == 0) }
        for (c in chunks.asReversed()) outputFrames.offerFirst(c)
    }
    private fun queuedMs() = outputFrames.sumOf { it.pcm.size } / 16.0
    /** Acknowledgement clips from assets/ack: 16 kHz mono PCM16. */
    private fun loadAckClips(ctx: Context): List<ShortArray> =
        (ctx.assets.list("ack") ?: emptyArray()).filter { it.endsWith(".pcm") }.sorted().map { name ->
            val bytes = ctx.assets.open("ack/$name").use { it.readBytes() }
            ShortArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 255) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
        }.also { check(it.isNotEmpty()) { "no acknowledgement clips in assets/ack" } }
    private fun openDownlink(): AudioRecord {
        // WhatsApp: the extractor's recorder, once; a rebuild (or no extractor) falls back to VOICE_DOWNLINK.
        voip?.record?.takeIf { recordRebuilds == 0 && it.state == AudioRecord.STATE_INITIALIZED }?.let { return it }
        val bufferBytes = config.optInt("record_buffer_ms", 0).takeIf { it > 0 }?.let { it * 32 }
        val r = AudioTools.openRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, 16_000, bufferBytes)
        check(r?.state == AudioRecord.STATE_INITIALIZED) { "downlink recorder not initialized" }
        return r!!
    }
    /** Config `uplink_route`: "call_assistant", "telephony_tx" or "auto" (default: last route that worked on this phone). */
    private fun chooseUplinkRoute(timing: CallLatencyTrace) {
        val configured = config.optString("uplink_route", "auto")
        uplinkRouteAuto = configured == "auto"
        uplinkRoute = when (configured) {
            "telephony_tx" -> UplinkRoute.TELEPHONY_TX
            "call_assistant" -> UplinkRoute.CALL_ASSISTANT
            else -> runCatching { UplinkRoute.valueOf(File(ROUTE_PATH).readText().trim()) }.getOrDefault(UplinkRoute.CALL_ASSISTANT)
        }
        timing.mark("uplink_route_chosen", mapOf("route" to uplinkRoute.name, "configured" to configured))
    }
    /** Low-latency mode (config uplink_low_latency, default on) can give the track a shorter platform path;
     * if this phone refuses it, the normal track is used. */
    private fun openUplink(): AudioTrack {
        voip?.let { v ->
            check(trackRebuilds == 0) { "WhatsApp injector track died" }
            trackScale = (v.track.sampleRate / 16_000).coerceAtLeast(1)
            trackBufferFrames = v.track.setBufferSizeInFrames(trackBufferFrames * trackScale).takeIf { it > 0 }?.div(trackScale) ?: trackBufferFrames
            return v.track
        }
        val wantLowLatency = config.optBoolean("uplink_low_latency", true)
        val t = if (!wantLowLatency) buildUplink(false) else
            runCatching { buildUplink(true).also { if (it.state != AudioTrack.STATE_INITIALIZED) { it.release(); error("uninitialized") } } }
                .onFailure { Log.w(TAG, "low-latency uplink refused: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull() ?: buildUplink(false)
        uplinkLowLatency = t.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        check(t.state == AudioTrack.STATE_INITIALIZED) { "uplink track not initialized (${uplinkRoute.name})" }
        // Trim the playable window: the default buffer kept 120-160 ms of agent audio queued.
        trackBufferFrames = t.setBufferSizeInFrames(trackBufferFrames).takeIf { it > 0 } ?: trackBufferFrames
        return t
    }
    private fun buildUplink(lowLatency: Boolean): AudioTrack = when (uplinkRoute) {
        UplinkRoute.CALL_ASSISTANT -> {
            val fmt = AudioFormat.Builder().setSampleRate(16_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            HiddenCallAudio.shellUplink(fmt, HiddenCallAudio.pstnRedirection(), lowLatency)
        }
        UplinkRoute.TELEPHONY_TX -> {
            val am = AudioTools.shellContext().getSystemService(AudioManager::class.java)
            val telephony = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY && it.isSink } ?: error("no telephony output device")
            AudioTools.openShellTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION, lowLatency = lowLatency).also {
                check(it.setPreferredDevice(telephony)) { "telephony output refused the uplink track" }
            }
        }
    }
    private fun resizeTrack(t: AudioTrack, frames: Int, timing: CallLatencyTrace, reason: String) {
        val wanted = frames.coerceIn(trackMinFrames, trackMaxFrames)
        val actual = runCatching { t.setBufferSizeInFrames(wanted * trackScale) }.getOrDefault(-1).let { if (it > 0) it / trackScale else it }
        if (actual > 0) trackBufferFrames = actual
        timing.event("track_buffer_resized", mapOf("requested_frames" to wanted, "frames" to actual, "reason" to reason))
    }
    /** AudioTrack.write returned ERROR_DEAD_OBJECT: the platform invalidated the track (HyperOS on MediaTek does
     * this ~0.3 s after the call goes active) and it must be recreated. Playback thread only. */
    private fun rebuildUplink(timing: CallLatencyTrace) {
        trackRebuilds++
        if (voip == null && uplinkRouteAuto && uplinkRoute == UplinkRoute.CALL_ASSISTANT) {
            // This phone kills the call-assistant track: switch now, and start there next time.
            uplinkRoute = UplinkRoute.TELEPHONY_TX
            runCatching { File(ROUTE_PATH).writeText(uplinkRoute.name) }
            timing.mark("uplink_route_switched", mapOf("route" to uplinkRoute.name))
        }
        runCatching { track?.release() }
        val fresh = openUplink().also { it.play() }
        track = fresh; trackWritten = 0; underrunBase = 0
        timing.mark("uplink_track_rebuilt_$trackRebuilds", mapOf("route" to uplinkRoute.name, "routed_type" to (fresh.routedDevice?.type ?: -1),
            "performance_mode" to fresh.performanceMode,
            "since_carrier_start_ms" to (SystemClock.elapsedRealtimeNanos() - carrierStartedNs) / 1e6))
    }
    /** Same for the downlink recorder (AudioRecord.read ERROR_DEAD_OBJECT). Capture thread only. */
    private fun rebuildDownlink(timing: CallLatencyTrace) {
        recordRebuilds++
        runCatching { record?.release() }
        val fresh = openDownlink().also { it.startRecording() }
        record = fresh
        timing.mark("downlink_record_rebuilt_$recordRebuilds", mapOf("routed_type" to (fresh.routedDevice?.type ?: -1),
            "since_carrier_start_ms" to (SystemClock.elapsedRealtimeNanos() - carrierStartedNs) / 1e6))
    }
    /** Plays out what's queued, then what the track holds, then the carrier leg. */
    private fun drain() {
        val drainDeadline = SystemClock.elapsedRealtime() + DRAIN_MAX_MS
        while (running.get() && outputFrames.any { it.ack || it.rms >= TurnLatencyTracker.AGENT_RMS } &&
            SystemClock.elapsedRealtime() < drainDeadline) Thread.sleep(20)
        val trackDeadline = SystemClock.elapsedRealtime() + 1_000
        while (running.get() && SystemClock.elapsedRealtime() < trackDeadline) {
            val head = ((track?.playbackHeadPosition?.toLong() ?: break) and 0xffffffffL) / trackScale
            if (trackWritten - head <= 0) break
            Thread.sleep(20)
        }
        // Audio accepted by the track still has to cross the carrier network to the caller.
        if (running.get()) Thread.sleep(CARRIER_TAIL_MS)
    }
    /** Gemini called end_call: the goodbye may still be arriving, so wait for its audio to stop, then drain. */
    private fun finishAfterAgentEnd(timing: CallLatencyTrace) {
        state = "FINISHING — agent said goodbye"
        val quietDeadline = SystemClock.elapsedRealtime() + DRAIN_MAX_MS
        // The tool call can come before the goodbye's audio: wait until audio has been quiet a while after it.
        while (running.get() && SystemClock.elapsedRealtime() < quietDeadline) {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - endRequestedNs >= GOODBYE_QUIET_NANOS && now - lastAgentRxEnergyNs >= GOODBYE_QUIET_NANOS) break
            Thread.sleep(20)
        }
        drain()
        timing.mark("agent_audio_drained_after_end_call")
        if (!running.get()) return // OFF, or the caller hung up meanwhile: nothing to hang up.
        endedByAgent = true
        state = "FINISHED — agent ended the call"
    }
    /** Gemini closed the session mid-call without end_call (error, time limit): play what's left; don't hang up. */
    private fun finishAfterRemoteEnd(timing: CallLatencyTrace) {
        drain()
        timing.mark("agent_audio_drained_after_remote_end")
    }
    /** Audio threads default to normal priority, so wake-ups wait behind other work. */
    private fun raisePriority(timing: CallLatencyTrace, thread: String) {
        val result = runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        timing.event("thread_priority", mapOf("thread" to thread, "nice" to runCatching { Process.getThreadPriority(Process.myTid()) }.getOrDefault(99),
            "raised" to result.isSuccess))
    }
    /** Transport and Wi-Fi link quality only: no SSID, BSSID or addresses. */
    private fun networkSnapshot(): Map<String, Any>? = runCatching {
        val cm = AudioTools.shellContext().getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching mapOf("transport" to "none")
        val out = mutableMapOf<String, Any>(
            "transport" to when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            },
            "validated" to caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            "down_kbps" to caps.linkDownstreamBandwidthKbps, "up_kbps" to caps.linkUpstreamBandwidthKbps)
        (caps.transportInfo as? android.net.wifi.WifiInfo)?.let { wifi ->
            out["wifi_rssi_dbm"] = wifi.rssi; out["wifi_link_mbps"] = wifi.linkSpeed
            out["wifi_frequency_mhz"] = wifi.frequency
        }
        out
    }.onFailure { Log.w(TAG, "network snapshot failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()

    companion object {
        private const val TAG = "CallStreamGemini"
        private const val FRAME_SAMPLES = 160
        /** Uplink track window: starts here (config track_buffer_ms), grows by 10 ms per underrun burst while the
         * agent speaks up to 50 ms, and shrinks 5 ms after a clean minute of speech. */
        private const val TRACK_BUFFER_MS = 30.0
        private const val TRACK_BUFFER_FRAMES = 480
        private const val TRACK_BUFFER_MIN_FRAMES = 320
        private const val TRACK_BUFFER_MAX_FRAMES = 800
        private const val TRACK_SHRINK_AFTER_WRITES = 6_000
        /** WhatsApp's window: 66 ms (two 32 ms submix cycles and a frame), up to ~130 ms within the track's capacity. */
        private const val VOIP_TRACK_MIN_FRAMES = 1_056
        private const val VOIP_TRACK_MAX_FRAMES = 2_048
        private const val VOIP_IDLE_TRACK_FRAMES = 528L
        /** Agent idle: silence kept in the track (config idle_track_ms). Waits are bounded by IDLE_WAIT_MAX. */
        private const val IDLE_TRACK_MS = 10.0
        private const val IDLE_NANOS = 300_000_000L
        private const val IDLE_WAIT_MAX_NANOS = 25_000_000L
        private const val AGENT_PENDING_NANOS = 300_000_000L
        /** Gemini sends a reply faster than real time; 120 s is far beyond any reply. */
        private const val MAX_OUTPUT_CHUNKS = 12_000
        /** A write blocked this long is a carrier stall (normal writes return in <= ~25 ms). */
        private const val STALL_MS = 100.0
        private const val CARRIER_WAIT_MS = 60_000L
        private const val CARRIER_TAIL_MS = 700L
        private const val DRAIN_MAX_MS = 8_000L
        /** After end_call, the goodbye is complete once no audio with energy has arrived for this long. */
        private const val GOODBYE_QUIET_NANOS = 400_000_000L
        private const val MAX_REBUILDS = 5 // per call; a track that keeps dying is a real failure
        /** Cost guard (2026-10-08 decision), config max_call_ms; Gemini Live itself caps audio sessions at 15 minutes. */
        private const val MAX_CALL_MS = 5 * 60_000L
        private const val PREFLIGHT_MS = 30_000L
        /** Preflight waits 7 s for the greeting to play out before the synthetic caller speaks. */
        private const val PREFLIGHT_LEAD_FRAMES = 700
        const val CONFIG_PATH = "/data/local/tmp/call-stream-agent.json"
        const val TRANSCRIPT_DIR = "/data/local/tmp/call-stream-transcripts"
        const val AUDIO_DIR = "/data/local/tmp/call-stream-audio"
        /** 400 ms: covers the detector's 300 ms window plus the speech onset just before it. */
        private const val PREROLL_FRAMES = 40
        private const val ROUTE_PATH = "/data/local/tmp/call-stream-uplink-route"

        /** Nagle off: each 10 ms caller frame leaves at once instead of waiting for the previous one's ACK. */
        private val CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .socketFactory(object : SocketFactory() {
                    private val base = getDefault()
                    private fun Socket.tuned() = apply { tcpNoDelay = true }
                    override fun createSocket(): Socket = base.createSocket().tuned()
                    override fun createSocket(host: String, port: Int): Socket = base.createSocket(host, port).tuned()
                    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
                        base.createSocket(host, port, local, localPort).tuned()
                    override fun createSocket(host: InetAddress, port: Int): Socket = base.createSocket(host, port).tuned()
                    override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
                        base.createSocket(address, port, local, localPort).tuned()
                })
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .build()
        }

        private fun rmsOf(data: ShortArray): Double {
            if (data.isEmpty()) return 0.0
            var sum = 0.0
            for (s in data) sum += s.toDouble() * s
            return kotlin.math.sqrt(sum / data.size)
        }

        /** Linear fade to silence over the chunk: ends a clip without a click. */
        private fun fadeOut(pcm: ShortArray): ShortArray =
            ShortArray(pcm.size) { (pcm[it] * (pcm.size - it).toDouble() / pcm.size).toInt().toShort() }

        /** Builds the HTTP client and loads its classes before the first call needs them. */
        fun preload() { runCatching { CLIENT } }

        /** While Call Stream waits for calls: refreshes DNS for Gemini's endpoint. Blocking; off the controller thread. */
        fun prewarm() {
            runCatching { InetAddress.getAllByName("generativelanguage.googleapis.com") }
        }
    }
}
