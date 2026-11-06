package dev.callstream.probe.shell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CallerAudioRecorderTest {
    @Test fun writesAValidWavAndSegmentsAtGaps() {
        val dir = createTempDirectory().toFile()
        val wav = File(dir, "a.wav"); val side = File(dir, "a.json")
        val r = CallerAudioRecorder(wav, side)
        val frame = ShortArray(160) { (it * 100 - 8_000).toShort() }
        for (i in 0 until 10) r.write(frame, 1_000.0 + i * 10)   // one turn: 100 ms
        for (i in 0 until 5) r.write(frame, 3_000.0 + i * 10)    // next turn after a gap: 50 ms
        assertEquals(0.15, r.close("trace-1"), 1e-9)

        val bytes = wav.readBytes()
        assertEquals(44 + 15 * 320, bytes.size)
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(36 + 15 * 320, h.getInt(4)); assertEquals(16_000, h.getInt(24)); assertEquals(15 * 320, h.getInt(40))
        assertEquals(frame[1], h.getShort(44 + 2))

        val segments = JSONObject(side.readText()).getJSONArray("segments")
        assertEquals(2, segments.length())
        assertEquals(0.0, segments.getJSONObject(0).getDouble("file_ms"), 1e-9)
        assertEquals(100.0, segments.getJSONObject(1).getDouble("file_ms"), 1e-9)
        assertEquals(3_000.0, segments.getJSONObject(1).getDouble("t_ms"), 1e-9)
    }

    private fun createTempDirectory() = kotlin.io.path.createTempDirectory("caller-audio")
}
