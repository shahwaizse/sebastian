package dev.callstream.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamViewTest {
    private fun phase(status: String) = StreamView.parse(status).phase

    @Test fun readsEachControllerState() {
        assertEquals(Phase.CONNECTING, phase("UNKNOWN — connecting to phone helper"))
        assertEquals(Phase.OFF, phase("enabled=false\nOFF — calls are yours\nmicMuted=false"))
        assertEquals(Phase.READY, phase("enabled=true\nREADY — AI answers once the agent is connected; mic muted during AI calls"))
        assertEquals(Phase.RINGING, phase("enabled=true\nPREPARING — connecting agent while ringing"))
        assertEquals(Phase.RINGING, phase("enabled=true\nANSWERING — agent ready"))
        assertEquals(Phase.ON_CALL, phase("enabled=true\nAI CALL — Pixel mic muted\nstatus"))
        assertEquals(Phase.ERROR, phase("enabled=true\nERROR — IllegalStateException: boom"))
    }

    @Test fun outboundStatesCarryTheirDetail() {
        assertEquals(StreamView(Phase.DIALING, "Ahmed Raza (call 2)"), StreamView.parse("enabled=true\nOUTBOUND — calling Ahmed Raza (call 2)"))
        assertEquals(StreamView(Phase.READY, "callback at 11:30"), StreamView.parse("enabled=true\nOUTBOUND — waiting: callback at 11:30"))
        assertEquals(StreamView(Phase.ON_CALL, "outbound"), StreamView.parse("enabled=true\nAI CALL — outbound; mic muted"))
    }

    @Test fun profileNamesReadAsTitles() {
        assertEquals("Acme Devanagari", profileTitle("acme-devanagari"))
        assertEquals("Fbi Prank", profileTitle("fbi-prank"))
    }
}
