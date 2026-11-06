package dev.callstream.probe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.callstream.probe.leads.LeadTime
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One profile in an agent file, against what the phone has now (shell/AgentFile.preview). */
data class FileChange(val name: String, val isNew: Boolean, val promptChars: Int, val prevPromptChars: Int, val promptChanged: Boolean,
                      val voice: String, val prevVoice: String, val otherChanges: List<String>)

data class AgentFilePreview(val client: String, val changes: List<FileChange>, val ignored: List<String>, val clientName: String = "",
                            /** The client the phone works for now, when this file switches it to another; else null. */
                            val switchFrom: String? = null, val removes: Int = 0, val firstSetup: Boolean = false, val setupChanged: Boolean = false) {
    companion object {
        fun parse(json: String): AgentFilePreview {
            val o = JSONObject(json)
            val p = o.getJSONArray("profiles")
            val ignored = o.optJSONArray("ignored")
            return AgentFilePreview(o.optString("client"), (0 until p.length()).map { p.getJSONObject(it) }.map { x ->
                val other = x.optJSONArray("other_changes")
                FileChange(x.getString("name"), x.optBoolean("new"), x.optInt("prompt_chars"), x.optInt("prev_prompt_chars"),
                    x.optBoolean("prompt_changed"), x.optString("voice"), x.optString("prev_voice"),
                    (0 until (other?.length() ?: 0)).map { other!!.getString(it) })
            }, (0 until (ignored?.length() ?: 0)).map { ignored!!.getString(it) }, o.optString("client_name"),
                o.optString("switch_from").takeIf { !o.isNull("switch_from") && it.isNotEmpty() }, o.optInt("removes"),
                o.optBoolean("first_setup"), o.optBoolean("setup_changed"))
        }
    }
}

/** An agent file the person opened: its text, and the helper's preview or the reason it can't be used. */
data class AgentImport(val text: String, val preview: AgentFilePreview? = null, val error: String? = null)

/** One profile's instructions as the phone has them (AgentConfig.prompt). */
data class PromptView(val profile: String, val prompt: String, val source: String, val at: String) {
    companion object {
        fun parse(json: String) = JSONObject(json).let {
            PromptView(it.getString("profile"), it.optString("prompt"), it.optString("source"), it.optString("at"))
        }
    }
}

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
/** "2026-10-22 14:02" → "22 Oct, 2:02 PM". */
fun shortTime(text: String): String = LeadTime.parse(text)?.let { WHEN.format(it) } ?: text

/** Where a profile's instructions came from, in words. */
fun changeLine(source: String, at: String): String = when (source) {
    "file" -> "Updated from a file · ${shortTime(at)}"
    "edit" -> "Edited on this phone · ${shortTime(at)}"
    else -> "From the agent file"
}

/** Settings a file can change besides the instructions and voice, in words a client can follow. */
private val SETTING_NAMES = mapOf(
    "vad_silence_ms" to "pause before replying", "vad_prefix_padding_ms" to "interruption sensitivity",
    "vad_start_sensitivity" to "how readily it hears the caller", "vad_end_sensitivity" to "when it decides the caller is done",
    "thinking_budget" to "thinking", "gemini_model" to "AI model", "temperature" to "creativity", "transcription_language" to "transcript language",
    "extract_model" to "lead sheet filling", "affective_dialog" to "emotional tone", "kickoff_text" to "how the call opens",
    "kickoff_mode" to "how the call opens", "turn_detection" to "turn taking", "activity_handling" to "interruptions",
    "turn_coverage" to "turn taking", "api_version" to "connection version",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentFileSheet(import: AgentImport, current: String, stage: AppStage, busy: Boolean, onApply: () -> Unit, onDismiss: () -> Unit,
                   onGoToSetup: () -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        AgentFileContent(import, current, stage, busy, onApply, onDismiss, onGoToSetup)
    }
}

