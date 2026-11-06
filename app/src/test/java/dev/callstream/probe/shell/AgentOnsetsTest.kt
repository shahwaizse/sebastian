package dev.callstream.probe.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentOnsetsTest {
    @Test fun firstEnergyAndEnergyAfterAGapAreOnsets() {
        val o = AgentOnsets()
        assertNull(o.chunk(10.0, 0.0)) // quiet
        assertEquals(1, o.chunk(500.0, 10.0))
        assertNull(o.chunk(500.0, 20.0))
        assertNull(o.chunk(10.0, 200.0))
        assertNull(o.chunk(500.0, 250.0)) // 230 ms pause inside an utterance
        assertEquals(2, o.chunk(500.0, 600.0)) // 350 ms of quiet before it
        assertEquals(2, o.count)
    }
}
