package dev.callstream.probe.shell

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Gemini Live API messages (BidiGenerateContent over a WebSocket, v1beta). Building and parsing only;
 * no I/O, so it is unit-tested on the JVM. Field names follow ai.google.dev/api/live.
 */
internal object GeminiProtocol {
    /** Config api_version: "v1beta" (default) or "v1alpha" (some features shipped there first). */
    fun url(version: String = "v1beta") = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.$version.GenerativeService.BidiGenerateContent"
    const val DEFAULT_MODEL = "gemini-3.8-live"
    const val DEFAULT_VOICE = "Leda"
    /** The first user turn: the prompt tells the agent to greet when it arrives. */
    const val KICKOFF = "[call connected]"
    /** Appended to the kickoff: who placed the call. */
    const val INBOUND = "inbound: the caller phoned you"
    const val OUTBOUND = "outbound: you phoned them"
    const val END_CALL = "end_call"
    /** 2026-10-14 test from Lahore: thinking added 0.2-3.2 s per reply; budget 0 kept the plan amounts right. */
    const val DEFAULT_THINKING_BUDGET = 0
    const val DEFAULT_SILENCE_MS = 300

    /** The setup message, from the on-phone config (keys: system_prompt, gemini_model, gemini_voice, ...). */
    fun setup(cfg: JSONObject): String {
        val prompt = cfg.optString("system_prompt").ifBlank { error("no system_prompt in the on-phone config") }
        val generation = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig",
                JSONObject().put("voiceName", cfg.optString("gemini_voice", DEFAULT_VOICE)))))
        val thinking = cfg.optInt("thinking_budget", DEFAULT_THINKING_BUDGET)
        // -1 leaves thinking to the model.
        if (thinking >= 0) generation.put("thinkingConfig", JSONObject().put("thinkingBudget", thinking))
        if (cfg.has("temperature")) generation.put("temperature", cfg.getDouble("temperature"))
        // Adapts the reply's style (and, from a native-audio model, its delivery) to the caller's tone (supported on 3.8 Live).
        if (cfg.optBoolean("affective_dialog")) generation.put("enableAffectiveDialog", true)
        // Config turn_detection "phone": the phone decides turns (PhoneTurns) and sends activityStart/activityEnd.
        val detection = if (phoneTurns(cfg)) JSONObject().put("disabled", true) else JSONObject().put("silenceDurationMs", cfg.optInt("vad_silence_ms", DEFAULT_SILENCE_MS))
        if (!phoneTurns(cfg)) {
            cfg.optString("vad_start_sensitivity").takeIf { it.isNotEmpty() }?.let { detection.put("startOfSpeechSensitivity", "START_SENSITIVITY_${it.uppercase()}") }
            cfg.optString("vad_end_sensitivity").takeIf { it.isNotEmpty() }?.let { detection.put("endOfSpeechSensitivity", "END_SENSITIVITY_${it.uppercase()}") }
            if (cfg.has("vad_prefix_padding_ms")) detection.put("prefixPaddingMs", cfg.getInt("vad_prefix_padding_ms"))
        }
        val endCall = JSONObject().put("name", END_CALL)
            .put("description", "Hang up the phone call. Use only after you said goodbye and the caller wants to end; never because audio was unclear or silent.")
        val setup = JSONObject()
            .put("model", "models/" + cfg.optString("gemini_model", DEFAULT_MODEL))
            .put("generationConfig", generation)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(endCall))))
            .put("realtimeInputConfig", realtimeInput(cfg, detection))
        // Both sides as text, for reading the call into the lead sheet afterwards (config extract_model).
        if (cfg.optString("extract_model").isNotEmpty() || cfg.optBoolean("transcripts") || cfg.optBoolean("save_transcripts")) {
            // Config transcription_language (e.g. "ur-PK"): a hint for the transcripts' language and script. Without it
            // Gemini wrote Urdu callers in Devanagari (2026-10-17). The model hears the audio itself either way.
            val transcription = JSONObject()
            cfg.optString("transcription_language").takeIf { it.isNotEmpty() }?.let { transcription.put("languageCodes", JSONArray().put(it)) }
            setup.put("inputAudioTranscription", transcription).put("outputAudioTranscription", JSONObject(transcription.toString()))
        }
        return JSONObject().put("setup", setup).toString()
    }

    fun phoneTurns(cfg: JSONObject) = cfg.optString("turn_detection") == "phone"
    /** Phone turn detection: the caller started / stopped talking. activityStart also cuts Gemini's reply. */
    fun activityStart(): String = "{\"realtimeInput\":{\"activityStart\":{}}}"
    fun activityEnd(): String = "{\"realtimeInput\":{\"activityEnd\":{}}}"

    /**
     * Phone turns, at a cut-in: tells the model it was cut off and roughly what the caller heard, so it answers them instead
     * of finishing its sentence (build 68 resumed the cut sentence, and re-said the whole greeting after a "جی").
     * Appended to the history without starting a reply (turnComplete false): the reply comes at activityEnd.
     */
    fun interruptionNote(agentText: String, heardFraction: Double, greeting: Boolean): String {
        val heard = heardPart(agentText, heardFraction)
        val text = buildString {
            append("[The caller cut in while you were speaking")
            if (heard.isNotEmpty()) append("; they heard only about: \"").append(heard).append("…\"")
            else if (agentText.isNotBlank()) append(", right at the start")
            append(". Drop that point: don't continue it or say it again. Answer what they say now.")
            if (greeting) append(" This was your greeting: don't start it again; if they only said hello or جی, say just the part they missed, briefly.")
            append(']')
        }
        return JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("turnComplete", false)).toString()
    }

    /** The first [fraction] of [text], ending on a whole word (empty if under one word). */
    fun heardPart(text: String, fraction: Double): String {
        val t = text.trim()
        if (fraction >= 1.0) return t
        val cut = (t.length * fraction.coerceIn(0.0, 1.0)).toInt()
        val end = t.lastIndexOf(' ', cut)
        return if (end <= 0) "" else t.substring(0, end).trimEnd(' ', '،', ',', '۔', '.')
    }

    /** Turn handling around the voice activity detection. Config activity_handling: "start_of_activity_interrupts"
     * (Gemini's default: caller speech cuts the reply) or "no_interruption"; turn_coverage: "turn_includes_only_activity"
     * or "turn_includes_all_input" (whether audio between the caller's speech counts toward the turn). Unset: Gemini's default. */
    private fun realtimeInput(cfg: JSONObject, detection: JSONObject): JSONObject {
        val input = JSONObject().put("automaticActivityDetection", detection)
        cfg.optString("activity_handling").takeIf { it.isNotEmpty() }?.let { input.put("activityHandling", it.uppercase()) }
        cfg.optString("turn_coverage").takeIf { it.isNotEmpty() }?.let { input.put("turnCoverage", it.uppercase()) }
        return input
    }

    val PAKISTAN: java.time.ZoneId = java.time.ZoneId.of("Asia/Karachi")
    private val WHEN = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", java.util.Locale.ENGLISH)

    /**
     * The kickoff text: who placed the call, the contact's name if the list has a real one, [notes] about earlier
     * calls (a callback), and the local date and time.
     */
    fun callContext(kickoff: String, outbound: Boolean, contactName: String?, now: java.time.ZonedDateTime, notes: String? = null): String {
        val name = contactName?.trim()?.takeIf { it.isNotEmpty() && !Regex("(?i)contact \\d+").matches(it) }
        return buildString {
            append(kickoff).append(' ').append(if (outbound) OUTBOUND else INBOUND)
            if (outbound) append("; contact listed as: ").append(name ?: "no name on the list")
            if (outbound && !notes.isNullOrBlank()) append("; ").append(notes.trim())
            append(". Now in Pakistan: ").append(WHEN.format(now)).append('.')
            append(" The next days: ").append(week(now.toLocalDate())).append('.')
        }
    }

    /** Urdu weekday names, Monday first: the agent looks days up here instead of working them out. */
    private val URDU_DAYS = listOf("پیر", "منگل", "بدھ", "جمعرات", "جمعہ", "ہفتہ", "اتوار")
    private val DAY = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.ENGLISH)

    /** Today and the six days after it, each with its Urdu name: "today Saturday 17 October (ہفتہ); tomorrow …". */
    fun week(today: java.time.LocalDate): String = (0L..6L).joinToString("; ") { i ->
        val d = today.plusDays(i)
        val label = when (i) { 0L -> "today "; 1L -> "tomorrow (کل) "; 2L -> "day after tomorrow (پرسوں) "; else -> "" }
        label + DAY.format(d) + " (" + URDU_DAYS[d.dayOfWeek.value - 1] + ")"
    }

    /** Config kickoff_mode "realtime_text": the kickoff as realtime input instead of a conversation turn. */
    fun kickoffRealtime(text: String = KICKOFF): String = JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString()

    fun kickoff(text: String = KICKOFF): String = JSONObject().put("clientContent", JSONObject()
        .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
        .put("turnComplete", true)).toString()

    /** One chunk of caller audio, 16 kHz mono PCM16. Built by hand: it runs 100 times a second. */
    fun audio(pcm: ShortArray, count: Int = pcm.size): String {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) { val s = pcm[i].toInt(); bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s shr 8).toByte() }
        return "{\"realtimeInput\":{\"audio\":{\"data\":\"" + Base64.getEncoder().encodeToString(bytes) +
            "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}"
    }

    fun toolResponse(id: String, name: String): String = JSONObject().put("toolResponse", JSONObject().put("functionResponses",
        JSONArray().put(JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", "ok"))))).toString()

    /** What one server message carries. Transcript text is passed on only to the session's in-memory transcript. */
    class Server {
        var setupComplete = false
        val audio = ArrayList<ShortArray>()
        var audioRate = 24_000
        var interrupted = false
        var generationComplete = false
        var turnComplete = false
        val functionCalls = ArrayList<Pair<String, String>>() // id, name
        var voiceActivity: String? = null
        var voiceActivityOffset: String? = null
        var goAwayTimeLeft: String? = null
        var usage: JSONObject? = null
        /** The model sometimes writes a <disconnect/> tag into its transcript instead of calling end_call. */
        var disconnectTag = false
        /** Transcription of the caller's speech / the agent's speech in this message, if any. */
        var callerText: String? = null
        var agentText: String? = null
        var empty = true
    }

    fun parse(text: String): Server {
        val m = JSONObject(text)
        val s = Server()
        s.empty = m.length() == 0
        if (m.has("setupComplete")) s.setupComplete = true
        m.optJSONObject("usageMetadata")?.let { s.usage = it }
        m.optJSONObject("serverContent")?.let { c ->
            c.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    val mime = inline.optString("mimeType")
                    if (!mime.startsWith("audio/pcm")) continue
                    Regex("rate=(\\d+)").find(mime)?.let { s.audioRate = it.groupValues[1].toInt() }
                    val bytes = Base64.getDecoder().decode(inline.optString("data"))
                    s.audio += ShortArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 255) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
                }
            }
            s.interrupted = c.optBoolean("interrupted")
            s.generationComplete = c.optBoolean("generationComplete")
            s.turnComplete = c.optBoolean("turnComplete")
            c.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.contains("<disconnect")) s.disconnectTag = true; s.agentText = it }
            c.optJSONObject("inputTranscription")?.optString("text")?.let { s.callerText = it }
        }
        m.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) calls.optJSONObject(i)?.let { s.functionCalls += it.optString("id") to it.optString("name") }
        }
        m.optJSONObject("voiceActivity")?.let { s.voiceActivity = it.optString("type"); s.voiceActivityOffset = it.optString("audioOffset") }
        m.optJSONObject("goAway")?.let { s.goAwayTimeLeft = it.optString("timeLeft") }
        return s
    }

    /**
     * List prices for gemini-3.8-live (ai.google.dev pricing, 2026-10-14), USD per 1M tokens. Every turn's
     * prompt count includes the whole conversation so far, so a long call costs more per turn.
     */
    fun costUsd(usage: JSONObject): Double {
        var cost = 0.0
        fun details(key: String, text: Double, audio: Double) = usage.optJSONArray(key)?.let { arr ->
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                cost += d.optLong("tokenCount") * (if (d.optString("modality") == "AUDIO") audio else text) / 1e6
            }
        }
        details("promptTokensDetails", 0.75, 3.0)
        details("responseTokensDetails", 4.5, 12.0)
        cost += usage.optLong("thoughtsTokenCount") * 4.5 / 1e6
        return cost
    }
}
