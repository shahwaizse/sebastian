package dev.callstream.probe.shell

/**
 * Marks where the agent starts speaking: the first energetic chunk after [GAP_MS] of quiet (or the
 * first ever). Agent audio is clean digital TTS, so these onsets are exact, unlike the caller-side
 * energy gate. One instance per point in the pipeline (received from Retell, written into the call);
 * the per-turn report pairs them with Retell's word timestamps. Single-threaded per instance.
 */
internal class AgentOnsets {
    private var lastEnergyMs = Double.NEGATIVE_INFINITY
    var count = 0
        private set

    /** Returns the onset number (1-based) when this chunk starts a new utterance, else null. */
    fun chunk(rms: Double, atMs: Double): Int? {
        if (rms < TurnLatencyTracker.AGENT_RMS) return null
        val onset = atMs - lastEnergyMs >= GAP_MS
        lastEnergyMs = atMs
        return if (onset) ++count else null
    }

    companion object {
        const val GAP_MS = 300.0
    }
}
