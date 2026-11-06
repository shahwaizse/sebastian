package dev.callstream.probe.shell

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import java.lang.reflect.Method

/**
 * Reflection wrappers around @SystemApi AudioPolicy / AudioMix.
 * Hidden-API checks are off in the shell helper process (app_process).
 */
internal class HiddenAudioPolicy(private val context: Context) {

    data class InjectorSession(
        val policy: Any,
        val mix: Any,
        val track: AudioTrack,
        val presetName: String,
    )

    /**
     * [uids]: only these apps' capture is replaced (empty: every app's capture with [preset]).
     * [excludeUids]: these apps' capture is never replaced (Android 14+ checks exclusion rules for recorder mixes,
     * AudioPolicyMix areMixCriteriaMatched), so they can open the real [preset] mic with the phone's own processing.
     */
    fun registerInjector(
        preset: Int,
        sampleRate: Int = 16_000,
        uids: List<Int> = emptyList(),
        excludeUids: List<Int> = emptyList(),
    ): InjectorSession {
        val presetName = presetLabel(preset)
        val attrs = captureAttrs(preset)
        val rule = mixingRuleForCapture(attrs, uids, excludeUids)
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val mix = audioMix(rule, format)
        val policy = audioPolicy(mix)
        // AudioPolicy checks MODIFY_AUDIO_ROUTING with checkCallingOrSelfPermission. Inside a binder call from the
        // app that is the app's uid, which lacks it ("Cannot use AudioPolicy for pid … / uid 10363", 2026-10-25).
        val token = Binder.clearCallingIdentity()
        try {
            register(policy)
            // createAudioTrackSource builds its AudioTrack without a context, so it's attributed to the app package
            // in a shell-uid process and AudioFlinger refuses it ("could not create track, output 0", 2026-10-25,
            // with the mix registered and its output open). Same track, built here with shell attribution.
            val track = try {
                shellInjectorTrack(mix, format)
            } catch (t: Throwable) {
                unregisterPolicy(policy)
                throw IllegalStateException("injector track for $presetName: ${HiddenCallAudio.describeFailure(t)}", t)
            }
            return InjectorSession(policy, mix, track, presetName)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /** What AudioPolicy.createAudioTrackSource builds: USAGE_VIRTUAL_SOURCE tagged with the mix's address. */
    private fun shellInjectorTrack(mix: Any, format: AudioFormat): AudioTrack {
        val registration = mix.javaClass.getMethod("getRegistration").invoke(mix) as String
        // A hidden ordinary usage, not a system one: setSystemUsage rejects it ("Invalid system usage 15").
        val virtualSource = AudioAttributes::class.java.getField("USAGE_VIRTUAL_SOURCE").getInt(null)
        val builder = AudioAttributes.Builder().setUsage(virtualSource)
        AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
            .invoke(builder, "addr=$registration")
        val min = AudioTrack.getMinBufferSize(format.sampleRate, format.channelMask, format.encoding)
        return AudioTrack.Builder()
            .setAudioAttributes(builder.build())
            .setAudioFormat(format)
            // Room for the session's largest VoIP window (128 ms) twice over, at any rate.
            .setBufferSizeInBytes(min.coerceAtLeast(format.sampleRate / 1000 * 256 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setContext(AudioTools.shellContext())
            .build()
    }

    data class ExtractorSession(val policy: Any, val record: AudioRecord)

    /**
     * Takes the voice-communication playback of [uids] (a VoIP app's far end) into a loopback mix and returns the recorder
     * that reads it. The playback leaves the speaker/earpiece while registered. Same identity handling as
     * [registerInjector]; the recorder is what AudioPolicy.createAudioRecordSink builds, with shell attribution.
     */
    fun registerExtractor(uids: List<Int>, sampleRate: Int = 16_000, keepPlayback: Boolean = false): ExtractorSession {
        require(uids.isNotEmpty()) { "no app uid to capture" }
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val builderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        val usage = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build()
        builderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
            .invoke(builder, usage, ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null))
        // Rules of one kind are OR'd, kinds are AND'd: voice-communication playback of any of these apps.
        val addMixRule = builderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        val matchUid = ruleClass.getField("RULE_MATCH_UID").getInt(null)
        uids.forEach { addMixRule.invoke(builder, matchUid, it) }
        // Voice-communication playback is excluded from mixes unless this is set.
        builderClass.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType).invoke(builder, true)
        val rule = builderClass.getMethod("build").invoke(builder) ?: error("AudioMixingRule.Builder.build() returned null")
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        // keepPlayback: copy the audio to the loopback AND let it play where it was going (the earpiece).
        val mix = audioMix(rule, format, keepPlayback)
        val policy = audioPolicy(mix)
        val token = Binder.clearCallingIdentity()
        try {
            register(policy)
            val record = try {
                shellExtractorRecord(mix, sampleRate)
            } catch (t: Throwable) {
                unregisterPolicy(policy)
                throw IllegalStateException("extractor record: ${HiddenCallAudio.describeFailure(t)}", t)
            }
            return ExtractorSession(policy, record)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * The recorder for the mix: REMOTE_SUBMIX tagged with the mix address, fixed volume. (createAudioRecordSink uses the
     * VOICE_COMMUNICATION preset for a voice-communication mix; on the Pixel that recorder was routed to the built-in
     * mic, type 15, 2026-10-25.)
     */
    private fun shellExtractorRecord(mix: Any, sampleRate: Int): AudioRecord {
        val registration = mix.javaClass.getMethod("getRegistration").invoke(mix) as String
        val attrs = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attrs, MediaRecorder.AudioSource.REMOTE_SUBMIX)
        val addTag = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
        addTag.invoke(attrs, "addr=$registration")
        addTag.invoke(attrs, "fixedVolume")
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes((min * 2).coerceAtLeast(3200))
            .setContext(AudioTools.shellContext())
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(builder, attrs.build())
        return builder.build().also { check(it.state == AudioRecord.STATE_INITIALIZED) { "extractor record not initialized" } }
    }

    fun unregister(session: ExtractorSession) {
        runCatching { session.record.stop() }
        runCatching { session.record.release() }
        unregisterPolicy(session.policy)
    }

    fun unregister(session: InjectorSession) {
        try {
            session.track.pause()
            session.track.flush()
            session.track.release()
        } catch (_: Throwable) {
        }
        unregisterPolicy(session.policy)
    }

    private fun unregisterPolicy(policy: Any) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val method = AudioManager::class.java.methods.firstOrNull {
                it.name == "unregisterAudioPolicy" || it.name == "unregisterAudioPolicyAsync"
            } ?: error("AudioManager.unregisterAudioPolicy not found")
            method.invoke(am, policy)
        } catch (_: Throwable) {
        }
    }

    private fun captureAttrs(preset: Int): AudioAttributes {
        val builder = AudioAttributes.Builder()
        val method: Method = AudioAttributes.Builder::class.java.getMethod(
            "setCapturePreset",
            Int::class.javaPrimitiveType,
        )
        method.invoke(builder, preset)
        return builder.build()
    }

    private fun mixingRuleForCapture(attrs: AudioAttributes, uids: List<Int> = emptyList(), excludeUids: List<Int> = emptyList()): Any {
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val builderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val builder = builderClass.getConstructor().newInstance()

        val matchPreset = ruleClass.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null)
        builderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
            .invoke(builder, attrs, matchPreset)
        // Kinds of rules are AND'd: this preset, from one of these apps (Sebastian's own mic capture stays real).
        if (uids.isNotEmpty()) {
            val addMixRule = builderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            val matchUid = ruleClass.getField("RULE_MATCH_UID").getInt(null)
            uids.forEach { addMixRule.invoke(builder, matchUid, it) }
        }
        if (excludeUids.isNotEmpty()) {
            val excludeMixRule = builderClass.getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
            val matchUid = ruleClass.getField("RULE_MATCH_UID").getInt(null)
            excludeUids.forEach { excludeMixRule.invoke(builder, matchUid, it) }
        }

        runCatching {
            val injector = ruleClass.getField("MIX_ROLE_INJECTOR").getInt(null)
            builderClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
                .invoke(builder, injector)
        }

        runCatching {
            builderClass.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
        }

        return builderClass.getMethod("build").invoke(builder)
            ?: error("AudioMixingRule.Builder.build() returned null")
    }

    private fun audioMix(rule: Any, format: AudioFormat, keepPlayback: Boolean = false): Any {
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val builderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val builder = builderClass.getConstructor(ruleClass).newInstance(rule)
        builderClass.getMethod("setFormat", AudioFormat::class.java).invoke(builder, format)
        var flags = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
        if (keepPlayback) flags = flags or mixClass.getField("ROUTE_FLAG_RENDER").getInt(null)
        builderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(builder, flags)
        return builderClass.getMethod("build").invoke(builder)
            ?: error("AudioMix.Builder.build() returned null")
    }

    private fun audioPolicy(mix: Any): Any {
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val builder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(builder, mix)
        return policyBuilderClass.getMethod("build").invoke(builder)
            ?: error("AudioPolicy.Builder.build() returned null")
    }

    private fun register(policy: Any) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val method = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass)
        val result = method.invoke(am, policy)
        if (result is Int && result != 0) {
            error("registerAudioPolicy failed with code $result")
        }
        if (result is Boolean && !result) {
            error("registerAudioPolicy returned false")
        }
    }

    companion object {
        fun presetLabel(preset: Int): String = when (preset) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            1997 -> "ECHO_REFERENCE"
            else -> "SOURCE_$preset"
        }
    }
}
