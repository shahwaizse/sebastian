package dev.callstream.probe

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class TabItem(val tab: Tab, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val TABS = listOf(
    TabItem(Tab.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    TabItem(Tab.LEADS, "Leads", Icons.Filled.Groups, Icons.Outlined.Groups),
    TabItem(Tab.AGENT, "Agent", Icons.Filled.SupportAgent, Icons.Outlined.SupportAgent),
    TabItem(Tab.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/** The app: four tabs, snackbars for short confirmations, and the lead sheets over everything. */
@Composable
fun CallStreamUi(state: UiState, actions: UiActions, notice: Notice?, onNoticeShown: () -> Unit, startTab: Tab = Tab.HOME) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    val snackbar = remember { SnackbarHostState() }
    var shownNotice by remember { mutableStateOf<Notice?>(null) }
    // What was tapped that setup doesn't allow yet ("turn the agent on"): the setup gate is open while non-null.
    var gate by remember { mutableStateOf<String?>(null) }
    val goToSetup = { tab = Tab.SETTINGS }
    val stage = state.stage
    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        shownNotice = n
        onNoticeShown()
        val result = snackbar.showSnackbar(n.text, actionLabel = n.actionLabel, withDismissAction = n.actionLabel == null,
            duration = if (n.error || n.actionLabel != null || n.text.length > 60) SnackbarDuration.Long else SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) n.onAction?.invoke()
    }
    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                val error = shownNotice?.error == true
                Snackbar(data, shape = MaterialTheme.shapes.medium, actionColor = MaterialTheme.colorScheme.inversePrimary,
                    containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
                    contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = androidx.compose.ui.unit.Dp.Hairline) {
                TABS.forEach { item ->
                    val selected = tab == item.tab
                    NavigationBarItem(selected = selected, onClick = { tab = item.tab }, label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        icon = {
                            BadgedBox(badge = {
                                if (item.tab == Tab.LEADS && state.outbound.running) Badge(containerColor = Brand.colors.live)
                                if (item.tab == Tab.SETTINGS && stage.needsSetup) Badge()
                            }) { Icon(if (selected) item.selected else item.unselected, null) }
                        })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            AnimatedContent(tab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "tab") { current ->
                when (current) {
                    Tab.HOME -> HomeScreen(state, actions,
                        onOpenLeads = { filter -> actions.queryLeads(state.leadQuery.copy(filter = filter, limit = LeadQuery.PAGE)); tab = Tab.LEADS },
                        onOpenAgent = { tab = Tab.AGENT }, onOpenSettings = goToSetup, onGate = { gate = it })
                    Tab.LEADS -> if (stage.helperReady) LeadsScreen(state, actions, onGate = { gate = it })
                        else Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                            ScreenHeader("Leads"); NeedsSetup(stage, state.setup, "see and call your leads", goToSetup)
                        }
                    Tab.AGENT -> if (stage.helperReady) AgentScreen(state.agent, state.busy, actions::selectAgent,
                        onViewPrompt = { actions.viewPrompt(state.agent.profile) }, onImport = actions::importAgentFile, onUndo = actions::undoAgentChange)
                        else Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                            ScreenHeader("Agent", "Who answers your calls. Changes apply from the next call.")
                            NeedsSetup(stage, state.setup, "choose who answers your calls", goToSetup)
                        }
                    Tab.SETTINGS -> SettingsScreen(state, actions, onGate = { gate = it })
                }
            }
        }
    }
    state.editing?.let { lead ->
        LeadEditorSheet(state.spec, lead, lead.id in state.leads.interested, state.busy, state.editError,
            onSave = actions::saveLead, onDelete = { all -> actions.deleteLead(lead, all) }, onReset = { actions.resetLead(lead) },
            onTranscript = { actions.loadTranscript(lead) }, onDismiss = actions::closeEditor)
    }
    state.transcript?.let { (lead, lines) -> TranscriptSheet(lead, lines, actions::closeTranscript, onReport = { actions.reportAi(lead) }) }
    state.aiReport?.let { AiReportDialog(it, actions::sendAiReport, actions::closeAiReport) }
    if (state.showSetupGuide) SetupGuideSheet(state.phoneFamily, actions::openGuideLink, actions::closeSetupGuide)
    // Files can arrive from WhatsApp before setup is done: the sheets say so instead of waiting for a helper that isn't coming.
    state.leadImport?.let { LeadImportSheet(it, state.busy, stage, actions::changeLeadImport, actions::addLeads, actions::closeLeadImport,
        onGoToSetup = { actions.closeLeadImport(); goToSetup() }) }
    state.agentImport?.let { AgentFileSheet(it, state.agent.profile, stage, state.busy, actions::applyAgentFile, actions::closeAgentFile,
        onGoToSetup = { actions.closeAgentFile(); goToSetup() }) }
    gate?.let { what -> SetupGateDialog(what, state.setup, stage, goToSetup, onDismiss = { gate = null }) }
    state.promptView?.let { view ->
        PromptSheet(view, state.busy, onSave = { actions.savePrompt(view.profile, it) }, onDismiss = actions::closePrompt)
    }
}
