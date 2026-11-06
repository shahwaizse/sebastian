package dev.callstream.probe.shell

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The agent's 16 kHz audio to 48 kHz for the WhatsApp injector track: 3x zero-stuffing and a windowed-sinc low-pass
 * below 8 kHz, as three polyphase filters (only the non-zero taps are summed). On the Redmi a 16 kHz injector made
 * the remote submix run in 192 ms bursts, three of its 64 ms cycles, and the agent's audio underran (choppy for the
 * caller); at 48 kHz it flowed with no stall (2026-10-25). Streaming: chunks of any size give the same output as one
 * long buffer. Group delay ~0.5 ms. One thread at a time.
 */
internal class Upsampler16To48 {
    /** The last [PHASE_TAPS] - 1 inputs, newest last. */
    private val history = ShortArray(PHASE_TAPS - 1)

    fun process(input: ShortArray): ShortArray {
        val out = ShortArray(input.size * 3)
        val x = history + input
        for (n in input.indices) {
            val newest = n + history.size
            for (p in 0 until 3) {
                var acc = 0.0
                for (k in 0 until PHASE_TAPS) acc += H[3 * k + p] * x[newest - k]
                out[3 * n + p] = acc.roundToInt().coerceIn(-32_768, 32_767).toShort()
            }
        }
        x.copyInto(history, 0, x.size - history.size, x.size)
        return out
    }

    fun reset() = history.fill(0)

    companion object {
        private const val PHASE_TAPS = 16
        private const val TAPS = 3 * PHASE_TAPS

        /** Low-pass at 7.6 kHz for 48 kHz, Blackman window, gain 3 (each output phase sums every third tap). */
        private val H: DoubleArray = run {
            val fc = 7_600.0 / 48_000.0
            val mid = (TAPS - 1) / 2.0
            val h = DoubleArray(TAPS) { i ->
                val t = i - mid
                val sinc = if (t == 0.0) 2 * fc else sin(2 * PI * fc * t) / (PI * t)
                val w = 0.42 - 0.5 * cos(2 * PI * i / (TAPS - 1)) + 0.08 * cos(4 * PI * i / (TAPS - 1))
                sinc * w
            }
            val sum = h.sum()
            DoubleArray(TAPS) { h[it] * 3.0 / sum }
        }
    }
}
