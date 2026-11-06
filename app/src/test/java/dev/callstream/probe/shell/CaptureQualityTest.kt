package dev.callstream.probe.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CaptureQualityTest {
    private fun tone(hz: Double, amplitude: Double, frames: Int, sampleOffset: Int = 0) = List(frames) { f ->
        ShortArray(160) { i -> (amplitude * sin(2 * PI * hz * (sampleOffset + f * 160 + i) / 16_000.0)).toInt().toShort() }
    }

    @Test fun narrowbandSpeechHasNoHighBand() {
        val q = CaptureQuality()
        tone(1_000.0, 8_000.0, 50).forEach { q.frame(it, speech = true) }
        tone(3_400.0, 8_000.0, 50, 8_000).forEach { q.frame(it, speech = true) }
        assertTrue((q.snapshot()["caller_highband_db"] as Double) < -50)
    }

    @Test fun wideBandContentIsMeasured() {
        val q = CaptureQuality()
        // Equal power at 1 kHz and 6 kHz: half the energy is above the cutoff (-3 dB).
        val frames = tone(1_000.0, 6_000.0, 100).zip(tone(6_000.0, 6_000.0, 100)) { a, b ->
            ShortArray(160) { (a[it] + b[it]).toShort() }
        }
        frames.forEach { q.frame(it, speech = true) }
        assertEquals(-3.0, q.snapshot()["caller_highband_db"] as Double, 0.5)
    }

    @Test fun silenceIsNotCountedAsSpeech() {
        val q = CaptureQuality()
        tone(6_000.0, 6_000.0, 10).forEach { q.frame(it, speech = false) }
        assertEquals(0L, q.snapshot()["caller_speech_frames"])
        assertEquals("n/a", q.snapshot()["caller_highband_db"])
    }

    @Test fun clippingAndFlatTopsAreCounted() {
        val q = CaptureQuality()
        // A loud tone hard-clipped at full scale, then the same audio attenuated after clipping.
        val hard = List(10) { fr -> ShortArray(160) { i -> (60_000.0 * sin(2 * PI * 500 * (fr * 160 + i) / 16_000.0)).coerceIn(-32_767.0, 32_767.0).toInt().toShort() } }
        hard.forEach { q.frame(it, speech = true) }
        val s = q.snapshot()
        assertTrue((s["caller_clipped_samples"] as Long) > 100)
        assertTrue((s["caller_flat_top_runs"] as Long) >= 20) // two flat tops per 2 ms cycle
        assertEquals(32_767, s["caller_sample_peak"])

        val q2 = CaptureQuality()
        hard.forEach { f -> q2.frame(ShortArray(160) { (f[it] / 2).toShort() }, speech = true) }
        assertEquals(0L, q2.snapshot()["caller_clipped_samples"])
        assertTrue((q2.snapshot()["caller_flat_top_runs"] as Long) >= 20)
    }

    @Test fun cleanToneHasNoFlatTops() {
        val q = CaptureQuality()
        tone(440.0, 20_000.0, 50).forEach { q.frame(it, speech = true) }
        assertEquals(0L, q.snapshot()["caller_flat_top_runs"])
    }
}
