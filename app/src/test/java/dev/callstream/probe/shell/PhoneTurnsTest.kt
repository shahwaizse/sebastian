package dev.callstream.probe.shell

import dev.callstream.probe.shell.PhoneTurns.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTurnsTest {
    private val loud = 5_000.0
    private val quiet = 50.0

    /** Feeds 10 ms frames from [from] to [to]; returns every START/END with its time. */
    private fun PhoneTurns.run(from: Double, to: Double, echo: (Double) -> Boolean = { false }, level: (Double) -> Double): List<Pair<Double, Event>> {
        val out = mutableListOf<Pair<Double, Event>>()
        var t = from
        while (t < to) {
            frame(level(t), t, echo(t)).takeIf { it != Event.NONE }?.let { out += t to it }
            t += 10.0
        }
        return out
    }

    @Test fun startsAfterSustainedSpeechAndEndsAfterSilence() {
        val events = PhoneTurns().run(0.0, 3_000.0) { if (it in 100.0..1_090.0) loud else quiet }
        assertEquals(listOf(290.0 to Event.START, 1_590.0 to Event.END), events)
    }

    @Test fun shortNoisesAndQuietLevelsDoNotStart() {
        // 150 ms cough, then a click every 30 ms, then steady noise just under the speech level.
        assertTrue(PhoneTurns().run(0.0, 6_000.0) { t ->
            when {
                t in 100.0..240.0 -> loud
                t in 1_000.0..3_000.0 -> if ((t / 10).toInt() % 3 == 0) loud else quiet
                t >= 3_000.0 -> 700.0
                else -> quiet
            }
        }.isEmpty())
    }

    @Test fun pausesShorterThanTheSilenceKeepOneTurn() {
        // "جی ... <400 ms pause> ... question": one turn, not two.
        val events = PhoneTurns().run(0.0, 4_000.0) { if (it in 0.0..390.0 || it in 800.0..1_990.0) loud else quiet }
        assertEquals(listOf(Event.START, Event.END), events.map { it.second })
        assertEquals(2_490.0, events.last().first, 0.1)
    }

    @Test fun quieterTailSyllablesKeepTheTurnOpen() {
        val events = PhoneTurns().run(0.0, 3_000.0) { if (it < 500.0) loud else if (it < 1_500.0) 500.0 else quiet }
        assertEquals(1_990.0, events.last().first, 0.1)
    }

    @Test fun echoFlaggedFramesDoNotStartATurn() {
        assertTrue(PhoneTurns().run(0.0, 2_000.0, echo = { true }) { loud }.isEmpty())
    }

    @Test fun aStuckTurnEndsAtTheLimit() {
        val events = PhoneTurns(maxTurnMs = 1_000.0).run(0.0, 1_500.0) { loud }
        assertEquals(listOf(190.0 to Event.START, 1_190.0 to Event.END), events)
    }
}
