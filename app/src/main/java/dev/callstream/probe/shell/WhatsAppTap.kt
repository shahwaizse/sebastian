package dev.callstream.probe.shell

import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * WhatsApp's call audio, rerouted through Sebastian for as long as he's ON:
 * - [record]: the other person's voice (WhatsApp's voice-communication playback), off the earpiece;
 * - [track]: what WhatsApp sends as Rayyan's microphone (48 kHz). Sebastian writes Rayyan's real mic into it, with
 *   his own voice mixed in when he speaks.
 *
 * The hooks only catch audio that WhatsApp opens after they exist, so they're registered when Sebastian is turned
 * on, not when a call starts. The playback hook matches only WhatsApp; the mic hook takes any app's
 * voice-communication capture, so other VoIP apps' calls are relayed too while Sebastian is ON. If the helper dies, Android drops both hooks and WhatsApp's next call is normal again.
 */
internal class WhatsAppTap private constructor(
    private val policy: HiddenAudioPolicy,
    /** Null in observer mode: WhatsApp keeps its real microphone (and the phone's echo canceller). */
    private val injector: HiddenAudioPolicy.InjectorSession?,
    private val extractor: HiddenAudioPolicy.ExtractorSession,
) {
    val record: AudioRecord get() = extractor.record
    val track: AudioTrack? get() = injector?.track
    /** Observer mode: Sebastian listens beside the call. No mic hook; WhatsApp's caller audio still plays on the earpiece. */
    val observer: Boolean get() = injector == null

    fun close() {
        runCatching { policy.unregister(extractor) }
        injector?.let { runCatching { policy.unregister(it) } }
    }

    companion object {
        /** 48 kHz: at 16 kHz the Redmi's submix ran in 192 ms bursts (Call Stream, 2026-10-25). */
        const val TRACK_RATE = 48_000
        /** Whether the mic hook leaves Sebastian's own voice-communication capture real (see [open]). */
        @Volatile var selfExcluded = false; private set
        @Volatile var exclusionError: String? = null; private set

        /**
         * Observer mode (config "observer": true): no mic hook at all, so WhatsApp keeps the real microphone and its
         * own echo canceller (a normal call has no echo). The caller's audio is copied to Sebastian by a mix that also
         * leaves it playing on the earpiece (RENDER|LOOP_BACK). Sebastian then speaks to Rayyan's earpiece only.
         */
        fun openObserver(uids: List<Int>): WhatsAppTap {
            require(uids.isNotEmpty()) { "WhatsApp isn't installed" }
            val policy = HiddenAudioPolicy(AudioTools.shellContext())
            val extractor = policy.registerExtractor(uids, keepPlayback = true).also { it.record.startRecording() }
            return WhatsAppTap(policy, null, extractor)
        }

        fun open(uids: List<Int>): WhatsAppTap {
            require(uids.isNotEmpty()) { "WhatsApp isn't installed" }
            val policy = HiddenAudioPolicy(AudioTools.shellContext())
            // Same rule Call Stream proved on this Redmi: every voice-communication capture (with a uid filter on top, WhatsApp
            // most likely got no mic at all, 2026-11-04). So Sebastian's own mic uses another source (see openSelfVoip).
            // Sebastian's own uid is excluded, so he can record Rayyan through the real voice-call mic, with the phone's
            // echo canceller (fed by his voice-call earpiece track): what a normal WhatsApp call gets. Without the
            // phone's canceller the earpiece leaked back to the other side (2026-11-04). Rejected: no exclusion.
            val injector = runCatching {
                policy.registerInjector(MediaRecorder.AudioSource.VOICE_COMMUNICATION, TRACK_RATE, excludeUids = listOf(android.os.Process.myUid()))
                    .also { selfExcluded = true; exclusionError = null }
            }.getOrElse {
                selfExcluded = false
                exclusionError = "${it.javaClass.simpleName}: ${it.cause?.message ?: it.message}".take(200)
                policy.registerInjector(MediaRecorder.AudioSource.VOICE_COMMUNICATION, TRACK_RATE)
            }
            val extractor = try {
                // Recording from the start: the loopback output only exists while its recorder runs.
                policy.registerExtractor(uids).also { it.record.startRecording() }
            } catch (t: Throwable) {
                runCatching { policy.unregister(injector) }
                throw t
            }
            injector.track.play()
            return WhatsAppTap(policy, injector, extractor)
        }
    }
}
