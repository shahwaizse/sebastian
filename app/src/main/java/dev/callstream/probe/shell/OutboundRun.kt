package dev.callstream.probe.shell

import android.util.Log
import dev.callstream.probe.leads.CallExtractor
import dev.callstream.probe.leads.Lead
import dev.callstream.probe.leads.LeadRules
import dev.callstream.probe.leads.LeadStore
import dev.callstream.probe.leads.LeadTime
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The outbound run: dials the lead sheet one call at a time until stopped. Before each call it reads the
 * sheet again (LeadRules.pickNext), so a callback that fell due during a call is dialed right after it.
 * With nothing due it stays armed and checks again. Owned by [CallStreamController], which drives it from
 * its call-watching thread and guards it with its lock.
 */
internal class OutboundRun(private val store: LeadStore) {
    var stopRequested = false
        private set
    /** The row being called, or null between calls. */
    var current: Lead? = null
        private set
    private var currentStatus = ""
    /** Elapsed-realtime ms before which the next call isn't placed. */
    private var nextDialAt = 0L
    private var nextCheckAt = 0L
    private var checkedVersion = -1L
    /** Why nothing is being dialed right now. */
    var waiting = "checking the sheet"
        private set

    val finished get() = current == null && stopRequested

    /** The lead to dial now, marked dialing; null if none is due (see [waiting]). */
    fun begin(now: Long): Lead? {
        if (stopRequested || current != null || now < nextDialAt) return null
        if (now < nextCheckAt && store.version == checkedVersion) return null
        nextCheckAt = now + CHECK_MS
        checkedVersion = store.version
        return when (val next = LeadRules.pickNext(store.all(), LeadTime.now())) {
            is LeadRules.Next.Call -> {
                store.dialing(next.lead.id)
                currentStatus = Lead.DIALING
                waiting = ""
                next.lead.also { current = it }
            }
            is LeadRules.Next.Wait -> {
                waiting = if (next.until == null) "nothing left to call; new leads and callbacks are dialed as they come"
                    else "${next.why} at ${WHEN.format(next.until)}"
                null
            }
        }
    }

    fun update(status: String) {
        val lead = current ?: return
        if (status == currentStatus) return
        currentStatus = status
        store.status(lead.id, status)
    }

    /** Ends the current call; the next is placed [gapMs] later, after a fresh look at the sheet. */
    fun end(status: String, note: String, now: Long, gapMs: Long, talkSeconds: Int? = null): Lead? {
        val lead = current ?: return null
        store.status(lead.id, status, note, talkSeconds)
        current = null; currentStatus = ""
        nextDialAt = now + gapMs; nextCheckAt = 0
        return lead
    }

    /** No new calls; the call in progress (if any) runs to its end. */
    fun stop() { stopRequested = true }

    /** What the agent should know before a callback: the earlier calls and what they settled. Null for a first call. */
    fun notes(lead: Lead): String? {
        val earlier = store.history(lead)
        if (lead.callNo <= 1 && earlier.isEmpty()) return null
        return buildString {
            append("follow-up call ").append(lead.callNo)
            if (lead.followUpAt != null) append(", they asked to be called back at this time")
            earlier.lastOrNull()?.calledAt?.let { LeadTime.parse(it) }?.let { append("; last spoke ").append(WHEN.format(it)) }
            val known = AgentConfig.clientSpec().fields.mapNotNull { f -> earlier.lastOrNull { it.field(f.key).isNotBlank() }?.let { "${f.label}: ${it.field(f.key)}" } }
            if (known.isNotEmpty()) append("; known from earlier calls: ").append(known.joinToString(", "))
        }
    }

    /**
     * After an answered call: the transcript is saved on the row, and the small model fills in the row from it
     * (see CallExtractor). On its own thread; the next call doesn't wait for it. A callback it finds is dialed when due.
     */
    fun extractLater(lead: Lead, transcript: List<Pair<String, String>>, talkSeconds: Int, config: JSONObject) {
        if (transcript.isNotEmpty()) store.saveTranscript(lead.id, transcript)
        val model = config.optString("extract_model")
        val key = config.optString("gemini_api_key")
        if (model.isEmpty() || key.isEmpty()) return
        if (transcript.isEmpty()) { store.extractionFailed(lead.id, "no transcript"); return }
        val endedAt = LeadTime.now()
        Thread({
            val spec = AgentConfig.clientSpec()
            val body = CallExtractor.requestBody(spec, transcript, endedAt, talkSeconds, lead.name)
            var error = ""
            for (attempt in 0 until 3) {
                if (attempt > 0) Thread.sleep(attempt * 15_000L)
                val result = runCatching { CallExtractor.parse(spec, CallExtractor.call(key, model, body), endedAt) }
                result.onSuccess { store.extracted(lead.id, it); Log.i(TAG, "Lead ${lead.id} filled in after the call"); return@Thread }
                error = "${result.exceptionOrNull()!!.javaClass.simpleName}: ${result.exceptionOrNull()!!.message}"
                Log.w(TAG, "Extraction try ${attempt + 1} failed for lead ${lead.id}: $error")
            }
            store.extractionFailed(lead.id, "failed: $error")
        }, "lead-extraction").apply { isDaemon = true; start() }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("running", !finished)
        .put("stop_requested", stopRequested)
        .put("current_id", current?.id ?: -1)
        .put("waiting", waiting)
        .put("version", store.version)

    companion object {
        private const val TAG = "OutboundRun"
        /** While waiting, the sheet is read again this often (and at once when the app changes it). */
        private const val CHECK_MS = 5_000L
        private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)
        fun idleJson(version: Long): String = JSONObject().put("running", false).put("version", version).toString()
    }
}
