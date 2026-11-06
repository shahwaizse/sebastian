package cn.enaium.webrtc.aec3;

/**
 * The native entry points of webrtc-aec3-kmp 1.0.3 (MIT, github.com/Enaium/webrtc-aec3-kmp): WebRTC's AEC3 echo canceller.
 *
 * Sebastian ships only that project's arm64 libwebrtc_aec3_jni.so (app/src/main/jniLibs). Its Kotlin layer needs a
 * Kotlin 2.4 compiler, so this class replaces it: same package, class and method names, so the library's JNI symbols
 * (Java_cn_enaium_webrtc_aec3_Jni_*) bind here. Nothing loads the library from this class: see shell/EchoCanceller.
 *
 * Handles are native pointers. Audio buffers hold one 10 ms frame per channel, as floats in the int16 range.
 */
public final class Jni {
    public static final Jni INSTANCE = new Jni();

    private Jni() {}

    public native long configCreateDefault();
    public native void configDestroy(long config);
    public native void configSetDelayDefaultDelay(long config, int blocks);
    public native void configSetFilterInitialStateSeconds(long config, float seconds);
    public native void configSetFilterConservativeInitialPhase(long config, boolean on);
    public native boolean configValidate(long config);
    public native String configToJson(long config);
    public native long configFromJson(String json);

    public native long environmentCreate();
    public native void environmentDestroy(long env);

    public native long factoryCreate();
    public native long factoryCreateWithConfig(long config);
    public native void factoryDestroy(long factory);

    public native long echoControlCreate(long factory, long env, int sampleRate, int renderChannels, int captureChannels);
    public native void echoControlDestroy(long echo);
    public native void echoControlAnalyzeRender(long echo, long buffer);
    public native void echoControlAnalyzeCapture(long echo, long buffer);
    public native void echoControlProcessCapture(long echo, long buffer, boolean levelChange);
    public native double[] echoControlGetMetrics(long echo);
    public native void echoControlSetAudioBufferDelay(long echo, int delayMs);

    public native long audioBufferCreate(int sampleRate, int channels);
    public native void audioBufferDestroy(long buffer);
    public native void audioBufferWriteChannel(long buffer, int channel, float[] frame);
    public native float[] audioBufferReadChannel(long buffer, int channel);
    public native int audioBufferNumChannels(long buffer);
    public native int audioBufferSamplesPerChannel(long buffer);

    public native String version();
}
