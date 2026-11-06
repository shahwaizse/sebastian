package dev.callstream.probe.shell

import org.junit.Assert.*
import org.junit.Test

class TurnLatencyTrackerTest {
    private val events = mutableListOf<Pair<String, Map<String, Any>>>()
    private val tracker = TurnLatencyTracker { name, fields -> events += name to fields }
    private fun caller(start: Double) {
        repeat(10) { val id = tracker.callerAudio(500.0, 10.0, start + it * 10); tracker.sentAudio(500.0, start + it * 10 + 20, id) }
        tracker.callerAudio(0.0, 10.0, start + 390)
    }

    @Test fun measuresReplyFromLastEnergyRatherThanSilenceConfirmation() {
        caller(100.0)
        val id = tracker.agentAudio(500.0, 900.0)
        tracker.agentWritten(id, 930.0, 20.0, 10.0)
        val response = events.single { it.first == "turn_response" }.second
        assertEquals(740.0, response["caller_end_to_first_write_ms"])
        assertEquals(30.0, response["agent_receive_to_write_ms"])
        assertEquals(690.0, events.single { it.first == "agent_activity_start" }.second["last_sent_energy_to_agent_pcm_ms"])
    }

    @Test fun ignoresShortNoiseAndDoesNotInventReplyForGreeting() {
        tracker.callerAudio(1000.0, 10.0, 10.0); tracker.callerAudio(0.0, 10.0, 20.0)
        assertNull(tracker.agentAudio(500.0, 100.0))
        assertFalse(events.any { it.first == "caller_activity_start" || it.first == "turn_response" })
    }

    @Test fun emitsOnlyOneResponsePerSegmentAndReportsUnansweredAtEnd() {
        caller(0.0)
        val id = tracker.agentAudio(500.0, 600.0)
        tracker.agentWritten(id, 610.0, 5.0, 5.0); tracker.agentWritten(id, 620.0, 5.0, 5.0)
        assertEquals(1, events.count { it.first == "turn_response" })
        tracker.agentAudio(0.0, 1000.0); caller(1200.0); tracker.finish()
        assertEquals(1, events.count { it.first == "turn_unanswered" })
    }

    @Test fun marksOverlappingSpeechAndSupersededCallerSegments() {
        tracker.agentAudio(500.0, 0.0); caller(10.0)
        assertEquals(true, events.single { it.first == "caller_activity_end" }.second["overlap"])
        caller(700.0)
        assertEquals("new_caller_segment", events.single { it.first == "turn_unanswered" }.second["reason"])
    }

    @Test fun oldQueuedAudioDoesNotChangeTheNextTurnsSendBoundary() {
        caller(0.0); caller(500.0)
        tracker.sentAudio(500.0, 950.0, 1)
        tracker.agentAudio(500.0, 1000.0)
        assertEquals(390.0, events.last { it.first == "agent_activity_start" }.second["last_sent_energy_to_agent_pcm_ms"])
    }

    @Test fun repliesBeforeSilenceConfirmationRemainUnmatched() {
        repeat(3) { tracker.callerAudio(500.0, 10.0, 100.0 + it * 10) }
        assertNull(tracker.agentAudio(500.0, 200.0))
        assertFalse(events.last { it.first == "agent_activity_start" }.second["matched"] as Boolean)
    }

    @Test fun reportsAgentResponseLostBeforePlayback() {
        caller(0.0); tracker.agentAudio(500.0, 600.0); tracker.finish()
        assertEquals(1, events.count { it.first == "turn_unwritten" })
    }
}
