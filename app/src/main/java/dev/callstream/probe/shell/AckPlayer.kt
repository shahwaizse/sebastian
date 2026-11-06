package dev.callstream.probe.shell

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Hides Retell's reply time: when the caller finishes speaking, play a short acknowledgement in the
 * agent's own voice ("हम्म", "मैं आप को बताती हूँ", "समझ गई", "उम्म": the published agent's voice, recorded from
 * Retell) straight into the call, picked at random, never the same clip twice in a row. Retell's real answer takes ~1.2-2 s from the caller's last word (Oregon-hosted pipeline);
 * the acknowledgement is heard after ~0.4 s of silence plus the phone and carrier path.
 *
 * Plays once per caller utterance, only after real speech (>= [MIN_SPEECH_MS] of frames >= [SPEECH_RMS])
 * followed by [SILENCE_MS] of quiet, only while the agent is idle and nothing is queued, and at most
 * once per [COOLDOWN_MS]. Clips are scaled to the agent's live level. While a clip plays (and its echo
 * comes back), quiet caller-side frames are zeroed on the way to Retell, so the clip's echo isn't heard
 * as the caller. Retell's echo canceller can't remove it: the clip isn't in its reference.
 *
 * [caller] runs on the capture thread, [agentWritten] on the playback thread.
 */
internal class AckPlayer(private val clips: List<ShortArray>, private val silenceMs: Double = SILENCE_MS,
                         private val agentIdleMs: Double = AGENT_IDLE_MS, private val random: Random = Random.Default) {
    private val clipRms = clips.map { c -> sqrt(c.sumOf { it.toDouble() * it } / c.size.coerceAtLeast(1)) }
    private var speechMs = 0.0
    private var lastSpeechMs = Double.NEGATIVE_INFINITY
    private var armed = false
    private var lastAckMs = Double.NEGATIVE_INFINITY
    private var lastAgentEnergyMs = Double.NEGATIVE_INFINITY
    private var agentLevel = 0.0
    private var lastClip = -1
    /** Trace ms until which quiet caller frames are zeroed (clip playing plus its echo). */
    var gateUntilMs = Double.NEGATIVE_INFINITY
        @Synchronized get
        private set
    var played = 0
        @Synchronized get
        private set
    /** Outcome of the latest end-of-utterance decision ("played", or why it was skipped), and how many so far. */
    var lastDecision = ""
        @Synchronized get
        private set
    var decisions = 0
        @Synchronized get
        private set
    /** Last caller speech frame (trace ms) of the utterance the latest clip answers. */
    var answeredSpeechEndMs = Double.NEGATIVE_INFINITY
        @Synchronized get
        private set

    /** Agent audio written into the call (not acknowledgements), as frame RMS. */
    @Synchronized fun agentWritten(rms: Double, atMs: Double) {
        if (rms < TurnLatencyTracker.AGENT_RMS) return
        lastAgentEnergyMs = atMs
        agentLevel = if (agentLevel == 0.0) rms else agentLevel + 0.01 * (rms - agentLevel)
    }

    /**
     * One 10 ms caller frame. Returns a clip (already scaled) to play now, or null.
     * [agentPending]: agent audio is queued or arriving, so Retell's reply has started.
     */
    @Synchronized fun caller(rms: Double, atMs: Double, agentPending: Boolean): ShortArray? {
        // Only speech made while the agent is silent counts: during agent audio (or one of our clips) the
        // capture carries echo and overlap, and build 48 armed on that and spoke "जी" right after the agent.
        val agentQuiet = atMs - lastAgentEnergyMs > AGENT_QUIET_FOR_SPEECH_MS && atMs > gateUntilMs
        if (!agentQuiet) { speechMs = 0.0; armed = false; return null }
        if (rms >= SPEECH_RMS) {
            speechMs += FRAME_MS; lastSpeechMs = atMs
            if (speechMs >= MIN_SPEECH_MS) armed = true
            return null
        }
        if (rms >= QUIET_RMS) return null // in between: neither speech nor silence
        if (!armed || atMs - lastSpeechMs < silenceMs) return null
        // The caller has finished an utterance.
        armed = false; speechMs = 0.0; decisions++
        lastDecision = when {
            atMs - lastAgentEnergyMs <= agentIdleMs -> "skipped_agent_speaking"
            agentPending -> "skipped_reply_arriving"
            atMs - lastAckMs < COOLDOWN_MS -> "skipped_cooldown"
            clips.isEmpty() -> "skipped_no_clips"
            else -> "played"
        }
        if (lastDecision != "played") return null
        val i = if (clips.size == 1) 0 else (random.nextInt(clips.size - 1).let { if (it >= lastClip && lastClip >= 0) it + 1 else it })
        lastClip = i
        val target = if (agentLevel > 0) agentLevel else clipRms[i]
        val scale = (target / clipRms[i]).coerceIn(MIN_SCALE, MAX_SCALE)
        val out = ShortArray(clips[i].size) { (clips[i][it] * scale).toInt().coerceIn(-32_767, 32_767).toShort() }
        lastAckMs = atMs; played++; answeredSpeechEndMs = lastSpeechMs
        gateUntilMs = atMs + out.size / 16.0 + ECHO_TAIL_MS
        return out
    }

    companion object {
        const val FRAME_MS = 10.0
        const val SPEECH_RMS = 1_000.0
        const val QUIET_RMS = 600.0
        const val MIN_SPEECH_MS = 400.0
        /** 250 ms (build 47-48) acknowledged pauses inside sentences, 400 ms (49) felt late. */
        const val SILENCE_MS = 300.0
        /** The agent must have been silent this long before caller speech counts (its echo trails it). */
        const val AGENT_QUIET_FOR_SPEECH_MS = 500.0
        /** Build 45-47 used 800 ms and skipped an acknowledgement 0.4 s after an agent fragment ended. */
        const val AGENT_IDLE_MS = 300.0
        const val COOLDOWN_MS = 2_000.0
        const val ECHO_TAIL_MS = 400.0 // echo returns 10-180 ms after it's written; margin for the carrier
        /** Caller-side frames below this are zeroed while the gate is open; real speech is ~6,000. */
        const val GATE_RMS = 1_500.0
        const val MIN_SCALE = 0.5
        const val MAX_SCALE = 6.0
    }
}
