package dev.callstream.probe.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AckPlayerTest {
    private val clip = ShortArray(4_800) { if (it % 2 == 0) 800 else -800 } // 300 ms at RMS 800

    /** Caller speaks [speakMs] then is silent; returns (time, clip) of the first acknowledgement. */
    private fun AckPlayer.runCaller(from: Double, speakMs: Double, until: Double, agentPending: (Double) -> Boolean = { false }): Pair<Double, ShortArray>? {
        var t = from
        while (t < until) {
            val rms = if (t < from + speakMs) 5_000.0 else 100.0
            caller(rms, t, agentPending(t))?.let { return t to it }
            t += 10.0
        }
        return null
    }

    @Test fun playsAfterSpeechThenSilence() {
        val a = AckPlayer(listOf(clip))
        val (t, out) = a.runCaller(0.0, 1_000.0, 3_000.0)!!
        assertEquals(1_290.0, t, 0.1) // last speech at 990 ms + 300 ms of silence
        assertEquals(990.0, a.answeredSpeechEndMs, 0.1)
        assertEquals(clip.size, out.size)
        assertEquals(1, a.played)
        assertTrue(a.gateUntilMs > t + 300)
    }

    @Test fun shortSoundsDoNotTriggerIt() {
        val a = AckPlayer(listOf(clip))
        assertNull(a.runCaller(0.0, 200.0, 3_000.0))
    }

    @Test fun notWhileTheAgentIsTalkingOrAReplyIsComing() {
        val a = AckPlayer(listOf(clip))
        a.agentWritten(3_000.0, 1_000.0) // agent audible until well after the caller stops
        assertNull(a.runCaller(0.0, 1_000.0, 1_600.0))
        val b = AckPlayer(listOf(clip))
        assertNull(b.runCaller(0.0, 1_000.0, 3_000.0, agentPending = { true }))
    }

    @Test fun oncePerUtteranceAndCooldown() {
        val a = AckPlayer(listOf(clip))
        assertNotNull(a.runCaller(0.0, 1_000.0, 2_000.0))
        // Next utterance ends 1.6 s after the first acknowledgement (at 1,290 ms): inside the cooldown.
        assertNull(a.runCaller(1_800.0, 1_000.0, 4_000.0))
        assertNotNull(a.runCaller(5_000.0, 1_000.0, 7_000.0))
        assertEquals(2, a.played)
    }

    @Test fun scalesClipsToTheAgentsLiveLevel() {
        val a = AckPlayer(listOf(clip))
        repeat(500) { a.agentWritten(2_400.0, -10_000.0 + it) } // learned level 2,400, long ago
        val out = a.runCaller(0.0, 1_000.0, 3_000.0)!!.second
        assertEquals(2_400, out[0].toInt())
    }

    @Test fun recordsWhyAnUtteranceWasSkipped() {
        val b = AckPlayer(listOf(clip))
        b.runCaller(0.0, 1_000.0, 3_000.0, agentPending = { true })
        assertEquals("skipped_reply_arriving", b.lastDecision)
        assertEquals(1, b.decisions)
    }

    @Test fun soundDuringTheAgentNeverArmsIt() {
        val a = AckPlayer(listOf(clip))
        // Echo/overlap at caller level while the agent talks (0-2 s), then the agent stops: no "जी" after the agent.
        var t = 0.0; var out: ShortArray? = null
        while (t < 4_000) {
            if (t < 2_000) a.agentWritten(3_000.0, t)
            out = out ?: a.caller(if (t < 2_000) 5_000.0 else 100.0, t, false)
            t += 10
        }
        assertNull(out)
        assertEquals(0, a.decisions)
    }

    @Test fun speechAfterTheAgentFinishedIsAcknowledged() {
        val a = AckPlayer(listOf(clip))
        var t = 0.0
        while (t < 1_000) { a.agentWritten(3_000.0, t); a.caller(100.0, t, false); t += 10 }
        // Agent silent from 1 s; the caller talks 1.6-2.6 s, then stops.
        val r = a.runCaller(1_600.0, 1_000.0, 4_000.0)
        assertNotNull(r)
        assertEquals(2_890.0, r!!.first, 0.1)
    }

    @Test fun randomClipsNeverRepeatBackToBack() {
        val clips = (1..4).map { n -> ShortArray(1_600 * n) { if (it % 2 == 0) 800 else -800 } } // told apart by length
        val a = AckPlayer(clips, random = kotlin.random.Random(7))
        val picked = (0 until 60).map { k -> a.runCaller(k * 5_000.0, 1_000.0, k * 5_000.0 + 3_000.0)!!.second.size }
        assertEquals(60, a.played)
        assertTrue(picked.zipWithNext().none { (x, y) -> x == y })
        assertEquals(clips.map { it.size }.toSet(), picked.toSet())
    }
}
