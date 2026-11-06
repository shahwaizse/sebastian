package dev.callstream.probe.shell

import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Diagnostic: saves a WhatsApp call's audio on the phone as raw 16 kHz mono PCM16, one file per point in the relay,
 * to find where the other side's echo comes from (config "record_call": true). Files stay in [DIR] on the phone.
 *
 * - in:  the other side's voice as Sebastian reads it from WhatsApp
 * - ear: what Sebastian plays on Rayyan's earpiece (in + Sebastian's voice)
 * - mic: Rayyan's raw microphone
 * - out: what Sebastian sends WhatsApp as Rayyan's mic (mic after ducking + Sebastian's voice)
 *
 * Each stream is written from one thread, every frame, so samples are continuous. The phone-clock time of each
 * stream's first frame goes into the .txt file, which lines the streams up with each other.
 */
internal class CallTape(stamp: Long) {
    class Stream(val file: File) {
        private val os = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
        private val bytes = ByteArray(2 * 4096)
        @Volatile var firstNs = 0L; private set
        @Volatile var samples = 0L; private set

        fun write(pcm: ShortArray, n: Int) {
            if (n <= 0) return
            if (firstNs == 0L) firstNs = SystemClock.elapsedRealtimeNanos()
            val m = minOf(n, bytes.size / 2)
            for (i in 0 until m) { val s = pcm[i].toInt(); bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s shr 8).toByte() }
            runCatching { os.write(bytes, 0, 2 * m) }
            samples += m
        }

