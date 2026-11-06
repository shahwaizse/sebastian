package dev.callstream.probe.shell

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Small DSP + IO helpers for the probe: PCM capture with samples kept,
 * WAV writing, and a Goertzel tone detector. Pure Kotlin, no deps.
 */
internal object AudioTools {

    class PcmCapture(
        val state: String,
        val rms: Double,
        val peak: Int,
        val samples: ShortArray,
        val sampleRate: Int,
    )

    /**
     * How the most recent [openRecord] was constructed.
     * Shell UserService must not attribute as the app package: AudioFlinger
     * rejects that pair (calling uid 2000, package dev.callstream.probe)
     * with status -1 for every source, including MIC.
     */
    var lastOpenNote: String = ""
        private set

    /** Opens a raw AudioRecord for [source] at 16 kHz mono PCM16. [bufferBytes] (floored at the platform
     * minimum) overrides the default of twice the minimum, at least 100 ms. */
    fun openRecord(source: Int, sampleRate: Int = 16_000, bufferBytes: Int? = null): AudioRecord {
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val buf = bufferBytes?.coerceAtLeast(min) ?: (min * 2).coerceAtLeast(3200)
        val shell = runCatching { openShellRecord(source, sampleRate, buf) }
        val opened = shell.getOrNull()
        if (opened != null && opened.state == AudioRecord.STATE_INITIALIZED) {
            lastOpenNote = "shell-attr uid=${Process.myUid()} pkg=$SHELL_PACKAGE"
            Log.i(TAG, "openRecord source=$source $lastOpenNote")
            return opened
        }
        opened?.let { runCatching { it.release() } }
        val why = shell.exceptionOrNull()?.message?.replace('\n', ' ') ?: "uninit"
        lastOpenNote = "public-fallback ($why)"
        Log.w(TAG, "openRecord source=$source $lastOpenNote")
        return AudioRecord(
            source, sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, buf,
        )
    }

    /**
     * Android 17 builds AudioRecord from the current Application's
     * attribution. The public constructor therefore records as the probe
     * package while this process is shell. Builder.setContext is the
     * supported injection point: the private constructor reads
     * context.getAttributionSource(). Hidden preset/attributes calls stay
     * in this shell process, which is exempt from the hidden-API blocklist.
     */
    /** Context whose attribution is shell uid + `com.android.shell`. */
    fun shellContext(): Context {
        val base = applicationContext() ?: error("no application context")
        val attribution = AttributionSource.Builder(Process.myUid())
            .setPackageName(SHELL_PACKAGE)
            .setPid(Process.myPid())
            .build()
        return ShellAttributionContext(base, attribution)
    }

    private fun openShellRecord(source: Int, sampleRate: Int, buf: Int): AudioRecord {
        val attrsBuilder = AudioAttributes.Builder()
        val setPreset = AudioAttributes.Builder::class.java.getMethod(
            "setInternalCapturePreset",
            Int::class.javaPrimitiveType,
        )
        setPreset.invoke(attrsBuilder, source)
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(buf)
            .setContext(shellContext())
        val setAttrs = AudioRecord.Builder::class.java.getMethod(
            "setAudioAttributes",
            AudioAttributes::class.java,
        )
        setAttrs.invoke(builder, attrsBuilder.build())
        return builder.build()
    }

