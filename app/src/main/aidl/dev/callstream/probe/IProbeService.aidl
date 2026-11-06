package dev.callstream.probe;

import android.app.PendingIntent;

interface IProbeService {
    void destroy() = 16777114;
    void exit() = 1;

    String identity() = 2;
    String classifyPath() = 7;
    String probeCaptureSources(int durationMs) = 8;
    String listDevices() = 9;
    String startInjectorTone(int hz) = 10;
    String stopInjector() = 11;
    String audioMode() = 13;
    String oracle() = 14;
    String officialUplinkTone(int hz) = 15;
    String officialDownlinkCapture(int durationMs) = 16;
    String uplinkCheck(int hz, int durationMs) = 17;
    String telephonyTxTone(int hz, int durationMs) = 18;
    String armAgent() = 19;
    String agentStatus() = 20;
    String stopAgent() = 21;
    String agentPreflight() = 22;
    String setCallStreamEnabled(boolean enabled) = 23;
    String callStreamStatus() = 24;
    String startOutbound(String contactsJson) = 25;
    String stopOutbound() = 26;
    String outboundStatus() = 27;
    String agentOptions() = 28;
    String selectAgent(String profile, String voice) = 29;
    String leadsImport(String contactsJson) = 30;
    String leadsQuery(String queryJson) = 31;
    String leadSave(String leadJson) = 32;
    String leadDelete(long id, boolean allForNumber) = 33;
    String leadReset(long id) = 34;
    String leadTranscript(long id) = 35;
    String leadsDelete(String requestJson) = 49;
    String startTapProbe(int secondsEach) = 36;
    String tapProbeStatus() = 37;
    String setGeminiKey(String key) = 38;
    String setupStatus() = 39;
    String agentFilePreview(String json) = 40;
    String agentFileApply(String json) = 41;
    String agentUndo() = 42;
    String agentPrompt(String profile) = 43;
    String agentEditPrompt(String profile, String prompt) = 44;
    String installUpdate(String url, String sha256) = 46;
    String testAgent() = 47;
    String supportReport() = 48;
    /** Lab (adb): the helper's call events (as in Send report); press WhatsApp's Hang-up now. */
    String callEvents() = 51;
    String whatsAppHangUp() = 52;
    String injectorLoopback(int mixRate, int recordRate, int durationMs) = 53;
    /** For WhatsAppCallActivity: the WhatsApp call notice's Answer or Hang-up ("answer" or "hangup"), or null. */
    PendingIntent whatsAppIntent(String action) = 54;
    /** Sebastian: ON/OFF, status, call a contact by name, speak a line into the live call (lab). */
    String sebastianSetEnabled(boolean enabled) = 60;
    String sebastianStatus() = 61;
    String sebastianDial(String name) = 62;
    String sebastianSay(String text) = 63;
    /** A spoken command after "Hey Sebastian" (16 kHz mono PCM16): transcribed and acted on ("call Vigotel"). */
    String sebastianCommand(in byte[] pcm) = 64;
    /** For the app's "Hey Sebastian" listener: {"token": short-lived AssemblyAI streaming token, "names": [...]}. */
    String sebastianSttToken() = 65;
    /** A command the app already transcribed ("hey sebastian call vigotel"): acted on. */
    String sebastianCommandText(String text) = 66;
    /** Saves Sebastian's settings file (JSON with the person's own AssemblyAI key) where only the helper can read it. */
    String sebastianSetConfig(String json) = 67;
}
