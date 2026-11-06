package dev.callstream.probe.shell

import android.content.Context
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import dev.callstream.probe.IProbeService
import dev.callstream.probe.leads.LeadStore

class ProbeService : IProbeService.Stub {

    private val appContext: Context?

    @Keep
    constructor() : this(null)

    @Keep
    constructor(context: Context?) {
        appContext = context?.applicationContext ?: context
        Log.i(TAG, "ProbeService start uid=${Os.getuid()} pid=${Os.getpid()} ctx=$appContext")
    }

    private val agent = GeminiSession()
    /** The lead sheet (SQLite, shell-owned); opened on first use. */
    private val leads by lazy { LeadStore(LEADS_PATH) { AgentConfig.clientSpec() } }
    private val callStream = CallStreamController(agent) { leads }
    /** Sebastian replaces Call Stream's AI answering: the app's ON/OFF switch drives him instead. */
    private val sebastian = SebastianController()
    private val lab by lazy {
        LabProbes(appContext, pauseCalls = { callStream.setEnabled(false, persist = false); agent.stop() },
            callsBusy = { callStream.isEnabled() || agent.isLive() })
    }

    // Lab probes (adb only; see LabProbes).
    override fun startTapProbe(secondsEach: Int): String = lab.startTapProbe(secondsEach)
    override fun tapProbeStatus(): String = lab.tapProbeStatus()
    override fun classifyPath(): String = lab.classifyPath()
    override fun probeCaptureSources(durationMs: Int): String = lab.probeCaptureSources(durationMs)
    override fun listDevices(): String = lab.listDevices()
    override fun startInjectorTone(hz: Int): String = lab.startInjectorTone(hz)
    override fun stopInjector(): String = lab.stopAll()
    override fun audioMode(): String = lab.audioMode()
    override fun oracle(): String = lab.oracle()
    override fun officialUplinkTone(hz: Int): String = lab.officialUplinkTone(hz)
    override fun officialDownlinkCapture(durationMs: Int): String = lab.officialDownlinkCapture(durationMs)
    override fun uplinkCheck(hz: Int, durationMs: Int): String = lab.uplinkCheck(hz, durationMs)
    override fun telephonyTxTone(hz: Int, durationMs: Int): String = lab.telephonyTxTone(hz, durationMs)

    override fun setCallStreamEnabled(enabled: Boolean): String {
        if (enabled) lab.stopTones()
        return try { sebastian.setEnabled(enabled) } catch (t: Throwable) {
            val cause = t.cause ?: t
            Log.e(TAG, "Sebastian toggle failed", cause)
            "ERROR — ${cause.javaClass.simpleName}: ${cause.message}"
        }
    }
    override fun callStreamStatus(): String = sebastian.status()
    override fun sebastianSetEnabled(enabled: Boolean): String = setCallStreamEnabled(enabled)
    override fun sebastianStatus(): String = sebastian.status()
    override fun sebastianDial(name: String): String = try { sebastian.dial(name) } catch (t: Throwable) { "ERROR — ${t.message}" }
    override fun sebastianSay(text: String): String = sebastian.say(text)
    override fun sebastianCommand(pcm: ByteArray): String = try { sebastian.command(pcm) } catch (t: Throwable) { "ERROR — ${t.message}" }
    override fun sebastianSttToken(): String = try { sebastian.sttToken() } catch (t: Throwable) { org.json.JSONObject().put("error", t.message).toString() }
    override fun sebastianCommandText(text: String): String = try { sebastian.commandText(text) } catch (t: Throwable) { "ERROR — ${t.message}" }
    override fun sebastianSetConfig(json: String): String = try { sebastian.setConfig(json) } catch (t: Throwable) { "ERROR — ${t.message}" }
    // Call Stream's AI lead dialing would talk instead of Rayyan: never in Sebastian.
    override fun startOutbound(contactsJson: String): String = "Sebastian doesn't run lead sheets"
    override fun stopOutbound(): String = callStream.stopOutbound()
    override fun outboundStatus(): String = callStream.outboundStatus()
    override fun leadsImport(contactsJson: String): String = sheet { it.import(contactsJson) }
    override fun leadsQuery(queryJson: String): String = sheet { it.query(queryJson) }
    override fun leadSave(leadJson: String): String = sheet { it.save(leadJson) }
    override fun leadDelete(id: Long, allForNumber: Boolean): String = sheet { it.delete(id, allForNumber) }
    override fun leadReset(id: Long): String = sheet { it.reset(id) }
    override fun leadTranscript(id: Long): String = sheet { it.transcript(id) }
    override fun leadsDelete(requestJson: String): String = sheet { it.deleteMany(requestJson) }

