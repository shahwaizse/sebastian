package dev.callstream.probe.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class Upsampler16To48Test {
    private fun rms(a: ShortArray, from: Int = 0) = sqrt(a.drop(from).sumOf { it.toDouble() * it } / (a.size - from))

    @Test fun threeOutputsPerInput() = assertEquals(480, Upsampler16To48().process(ShortArray(160)).size)

    @Test fun dcPassesAtUnityGain() {
        val out = Upsampler16To48().process(ShortArray(320) { 10_000 })
        // After the filter fills (16 inputs), every output is the input level.
        out.drop(3 * 20).forEach { assertTrue("got $it", abs(it - 10_000) <= 40) }
    }

    @Test fun speechBandToneKeepsItsLevel() {
        val tone = ShortArray(1_600) { (8_000 * sin(2 * PI * 1_000 * it / 16_000.0)).toInt().toShort() }
        val out = Upsampler16To48().process(tone)
        assertEquals(rms(tone, 20), rms(out, 60), rms(tone, 20) * 0.03)
    }

    @Test fun chunksMatchOneBuffer() {
        val input = ShortArray(1_000) { (5_000 * sin(it * 0.37)).toInt().toShort() }
        val whole = Upsampler16To48().process(input)
        val u = Upsampler16To48()
        val parts = listOf(0..159, 160..160, 161..539, 540..999).map { r -> u.process(input.sliceArray(r)) }
        assertArrayEquals(whole, parts.reduce { a, b -> a + b })
    }
}
