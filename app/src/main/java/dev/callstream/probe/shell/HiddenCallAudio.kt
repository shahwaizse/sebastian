package dev.callstream.probe.shell

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.lang.reflect.InvocationTargetException

/**
 * Reflection wrappers for the hidden (Android 13+) Call Audio
 * Interception System APIs. docs/archive/RESEARCH-BRIEF-2026-10.md section 5.0 is the reference.
 *
 * These run ONLY inside the shell helper (shell uid), where
 * hidden-API restrictions are relaxed. Never call from the app process.
 */
internal object HiddenCallAudio {

    /** MediaRecorder.AudioSource.ECHO_REFERENCE (hidden, = 1997). */
    const val ECHO_REFERENCE_SOURCE = 1997

    fun isPstnCallAudioInterceptable(am: AudioManager): Boolean {
        val m = AudioManager::class.java.getMethod("isPstnCallAudioInterceptable")
        return m.invoke(am) as Boolean
    }

    fun uplinkInjectionTrack(am: AudioManager, format: AudioFormat): AudioTrack {
        val m = AudioManager::class.java.getMethod(
            "getCallUplinkInjectionAudioTrack",
            AudioFormat::class.java,
        )
        return m.invoke(am, format) as AudioTrack
    }

    fun downlinkExtractionRecord(am: AudioManager, format: AudioFormat): AudioRecord {
        val m = AudioManager::class.java.getMethod(
            "getCallDownlinkExtractionAudioRecord",
            AudioFormat::class.java,
        )
        return m.invoke(am, format) as AudioRecord
    }

    /**
     * The mode the official helpers would pass to `setCallRedirectionMode`.
     * Throws [IllegalStateException] when no call is up, matching the
     * framework's WRONG-MODE text.
     */
    fun redirectionForCurrentMode(am: AudioManager): Int {
        val pstn = redirectionConstant("CALL_REDIRECT_PSTN")
        val voip = redirectionConstant("CALL_REDIRECT_VOIP")
        return when (am.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT -> pstn
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_COMMUNICATION_REDIRECT -> voip
            else -> throw IllegalStateException("not available in mode ${modeLabel(am.mode)}")
        }
    }

    fun pstnRedirection(): Int = redirectionConstant("CALL_REDIRECT_PSTN")

    fun voipRedirection(): Int = redirectionConstant("CALL_REDIRECT_VOIP")

    fun redirectionName(mode: Int): String = when (mode) {
        pstnRedirection() -> "PSTN"
        voipRedirection() -> "VOIP"
        else -> "MODE_$mode"
    }

    /**
     * Same builder the official downlink helper uses, with the shell
     * attribution context. The convenience method cannot take a context,
     * so on Android 17 it builds the record as the app package while the
     * process uid is shell, and AudioFlinger rejects it.
     */
    fun shellDownlink(format: AudioFormat, redirectionMode: Int): AudioRecord {
        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setContext(AudioTools.shellContext())
        val min = AudioRecord.getMinBufferSize(
            format.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min > 0) builder.setBufferSizeInBytes(min * 2)
        // PSTN extraction is VOICE_DOWNLINK plus the call-redirection flag.
        // Without the preset, build() keeps the default mic source and the
        // in-call recording is silent. VoIP extraction ignores this and
        // builds its own mix.
        if (redirectionMode == pstnRedirection()) {
            val attrs = AudioAttributes.Builder()
            AudioAttributes.Builder::class.java
                .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
                .invoke(attrs, MediaRecorder.AudioSource.VOICE_DOWNLINK)
            val setAttrs = AudioRecord.Builder::class.java.getMethod(
                "setAudioAttributes",
                AudioAttributes::class.java,
            )
            setAttrs.invoke(builder, attrs.build())
        }
        setRedirection(builder, redirectionMode)
        return builder.build()
    }

    /**
     * Same builder the official uplink helper uses, with shell attribution.
     *
     * This phone's `getCallUplinkInjectionAudioTrack` sets
     * `setSystemUsage(USAGE_CALL_ASSISTANT)` and speech content type before
     * `setCallRedirectionMode`. PSTN `build()` only adds the redirection flag
     * on top of those attributes. Without the usage, the track stays
     * `USAGE_MEDIA` and plays on the phone speaker.
     * VoIP `build()` ignores the attributes and uses the injector mix.
     */
    fun shellUplink(format: AudioFormat, redirectionMode: Int, lowLatency: Boolean = false): AudioTrack {
        val rate = if (format.sampleRate > 0) format.sampleRate else 16_000
        val min = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(callAssistantAttributes())
            .setAudioFormat(format)
            .setBufferSizeInBytes((min * 2).coerceAtLeast(3200))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setContext(AudioTools.shellContext())
        if (lowLatency) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        setRedirection(builder, redirectionMode)
        return builder.build()
    }

    private fun callAssistantAttributes(): AudioAttributes {
        val usage = AudioAttributes::class.java.getField("USAGE_CALL_ASSISTANT").getInt(null)
        val builder = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        try {
            AudioAttributes.Builder::class.java
                .getMethod("setSystemUsage", Int::class.javaPrimitiveType)
                .invoke(builder, usage)
        } catch (t: InvocationTargetException) {
            throw t.targetException ?: t
        }
        return builder.build()
    }

    private fun setRedirection(builder: Any, redirectionMode: Int) {
        val method = builder.javaClass.getMethod(
            "setCallRedirectionMode",
            Int::class.javaPrimitiveType,
        )
        try {
            method.invoke(builder, redirectionMode)
        } catch (t: InvocationTargetException) {
            throw t.targetException ?: t
        }
    }

    private fun redirectionConstant(name: String): Int {
        return AudioManager::class.java.getField(name).getInt(null)
    }

    private fun modeLabel(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
        AudioManager.MODE_CALL_REDIRECT -> "MODE_CALL_REDIRECT"
        AudioManager.MODE_COMMUNICATION_REDIRECT -> "MODE_COMMUNICATION_REDIRECT"
        else -> "MODE_$mode"
    }

    /**
     * Turns a failure from the wrappers (or a direct call site) into a
     * stable, greppable one-line reason. Unwraps InvocationTargetException
     * so the reason is the framework exception, not the reflection wrapper.
     */
    fun describeFailure(t: Throwable): String {
        if (t is NoSuchMethodException || t is NoSuchFieldException) {
            return "API-MISSING: hidden method not on this build (sdk<13 or blocked): ${t.message?.trim()}"
        }
        if (t is IllegalAccessException) {
            return "API-BLOCKED: ${t.message?.trim()}"
        }
        val target = (t as? InvocationTargetException)?.targetException ?: t
        return when (target) {
            is SecurityException ->
                "PERMISSION-DENIED: ${target.message?.trim()}"
            is IllegalStateException ->
                "WRONG-MODE: ${target.message?.trim()}"
            is UnsupportedOperationException ->
                "PSTN-NOT-ACCESSIBLE: ${target.message?.trim()}"
            is NullPointerException ->
                "BAD-ARG: ${target.message?.trim()}"
            else ->
                "FAILED: ${target.javaClass.simpleName}: ${target.message?.trim()}"
        }
    }
}