/** The sheet's content, apart so it can be rendered on its own (ScreenShots). */
@Composable
fun AgentFileContent(import: AgentImport, current: String, stage: AppStage, busy: Boolean, onApply: () -> Unit, onDismiss: () -> Unit,
                     onGoToSetup: () -> Unit = {}) {
    val helperBound = stage.helperReady || stage == AppStage.LOADING
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val error = import.error
        val preview = import.preview
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(if (error != null) Icons.Outlined.ErrorOutline else Icons.Outlined.SystemUpdateAlt, if (error != null) Tone.DANGER else Tone.ACCENT, 48.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (error != null) "Can't use this file" else "Update your agent", style = MaterialTheme.typography.headlineSmall)
                Text(if (error != null) "Nothing on this phone was changed." else "Check what changes, then apply. You can undo it afterwards.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            error != null -> {
                Text(error, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Close") }
            }
            // The file is read by the helper: with none coming, say what to do instead of waiting.
            preview == null && !helperBound && !stage.waiting -> {
                Text("Finish setting up this phone first: Call Stream reads the file once it's connected. Then open the file again.",
                    style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onGoToSetup, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Go to setup") }
                TextButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Not now") }
            }
            preview == null -> Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Text(if (helperBound) "Reading the file…" else "Waiting for this phone to connect…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val who = preview.clientName.ifEmpty { profileTitle(preview.client) }
                when {
                    preview.switchFrom != null -> SetupNote(Tone.BUSY, "Switches this phone from ${preview.switchFrom} to $who",
                        "Its ${preview.removes} current ${if (preview.removes == 1) "agent is" else "agents are"} replaced. The lead sheet keeps its rows.")
                    preview.firstSetup -> SetupNote(Tone.LIVE, "Sets this phone up for $who", "Their agents and lead sheet columns.")
                    preview.setupChanged -> SetupNote(Tone.ACCENT, "Updates $who's lead sheet setup", "Columns, the interested rule or extraction.")
                }
                preview.changes.forEach { ChangeCard(it, it.name == current) }
                if (preview.ignored.isNotEmpty()) Text("This app version skips some settings in the file: ${preview.ignored.joinToString()}. Update the app to use them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Applies from the next call. A call in progress isn't affected.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onApply, enabled = !busy && helperBound, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Apply update") }
                TextButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun SetupNote(tone: Tone, title: String, body: String) {
    val (bg, fg) = toneColors(tone)
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = fg)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ChangeCard(change: FileChange, inUse: Boolean) {
    AppCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(profileTitle(change.name), 40.dp, Brand.colors.signal)
            Text(profileTitle(change.name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (inUse) Pill("In use", Tone.LIVE, dot = true)
            Pill(if (change.isNew) "New" else "Update", Tone.ACCENT)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fun fmt(n: Int) = "%,d".format(n)
            FactLine("Instructions", when {
                change.isNew -> "${fmt(change.promptChars)} characters"
                change.promptChanged -> "updated · ${fmt(change.promptChars)} characters (was ${fmt(change.prevPromptChars)})"
                else -> "unchanged"
            }, highlight = change.isNew || change.promptChanged)
            FactLine("Voice", if (!change.isNew && change.prevVoice.isNotEmpty() && change.prevVoice != change.voice) "${change.prevVoice} → ${change.voice}"
                else change.voice.ifEmpty { "default" }, highlight = !change.isNew && change.prevVoice != change.voice)
            if (change.otherChanges.isNotEmpty()) FactLine("Also changes",
                change.otherChanges.map { SETTING_NAMES[it] ?: it.replace('_', ' ') }.distinct().joinToString(), highlight = true)
        }
    }
}

@Composable
private fun FactLine(label: String, value: String, highlight: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.padding(top = 1.dp).size(width = 96.dp, height = 20.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (highlight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A profile's instructions: read-only first; editing is one more tap behind a warning. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptSheet(view: PromptView, busy: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var editing by remember(view.profile) { mutableStateOf(false) }
    var confirmEdit by remember { mutableStateOf(false) }
    var draft by remember(view.profile, view.prompt) { mutableStateOf(view.prompt) }
    ModalBottomSheet(onDismissRequest = { if (!editing) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(Icons.Outlined.Description, Tone.ACCENT, 44.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${profileTitle(view.profile)}'s instructions", style = MaterialTheme.typography.titleLarge)
                    Text("${changeLine(view.source, view.at)} · ${"%,d".format((if (editing) draft else view.prompt).length)} characters",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (editing) {
                Text("Changes apply from the next call. Keep the structure; small fixes like a phone number or a price are safest.",
                    style = MaterialTheme.typography.bodySmall, color = Brand.colors.busy)
                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp), enabled = !busy,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp), shape = MaterialTheme.shapes.medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(draft); editing = false }, enabled = !busy && draft.isNotBlank() && draft != view.prompt) { Text("Save") }
                    TextButton(onClick = { draft = view.prompt; editing = false }) { Text("Cancel") }
                }
            } else {
                SelectionContainer {
                    Text(view.prompt, Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).verticalScroll(rememberScrollState()).padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                }
                TextButton(onClick = { confirmEdit = true }, enabled = !busy) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Text("  Edit")
                }
            }
        }
    }
    if (confirmEdit) AlertDialog(
        onDismissRequest = { confirmEdit = false },
        title = { Text("Edit the instructions?") },
        text = { Text("These tell your agent what to say on every call. Only change them if Call Stream support asked you to. You can undo the change afterwards.") },
        confirmButton = { TextButton(onClick = { confirmEdit = false; editing = true }) { Text("Edit") } },
        dismissButton = { TextButton(onClick = { confirmEdit = false }) { Text("Cancel") } },
    )
}

/** The Agent tab's last card: closed by default, so everyday operators never need to open it. */
@Composable
fun AdvancedCard(options: AgentOptions, busy: Boolean, onViewPrompt: () -> Unit, onImport: () -> Unit, onUndo: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }
    AppCard(padding = 8.dp) {
        ListItemRow("Advanced", "Instructions, agent files and undo", Modifier.padding(horizontal = 8.dp), onClick = { open = !open },
            leading = { IconBadge(Icons.Outlined.Tune, Tone.IDLE) },
            trailing = { Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
        AnimatedVisibility(open, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                ListItemRow("View instructions", "What ${profileTitle(options.profile)} is told to say and do", onClick = onViewPrompt,
                    leading = { IconBadge(Icons.Outlined.Description, Tone.ACCENT) })
                ListItemRow("Import agent file", "Apply a .callstream file from Call Stream support", onClick = if (busy) null else onImport,
                    leading = { IconBadge(Icons.Outlined.FileOpen, Tone.ACCENT) })
                options.undo?.let { (what, at) ->
                    ListItemRow("Undo last change", "$what · ${shortTime(at)}", onClick = if (busy) null else ({ confirmUndo = true }),
                        leading = { IconBadge(Icons.AutoMirrored.Outlined.Undo, Tone.BUSY) })
                }
            }
        }
    }
    if (confirmUndo) AlertDialog(
        onDismissRequest = { confirmUndo = false },
        title = { Text("Undo the last change?") },
        text = { Text("${options.undo?.first.orEmpty()}. Your agent goes back to how it was before.") },
        confirmButton = { TextButton(onClick = { confirmUndo = false; onUndo() }) { Text("Undo") } },
        dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Cancel") } },
    )
}
