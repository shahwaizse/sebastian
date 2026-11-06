package dev.callstream.probe.shell

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Gemini's 24 kHz agent audio to the 16 kHz the carrier track takes: 2x zero-stuffing to 48 kHz, a
 * windowed-sinc low-pass below 8 kHz, every third sample kept (polyphase, only the non-zero taps are
 * summed). Streaming: chunks of any size give the same output as one long buffer. Group delay ~0.65 ms.
 * One thread at a time.
 */
internal class Resampler24To16 {
    private var buf = ShortArray(0)
    /** Input index (24 kHz) of buf[0]. */
    private var bufStart = 0L
    private var nextOut = 0L

    fun process(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        buf = buf + input
        val lastIn = bufStart + buf.size - 1
        // Output n sits at 48 kHz index 3n; it needs input index floor(3n / 2).
        val count = maxOf(0L, (2 * lastIn + 1) / 3 + 1 - nextOut).toInt()
        val out = ShortArray(count)
        for (o in 0 until count) {
            val m = 3 * (nextOut + o)
            var acc = 0.0
            var k = (m % 2).toInt()
            while (k < TAPS) {
                val xi = (m - k) / 2
                if (xi < bufStart) break
                acc += H[k] * buf[(xi - bufStart).toInt()]
                k += 2
            }
            out[o] = acc.roundToInt().coerceIn(-32_768, 32_767).toShort()
        }
        nextOut += count
        // Keep only the inputs the next output's taps can still reach.
        val keepFrom = maxOf(bufStart, (3 * nextOut - TAPS) / 2)
        if (keepFrom > bufStart) { buf = buf.copyOfRange((keepFrom - bufStart).toInt(), buf.size); bufStart = keepFrom }
        return out
    }

    /** A new utterance (after an interruption): forget the previous audio. */
    fun reset() { buf = ShortArray(0); bufStart = 0; nextOut = 0 }

    companion object {
        private const val TAPS = 63
        /** Low-pass at 7.4 kHz on the 48 kHz grid, gain 2 to make up for the zero-stuffing. */
        private val H = DoubleArray(TAPS) { n ->
            val x = n - (TAPS - 1) / 2.0
            val fc = 7_400.0 / 48_000.0
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            2 * sinc * (0.54 - 0.46 * cos(2 * PI * n / (TAPS - 1)))
        }
    }
}
