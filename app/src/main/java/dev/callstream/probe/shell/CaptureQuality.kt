package dev.callstream.probe.shell

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Caller-audio quality from the carrier downlink, as numbers only (no PCM kept).
 *
 * - Clipping: samples at full scale, plus flat-topped runs at any level (audio clipped upstream,
 *   then attenuated, no longer reaches full scale but keeps its flat tops).
 * - Bandwidth: share of speech energy above 5 kHz. Narrowband carrier audio (AMR-NB, 8 kHz)
 *   has none, so it sits near the filter floor (below about -50 dB); wideband (AMR-WB, EVS)
 *   speech typically measures -15 to -35 dB.
 *
 * [frame] runs on the capture thread; [snapshot] is read for reporting and may be slightly stale.
 */
internal class CaptureQuality {
    private val history = DoubleArray(TAPS)
    private var pos = 0
    private var lastSample = 0
    private var runLength = 0
    private var runCounted = false

    private var speechFrames = 0L
    private var clippedSamples = 0L
    private var flatTopRuns = 0L
    private var peak = 0
    private var speechEnergy = 0.0
    private var highBandEnergy = 0.0

    /** Returns the frame's high-band share in dB when it is speech, else null. */
    fun frame(pcm: ShortArray, speech: Boolean): Double? {
        var total = 0.0; var high = 0.0
        for (s in pcm) {
            val v = s.toInt()
            val mag = abs(v)
            if (mag > peak) peak = mag
            if (mag >= CLIP_LEVEL) clippedSamples++
            if (v == lastSample && mag >= FLAT_TOP_LEVEL) {
                runLength++
                if (runLength >= FLAT_TOP_RUN - 1 && !runCounted) { flatTopRuns++; runCounted = true }
            } else { runLength = 0; runCounted = false }
            lastSample = v
            history[pos] = v.toDouble()
            var y = 0.0
            var i = pos
            for (k in 0 until TAPS) {
                y += HIGH_PASS[k] * history[i]
                i = if (i == 0) TAPS - 1 else i - 1
            }
            pos = if (pos == TAPS - 1) 0 else pos + 1
            total += v.toDouble() * v; high += y * y
        }
        if (!speech || total <= 0.0) return null
        speechFrames++; speechEnergy += total; highBandEnergy += high
        return db(high, total)
    }

    fun snapshot(): Map<String, Any> = mapOf(
        "caller_speech_frames" to speechFrames,
        "caller_clipped_samples" to clippedSamples,
        "caller_flat_top_runs" to flatTopRuns,
        "caller_sample_peak" to peak,
        "caller_highband_db" to if (speechEnergy > 0) db(highBandEnergy, speechEnergy) else "n/a")

    companion object {
        const val CLIP_LEVEL = 32_700
        const val FLAT_TOP_LEVEL = 8_000
        const val FLAT_TOP_RUN = 3
        const val CUTOFF_HZ = 5_000.0
        private const val TAPS = 95
        private fun db(part: Double, whole: Double) = 10 * log10(maxOf(part, 1e-9) / whole)

        /** Blackman-windowed sinc high-pass at [CUTOFF_HZ] for 16 kHz audio (spectral inversion of a low-pass). */
        private val HIGH_PASS: DoubleArray = run {
            val fc = CUTOFF_HZ / 16_000.0
            val m = TAPS - 1
            val lowPass = DoubleArray(TAPS) { n ->
                val x = n - m / 2.0
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                sinc * (0.42 - 0.5 * cos(2 * PI * n / m) + 0.08 * cos(4 * PI * n / m))
            }
            val gain = lowPass.sum()
            DoubleArray(TAPS) { n -> (if (n == m / 2) 1.0 else 0.0) - lowPass[n] / gain }
        }
    }
}
