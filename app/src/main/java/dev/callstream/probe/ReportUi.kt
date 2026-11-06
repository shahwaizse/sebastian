package dev.callstream.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.callstream.probe.leads.Lead
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reporting what the AI said, without leaving the app (Google Play's AI-generated content policy). From a call's
 * transcript the report is about that call; from Settings → Support it's about the agent in general. It goes to
 * [BuildConfig.REPORT_URL] (bridge/report-worker), never to the client's own servers.
 */
enum class AiReportReason(val id: String, val label: String) {
    OFFENSIVE("offensive", "Offensive or hateful"),
    HARMFUL("harmful", "Harmful or dangerous"),
    MISLEADING("misleading", "False or misleading"),
    OTHER("other", "Something else"),
}

/** An open report: [lead] and [lines] when it's about one call. [error]: the last send failed. */
data class AiReportState(val lead: Lead? = null, val lines: List<Pair<String, String>> = emptyList(),
                         val sending: Boolean = false, val error: String? = null)

object AiReports {
    private const val MAX_NOTE = 2000

    /** The report's JSON. The lead's number and name never go in it; the transcript only when [withTranscript]. */
    fun payload(report: AiReportState, reason: AiReportReason, note: String, withTranscript: Boolean, app: JSONObject): JSONObject {
        val o = JSONObject().put("reason", reason.id).put("note", note.trim().take(MAX_NOTE)).put("app", app)
        report.lead?.let { lead ->
            val call = JSONObject().put("lead_id", lead.id).put("call_no", lead.callNo).put("called_at", lead.calledAt ?: "")
                .put("talk_time", lead.talkTime)
            if (withTranscript) call.put("transcript", JSONArray(report.lines.map { (who, text) ->
                JSONObject().put("who", if (who == "Agent") "agent" else "caller").put("text", text) }))
            o.put("call", call)
        }
        return o
    }

    /** Blocking; throws with a readable message. */
    fun send(payload: JSONObject) {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        HTTP.newCall(Request.Builder().url(BuildConfig.REPORT_URL).post(body).build()).execute().use { r ->
            check(r.isSuccessful) { "Couldn't send the report (HTTP ${r.code}). Try again." }
        }
    }

    private val HTTP by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }
}

@Composable
fun AiReportDialog(report: AiReportState, onSend: (AiReportReason, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf<AiReportReason?>(null) }
    var note by remember { mutableStateOf("") }
    var withTranscript by remember { mutableStateOf(true) }
    val aboutCall = report.lead != null
    AlertDialog(
        onDismissRequest = { if (!report.sending) onDismiss() },
        title = { Text(if (aboutCall) "Report this call" else "Report the AI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (aboutCall) "Tell us what was wrong with what the agent said. We review every report."
                    else "Tell us if the agent said something offensive, harmful or untrue. We review every report.",
                    style = MaterialTheme.typography.bodyMedium)
                for (r in AiReportReason.entries) Row(Modifier.fillMaxWidth().selectable(reason == r, enabled = !report.sending) { reason = r },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = reason == r, onClick = null, enabled = !report.sending)
                    Text(r.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), enabled = !report.sending,
                    label = { Text("What happened? (optional)") }, minLines = 2, maxLines = 5)
                if (aboutCall && report.lines.isNotEmpty()) Row(Modifier.fillMaxWidth().selectable(withTranscript, enabled = !report.sending) {
                    withTranscript = !withTranscript }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withTranscript, onCheckedChange = null, enabled = !report.sending)
                    Text("Include this call's transcript (not the number or name)", Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
                report.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (report.sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { reason?.let { onSend(it, note, aboutCall && withTranscript) } }, enabled = reason != null) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !report.sending) { Text("Cancel") } },
    )
}
