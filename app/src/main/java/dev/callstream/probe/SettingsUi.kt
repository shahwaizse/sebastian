package dev.callstream.probe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(state: UiState, actions: UiActions, onGate: (String) -> Unit = {}) {
    val stage = state.stage
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Settings")
        // While something is missing, the setup list comes first: Home's "Continue setup" lands here.
        if (stage.needsSetup) { SectionTitle("Setup"); SetupSummaryCard(state, actions) }
        SectionTitle("Gemini API key")
        KeyCard(state.agent, stage, state.busy, state.keyMessage, actions::saveKey)
        if (!stage.needsSetup) { SectionTitle("Setup"); SetupSummaryCard(state, actions) }
        SectionTitle("This phone")
        PhoneCard(state)
        SectionTitle("Support")
        SupportCard(state.support, state.helperBound && !state.busy, onTest = { if (stage == AppStage.READY) actions.testAgent() else if (stage.needsSetup) onGate("test your agent") },
            onReport = actions::sendReport, onReportAi = { actions.reportAi(null) },
            offline = if (state.helperBound) null else if (stage.waiting) "Connecting to this phone…" else "Needs this phone connected: finish setup above.")
        SectionTitle("App")
        if (BuildConfig.SELF_UPDATE) AppVersionCard(state.version, state.updates, viaHelper = state.helperBound, enabled = !state.busy,
            onCheck = actions::checkForUpdates, onInstall = actions::installUpdate, onBeta = actions::setBetaUpdates)
        else PlayVersionCard(state.version)
        DiagnosticsCard(state, actions)
        if (state.spec.title.isNotEmpty()) Text("Set up for ${state.spec.title}", Modifier.fillMaxWidth().padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
    }
}

/** The Gemini key: the helper checks it with Google before saving and never sends it back, so the app only knows
 * where the key in use came from and its last 4 characters. [onSave] gets "" to remove it. */
@Composable
private fun KeyCard(options: AgentOptions, stage: AppStage, working: Boolean, message: Notice?, onSave: (String) -> Unit) {
    var entered by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    // The helper checks and keeps the key: until it answers there's nothing to type into.
    val busy = working || !stage.helperReady
    val hasKey = options.keySource == "app" || options.keySource == "provisioned"
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.Key, when { !stage.helperReady -> Tone.IDLE; hasKey -> Tone.LIVE; else -> Tone.DANGER })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(keyTitle(options, stage), style = MaterialTheme.typography.titleMedium)
                Text("Your agent uses it to talk. Get one free at aistudio.google.com → Get API key.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (stage.helperReady) OutlinedTextField(value = entered, onValueChange = { entered = it.trim() }, enabled = !busy, singleLine = true,
            label = { Text(if (options.keySource == "none") "Paste your key" else "Replace with a new key") },
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { shown = !shown }) {
                    Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (shown) "Hide key" else "Show key")
                }
            },
            isError = message?.error == true,
            supportingText = message?.let { { Text(it.text) } },
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
        if (stage.helperReady) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSave(entered); entered = "" }, enabled = !busy && entered.isNotEmpty()) { Text("Check and save") }
            if (options.keySource == "app") TextButton(onClick = { onSave("") }, enabled = !busy) { Text("Remove") }
        }
    }
}

@Composable
private fun SetupSummaryCard(state: UiState, actions: UiActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (state.stage.needsSetup) { AppCard(padding = 16.dp) { SetupList(state.setup, state.busy, actions::setupAction) }; return }
    // Connecting: the list would show steps "to do" that are only not checked yet.
    val checking = state.stage.waiting
    AppCard(padding = 8.dp) {
        ListItemRow(if (checking) "Checking setup…" else "Setup complete",
            if (checking) "Connecting to this phone" else "Everything this phone needs is in place", Modifier.padding(horizontal = 8.dp),
            onClick = if (checking) null else ({ open = !open }),
            leading = { if (checking) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp) }
                else IconBadge(Icons.Outlined.TaskAlt, Tone.LIVE) },
            trailing = { if (!checking) Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
        AnimatedVisibility(open && !checking, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(horizontal = 8.dp)) { SetupList(state.setup, state.busy, actions::setupAction) }
        }
    }
}

@Composable
private fun PhoneCard(state: UiState) {
    AppCard(padding = 8.dp) {
        Column {
            ListItemRow("Wireless debugging", if (state.adb.paired) "Call Stream is paired" else "Not paired yet",
                Modifier.padding(horizontal = 8.dp), leading = { IconBadge(Icons.Outlined.Hub, Tone.IDLE) },
                trailing = { Pill(if (state.adb.paired) "Paired" else "Not paired", if (state.adb.paired) Tone.LIVE else Tone.IDLE, dot = true) })
            val (helper, tone) = when {
                state.helperBound -> "Connected" to Tone.LIVE
                state.stage.waiting -> "Connecting" to Tone.BUSY
                state.stage == AppStage.OFFLINE -> "Offline" to Tone.DANGER
                else -> "Not started" to Tone.IDLE
            }
            ListItemRow("Phone helper", if (state.helperBound) state.diagnostics.helperUid.orEmpty().ifEmpty { "Connected" } else state.adb.connectError ?: "Not connected",
                Modifier.padding(horizontal = 8.dp), leading = { IconBadge(Icons.Outlined.PhoneAndroid, Tone.IDLE) },
                trailing = { Pill(helper, tone, dot = true) })
            ListItemRow("Audio", state.diagnostics.audioMode.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                Modifier.padding(horizontal = 8.dp), leading = { IconBadge(Icons.Outlined.GraphicEq, Tone.IDLE) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticsCard(state: UiState, actions: UiActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    AppCard(padding = 8.dp) {
        ListItemRow("Diagnostics", "Status, helper identity and the log", Modifier.padding(horizontal = 8.dp), onClick = { open = !open },
            leading = { IconBadge(Icons.Outlined.BugReport, Tone.IDLE) },
            trailing = { Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
        AnimatedVisibility(open, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = actions::refreshStatus, enabled = state.helperBound && !state.busy) { Text("Refresh") }
                    FilledTonalButton(onClick = actions::identity, enabled = state.helperBound && !state.busy) { Text("Identity") }
                    TextButton(onClick = actions::clearLog, enabled = !state.busy) { Text("Clear log") }
                }
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(state.diagnostics.rawStatus, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                        Text(state.diagnostics.log, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