    /** The binder hands the app only the message; the stack stays in the helper's log. */
    private fun sheet(call: (LeadStore) -> String): String = try { call(leads) } catch (t: Throwable) {
        Log.e(TAG, "Lead sheet call failed", t)
        throw t
    }

    /** Called by HelperDaemon once at helper start. */
    fun restoreCallStream(): String? = runCatching { sebastian.restorePersisted() }.getOrElse { "restore failed: ${it.message}" }

    override fun armAgent(): String {
        stopInjector()
        return agent.arm()
    }
    override fun agentPreflight(): String {
        stopInjector()
        return agent.arm(true)
    }
    override fun agentStatus(): String = agent.status()
    override fun agentOptions(): String = AgentConfig.options()
    override fun selectAgent(profile: String, voice: String): String = AgentConfig.select(profile, voice)
    override fun setGeminiKey(key: String): String = AgentConfig.setKey(key)
    override fun setupStatus(): String = PhoneSetup.ensure(appContext ?: error("helper has no context"))
    override fun agentFilePreview(json: String): String = AgentConfig.previewFile(json)
    override fun agentFileApply(json: String): String = AgentConfig.applyFile(json)
    override fun agentUndo(): String = AgentConfig.undo()
    override fun agentPrompt(profile: String): String = AgentConfig.prompt(profile)
    override fun agentEditPrompt(profile: String, prompt: String): String = AgentConfig.editPrompt(profile, prompt)
    override fun installUpdate(url: String, sha256: String): String = Updater.install(url, sha256, callActive = agent.isLive())
    /**
     * Settings → Test my agent: the preflight (a short Gemini session with a recorded Urdu caller line), waited for.
     * Call Stream pauses for it and comes back on if it was on. {"pass", "reply_ms", "message"}.
     */
    override fun testAgent(): String {
        check(!agent.isLive()) { "A call is in progress. Test after it ends." }
        check(java.io.File(GeminiSession.CONFIG_PATH).canRead()) { "No agent on this phone yet: open your agent file first." }
        val wasOn = callStream.isEnabled()
        stopInjector()
        try {
            agent.arm(true)
            val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
            while (agent.isLive() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
            if (agent.isLive()) agent.stop()
            val state = agent.status().lineSequence().first()
            val reply = agent.preflightReplyMs
            val pass = state.startsWith("PREFLIGHT PASS") && reply >= 0
            return org.json.JSONObject().put("pass", pass).put("reply_ms", reply.toLong())
                .put("message", state.substringAfter(" — ", state)).toString()
        } finally {
            // (Call Stream stays off in Sebastian)
        }
    }

    override fun supportReport(): String = Support.report(
        setup = PhoneSetup.ensure(appContext ?: error("helper has no context")), status = callStream.status(),
        outbound = callStream.outboundStatus(), options = runCatching { AgentConfig.options() }.getOrElse { "{}" },
        calls = callStream.events() + "\n\n" + WhatsAppCalls.telecomAccounts())

    override fun callEvents(): String = callStream.events() + "\n\n" + WhatsAppCalls.telecomAccounts()
    override fun whatsAppHangUp(): String = callStream.labWhatsAppHangUp()
    override fun injectorLoopback(mixRate: Int, recordRate: Int, durationMs: Int): String =
        runCatching { lab.injectorLoopback(mixRate, recordRate, durationMs) }.getOrElse { "FAILED: ${it.javaClass.simpleName}: ${it.message}" }

    override fun whatsAppIntent(action: String): android.app.PendingIntent? = callStream.whatsAppIntent(action)

    override fun stopAgent(): String = callStream.setEnabled(false, persist = false)

    override fun destroy() {
        runCatching { stopInjector() }
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    override fun exit() = destroy()

    override fun identity(): String {
        val uid = Os.getuid()
        val kind = when (uid) {
            0 -> "root"
            2000 -> "shell"
            else -> "other"
        }
        return buildString {
            appendLine("uid=$uid ($kind)")
            appendLine("pid=${Os.getpid()}")
            appendLine("tid=${Process.myTid()}")
            appendLine("user=${lab.exec("whoami").trim()}")
            appendLine("context=${appContext?.javaClass?.name ?: "null"}")
            appendLine(lab.exec("getprop", "ro.product.model").trim().let { "model=$it" })
            appendLine(lab.exec("getprop", "ro.build.version.release").trim().let { "android=$it" })
            appendLine(lab.exec("getprop", "ro.build.version.sdk").trim().let { "sdk=$it" })
            appendLine(lab.exec("getprop", "ro.hardware").trim().let { "hardware=$it" })
            appendLine(lab.exec("getprop", "ro.product.device").trim().let { "device=$it" })
        }.trim()
    }


    companion object {
        private const val TAG = "CallStreamProbe"
        const val LEADS_PATH = "/data/local/tmp/call-stream-leads.db"
    }
}
