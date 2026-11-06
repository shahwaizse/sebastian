package dev.callstream.probe.shell

import kotlin.math.abs

/**
 * Undoes upstream double-talk ducking of the caller before their audio is sent to the agent.
 *
 * While the caller hears the agent, their handset or network attenuated their voice by 9-18 dB
 * (2026-10-07: mean frame RMS 6,200-6,750 alone vs 680-2,530 over the agent, phone at the ear), so
 * Retell neither transcribed nor acted on interruptions. Agent echo also comes back (~15% of the
 * agent's level, 10-180 ms later), so the caller isn't simply amplified: only frames clearly above
 * the predicted echo are boosted, by the measured ratio of the caller's normal level to their level
 * over the agent, capped at [MAX_GAIN], with a peak limiter. Echo-level frames are flagged by [isEcho].
 *
 * [agent] runs on the playback thread, [caller] on the capture thread.
 */
internal class DuckingCompensator {
    private val agentRms = DoubleArray(BINS)
    private val agentBin = LongArray(BINS) { -1 }
    private var lastAgentEnergyMs = Double.NEGATIVE_INFINITY
    private var aloneLevel = 0.0   // caller speech level with the agent silent (EMA of frame RMS)
    private var overLevel = 0.0    // caller speech level while the agent is audible
    var echoGain = DEFAULT_ECHO_GAIN
    var echoLagMs = DEFAULT_ECHO_LAG_MS
    var boostedFrames = 0L
        @Synchronized get
        private set
    var gainSum = 0.0
        @Synchronized get
        private set

    /** Agent audio written into the call, as a 10 ms frame RMS. */
    @Synchronized fun agent(rms: Double, atMs: Double) {
        val bin = (atMs / BIN_MS).toLong()
        val i = (bin % BINS).toInt()
        if (agentBin[i] == bin) agentRms[i] = maxOf(agentRms[i], rms) else { agentRms[i] = rms; agentBin[i] = bin }
        if (rms >= TurnLatencyTracker.AGENT_RMS) lastAgentEnergyMs = atMs
    }

    /**
     * Adopts the echo probe's latest estimate only when it's plausible echo. Turn-taking also correlates the agent's
     * envelope with the caller's (they answer ~0.5-0.9 s after the agent stops): on 2026-10-17 the probe reported
     * lags of 470-850 ms and gains up to 0.44 at correlation 0.35-0.46, and adopting them gated 56-69% of the
     * caller audio, including the start of their answers. Measured echo is 10-180 ms at ~0.15.
     */
    @Synchronized fun echoEstimate(lagMs: Double, correlation: Double, gain: Double) {
        if (correlation >= 0.5 && lagMs <= MAX_ECHO_LAG_MS && gain in 0.0..MAX_ECHO_GAIN) {
            echoLagMs = lagMs; echoGain = maxOf(gain, DEFAULT_ECHO_GAIN)
        }
    }

    /** This caller frame is most likely the agent's own echo: the agent is audible and the frame isn't clearly
     * above the predicted echo. With no echo canceller in the path, such frames are kept from the agent. */
    @Synchronized fun isEcho(rms: Double, atMs: Double): Boolean {
        if (atMs - lastAgentEnergyMs >= AUDIBLE_MS) return false
        val echo = echoGain * agentNear(atMs - echoLagMs)
        return echo > 0 && rms < ECHO_MARGIN * echo
    }

    /** Returns the gain to apply to this caller frame (1.0 = unchanged); boosts [pcm] in place. */
    @Synchronized fun caller(pcm: ShortArray, rms: Double, atMs: Double): Double {
        val agentAudible = atMs - lastAgentEnergyMs < AUDIBLE_MS
        if (!agentAudible) {
            if (rms >= SPEECH_RMS) aloneLevel = ema(aloneLevel, rms)
            return 1.0
        }
        val echo = echoGain * agentNear(atMs - echoLagMs)
        if (rms < SPEECH_FLOOR_RMS || rms < ECHO_MARGIN * echo) return 1.0
        overLevel = ema(overLevel, rms)
        val gain = if (aloneLevel > 0 && overLevel > 0) (aloneLevel / overLevel).coerceIn(1.0, MAX_GAIN) else DEFAULT_GAIN
        if (gain <= 1.0) return 1.0
        var peak = 0
        for (s in pcm) peak = maxOf(peak, abs(s.toInt()))
        val applied = minOf(gain, if (peak > 0) LIMIT_PEAK / peak else gain)
        if (applied <= 1.0) return 1.0
        for (i in pcm.indices) pcm[i] = (pcm[i] * applied).toInt().coerceIn(-32_767, 32_767).toShort()
        boostedFrames++; gainSum += applied
        return applied
    }

    private fun agentNear(atMs: Double): Double {
        var best = 0.0
        val center = (atMs / BIN_MS).toLong()
        for (b in center - SPREAD_BINS..center + SPREAD_BINS) {
            if (b < 0) continue
            val i = (b % BINS).toInt()
            if (agentBin[i] == b) best = maxOf(best, agentRms[i])
        }
        return best
    }

    private fun ema(old: Double, value: Double) = if (old == 0.0) value else old + 0.02 * (value - old)

    companion object {
        private const val BIN_MS = 10.0
        private const val BINS = 300 // 3 s of agent history
        private const val SPREAD_BINS = 6 // echo window +/-60 ms around the estimated lag
        const val AUDIBLE_MS = 600.0 // ducking lasts while the caller hears the agent, a little after we write
        const val SPEECH_RMS = 1_000.0
        const val SPEECH_FLOOR_RMS = 300.0
        const val ECHO_MARGIN = 2.0
        const val MAX_GAIN = 4.0 // +12 dB
        const val DEFAULT_GAIN = 2.0 // +6 dB until the caller's levels are learned
        const val LIMIT_PEAK = 26_000.0
        const val DEFAULT_ECHO_GAIN = 0.15 // measured 0.14-0.17 on 2026-10-07
        const val DEFAULT_ECHO_LAG_MS = 150.0
        const val MAX_ECHO_LAG_MS = 250.0
        const val MAX_ECHO_GAIN = 0.3
    }
}
