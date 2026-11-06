package dev.callstream.probe.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckingCompensatorTest {
    private fun frame(level: Int) = ShortArray(160) { if (it % 2 == 0) level.toShort() else (-level).toShort() }

    @Test fun noBoostWhileTheAgentIsSilent() {
        val d = DuckingCompensator()
        val pcm = frame(5_000)
        assertEquals(1.0, d.caller(pcm, 5_000.0, 1_000.0), 0.0)
        assertEquals(5_000, pcm[0].toInt())
    }

    @Test fun duckedSpeechOverTheAgentIsBoostedByTheLearnedRatio() {
        val d = DuckingCompensator()
        // Caller alone at RMS 6,000 for 2 s.
        var t = 0.0
        while (t < 2_000) { d.caller(frame(6_000), 6_000.0, t); t += 10 }
        // Agent talks at 3,000 from 2 s; the caller speaks ducked at 1,500 (echo predicted ~450).
        var gain = 1.0
        while (t < 4_000) {
            d.agent(3_000.0, t)
            gain = d.caller(frame(1_500), 1_500.0, t)
            t += 10
        }
        assertTrue("gain $gain", gain > 3.5 && gain <= DuckingCompensator.MAX_GAIN)
        assertTrue(d.boostedFrames > 100)
    }

    @Test fun echoLevelSoundIsLeftAlone() {
        val d = DuckingCompensator()
        var t = 0.0
        while (t < 1_000) { d.caller(frame(6_000), 6_000.0, t); t += 10 }
        // Agent at 6,000; captured sound at 1,000 is within 2x of the predicted echo (0.15 * 6,000).
        var applied = 1.0
        while (t < 2_000) { d.agent(6_000.0, t); applied = d.caller(frame(1_000), 1_000.0, t); t += 10 }
        assertEquals(1.0, applied, 0.0)
    }

    @Test fun limiterKeepsPeaksBelowClipping() {
        val d = DuckingCompensator()
        var t = 0.0
        while (t < 1_000) { d.caller(frame(20_000), 20_000.0, t); t += 10 }
        val pcm = frame(10_000)
        var applied = 1.0
        while (t < 1_500) { d.agent(2_000.0, t); applied = d.caller(frame(10_000), 10_000.0, t); t += 10 }
        d.agent(2_000.0, t); d.caller(pcm, 10_000.0, t)
        assertTrue(applied <= DuckingCompensator.LIMIT_PEAK / 10_000 + 1e-9)
        assertTrue(pcm.all { kotlin.math.abs(it.toInt()) <= 26_000 })
    }

    @Test fun turnTakingIsNotMistakenForEcho() {
        val d = DuckingCompensator()
        // Agent speaks at 5,000 until 3 s; the caller answers at 1,500 from 3.25 s.
        var t = 0.0
        while (t < 3_000) { d.agent(5_000.0, t); t += 10 }
        // The probe sees the caller's answer follow the agent and reports it as "echo" 820 ms late at 0.44.
        d.echoEstimate(820.0, 0.4, 0.44)
        d.echoEstimate(700.0, 0.6, 0.2)
        assertEquals(DuckingCompensator.DEFAULT_ECHO_LAG_MS, d.echoLagMs, 0.0)
        assertTrue("the start of the caller's answer must reach the agent", !d.isEcho(1_500.0, 3_250.0))
        // A plausible estimate is still adopted.
        d.echoEstimate(120.0, 0.6, 0.18)
        assertEquals(120.0, d.echoLagMs, 0.0)
    }
}
