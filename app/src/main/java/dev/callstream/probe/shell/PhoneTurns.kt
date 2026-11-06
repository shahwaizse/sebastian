package dev.callstream.probe.shell

/**
 * Phone-side turn detection (config turn_detection "phone"): the phone, not Gemini, decides when the caller starts and
 * stops talking, and tells Gemini with activityStart / activityEnd. On 2026-10-18 Gemini's own detection, on narrowband
 * call audio, missed the caller talking over the greeting and closed turns mid-sentence (even at 500 ms silence) while
 * the phone's level detector heard continuous speech.
 *
 * Start: at least [startMs] of frames at or above [speechRms] within the last [WINDOW_MS] (density, so the short dips
 * between syllables don't reset it and sparse clicks don't add up), not counting frames flagged as the agent's echo.
 * End: [silenceMs] without a frame at or above [endRms], or [maxTurnMs] in all (then quiet is needed before the next
 * start). Capture thread only.
 */
internal class PhoneTurns(
    private val speechRms: Double = SPEECH_RMS,
    private val startMs: Double = START_MS,
    private val silenceMs: Double = SILENCE_MS,
    private val endRms: Double = END_RMS,
    private val maxTurnMs: Double = MAX_TURN_MS,
) {
    enum class Event { NONE, START, END }
    private val window = ArrayDeque<Pair<Double, Boolean>>() // frame time, speech
    var speaking = false
        private set
    var startedAtMs = 0.0
        private set
    var lastVoiceMs = 0.0
        private set
    /** After a turn cut off at [maxTurnMs], a new one needs the caller to go quiet first. */
    private var needQuiet = false

    fun frame(rms: Double, atMs: Double, echo: Boolean = false): Event {
        val speech = rms >= speechRms && !echo
        window.addLast(atMs to speech)
        while (window.first().first <= atMs - WINDOW_MS) window.removeFirst()
        if (!speaking) {
            if (!speech) needQuiet = false
            if (speech && !needQuiet && window.count { it.second } * FRAME_MS >= startMs) {
                speaking = true; startedAtMs = atMs; lastVoiceMs = atMs
                return Event.START
            }
            return Event.NONE
        }
        if (rms >= endRms && !echo) lastVoiceMs = atMs
        if (atMs - lastVoiceMs >= silenceMs || atMs - startedAtMs >= maxTurnMs) {
            needQuiet = atMs - lastVoiceMs < silenceMs
            speaking = false; window.clear()
            return Event.END
        }
        return Event.NONE
    }

    companion object {
        const val FRAME_MS = 10.0
        const val WINDOW_MS = 300.0
        /** 2026-10-18 traces: caller speech over the agent averaged 1,000-7,500 (ducked continuations ~1,000-1,400);
         * the sounds that falsely cut the agent were 200-1,100 and under 250 ms. */
        const val SPEECH_RMS = 800.0
        const val START_MS = 200.0
        /** Quieter tail syllables still count as the caller talking. */
        const val END_RMS = 400.0
        const val SILENCE_MS = 500.0
        /** A stuck detector (steady line noise) mustn't hold the turn open forever. */
        const val MAX_TURN_MS = 30_000.0
    }
}