    /**
     * Playback track attributed as shell. [usage] is
     * [AudioAttributes.USAGE_VOICE_COMMUNICATION] or
     * [AudioAttributes.USAGE_MEDIA]. Throws if the track does not initialize,
     * so a caller cannot fall through to the app-package constructor.
     */
    fun openShellTrack(usage: Int, sampleRate: Int = 16_000, lowLatency: Boolean = false): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val buf = (min * 2).coerceAtLeast(3200)
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(buf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setContext(shellContext())
            .apply { if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
            .build()
    }

    private fun applicationContext(): Context? {
        return try {
            val thread = Class.forName("android.app.ActivityThread")
            thread.getMethod("currentApplication").invoke(null) as? Context
        } catch (t: Throwable) {
            Log.w(TAG, "currentApplication failed", t)
            null
        }
    }

    private class ShellAttributionContext(
        base: Context,
        private val attribution: AttributionSource,
    ) : ContextWrapper(base) {
        override fun getAttributionSource(): AttributionSource = attribution
    }

    /**
     * Reads up to [durationMs] of PCM from an already-constructed
     * [record], keeping the samples. Stops (but does not release) the
     * record. Never throws for capture errors; state carries the reason.
     */
    fun readPcm(record: AudioRecord, durationMs: Int, sampleRate: Int = 16_000): PcmCapture {
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            return PcmCapture("uninit", 0.0, 0, ShortArray(0), sampleRate)
        }
        val chunk = ShortArray(3200)
        val collected = ArrayList<ShortArray>()
        var n = 0
        var sumSq = 0.0
        var peak = 0
        val deadline = SystemClock.elapsedRealtime() + durationMs
        return try {
            record.startRecording()
            while (SystemClock.elapsedRealtime() < deadline) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                val slice = chunk.copyOf(read)
                collected += slice
                for (i in 0 until read) {
                    val s = slice[i].toInt()
                    sumSq += s.toDouble() * s
                    val a = kotlin.math.abs(s)
                    if (a > peak) peak = a
                    n++
                }
            }
            val all = ShortArray(n)
            var p = 0
            for (s in collected) {
                s.copyInto(all, p); p += s.size
            }
            PcmCapture(
                "ok",
                if (n == 0) 0.0 else kotlin.math.sqrt(sumSq / n),
                peak,
                all,
                sampleRate,
            )
        } catch (t: Throwable) {
            PcmCapture("failed-start: ${t.message?.replace('\n', ' ')}", 0.0, 0, ShortArray(0), sampleRate)
        } finally {
            runCatching { record.stop() }
        }
    }

    /** 44-byte RIFF/WAVE header + PCM16 payload, little-endian. */
    fun wavBytes(samples: ShortArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val dataLen = samples.size * channels * 2
        val out = ByteArray(44 + dataLen)
        fun le32(off: Int, v: Int) {
            out[off] = (v and 0xff).toByte()
            out[off + 1] = ((v ushr 8) and 0xff).toByte()
            out[off + 2] = ((v ushr 16) and 0xff).toByte()
            out[off + 3] = ((v ushr 24) and 0xff).toByte()
        }
        fun le16(off: Int, v: Int) {
            out[off] = (v and 0xff).toByte()
            out[off + 1] = ((v ushr 8) and 0xff).toByte()
        }
        fun str(off: Int, s: String) = s.forEachIndexed { i, c -> out[off + i] = c.code.toByte() }
        str(0, "RIFF"); le32(4, 36 + dataLen); str(8, "WAVE")
        str(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, channels)
        le32(24, sampleRate); le32(28, sampleRate * channels * 2)
        le16(32, channels * 2); le16(34, 16)
        str(36, "data"); le32(40, dataLen)
        var p = 44
        for (s in samples) {
            out[p++] = (s.toInt() and 0xff).toByte()
            out[p++] = ((s.toInt() shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Goertzel power at [freqHz], normalized by n^2 so it is level-agnostic. */
    fun goertzel(samples: ShortArray, sampleRate: Int, freqHz: Int): Double {
        val n = samples.size
        if (n == 0) return 0.0
        val k = 2.0 * Math.PI * freqHz / sampleRate
        val coeff = 2.0 * kotlin.math.cos(k)
        var s1 = 0.0
        var s2 = 0.0
        for (s in samples) {
            val x = s.toDouble()
            val s0 = x + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return power / (n.toDouble() * n.toDouble())
    }

    /**
     * Tone verdict for a capture window. Heuristic: target bin must
     * beat both guard bins by 20x and the window must have energy.
     * ALWAYS print the raw numbers — the verdict is a convenience,
     * not the truth.
     */
    fun toneReport(samples: ShortArray, sampleRate: Int, freqHz: Int): String {
        if (samples.isEmpty()) return "goertzel: no samples"
        val target = goertzel(samples, sampleRate, freqHz)
        val guardLow = goertzel(samples, sampleRate, freqHz - 110)
        val guardHigh = goertzel(samples, sampleRate, freqHz + 110)
        var sumSq = 0.0
        var peak = 0
        for (s in samples) {
            val v = s.toInt()
            sumSq += v.toDouble() * v
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size)
        val guardMin = minOf(guardLow, guardHigh).coerceAtLeast(1e-12)
        val ratio = target / guardMin
        val verdict = if (rms > 20.0 && ratio > 20.0) "TONE-DETECTED" else "NO-TONE"
        return "goertzel@${freqHz}Hz=%.3e guards(%d/%dHz)min=%.3e ratio=%.1f rms=%.1f peak=%d verdict=%s"
            .format(target, freqHz - 110, freqHz + 110, guardMin, ratio, rms, peak, verdict)
    }

    private const val TAG = "CallStreamProbe"
    private const val SHELL_PACKAGE = "com.android.shell"
}
