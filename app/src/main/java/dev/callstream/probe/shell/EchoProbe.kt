package dev.callstream.probe.shell

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Estimates how much of the agent audio written to the carrier uplink comes back in the
 * captured downlink, from 10 ms loudness envelopes only (no audio is kept). Reports the
 * best-matching delay, the envelope correlation at that delay, and a linear level gain.
 */
internal class EchoProbe {
    private val agent = DoubleArray(BINS)
    private val captured = DoubleArray(BINS)
    private val agentStamp = LongArray(BINS) { -1 }
    private val capturedStamp = LongArray(BINS) { -1 }
    private var latestBin = -1L

    @Synchronized fun agentWritten(rms: Double, atMs: Double) = put(agent, agentStamp, rms, atMs)
    @Synchronized fun captured(rms: Double, atMs: Double) = put(captured, capturedStamp, rms, atMs)

    private fun put(values: DoubleArray, stamps: LongArray, rms: Double, atMs: Double) {
        val bin = (atMs / BIN_MS).toLong()
        val i = (bin % BINS).toInt()
        if (stamps[i] == bin) values[i] = maxOf(values[i], rms) else { values[i] = rms; stamps[i] = bin }
        latestBin = maxOf(latestBin, bin)
    }

    /** Null when the agent was not audible enough in the window to estimate echo. */
    @Synchronized fun report(windowMs: Double = 5_000.0): Map<String, Any>? {
        if (latestBin < 0) return null
        val first = latestBin - (windowMs / BIN_MS).toLong()
        var best: Triple<Int, Double, Double>? = null
        for (lag in 0..MAX_LAG_BINS) {
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            var activeAgent = 0
            for (b in first..latestBin - lag) {
                val i = (b % BINS).toInt(); val j = ((b + lag) % BINS).toInt()
                if (b < 0 || agentStamp[i] != b || capturedStamp[j] != b + lag) continue
                xs += agent[i]; ys += captured[j]
                if (agent[i] >= TurnLatencyTracker.AGENT_RMS) activeAgent++
            }
            if (activeAgent < MIN_ACTIVE_BINS) continue
            val r = pearson(xs.map { ln(1 + it) }, ys.map { ln(1 + it) }) ?: continue
            if (best == null || r > best.second) best = Triple(lag, r, slope(xs, ys))
        }
        val (lag, r, gain) = best ?: return null
        return mapOf("echo_lag_ms" to lag * BIN_MS, "echo_correlation" to r, "echo_gain" to gain)
    }

    private fun pearson(x: List<Double>, y: List<Double>): Double? {
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (k in x.indices) { val dx = x[k] - mx; val dy = y[k] - my; sxy += dx * dy; sxx += dx * dx; syy += dy * dy }
        return if (sxx <= 0 || syy <= 0) null else sxy / sqrt(sxx * syy)
    }
    private fun slope(x: List<Double>, y: List<Double>): Double {
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0
        for (k in x.indices) { sxy += (x[k] - mx) * (y[k] - my); sxx += (x[k] - mx) * (x[k] - mx) }
        return if (sxx <= 0) 0.0 else sxy / sxx
    }

    companion object {
        private const val BIN_MS = 10.0
        private const val BINS = 800 // 8 s history
        private const val MAX_LAG_BINS = 100 // search echo delays up to 1 s
        private const val MIN_ACTIVE_BINS = 50 // need ≥0.5 s of audible agent audio
    }
}
