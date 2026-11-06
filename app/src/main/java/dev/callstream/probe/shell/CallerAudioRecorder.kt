package dev.callstream.probe.shell

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Config save_caller_audio (test calls): the exact caller audio sent to Gemini, as a 16 kHz mono WAV, plus a sidecar
 * JSON listing where each stretch of sent audio starts (file offset and trace time). With phone turns only the caller's
 * turns are sent, so the WAV is those turns back to back and the sidecar lines them up with the trace and transcript.
 * Capture thread only.
 */
internal class CallerAudioRecorder(private val wav: File, private val sidecar: File) {
    private val out = BufferedOutputStream(FileOutputStream(wav), 64 * 1024)
    private val segments = JSONArray()
    private var samples = 0L
    private var lastAtMs = Double.NEGATIVE_INFINITY
    private val bytes = ByteArray(FRAME_BYTES)

    init { out.write(header(0)) }

    /** One frame as sent; a gap of more than a frame since the last one starts a new segment. */
    fun write(pcm: ShortArray, atMs: Double) {
        if (atMs - lastAtMs > GAP_MS) segments.put(JSONObject().put("file_ms", samples / 16.0).put("t_ms", atMs))
        lastAtMs = atMs
        val buf = if (pcm.size * 2 == bytes.size) bytes else ByteArray(pcm.size * 2)
        for (i in pcm.indices) { val s = pcm[i].toInt(); buf[2 * i] = s.toByte(); buf[2 * i + 1] = (s shr 8).toByte() }
        out.write(buf, 0, pcm.size * 2)
        samples += pcm.size
    }

    /** Finishes the WAV header and writes the sidecar; returns the seconds saved. */
    fun close(trace: String): Double {
        out.close()
        RandomAccessFile(wav, "rw").use { it.seek(0); it.write(header(samples * 2)) }
        sidecar.writeText(JSONObject().put("trace", trace).put("rate", 16_000).put("seconds", samples / 16_000.0).put("segments", segments).toString(1))
        for (f in listOf(wav, sidecar)) { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) }
        return samples / 16_000.0
    }

    companion object {
        private const val FRAME_BYTES = 320
        /** Capture frames arrive in bursts; only a real pause between sent stretches (phone turns) starts a segment. */
        private const val GAP_MS = 300.0

        /** A 44-byte PCM WAV header for [dataBytes] of 16 kHz mono 16-bit audio. */
        fun header(dataBytes: Long): ByteArray {
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(dataBytes.toInt())
            return h.array()
        }
    }
}
