package dev.callstream.probe

import dev.callstream.probe.leads.ClientSpec
import dev.callstream.probe.leads.Lead

/** What the home screen's hero shows, read from the helper's status text (CallStreamController.status). */
enum class Phase { CONNECTING, OFF, READY, RINGING, ON_CALL, DIALING, ERROR }

data class StreamView(val phase: Phase, val detail: String = "") {
    val known get() = phase != Phase.CONNECTING
    val on get() = phase != Phase.OFF && phase != Phase.CONNECTING

    companion object {
        /** "enabled=true\nREADY — …\n…": line 1 is the switch, line 2 the controller's state. */
        fun parse(status: String): StreamView {
            if (!status.startsWith("enabled=")) return StreamView(Phase.CONNECTING)
            val state = status.lineSequence().drop(1).firstOrNull().orEmpty()
            val after = state.substringAfter(" — ", "").trim()
            if (!status.startsWith("enabled=true")) return StreamView(if (state.startsWith("ERROR")) Phase.ERROR else Phase.OFF,
                if (state.startsWith("ERROR")) after else "")
            return when {
                state.startsWith("AI CALL") -> StreamView(Phase.ON_CALL, if (after.startsWith("outbound")) "outbound" else "")
                state.startsWith("PREPARING") || state.startsWith("ANSWERING") -> StreamView(Phase.RINGING)
                state.startsWith("OUTBOUND — calling ") -> StreamView(Phase.DIALING, state.removePrefix("OUTBOUND — calling ").trim())
                state.startsWith("OUTBOUND — waiting") -> StreamView(Phase.READY, after.removePrefix("waiting:").trim())
                state.startsWith("ERROR") -> StreamView(Phase.ERROR, after)
                else -> StreamView(Phase.READY)
            }
        }
    }
}

/** "acme-devanagari" → "Acme Devanagari". */
fun profileTitle(name: String): String = name.split('-', '_').filter { it.isNotEmpty() }
    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

enum class Tab { HOME, LEADS, AGENT, SETTINGS }

/** Everything the screens draw. MainActivity builds it from its state; screenshot tests build it by hand. */
data class UiState(
    val spec: ClientSpec,
    val version: String,
    val helperBound: Boolean,
    val stream: StreamView,
    val controlError: String?,
    val busy: Boolean,
    val agent: AgentOptions,
    val setup: List<SetupStep>,
    val leads: LeadsPage,
    val leadQuery: LeadQuery,
    val outbound: OutboundState,
    val canCall: Boolean,
    val keyMessage: Notice?,
    val editing: Lead?,
    val editError: String?,
    val transcript: Pair<Lead, List<Pair<String, String>>>?,
    val diagnostics: Diagnostics,
    val agentImport: AgentImport? = null,
    /** A lead file being checked before upload (LeadImportSheet), open while non-null. */
    val leadImport: LeadImport? = null,
    val promptView: PromptView? = null,
    val updates: UpdateState = UpdateState(),
    /** Setup was complete on this phone before: an incomplete step now means something broke, not a new phone. */
    val setupEverComplete: Boolean = false,
    val support: SupportState = SupportState(),
    val showSetupGuide: Boolean = false,
    /** Developer options and the app's ADB pairing, as the app sees them (Settings → Phone). */
    val adb: AdbState = AdbState(),
    val phoneFamily: PhoneFamily = PhoneFamily.OTHER,
    /** Report the AI (ReportUi), open while non-null. */
    val aiReport: AiReportState? = null,
    /** The app is starting the helper or waiting for it to answer (MainActivity.bindHelper). */
    val connecting: Boolean = false,
    /** The helper has reported its permissions and grants (SetupStatus) since it connected. */
    val setupChecked: Boolean = true,
) {
    val setupComplete get() = setup.none { it.required && it.state != StepState.DONE }
    val stage get() = appStage(adb.paired, helperBound, connecting, adb.connectError, agent.loaded && setupChecked, setupComplete)
}

/** One published build (version.json, written by bridge/release.py). */
data class Release(val versionCode: Int, val versionName: String, val url: String, val sha256: String, val notes: String, val sizeBytes: Long)

/** What the app knows about updates: [available] is newer than this build; [status] is a check or install in progress
 * or its error. */
data class UpdateState(val available: Release? = null, val beta: Boolean = false, val checking: Boolean = false,
                       val installing: Boolean = false, val status: String? = null)

data class Diagnostics(val helperUid: String?, val audioMode: String, val rawStatus: String, val log: String)

interface UiActions {
    fun setStream(on: Boolean)
    fun selectAgent(profile: String, voice: String)
    fun saveKey(key: String)
    fun setupAction(action: StepAction)
    fun queryLeads(query: LeadQuery)
    fun uploadContacts()
    fun changeLeadImport(import: LeadImport)
    fun addLeads()
    fun closeLeadImport()
    fun startCalling()
    fun stopCalling()
    fun exportInterested()
    fun openLead(lead: Lead)
    fun saveLead(lead: Lead)
    fun deleteLead(lead: Lead, allForNumber: Boolean)
    fun deleteLeads(selection: LeadSelection, query: LeadQuery)
    fun resetLead(lead: Lead)
    fun loadTranscript(lead: Lead)
    fun closeEditor()
    fun closeTranscript()
    fun refreshStatus()
    fun identity()
    fun clearLog()
    fun importAgentFile()
    fun applyAgentFile()
    fun closeAgentFile()
    fun undoAgentChange()
    fun viewPrompt(profile: String)
    fun savePrompt(profile: String, prompt: String)
    fun closePrompt()
    fun checkForUpdates()
    fun installUpdate()
    fun setBetaUpdates(beta: Boolean)
    fun testAgent()
    fun sendReport()
    /** Opens Report the AI: about [lead]'s call (the transcript on screen) or, with null, the agent in general. */
    fun reportAi(lead: Lead?)
    fun sendAiReport(reason: AiReportReason, note: String, withTranscript: Boolean)
    fun closeAiReport()
    fun closeSetupGuide()
    fun openGuideLink(link: GuideLink)
}
