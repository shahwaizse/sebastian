package dev.callstream.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * adb-triggerable entry point. Broadcasters must hold DUMP (shell does,
 * apps do not). Ops map 1:1 to IProbeService methods.
 *
 * Usage:
 *   adb shell am broadcast -a dev.callstream.probe.RUN \
 *       -p dev.callstream.probe --es op oracle
 *
 * Each op MUST finish in well under 10 s (broadcast ANR budget):
 * receiver caps durationMs at 2000. Dumps are NOT receiver ops — the
 * verifier runs dumpsys directly over adb.
 *
 * The AIDL call runs on the worker thread. The shell helper is launched or
 * reconnected through [HelperConnector] and stays running between ops;
 * use stop_all to stop tones.
 */
class ProbeReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "CallStreamProbeRX"
        const val ACTION = "dev.rawaiz.sebastian.RUN"

        /** Cap every receiver-driven window at 2 s (ANR budget). */
        private const val MAX_MS = 2000

        private val gate = Any()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val op = intent.getStringExtra("op")?.trim()?.lowercase() ?: "none"
        val durationMs = (intent.getIntExtra("durationMs", 1000)).coerceIn(250, MAX_MS)
        val hz = intent.getIntExtra("hz", 440)
        val pending = goAsync()
        Thread {
            try {
                val result = runOp(context, intent, op, durationMs, hz)
                logResult("op=$op result:\n$result")
                writeResult(context, op, result)
            } catch (t: Throwable) {
                logResult("op=$op CRASHED: ${t.stackTraceToString().take(2000)}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun runOp(context: Context, intent: Intent, op: String, durationMs: Int, hz: Int): String {
        synchronized(gate) {
            val ib = try {
                HelperConnector.obtain(context, 9_000)
            } catch (t: Throwable) {
                return t.message ?: "BIND-FAILED: ${t.javaClass.simpleName}"
            }
            val svc = IProbeService.Stub.asInterface(ib)
            val result = try {
                when (op) {
                    // Sebastian (adb): --es name Shahwaiz / --es text "Hello"
                    "sebastian_on" -> svc.sebastianSetEnabled(true)
                    "sebastian_off" -> svc.sebastianSetEnabled(false)
                    "sebastian_status" -> svc.sebastianStatus()
                    "sebastian_dial" -> svc.sebastianDial(intent.getStringExtra("name").orEmpty())
                    "sebastian_say" -> svc.sebastianSay(intent.getStringExtra("text").orEmpty())
                    "stream_on" -> svc.setCallStreamEnabled(true)
                    "stream_off" -> svc.setCallStreamEnabled(false)
                    "stream_status" -> svc.callStreamStatus()
                    // Dials the lead sheet. Lab entry: --es numbers "+923001234567,+923011234567" adds them first (no names).
                    "outbound_start" -> svc.startOutbound(org.json.JSONArray().apply {
                        intent.getStringExtra("numbers").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { n -> put(org.json.JSONObject().put("name", "").put("number", n)) }
                    }.toString())
                    "outbound_stop" -> svc.stopOutbound()
                    "outbound_status" -> svc.outboundStatus()
                    // --es query '{"filter":"interested"}' (all, interested, follow_ups, not_called; search, offset, limit)
                    "leads_query" -> svc.leadsQuery(intent.getStringExtra("query") ?: "{}")
                    // --el id 5: that call's saved transcript (JSON), for debugging a call
                    "lead_transcript" -> svc.leadTranscript(intent.getLongExtra("id", -1))
                    "agent_preflight" -> svc.agentPreflight()
                    "test_agent" -> svc.testAgent()
                    "support_report" -> org.json.JSONObject(svc.supportReport()).let { r ->
                        val files = r.getJSONObject("files")
                        r.getJSONObject("phone").toString() + "\n" + files.keys().asSequence().joinToString("\n") { "${it}: ${files.getString(it).length} chars" }
                    }
                    // --ei hz <mix rate> --ei rate <record rate> --ei durationMs 2000
                    "injector_loopback" -> svc.injectorLoopback(if (hz == 440) 16_000 else hz, intent.getIntExtra("rate", 16_000), durationMs)
                    "call_events" -> svc.callEvents()
                    "whatsapp_hangup" -> svc.whatsAppHangUp()
                    "agent_arm" -> svc.armAgent()
                    "agent_status" -> svc.agentStatus()
                    "agent_stop" -> svc.stopAgent()
                    "agent_options" -> svc.agentOptions()
                    // --es profile acme [--es voice Orus]; no voice = the profile's own.
                    "agent_select" -> svc.selectAgent(intent.getStringExtra("profile").orEmpty(), intent.getStringExtra("voice").orEmpty())
                    // --ei seconds 10: during one live call (Call Stream OFF), records each capture point in turn.
                    "tap_probe" -> svc.startTapProbe(intent.getIntExtra("seconds", 10))
                    "tap_status" -> svc.tapProbeStatus()
                    "identity" -> svc.identity()
                    "oracle" -> svc.oracle()
                    "classify" -> svc.classifyPath()
                    "capture" -> svc.probeCaptureSources(durationMs)
                    "devices" -> svc.listDevices()
                    "mode" -> svc.audioMode()
                    "official_capture" -> svc.officialDownlinkCapture(durationMs)
                    "official_tone" -> svc.officialUplinkTone(hz)
                    "injector_tone" -> svc.startInjectorTone(hz)
                    "uplink_check" -> svc.uplinkCheck(hz, durationMs)
                    "tx_tone" -> svc.telephonyTxTone(hz, durationMs)
                    "stop_all" -> svc.stopInjector()
                    else -> "unknown op: $op (valid: identity oracle classify capture devices mode official_capture official_tone injector_tone uplink_check tx_tone stop_all)"
                }
            } catch (t: Throwable) {
                "OP-FAILED $op: ${t.message}\n${t.stackTraceToString().take(1200)}"
            }
            val sustain = op.startsWith("stream_") || op.startsWith("agent_") || op.startsWith("outbound_") ||
                (op == "official_tone" && result.contains("track: OPEN")) ||
                (op == "injector_tone" && result.startsWith("injector registered"))
            return if (sustain) result + "\n\nreceiver: helper retained for the active session." else result
        }
    }

    private fun logResult(text: String) {
        text.chunked(3500).forEachIndexed { i, c -> Log.i(TAG, "[$i] $c") }
    }

    private fun writeResult(context: Context, op: String, result: String) {
        try {
            val dir = context.getExternalFilesDir("dumps")
                ?: File(context.filesDir, "dumps").apply { mkdirs() }
            File(dir, "rx_${op}_${System.currentTimeMillis()}.txt").writeText(result + "\n")
        } catch (t: Throwable) {
            Log.w(TAG, "writeResult failed: ${t.message}")
        }
    }
}
