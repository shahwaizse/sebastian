package dev.callstream.probe

import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.callstream.probe.leads.Lead
import dev.callstream.probe.leads.LeadRules
import dev.callstream.probe.leads.LeadTime
import dev.callstream.probe.adb.LocalAdb

class MainActivity : ComponentActivity() {

    private var adb by mutableStateOf(AdbState())
    /** Why the last helper start failed; shown under Connect in the checklist. */
    private var connectError by mutableStateOf<String?>(null)
    private var helperBound by mutableStateOf(false)
    private var helperUid by mutableStateOf<String?>(null)
    private var audioMode by mutableStateOf("—")
    private var logText by mutableStateOf(INTRO)
    private var busy by mutableStateOf(false)
    private var streamStatus by mutableStateOf("UNKNOWN — connecting to phone helper")
    private var controlError by mutableStateOf<String?>(null)
    private var outbound by mutableStateOf(OutboundState.IDLE)
    private var notice by mutableStateOf<Notice?>(null)
    private var leadsPage by mutableStateOf(LeadsPage.EMPTY)
    private var leadQuery by mutableStateOf(LeadQuery())
    private var editing by mutableStateOf<Lead?>(null)
    private var editError by mutableStateOf<String?>(null)
    private var transcript by mutableStateOf<Pair<Lead, List<Pair<String, String>>>?>(null)
    private var agentOptions by mutableStateOf(AgentOptions.NONE)
    private var keyMessage by mutableStateOf<Notice?>(null)
    private var setupStatus by mutableStateOf<SetupStatus?>(null)
    private var agentImport by mutableStateOf<AgentImport?>(null)
    private var leadImport by mutableStateOf<LeadImport?>(null)
    private var promptView by mutableStateOf<PromptView?>(null)
    private val prefs by lazy { getSharedPreferences("callstream", MODE_PRIVATE) }
    private var updates by mutableStateOf(UpdateState())
    private var lastUpdateCheck = 0L
    private var setupEverComplete by mutableStateOf(false)
    private var support by mutableStateOf(SupportState())
    private var showSetupGuide by mutableStateOf(false)
    private var aiReport by mutableStateOf<AiReportState?>(null)
    private val family = phoneFamily(android.os.Build.MANUFACTURER, android.os.Build.BRAND)
    /** Xiaomi's "USB debugging (Security settings)" as this app can read it (before the helper runs); null: not Xiaomi or unreadable. */
    private var xiaomiAdbInput by mutableStateOf<Boolean?>(null)
    /** Starting or reaching the helper (bindHelper); the screens show progress meanwhile. */
    private var binding by mutableStateOf(false)
    private var polling = false
    private val handler = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            val svc = probe
            if (svc != null && !polling) {
                polling = true
                Thread {
                    val result = runCatching { svc.callStreamStatus() }.getOrElse { "UNKNOWN — helper unavailable" }
                    val run = runCatching { OutboundState.parse(svc.outboundStatus()) }.getOrDefault(OutboundState.IDLE)
                    // A failed read keeps what was shown: the agent doesn't vanish for one missed tick.
                    val agents = runCatching { AgentOptions.parse(svc.agentOptions()) }.getOrNull()?.takeIf { it.loaded }
                    runOnUiThread {
                        streamStatus = result; outbound = run; agents?.let { agentOptions = it }; polling = false; tickMode()
                        if (run.version != leadsPage.version) refreshLeads()
                    }
                }.start()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private var probe: IProbeService? = null

    private val pickCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::loadContacts) }
    private val pickAgentFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::readAgentFile) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { WakeService.start(this) } // the "Hey Sebastian" listener needs the microphone permission first

    /** Pairing posts its code box as a notification, so it needs that permission first (the helper grants it later). */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPairing() else notify("Allow Sebastian's notifications: the pairing code is typed into one.", error = true)
    }

    /** Runs on the attaching binder thread; the helper may also re-attach after a restart. */
    private fun onHelperAttached(service: IBinder) {
        val stub = IProbeService.Stub.asInterface(service)
        val linked = runCatching {
            service.linkToDeath({
                runOnUiThread {
                    probe = null
                    helperBound = false
                    binding = false
                    setupStatus = null
                    streamStatus = "UNKNOWN — helper disconnected"
                    helperUid = null
                    append("Helper disconnected.")
                    // Start it again while the screen is open (needs Wi-Fi); the Wi-Fi watch covers the rest.
                    handler.postDelayed({ if (!helperBound) bindHelper() }, 2_000)
                }
            }, 0)
        }.isSuccess
        if (!linked) { // already dead: not connected, try again
            runOnUiThread { binding = false; append("Helper died while connecting."); handler.postDelayed({ if (!helperBound) bindHelper() }, 2_000) }
            return
        }
        val id = runCatching { stub.identity() }.getOrElse { "identity failed: ${it.message}" }
        runOnUiThread {
            probe = stub
            binding = false
            helperBound = true
            connectError = null
            refreshAdb()
            helperUid = id.lineSequence().firstOrNull().orEmpty()
            append("Helper bound.\n$id")
            checkSetup()
            if (agentImport?.let { it.preview == null && it.error == null } == true) previewAgentFile() // opened before the helper was up
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HelperConnector.onAttached = ::onHelperAttached
        refreshAdb()
        requestAppPerms()
        // Sebastian: start listening for "Hey Sebastian" (a foreground service has to start from the foreground).
        WakeService.start(this)
        tickMode()
        if (savedInstanceState == null) handleIntent(intent)

        updates = updates.copy(beta = prefs.getBoolean("beta_updates", false))
        setupEverComplete = prefs.getBoolean("setup_complete_once", false)
        enableEdgeToEdge()
        setContent {
            CallStreamTheme {
                CallStreamUi(uiState(), actions, notice, onNoticeShown = { notice = null })
            }
        }
    }

    private fun uiState(): UiState {
        // A Reconnect in progress replaces the last failure's message.
        val shownAdb = adb.copy(connectError = connectError.takeIf { !binding })
        return UiState(
            spec = agentOptions.client,
            version = BuildConfig.VERSION_NAME,
            helperBound = helperBound,
            stream = StreamView.parse(streamStatus),
            controlError = controlError,
            busy = busy,
            agent = agentOptions,
            setup = setupSteps(shownAdb, helperBound, setupStatus, family, xiaomiAdbInput),
            leads = leadsPage,
            leadQuery = leadQuery,
            outbound = outbound,
            canCall = helperBound && streamStatus.startsWith("enabled=true") && streamStatus.contains("outbound=available"),
            keyMessage = keyMessage,
            editing = editing,
            editError = editError,
            transcript = transcript,
            diagnostics = Diagnostics(helperUid, audioMode, streamStatus, logText),
            agentImport = agentImport,
            leadImport = leadImport,
            promptView = promptView,
            updates = updates,
            setupEverComplete = setupEverComplete,
            support = support,
            showSetupGuide = showSetupGuide,
            adb = shownAdb,
            phoneFamily = family,
            aiReport = aiReport,
            connecting = binding,
            setupChecked = setupStatus != null,
        ).also { if (it.setupComplete && !setupEverComplete) { setupEverComplete = true; prefs.edit().putBoolean("setup_complete_once", true).apply() } }
    }

    private val actions = object : UiActions {
        override fun setStream(on: Boolean) = runRemote(if (on) "Sebastian on" else "Sebastian off") { it.setCallStreamEnabled(on) }
        override fun selectAgent(profile: String, voice: String) = this@MainActivity.selectAgent(profile, voice)
        override fun saveKey(key: String) = this@MainActivity.saveKey(key)
        override fun setupAction(action: StepAction) = when (action) {
            StepAction.SETUP_GUIDE -> showSetupGuide = true
            StepAction.OPEN_ABOUT_PHONE -> openGuideLink(GuideLink.ABOUT_PHONE)
            StepAction.OPEN_USB_DEBUGGING -> openGuideLink(GuideLink.USB_DEBUGGING)
            StepAction.OPEN_WIRELESS_DEBUGGING -> openGuideLink(GuideLink.WIRELESS_DEBUGGING)
            StepAction.PAIR -> startPairing()
            StepAction.RECONNECT -> bindHelper()
            StepAction.CHECK_AGAIN -> checkSetup()
        }
        override fun queryLeads(query: LeadQuery) { leadQuery = query; refreshLeads() }
        override fun uploadContacts() = pickCsv.launch(arrayOf("*/*")) // some file managers label CSV as text or binary; the parser decides
        override fun changeLeadImport(import: LeadImport) { leadImport = import }
        override fun addLeads() = this@MainActivity.addLeads()
        override fun closeLeadImport() { leadImport = null }
        override fun startCalling() {
            if (!uiState().canCall) { notify("Turn the agent on from Home to start calling."); return }
            runOutbound { it.startOutbound("[]") }
        }
        override fun stopCalling() = runOutbound { it.stopOutbound() }
        override fun exportInterested() = this@MainActivity.exportInterested()
        override fun openLead(lead: Lead) { editError = null; editing = lead }
        override fun saveLead(lead: Lead) = this@MainActivity.saveLead(lead)
        override fun deleteLead(lead: Lead, allForNumber: Boolean) = this@MainActivity.deleteLead(lead, allForNumber)
        override fun deleteLeads(selection: LeadSelection, query: LeadQuery) = this@MainActivity.deleteLeads(selection, query)
        override fun resetLead(lead: Lead) = this@MainActivity.resetLead(lead)
        override fun loadTranscript(lead: Lead) = this@MainActivity.loadTranscript(lead)
        override fun closeEditor() { editing = null }
        override fun closeTranscript() { transcript = null }
        override fun refreshStatus() = runRemote("Sebastian status") { it.callStreamStatus() }
        override fun identity() = runRemote("identity") { it.identity() }
        override fun clearLog() { logText = "" }
        override fun importAgentFile() = pickAgentFile.launch(arrayOf("*/*")) // .callstream has no registered type
        override fun applyAgentFile() = this@MainActivity.applyAgentFile()
        override fun closeAgentFile() { agentImport = null }
        override fun undoAgentChange() = agentCall({ it.agentUndo() }) { notify("Undone. Your agent is back to how it was.") }
        override fun viewPrompt(profile: String) {
            val svc = probe ?: run { notify("Helper is not connected", error = true); return }
            Thread {
                val r = runCatching { PromptView.parse(svc.agentPrompt(profile)) }
                runOnUiThread { r.onSuccess { promptView = it }.onFailure { notify(it.message ?: "Couldn't read the instructions", error = true) } }
            }.start()
        }
        override fun savePrompt(profile: String, prompt: String) =
            agentCall({ it.agentEditPrompt(profile, prompt) }) { viewPrompt(profile); notify("Saved. Applies from the next call.", undo = true) }
        override fun closePrompt() { promptView = null }
        override fun checkForUpdates() = this@MainActivity.checkForUpdates(quiet = false)
        override fun installUpdate() = this@MainActivity.installUpdate()
        override fun closeSetupGuide() { showSetupGuide = false }
        override fun openGuideLink(link: GuideLink) = this@MainActivity.openGuideLink(link)
        override fun testAgent() = this@MainActivity.testAgent()
        override fun sendReport() = this@MainActivity.sendReport()
        override fun reportAi(lead: Lead?) {
            aiReport = AiReportState(lead, if (lead != null) transcript?.takeIf { it.first.id == lead.id }?.second.orEmpty() else emptyList())
        }
        override fun sendAiReport(reason: AiReportReason, note: String, withTranscript: Boolean) =
            this@MainActivity.sendAiReport(reason, note, withTranscript)
        override fun closeAiReport() { aiReport = null }
        override fun setBetaUpdates(beta: Boolean) {
            prefs.edit().putBoolean("beta_updates", beta).apply()
            updates = updates.copy(beta = beta, available = null)
            this@MainActivity.checkForUpdates(quiet = true)
        }
    }

    /** A system property, read the way apps can: the platform's reader, else `getprop`. Null if neither answers. */
    private fun readProperty(name: String): String? =
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String }.getOrNull()
            ?: runCatching { ProcessBuilder("getprop", name).start().inputStream.bufferedReader().use { it.readText().trim() } }.getOrNull()

    /** What the app can see of Developer options and its pairing (AdbState). */
    private fun refreshAdb() {
        fun global(name: String) = switchReading(android.provider.Settings.Global.getInt(contentResolver, name, 0), android.os.Build.VERSION.SDK_INT)
        adb = AdbState(developerOptions = global(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
            usbDebugging = global(android.provider.Settings.Global.ADB_ENABLED), wirelessDebugging = LocalAdb.wirelessDebuggingOn(this),
            paired = LocalAdb.isPaired(this), onWifi = LocalAdb.onWifi(this), canWriteSettings = LocalAdb.canWriteSettings(this))
    }

    /** Posts the code box (PairingService) and opens Developer options, where the pairing code is. */
    private fun startPairing() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS); return
        }
        if (!LocalAdb.onWifi(this)) { notify("Connect to Wi-Fi first: Wireless debugging only works on Wi-Fi.", error = true); return }
        if (!PairingService.prompt(this)) { notify("Allow Sebastian's notifications: the pairing code is typed into one.", error = true); return }
        append("Pairing: waiting for the code in the notification.")
        openGuideLink(GuideLink.WIRELESS_DEBUGGING)
    }

    /** The guide's buttons. Developer options can't open while they're off: About phone (to turn them on) instead. */
    private fun openGuideLink(link: GuideLink) {
        // Scrolled to the step's row, highlighted, where the phone's Settings knows the key (SettingKeys).
        val key = SettingKeys.forLink(link, family)
        fun settings(action: String) = android.content.Intent(action).apply {
            if (key != null) {
                putExtra(":settings:fragment_args_key", key)
                putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
            }
        }
        val intents = when (link) {
            GuideLink.ABOUT_PHONE -> listOf(settings(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS))
            GuideLink.WIRELESS_DEBUGGING, GuideLink.USB_DEBUGGING, GuideLink.DEVELOPER_OPTIONS ->
                listOf(settings(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                    android.content.Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS))
        } + android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
        val opened = intents.any { runCatching { startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
        if (!opened) notify("Couldn't open that screen. Open Settings yourself.", error = true)
    }

    /** The helper runs the preflight and waits for it (~30 s); Sebastian comes back on by itself. */
    private fun testAgent() {
        val svc = probe ?: run { notify("Helper is not connected", error = true); return }
        if (support.testing) return
        support = support.copy(testing = true, testResult = null)
        Thread {
            val r = runCatching { org.json.JSONObject(svc.testAgent()) }
            runOnUiThread {
                support = support.copy(testing = false, testResult = r.fold(
                    { TestResult(it.optBoolean("pass"), it.optLong("reply_ms"), "The test didn't pass: ${it.optString("message")}") },
                    { TestResult(false, 0, it.message ?: "The test couldn't run.") }))
            }
        }.start()
    }

    /** The helper's report plus the app's side, zipped and handed to the share sheet (WhatsApp, email, …). */
    private fun sendReport() {
        val svc = probe ?: run { notify("Helper is not connected", error = true); return }
        if (support.reporting) return
        support = support.copy(reporting = true)
        val state = uiState()
        Thread {
            val r = runCatching {
                val report = org.json.JSONObject(svc.supportReport())
                val phone = report.getJSONObject("phone")
                val app = org.json.JSONObject().put("version", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
                    .put("beta_updates", state.updates.beta).put("update_available", state.updates.available?.versionName)
                    .put("client", state.spec.id).put("client_name", state.spec.title)
                    .put("profile", state.agent.profile).put("voice", state.agent.voice).put("key", state.agent.keySource)
                    .put("setup", org.json.JSONArray(state.setup.map { "${it.state}: ${it.title}${if (it.detail.isNotEmpty()) " (${it.detail})" else ""}" }))
                    .put("stream", state.stream.phase.name).put("created", LeadTime.SECOND.format(LeadTime.now()))
                val dir = java.io.File(cacheDir, "reports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(LeadTime.now())
                val model = phone.optString("model").replace(Regex("[^A-Za-z0-9]+"), "-")
                val file = java.io.File(dir, "callstream-report-$model-$stamp.zip")
                java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                    fun put(name: String, text: String) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                    put("app.json", app.toString(1))
                    put("phone.json", phone.toString(1))
                    val files = report.getJSONObject("files")
                    for (name in files.keys()) put(name, files.getString(name))
                }
                file
            }
            runOnUiThread {
                support = support.copy(reporting = false)
                r.onSuccess { file ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Sebastian report")
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(android.content.Intent.createChooser(send, "Send the report to Sebastian support"))
                }.onFailure { notify("Couldn't make the report: ${it.message}", error = true) }
            }
        }.start()
    }

    /** Report the AI (ReportUi): straight to our endpoint, so it works with the helper down too. */
    private fun sendAiReport(reason: AiReportReason, note: String, withTranscript: Boolean) {
        val report = aiReport ?: return
        if (report.sending) return
        aiReport = report.copy(sending = true, error = null)
        val state = uiState()
        val app = org.json.JSONObject().put("version", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
            .put("store", BuildConfig.FLAVOR).put("client", state.spec.id).put("profile", state.agent.profile).put("voice", state.agent.voice)
            .put("phone", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("created", LeadTime.SECOND.format(LeadTime.now()))
        Thread {
            val r = runCatching { AiReports.send(AiReports.payload(report, reason, note, withTranscript, app)) }
            runOnUiThread {
                r.onSuccess { aiReport = null; notify("Thanks. We'll review your report.") }
                    .onFailure { aiReport = aiReport?.copy(sending = false, error = it.message ?: "Couldn't send the report. Try again.") }
            }
        }.start()
    }

    /** Looks for a newer build ([quiet]: on app open, errors stay silent). The Google Play build never looks: Play updates it. */
    private fun checkForUpdates(quiet: Boolean) {
        if (!BuildConfig.SELF_UPDATE || updates.checking || updates.installing) return
        lastUpdateCheck = System.currentTimeMillis()
        updates = updates.copy(checking = true, status = null)
        val beta = updates.beta
        Thread {
            val r = runCatching { AppUpdates.check(beta, BuildConfig.VERSION_CODE) }
            runOnUiThread {
                updates = r.fold({ updates.copy(checking = false, available = it, status = null) },
                    { updates.copy(checking = false, status = if (quiet) null else "Couldn't check: ${it.message}") })
            }
        }.start()
    }

    /** The helper installs it and reopens the app; this call only returns if something went wrong. */
    /**
     * The helper installs it without a prompt and reopens the app (this call then never returns). With no helper, or when
     * it can't install, Android's own installer takes over: updating never waits on setup, which may be what needs fixing.
     */
    private fun installUpdate() {
        val release = updates.available ?: return
        if (!BuildConfig.SELF_UPDATE || updates.installing) return
        val svc = probe
        updates = updates.copy(installing = true, status = null)
        Thread {
            val helper = svc?.let { runCatching { it.installUpdate(release.url, release.sha256) } }
            if (helper?.getOrNull() == "Installed") return@Thread // the app is being replaced and reopened
            val helperError = helper?.let { it.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName } ?: it.getOrNull() }
            // Errors the installer would hit too: say them, don't retry another way.
            if (helperError != null && ("call is in progress" in helperError || "different key" in helperError)) {
                runOnUiThread { updates = updates.copy(installing = false, status = helperError); notify(helperError, error = true) }
                return@Thread
            }
            val installer = runCatching { AppUpdates.installerIntent(this, release) }
            runOnUiThread {
                installer.onSuccess { intent ->
                    startActivity(intent)
                    updates = updates.copy(installing = false, status = "Finish in Android's installer: tap Install.")
                }.onFailure {
                    val error = it.message ?: "Couldn't download the update."
                    updates = updates.copy(installing = false, status = error); notify(error, error = true)
                }
            }
        }.start()
    }

    /** An agent change through the helper; the new options come back. [undo]-able notices offer Undo right away. */
    private fun agentCall(call: (IProbeService) -> String, done: () -> Unit) {
        val svc = probe ?: run { notify("Helper is not connected", error = true); return }
        if (busy) return
        busy = true
        Thread {
            val result = runCatching { AgentOptions.parse(call(svc)) }
            runOnUiThread {
                busy = false
                result.onSuccess { agentOptions = it; done() }.onFailure { notify(it.message ?: it.javaClass.simpleName, error = true) }
            }
        }.start()
    }

    private fun notify(text: String, error: Boolean = false, undo: Boolean) {
        notice = if (undo) Notice(text, error, "Undo", { actions.undoAgentChange() }) else Notice(text, error)
    }

    /** A file opened from WhatsApp, email or a file manager (manifest): an agent file (.callstream) or a lead list. */
    private fun handleIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        intent.action = null // a rotation or return to the app shouldn't open it again
        openFile(uri)
    }

    /** Chat apps send any file as a generic document, so the contents decide: agent files are JSON, the rest is leads. */
    private fun openFile(uri: Uri) {
        Thread {
            val bytes = runCatching { readBytes(uri, MAX_CSV_BYTES) }
            val isAgentFile = bytes.getOrNull()?.let { b -> !LeadFile.isXlsx(b) && String(b, 0, minOf(b.size, 64), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{") } == true
            runOnUiThread { if (isAgentFile) readAgentFile(uri) else loadContacts(uri) }
        }.start()
    }

    private fun readBytes(uri: Uri, max: Int): ByteArray = contentResolver.openInputStream(uri)!!.use { input ->
        val bytes = input.readNBytes(max + 1)
        check(bytes.size <= max) { "That file is too large" }
        bytes
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Lead file"


    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun readAgentFile(uri: Uri) {
        Thread {
            val text = runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    val bytes = input.readNBytes(MAX_AGENT_FILE_BYTES + 1)
                    check(bytes.size <= MAX_AGENT_FILE_BYTES) { "That file is too large to be an agent file." }
                    String(bytes, Charsets.UTF_8)
                }
            }
            runOnUiThread {
                // A Sebastian setup file (settings only, no key) isn't a Sebastian agent file: ask for the key.
                if (text.getOrNull()?.let(::isSebastianSetup) == true) { askSebastianKey(text.getOrThrow()); return@runOnUiThread }
                text.onSuccess { agentImport = AgentImport(it); previewAgentFile() }
                    .onFailure { agentImport = AgentImport("", error = "Couldn't open that file: ${it.message}") }
            }
        }.start()
    }

    private fun isSebastianSetup(text: String) = text.trimStart().startsWith("{") && "\"sebastian_setup\"" in text

    /** Asks for the person's own AssemblyAI key, adds it to the setup file's settings and has the helper save them. */
    private fun askSebastianKey(setup: String) {
        val input = android.widget.EditText(this).apply {
            hint = "AssemblyAI API key"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Set up Sebastian")
            .setMessage("Paste your own AssemblyAI API key (assemblyai.com, Dashboard). It is checked with AssemblyAI, then saved only on this phone.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                val svc = probe
                if (key.length < 16) { notify("That doesn't look like an AssemblyAI key.", error = true); return@setPositiveButton }
                if (svc == null) { notify("Finish the phone setup first (the helper isn't running), then open the file again.", error = true); return@setPositiveButton }
                Thread {
                    val result = runCatching { svc.sebastianSetConfig(org.json.JSONObject(setup).put("assemblyai_api_key", key).toString()) }
                        .getOrElse { "ERROR — ${it.message}" }
                    runOnUiThread { notify(if (result.startsWith("OK")) "Sebastian is set up. Turn it ON." else result.removePrefix("ERROR — "), error = !result.startsWith("OK")) }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Waits for the helper when the file was opened before it connected (onHelperAttached retries). */
    private fun previewAgentFile() {
        val svc = probe ?: return
        val pending = agentImport ?: return
        Thread {
            val r = runCatching { AgentFilePreview.parse(svc.agentFilePreview(pending.text)) }
            runOnUiThread {
                if (agentImport?.text != pending.text) return@runOnUiThread
                agentImport = r.fold({ pending.copy(preview = it) }, { pending.copy(error = it.message ?: "This file can't be used.") })
            }
        }.start()
    }

    private fun applyAgentFile() {
        val pending = agentImport ?: return
        agentCall({ it.agentFileApply(pending.text) }) {
            agentImport = null
            notify("Agent updated. Applies from the next call.", undo = true)
        }
    }

    private fun notify(text: String, error: Boolean = false) { notice = Notice(text, error) }

    override fun onResume() {
        super.onResume()
        refreshAdb()
        tickMode()
        if (!helperBound) bindHelper()
        if (helperBound) checkSetup() // the person may have changed something in Settings meanwhile
        if (family == PhoneFamily.XIAOMI) Thread { readProperty(dev.callstream.probe.shell.PhoneSetup.XIAOMI_ADB_INPUT)?.let { v ->
            runOnUiThread { xiaomiAdbInput = v == "1" } } }.start()
        if (System.currentTimeMillis() - lastUpdateCheck > 30 * 60_000L) checkForUpdates(quiet = true)
        handler.removeCallbacks(statusTick)
        handler.post(statusTick)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTick)
        super.onPause()
    }

    override fun onDestroy() {
        // The helper keeps running; only this screen stops listening for it.
        if (HelperConnector.onAttached == ::onHelperAttached) HelperConnector.onAttached = null
        super.onDestroy()
    }

    private fun bindHelper() {
        if (helperBound || binding) return
        binding = true
        append("Starting helper as shell…")
        Thread {
            // Attachment is reported through onHelperAttached; an already-attached helper too.
            runCatching { HelperConnector.obtain(this) }
                .onSuccess { if (!helperBound) onHelperAttached(it) }
                .onFailure { runOnUiThread {
                    binding = false
                    // Not paired yet is the normal state of a new phone, not an error to show under Connect. Other failures
                    // (the helper started but didn't answer) get words a person can act on; the details go to the log.
                    connectError = if (!LocalAdb.isPaired(this)) null else (it as? dev.callstream.probe.adb.AdbProblem)?.message
                        ?: "Sebastian didn't start on this phone. Tap Reconnect; if it keeps failing, restart the phone."
                    if ((it as? dev.callstream.probe.adb.AdbProblem)?.kind == dev.callstream.probe.adb.AdbProblem.Kind.NO_WIFI) HelperRevive.watchWifi(this)
                    refreshAdb()
                    append("helper start failed: ${it.message}")
                } }
        }.start()
    }

    private fun unbindHelper() {
        // Stops the shell helper process (and any AI call it owns).
        val service = probe
        Thread { runCatching { service?.destroy() } }.start()
        probe = null
        helperBound = false
        helperUid = null
        append("Helper unbound.")
    }

    private fun runRemote(label: String, block: (IProbeService) -> String) {
        val svc = probe
        if (svc == null) {
            append("Helper is not bound.")
            return
        }
        if (busy) return
        busy = true
        append("▸ $label")
        Thread {
            val result = try {
                block(svc)
            } catch (t: Throwable) {
                "FAILED $label\n${t.message}\n${t.stackTraceToString().take(1500)}"
            }
            runOnUiThread {
                busy = false
                append(result)
                if (result.startsWith("enabled=")) { streamStatus = result; controlError = null }
                else if (label.startsWith("Sebastian")) controlError = result.lineSequence().first()
                tickMode()
            }
        }.start()
    }

    /** Reads a lead file (Excel or CSV) and opens the preview; nothing is added until the person taps Add. */
    private fun loadContacts(uri: Uri) {
        Thread {
            val name = displayName(uri)
            val import = runCatching { LeadImport.of(name, LeadFile.read(readBytes(uri, MAX_CSV_BYTES))) }
                .getOrElse { LeadImport(name, error = "Couldn't read that file: ${it.message}") }
            runOnUiThread { leadImport = import }
        }.start()
    }

    private fun addLeads() {
        val pending = leadImport ?: return
        val upload = pending.upload ?: return
        if (busy) return
        busy = true
        leadImport = pending.copy(addError = null)
        Thread {
            // Into the sheet in parts: a binder call carries at most ~1 MB.
            val added = runCatching {
                val svc = probe ?: error("Helper is not connected")
                var totals = Triple(0, 0, 0)
                upload.contacts.chunked(IMPORT_CHUNK).forEach { part ->
                    val r = org.json.JSONObject(svc.leadsImport(OutboundContacts.toJson(part)))
                    totals = Triple(totals.first + r.optInt("added"), totals.second + r.optInt("duplicates"), totals.third + r.optInt("names_filled"))
                }
                totals
            }
            runOnUiThread {
                busy = false
                added.onSuccess { totals ->
                    leadImport = null
                    notify(buildString {
                        append("Added ${totals.first} new ${if (totals.first == 1) "lead" else "leads"}")
                        if (totals.second > 0) append("; ${totals.second} already in the sheet")
                        if (totals.third > 0) append(" (${totals.third} got a name)")
                    })
                    refreshLeads()
                }.onFailure { e -> leadImport = leadImport?.copy(addError = e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    /** Fetches the page for [leadQuery] (and re-fetches when the sheet changed). */
    private fun refreshLeads() {
        val svc = probe ?: return
        val query = leadQuery
        Thread {
            val page = runCatching { LeadsPage.parse(svc.leadsQuery(query.toJson())) }
            runOnUiThread {
                // A newer query was asked for meanwhile: its own fetch will land.
                if (query != leadQuery) return@runOnUiThread
                page.onSuccess { leadsPage = it }.onFailure { notify("Couldn't read the lead sheet: ${it.message}", error = true) }
            }
        }.start()
    }

    private fun saveLead(lead: Lead) {
        val svc = probe ?: run { editError = "Helper is not connected"; return }
        if (busy) return
        busy = true
        Thread {
            val result = runCatching { svc.leadSave(lead.toJson().toString()) }
            runOnUiThread {
                busy = false
                result.onSuccess { editing = null; notify(if (lead.id == 0L) "Added ${lead.name.ifEmpty { lead.number }}" else "Saved"); refreshLeads() }
                    .onFailure { editError = it.message ?: it.javaClass.simpleName }
            }
        }.start()
    }

    private fun deleteLead(lead: Lead, allForNumber: Boolean) {
        val svc = probe ?: run { editError = "Helper is not connected"; return }
        if (busy) return
        busy = true
        Thread {
            val result = runCatching { org.json.JSONObject(svc.leadDelete(lead.id, allForNumber)).optInt("deleted") }
            runOnUiThread {
                busy = false
                result.onSuccess { n -> editing = null; notify("Deleted $n ${if (n == 1) "entry" else "entries"}"); refreshLeads() }
                    .onFailure { editError = it.message ?: it.javaClass.simpleName }
            }
        }.start()
    }

    private fun deleteLeads(selection: LeadSelection, query: LeadQuery) {
        val svc = probe ?: run { notify("Helper is not connected", error = true); return }
        if (busy) return
        busy = true
        Thread {
            val result = runCatching { org.json.JSONObject(svc.leadsDelete(selection.toJson(query))) }
            runOnUiThread {
                busy = false
                result.onSuccess { r ->
                    val people = r.optInt("people"); val inCall = r.optInt("in_call")
                    notify(buildString {
                        append("Deleted $people ${if (people == 1) "lead" else "leads"}")
                        if (inCall > 0) append("; $inCall in a call ${if (inCall == 1) "was" else "were"} kept")
                    })
                    refreshLeads()
                }.onFailure { notify("Couldn't delete: ${it.message}", error = true) }
            }
        }.start()
    }

    private fun loadTranscript(lead: Lead) {
        val svc = probe ?: run { editError = "Helper is not connected"; return }
        Thread {
            val result = runCatching {
                val a = org.json.JSONArray(svc.leadTranscript(lead.id))
                (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("who") to o.optString("text") } }
            }
            runOnUiThread {
                result.onSuccess { editing = null; transcript = lead to it }.onFailure { editError = "Couldn't read the transcript: ${it.message}" }
            }
        }.start()
    }

    private fun resetLead(lead: Lead) {
        val svc = probe ?: run { editError = "Helper is not connected"; return }
        if (busy) return
        busy = true
        Thread {
            val result = runCatching { svc.leadReset(lead.id) }
            runOnUiThread {
                busy = false
                result.onSuccess { editing = null; notify("${lead.name.ifEmpty { lead.number }} will be called again"); refreshLeads() }
                    .onFailure { editError = it.message ?: it.javaClass.simpleName }
            }
        }.start()
    }

    /** Every interested person as a CSV, handed to the share sheet (WhatsApp, email, Drive…). */
    private fun exportInterested() {
        val svc = probe ?: run { notify("Helper is not connected", error = true); return }
        val spec = agentOptions.client
        val rule = spec.interested ?: return
        if (busy) return
        busy = true
        Thread {
            val result = runCatching {
                val rows = ArrayList<Pair<Lead, Int>>()
                while (true) {
                    val page = LeadsPage.parse(svc.leadsQuery(org.json.JSONObject().put("filter", "interested").put("offset", rows.size).put("limit", 500).toString()))
                    page.rows.forEach { rows += it to (page.calls[it.id] ?: 1) }
                    if (page.rows.isEmpty() || rows.size >= page.total) break
                }
                check(rows.isNotEmpty() && rows.all { rule(it.first) }) { "No interested leads to export" }
                val dir = java.io.File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(LeadTime.now())
                val file = java.io.File(dir, "${spec.id}-interested-$stamp.csv")
                file.writeText(LeadRules.exportCsv(rows, spec))
                file to rows.size
            }
            runOnUiThread {
                busy = false
                result.onSuccess { (file, count) ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/csv")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(android.content.Intent.createChooser(send, "Share $count interested ${if (count == 1) "lead" else "leads"}"))
                    notify("Exported $count interested ${if (count == 1) "lead" else "leads"}")
                }.onFailure { notify("Export failed: ${it.message}", error = true) }
            }
        }.start()
    }

    private fun selectAgent(profile: String, voice: String) {
        val svc = probe ?: run { notify("Helper is not connected", error = true); return }
        if (busy) return
        busy = true
        Thread {
            val result = runCatching { AgentOptions.parse(svc.selectAgent(profile, voice)) }
            runOnUiThread {
                busy = false
                result.onSuccess { agentOptions = it; notify("${profileTitle(it.profile)} with ${it.voice}. Applies from the next call.") }
                    .onFailure { notify(it.message ?: it.javaClass.simpleName, error = true) }
            }
        }.start()
    }

    /** The helper grants what it can and reports the rest (shell/PhoneSetup). */
    private fun checkSetup() {
        val svc = probe ?: return
        Thread {
            val status = runCatching { SetupStatus.parse(svc.setupStatus()) }.getOrNull()
            runOnUiThread { if (status != null) setupStatus = status }
        }.start()
    }

    private fun saveKey(key: String) {
        val svc = probe ?: run { keyMessage = Notice("Helper is not connected", error = true); return }
        if (busy) return
        busy = true
        keyMessage = Notice(if (key.isEmpty()) "Removing…" else "Checking the key with Google…")
        Thread {
            val result = runCatching { AgentOptions.parse(svc.setGeminiKey(key)) }
            runOnUiThread {
                busy = false
                result.onSuccess {
                    agentOptions = it
                    keyMessage = null
                    notify(when {
                        key.isNotEmpty() -> "Key works and is saved. Applies from the next call."
                        it.keySource == "provisioned" -> "Removed. Calls use the key set from the laptop again."
                        else -> "Removed. Calls won't connect until a key is saved."
                    })
                }.onFailure { keyMessage = Notice(it.message ?: it.javaClass.simpleName, error = true) }
            }
        }.start()
    }

    private fun runOutbound(block: (IProbeService) -> String) {
        val svc = probe ?: run { notify("Helper is not connected", error = true); return }
        if (busy) return
        busy = true
        Thread {
            val result = runCatching { block(svc) }
            runOnUiThread {
                busy = false
                result.onSuccess { outbound = OutboundState.parse(it); refreshLeads() }
                    .onFailure { notify(it.message ?: it.javaClass.simpleName, error = true) }
            }
        }.start()
    }

    private fun tickMode() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        audioMode = when (am.mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
            AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
            AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMMUNICATION_REDIRECT"
            else -> "MODE_${am.mode}"
        }
    }

    private fun requestAppPerms() {
        val need = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_PHONE_STATE,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
    }

    private fun append(msg: String) {
        logText = (logText + "\n\n" + msg.trim()).trim().takeLast(60_000)
    }

    companion object {
        private const val MAX_CSV_BYTES = 4 shl 20
        private const val MAX_AGENT_FILE_BYTES = 1 shl 20
        private const val IMPORT_CHUNK = 2_000
        private val INTRO = "Sebastian connects incoming carrier calls directly to a Gemini voice agent on this phone."
    }
}
