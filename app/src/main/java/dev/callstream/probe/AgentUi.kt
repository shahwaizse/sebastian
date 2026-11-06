package dev.callstream.probe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.callstream.probe.leads.ClientSpec
import org.json.JSONObject

/** The helper's agent choices (see shell/AgentConfig.options). */
data class AgentOptions(val profiles: List<Pair<String, String>>, val profileVoices: Map<String, String>,
                        val voices: List<Triple<String, String, String>>, val profile: String, val voice: String, val voiceOverride: String,
                        val keySource: String = "", val keyHint: String = "",
                        /** Profiles changed on the phone: name → (source "file"/"edit", when). */
                        val changes: Map<String, Pair<String, String>> = emptyMap(),
                        /** The last change Undo would revert: (what, when). */
                        val undo: Pair<String, String>? = null,
                        /** Who this phone works for and their lead sheet setup (from the last agent file). */
                        val client: ClientSpec = ClientSpec.NONE,
                        /** False until the helper has answered: nothing here is known yet. */
                        val loaded: Boolean = true) {
    companion object {
        val NONE = AgentOptions(emptyList(), emptyMap(), emptyList(), "", "", "", loaded = false)
        fun parse(json: String): AgentOptions = runCatching {
            val o = JSONObject(json)
            val p = o.getJSONArray("profiles")
            val v = o.getJSONArray("voices")
            val profiles = (0 until p.length()).map { p.getJSONObject(it) }
            AgentOptions(profiles.map { it.getString("name") to it.optString("description") },
                profiles.associate { it.getString("name") to it.optString("voice") },
                (0 until v.length()).map { v.getJSONObject(it).let { x -> Triple(x.getString("name"), x.optString("style"), x.optString("gender")) } },
                o.optString("profile"), o.optString("voice"), o.optString("voice_override"),
                o.optJSONObject("key")?.optString("source").orEmpty(), o.optJSONObject("key")?.optString("hint").orEmpty(),
                o.optJSONObject("changes")?.let { c -> c.keys().asSequence().associateWith { n -> c.getJSONObject(n).let { it.optString("source") to it.optString("at") } } }
                    ?: emptyMap(),
                o.optJSONObject("undo")?.let { it.optString("what") to it.optString("at") },
                runCatching { ClientSpec.fromJson(o.optJSONObject("client")) }.getOrDefault(ClientSpec.NONE))
        }.getOrDefault(NONE)
    }
}

/** Who answers: personality cards and a voice list. A change applies from the next call. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(options: AgentOptions, busy: Boolean, onSelect: (profile: String, voice: String) -> Unit,
                onViewPrompt: () -> Unit = {}, onImport: () -> Unit = {}, onUndo: () -> Unit = {}) {
    var gender by rememberSaveable { mutableStateOf("All") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Agent", "Who answers your calls. Changes apply from the next call.")
        if (options.profiles.isEmpty()) {
            EmptyState(Icons.Outlined.SupportAgent, "No agent yet", "Open the agent file you were sent on WhatsApp or email, or import it here.") {
                androidx.compose.material3.Button(onClick = onImport, enabled = !busy) { Text("Import agent file") }
            }
            return@Column
        }
        val name = profileTitle(options.profile)
        AppCard(padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(name, 64.dp, Brand.colors.signal)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall)
                    Text("Speaks as ${options.voice}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionTitle("Personality")
        options.profiles.forEach { (profile, description) ->
            val selected = profile == options.profile
            Surface(Modifier.fillMaxWidth().selectable(selected, enabled = !busy, role = Role.RadioButton) { if (!selected) onSelect(profile, "") },
                shape = MaterialTheme.shapes.large, color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Brand.colors.card,
                border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Brand.colors.cardBorder)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected, onClick = null, enabled = !busy)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profileTitle(profile), style = MaterialTheme.typography.titleMedium)
                        if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        options.profileVoices[profile]?.takeIf { it.isNotEmpty() }?.let {
                            Text("Default voice: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        options.changes[profile]?.let { (source, at) ->
                            Text(changeLine(source, at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }

        SectionTitle("Voice")
        val filters = listOf("All", "Female", "Male")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            filters.forEachIndexed { i, f ->
                SegmentedButton(selected = gender == f, onClick = { gender = f }, shape = SegmentedButtonDefaults.itemShape(i, filters.size)) { Text(f) }
            }
        }
        AppCard(padding = 8.dp) {
            Column {
                val own = options.profileVoices[options.profile].orEmpty()
                VoiceRow("Profile default", own.ifEmpty { "The personality's own voice" }, options.voiceOverride.isEmpty(), !busy,
                    leading = { IconBadge(Icons.Outlined.AutoAwesome, Tone.ACCENT) }) { onSelect(options.profile, "") }
                options.voices.filter { gender == "All" || it.third == gender }.forEach { (voice, style, sex) ->
                    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    VoiceRow(voice, listOf(sex, style).filter { it.isNotEmpty() }.joinToString(" · "), options.voiceOverride == voice, !busy) {
                        onSelect(options.profile, voice)
                    }
                }
            }
        }
        SectionTitle("More")
        AdvancedCard(options, busy, onViewPrompt, onImport, onUndo)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean,
                     leading: @Composable () -> Unit = { Avatar(title, 40.dp) }, onClick: () -> Unit) {
    ListItemRow(title, subtitle, Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = 8.dp),
        leading = leading,
        trailing = { if (selected) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary) })
}
