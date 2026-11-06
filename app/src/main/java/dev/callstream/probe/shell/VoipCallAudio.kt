package dev.callstream.probe.shell

import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A VoIP app's call audio (WhatsApp), taken over for the agent through two loopback mixes:
 * - the injector replaces the app's microphone (VOICE_COMMUNICATION capture) with [track], so nothing from the room
 *   goes out;
 * - the extractor takes the app's voice-communication playback (the caller) into [record], off the earpiece.
 *
 * Register before the call is answered: the app opens its recorder and player at answer, straight into the mixes.
 * Registering took 1.3 s on the Pixel, so [openAsync] does it while the agent connects to Gemini; answer once [ready].
 * Proven on the Pixel 2026-10-25 (docs/RESEARCH.md §6). [close] unregisters both.
 */
internal class VoipCallAudio private constructor() {
    private val policy = HiddenAudioPolicy(AudioTools.shellContext())
    @Volatile private var injector: HiddenAudioPolicy.InjectorSession? = null
    @Volatile private var extractor: HiddenAudioPolicy.ExtractorSession? = null
    /** The extractor's error when it couldn't be built (the caller is then read from VOICE_DOWNLINK). */
    @Volatile var extractorError: String? = null
        private set
    /** Why the injector couldn't be registered: the agent can't take this call. */
    @Volatile var error: String? = null
        private set
    private val done = CountDownLatch(1)
    @Volatile private var closed = false

    /** Registration finished: [error] says whether it worked. */
    val finished: Boolean get() = done.count == 0L
    val ready: Boolean get() = finished && error == null

    val track: AudioTrack get() { await(); return injector!!.track }
    /** The caller's audio: the extractor's recorder, or null to fall back to VOICE_DOWNLINK. */
    val record: AudioRecord? get() { await(); return extractor?.record }

    private fun await() {
        check(done.await(AWAIT_MS, TimeUnit.MILLISECONDS)) { "WhatsApp call audio not registered after $AWAIT_MS ms" }
        error?.let { error(it) }
    }

    private fun register(appUids: List<Int>) {
        try {
            // 48 kHz: at 16 kHz the Redmi's submix ran in 192 ms bursts and the agent's audio underran (choppy).
            injector = policy.registerInjector(MediaRecorder.AudioSource.VOICE_COMMUNICATION, TRACK_RATE)
            // Recording from the start: the loopback output only exists while its recorder runs, so WhatsApp's
            // player opens straight into it at answer instead of the earpiece.
            extractor = runCatching { policy.registerExtractor(appUids).also { it.record.startRecording() } }
                .onFailure { extractorError = "${it.javaClass.simpleName}: ${it.message}".take(300); Log.w(TAG, "extractor failed: $extractorError") }
                .getOrNull()
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}".take(300)
            Log.w(TAG, "injector failed: $error")
        } finally {
            done.countDown()
            if (closed) release()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        // Still registering: register() releases what it got when it finishes.
        if (finished) release()
    }

    @Synchronized private fun release() {
        extractor?.let { policy.unregister(it) }; extractor = null
        injector?.let { policy.unregister(it) }; injector = null
        Log.i(TAG, "VoIP call audio released")
    }

    companion object {
        private const val TAG = "CallStreamVoip"
        private const val AWAIT_MS = 5_000L
        /** The injector track's rate; GeminiSession upsamples its 16 kHz audio 3x (Upsampler16To48). */
        const val TRACK_RATE = 48_000

        /** Starts registering both mixes for [appUids] on a background thread and returns at once. */
        fun openAsync(appUids: List<Int>): VoipCallAudio = VoipCallAudio().also { v ->
            Thread({ v.register(appUids) }, "voip-audio-register").apply { isDaemon = true; start() }
        }
    }
}
