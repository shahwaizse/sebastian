package dev.callstream.probe.shell

import cn.enaium.webrtc.aec3.Jni
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipFile

/**
 * WebRTC AEC3 on Rayyan's mic during a WhatsApp call (16 kHz mono, 10 ms frames).
 *
 * Why: Sebastian records Rayyan on the plain mic (WhatsApp's hook takes the call mic), which has no working echo
 * canceller, and the earpiece sits a few cm away. Measured on a real call (2026-11-04): what plays on the earpiece
 * comes back into the mic ~160 ms later, ~16 dB down, and went straight back to the other person. AEC3 removed
 * ~21 dB of it on that recording and left Rayyan's own voice untouched.
 *
 * [render] takes each frame as it goes to the earpiece (any thread); [process] cancels the echo in a mic frame, in
 * place, on the uplink thread. AEC3 isn't thread-safe, so render frames are queued and fed to it from [process].
 */
internal class EchoCanceller private constructor() {
    private val jni = Jni.INSTANCE
    private val config = jni.configCreateDefault()
    private val factory = jni.factoryCreateWithConfig(config)
    private val env = jni.environmentCreate()
    private val echo = jni.echoControlCreate(factory, env, RATE, 1, 1)
    private val renderBuf = jni.audioBufferCreate(RATE, 1)
    private val captureBuf = jni.audioBufferCreate(RATE, 1)
    private val pending = ConcurrentLinkedQueue<ShortArray>()
    private val rf = FloatArray(FRAME)
    private val cf = FloatArray(FRAME)
    @Volatile private var closed = false
    @Volatile var frames = 0L; private set

    /** A frame that was just written to the earpiece. */
    fun render(pcm: ShortArray, n: Int) {
        if (closed || n <= 0) return
        pending.add(pcm.copyOf(n))
        while (pending.size > MAX_PENDING) pending.poll()
    }

    /** Removes the earpiece echo from one mic frame of [FRAME] samples, in place. */
    @Synchronized fun process(frame: ShortArray, n: Int) {
        if (closed || n != FRAME) return
        while (true) {
            val r = pending.poll() ?: break
            if (r.size != FRAME) continue
            for (i in 0 until FRAME) rf[i] = r[i].toFloat()
            jni.audioBufferWriteChannel(renderBuf, 0, rf)
            jni.echoControlAnalyzeRender(echo, renderBuf)
        }
        for (i in 0 until FRAME) cf[i] = frame[i].toFloat()
        jni.audioBufferWriteChannel(captureBuf, 0, cf)
        jni.echoControlAnalyzeCapture(echo, captureBuf)
        jni.echoControlProcessCapture(echo, captureBuf, false)
        val out = jni.audioBufferReadChannel(captureBuf, 0)
        for (i in 0 until FRAME) frame[i] = out[i].toInt().coerceIn(-32768, 32767).toShort()
        frames++
    }

    /** "erl/erle/delay": how much echo AEC3 sees, how much it removes (dB) and the echo delay it found (ms). */
    @Synchronized fun metrics(): String = if (closed) "closed" else runCatching {
        val m = jni.echoControlGetMetrics(echo)
        "erl%.0f/erle%.1f/delay%.0fms".format(m[0], m[1], m[2])
    }.getOrDefault("?")

    @Synchronized fun close() {
        if (closed) return
        closed = true
        runCatching { jni.audioBufferDestroy(renderBuf); jni.audioBufferDestroy(captureBuf) }
        runCatching { jni.echoControlDestroy(echo); jni.environmentDestroy(env); jni.factoryDestroy(factory); jni.configDestroy(config) }
    }

    companion object {
        const val RATE = 16_000
        const val FRAME = 160
        /** ~0.5 s of earpiece audio waiting for the mic thread; older frames are dropped. */
        private const val MAX_PENDING = 50
        private const val LIB = "libwebrtc_aec3_jni.so"
        private const val LIB_DIR = "/data/local/tmp/sebastian/lib"
        @Volatile private var loaded = false

        /**
         * The helper runs under app_process with the APK on CLASSPATH, so System.loadLibrary can't find the app's
         * native libs. The .so is copied out of the APK into the helper's own folder (shell may run code there)
         * and loaded by path. Throws when it can't, and the caller goes without echo cancelling.
         */
        @Synchronized private fun load() {
            if (loaded) return
            val apk = System.getenv("CLASSPATH")?.split(':')?.firstOrNull { it.endsWith(".apk") }
                ?: error("no APK on CLASSPATH")
            val dir = File(LIB_DIR).apply { mkdirs() }
            val lib = File(dir, LIB)
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/$LIB") ?: error("$LIB isn't in the APK")
                if (!lib.exists() || lib.length() != entry.size) {
                    val tmp = File(dir, "$LIB.tmp")
                    zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    check(tmp.renameTo(lib)) { "couldn't place $LIB" }
                }
            }
            System.load(lib.path)
            loaded = true
        }

        fun create(): EchoCanceller { load(); return EchoCanceller() }
    }
}