        fun close() { runCatching { os.flush(); os.close() } }
    }

    private val base = File(DIR).apply { mkdirs() }
    private val name = "call_$stamp"
    val incoming = Stream(File(base, "${name}_in.pcm"))
    val ear = Stream(File(base, "${name}_ear.pcm"))
    val mic = Stream(File(base, "${name}_mic.pcm"))
    val outgoing = Stream(File(base, "${name}_out.pcm"))
    /** Observer mode: Sebastian's own voice, placed on the same clock as [ear] (the mic only hears him faintly off the earpiece). */
    private val bot = Stream(File(base, "${name}_bot.pcm"))
    private var botAnchorNs = 0L
    private var botSamples = 0L

    @Synchronized fun botVoice(pcm: ShortArray, nowNs: Long) {
        if (botAnchorNs == 0L) botAnchorNs = if (ear.firstNs > 0) ear.firstNs else nowNs
        val pos = (nowNs - botAnchorNs) * 16 / 1_000_000L
        val silence = ShortArray(4096)
        while (botSamples < pos) { val n = minOf(4096L, pos - botSamples).toInt(); bot.write(silence, n); botSamples += n }
        bot.write(pcm, pcm.size); botSamples += pcm.size
    }

    /** Closes the files and writes the timing sidecar. Returns its path. */
    fun close(): String {
        val all = mapOf("in" to incoming, "ear" to ear, "mic" to mic, "out" to outgoing, "bot" to bot)
        all.values.forEach { it.close() }
        val txt = File(base, "$name.txt")
        runCatching {
            txt.writeText(all.entries.joinToString("\n") { (k, s) -> "$k first_ns=${s.firstNs} samples=${s.samples}" } + "\nrate=16000\n")
        }
        Thread({
            runCatching { exportWav() }.onSuccess { wav -> if (wav != null) runCatching { encodeAac(wav) }.onFailure { android.util.Log.w("Sebastian", "call aac encode failed, keeping the wav: ${it.message}") } }
                .onFailure { android.util.Log.w("Sebastian", "call wav export failed: ${it.message}") }
        }, "sebastian-export").apply { isDaemon = true; start() }
        return txt.path
    }

    /**
     * One playable file for the whole call: Rayyan's mic plus the other side with Sebastian's voice, lined up by
     * their first frame, written to Downloads/Sebastian so the phone's Files app (and a share sheet) can reach it.
     * It's a WAV first, then [encodeAac] shrinks it to an .m4a.
     */
    private fun exportWav(): File? {
        val earFile = File(base, "${name}_ear.pcm")
        val micFile = File(base, "${name}_mic.pcm")
        val botFile = File(base, "${name}_bot.pcm")
        if (earFile.length() < 32_000) return null
        val dir = File(WAV_DIR).apply { mkdirs() }
        val wav = File(dir, "$name.wav")
        // Whichever stream started later is padded with silence so both begin at the same moment.
        val lagSamples = ((if (mic.firstNs > 0 && ear.firstNs > 0) mic.firstNs - ear.firstNs else 0L) * 16 / 1_000_000L)
        val earPad = if (lagSamples < 0) -lagSamples else 0L
        val micPad = if (lagSamples > 0) lagSamples else 0L
        val total = maxOf(earPad + earFile.length() / 2, micPad + micFile.length() / 2)
        val earS = readAligned(earFile, earPad, total)
        val micS = readAligned(micFile, micPad, total)
        val botS = if (botFile.length() > 0) readAligned(botFile, earPad, total) else null
        val (centers, lags) = alignLags(earS, micS)
        val size = earS.size
        val shifted = ShortArray(size)
        val duckAt = DoubleArray(size / FRAME + 1)
        var duck = 1.0
        var seg = 0
        // Speech level of each voice (frames that carry speech only), so the three can be brought to one loudness.
        val sumSq = DoubleArray(3); val count = LongArray(3)
        var f = 0
        while (f * FRAME < size) {
            val from = f * FRAME
            val to = minOf(size, from + FRAME)
            // The copy of the other person that the mic hears off the earpiece lags the clean one by an amount that drifts during the call.
            val mid = (from + to) / 2
            while (seg + 1 < centers.size && centers[seg + 1] <= mid) seg++
            val lag = when {
                mid <= centers[0] -> lags[0].toDouble()
                seg + 1 >= centers.size -> lags[centers.size - 1].toDouble()
                else -> lags[seg] + (lags[seg + 1] - lags[seg]) * (mid - centers[seg]).toDouble() / (centers[seg + 1] - centers[seg])
            }.toInt()
            var earSq = 0.0; var botSq = 0.0; var refSq = 0.0; var micSq = 0.0
            for (k in from until to) {
                val e = earS[(k + lag).coerceIn(0, size - 1)]
                shifted[k] = e
                val v = botS?.get(k)?.toInt() ?: 0
                val r = (e + v).coerceIn(-32_767, 32_767)
                earSq += e.toDouble() * e; botSq += v.toDouble() * v; refSq += r.toDouble() * r; micSq += micS[k].toDouble() * micS[k]
            }
            val n = (to - from).toDouble()
            val refRms = Math.sqrt(refSq / n); val micRms = Math.sqrt(micSq / n)
            // While they are louder than the mic, what the mic hears is their echo off the earpiece: mute it.
            duck = if (refRms > 250.0 && micRms < refRms) maxOf(0.0, duck - 0.5) else minOf(1.0, duck + 0.1)
            duckAt[f] = duck
            if (Math.sqrt(earSq / n) > SPEECH_RMS) { sumSq[0] += earSq / n; count[0]++ }
            if (Math.sqrt(botSq / n) > SPEECH_RMS) { sumSq[1] += botSq / n; count[1]++ }
            if (micRms * duck > SPEECH_RMS) { sumSq[2] += micSq * duck * duck / n; count[2]++ }
            f++
        }
        val gain = DoubleArray(3) { if (count[it] < 20) 1.0 else (TARGET_RMS / Math.sqrt(sumSq[it] / count[it])).coerceIn(0.25, 10.0) }
        val mix = ShortArray(size) { k ->
            val x = shifted[k] * gain[0] + (botS?.get(k)?.toInt() ?: 0) * gain[1] + micS[k] * duckAt[k / FRAME] * gain[2]
            // Soft limiter: unchanged at normal levels, rounds off the rare peak instead of clipping it.
            (32_767.0 * Math.tanh(x / 32_767.0)).toInt().toShort()
        }
        FileOutputStream(wav).use { w ->
            val dataBytes = mix.size * 2L
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(dataBytes.toInt())
            w.write(h.array())
            val bytes = java.nio.ByteBuffer.allocate(mix.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(mix)
            w.write(bytes.array())
        }
        return wav
    }


    private fun readAligned(file: File, pad: Long, total: Long): ShortArray {
        val out = ShortArray(total.toInt())
        val bytes = java.nio.ByteBuffer.wrap(file.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = minOf(bytes.remaining().toLong(), total - pad).toInt()
        if (n > 0) { bytes.position(0); bytes.get(out, pad.toInt(), n) }
        return out
    }

    /**
     * How far the clean copy of the other person (ear) runs behind the copy the mic hears off the earpiece, every few
     * seconds of the call (GCC-PHAT, 8 s windows). The gap drifts (0.4 s to 0.8 s in a minute), so one number won't do.
     * A window with no clear peak keeps the previous value.
     */
    private fun alignLags(ear: ShortArray, mic: ShortArray): Pair<IntArray, IntArray> {
        val win = 128_000
        var last = 6_400
        val centers = ArrayList<Int>(); val lags = ArrayList<Int>()
        var s = 0
        while (s + win <= ear.size) {
            val (lag, confidence) = gccPhat(ear, mic, s, win, 25_600)
            if (confidence >= 25.0) last = lag
            centers += s + win / 2; lags += last
            s += 48_000
        }
        if (centers.isEmpty()) return intArrayOf(0) to intArrayOf(last)
        return centers.toIntArray() to lags.toIntArray()
    }

    /** Best lag in 0..maxLag where ear[t + lag] matches mic[t], and how far its peak stands above the median. */
    private fun gccPhat(ear: ShortArray, mic: ShortArray, start: Int, win: Int, maxLag: Int): Pair<Int, Double> {
        val n = Integer.highestOneBit(2 * win - 1) shl 1
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until win) { re[i] = ear[start + i].toDouble(); im[i] = mic[start + i].toDouble() }
        fft(re, im, false)
        val rr = DoubleArray(n); val ri = DoubleArray(n)
        for (k in 0 until n) {
            val k2 = (n - k) and (n - 1)
            val ar = (re[k] + re[k2]) / 2; val ai = (im[k] - im[k2]) / 2
            val br = (im[k] + im[k2]) / 2; val bi = -(re[k] - re[k2]) / 2
            val xr = ar * br + ai * bi; val xi = ai * br - ar * bi
            val mag = Math.sqrt(xr * xr + xi * xi) + 1e-9
            rr[k] = xr / mag; ri[k] = xi / mag
        }
        fft(rr, ri, true)
        val abs = DoubleArray(maxLag + 1) { Math.abs(rr[it]) }
        var best = 0
        for (i in 1..maxLag) if (rr[i] > rr[best]) best = i
        val sorted = abs.sortedArray()
        return best to rr[best] / (sorted[sorted.size / 2] + 1e-12)
    }

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * Math.PI / len * (if (inverse) 1 else -1)
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /** Android has no MP3 encoder; AAC in an .m4a file plays everywhere (phone, WhatsApp, laptop) at a tenth of the size. */
    private fun encodeAac(wav: File) {
        val m4a = File(wav.parentFile, wav.nameWithoutExtension + ".m4a")
        val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, 16_000, 1).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = android.media.MediaMuxer(m4a.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var done = false
        try {
            codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = android.media.MediaCodec.BufferInfo()
            val pcm = wav.inputStream().buffered().also { it.skip(44) }
            var inputDone = false
            var samplesIn = 0L
            while (!done) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val chunk = ByteArray(minOf(buf.capacity(), 8_192))
                        val n = pcm.read(chunk)
                        val pts = samplesIn * 1_000_000L / 16_000
                        if (n <= 0) { codec.queueInputBuffer(i, 0, 0, pts, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { buf.put(chunk, 0, n); codec.queueInputBuffer(i, 0, n, pts, 0); samplesIn += n / 2 }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    o >= 0 -> {
                        val out = codec.getOutputBuffer(o)!!
                        if (info.size > 0 && info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) muxer.writeSampleData(track, out, info)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                }
            }
            pcm.close()
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }
            runCatching { if (track >= 0) muxer.stop() }; runCatching { muxer.release() }
        }
        if (done && m4a.length() > 1_000) wav.delete() else m4a.delete()
    }

    private fun readSample(s: java.io.DataInputStream): Int {
        val lo = s.read(); val hi = s.read()
        return if (lo < 0 || hi < 0) 0 else ((hi shl 8) or lo).toShort().toInt()
    }

    companion object {
        private const val FRAME = 160
        private const val SPEECH_RMS = 200.0
        /** Loudness every voice in the recording is brought to (RMS of the frames with speech). */
        private const val TARGET_RMS = 3_000.0
        const val DIR = "/data/local/tmp/sebastian-rec"
        const val WAV_DIR = "/sdcard/Download/Sebastian"
    }
}
