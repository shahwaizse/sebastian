package dev.callstream.probe.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EchoProbeTest {
    private fun envelope(seed: Int, n: Int): DoubleArray {
        val rnd = Random(seed)
        // Speech-like on/off envelope: bursts of 50–300 ms.
        val out = DoubleArray(n); var i = 0
        while (i < n) {
            val len = 5 + rnd.nextInt(25); val level = if (rnd.nextBoolean()) 500.0 + rnd.nextInt(3000) else 5.0
            for (k in 0 until len) if (i + k < n) out[i + k] = level * (0.8 + 0.4 * rnd.nextDouble())
            i += len
        }
        return out
    }

    @Test fun findsDelayAndGainOfEcho() {
        val probe = EchoProbe(); val agent = envelope(1, 500)
        for (k in agent.indices) {
            probe.agentWritten(agent[k], k * 10.0)
            val echoed = if (k >= 25) agent[k - 25] * 0.1 else 0.0
            probe.captured(echoed + 20.0, k * 10.0)
        }
        val r = probe.report()!!
        assertEquals(250.0, r["echo_lag_ms"] as Double, 0.1)
        assertTrue((r["echo_correlation"] as Double) > 0.9)
        assertEquals(0.1, r["echo_gain"] as Double, 0.02)
    }

    @Test fun lowCorrelationWithoutEcho() {
        val probe = EchoProbe(); val agent = envelope(2, 500); val caller = envelope(3, 500)
        for (k in agent.indices) { probe.agentWritten(agent[k], k * 10.0); probe.captured(caller[k], k * 10.0) }
        assertTrue((probe.report()!!["echo_correlation"] as Double) < 0.5)
    }

    @Test fun silentAgentGivesNoEstimate() {
        val probe = EchoProbe()
        for (k in 0 until 500) { probe.agentWritten(0.0, k * 10.0); probe.captured(300.0, k * 10.0) }
        assertNull(probe.report())
    }
}
