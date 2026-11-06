package dev.callstream.probe

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.callstream.probe.leads.ClientSpec
import dev.callstream.probe.leads.FieldType
import dev.callstream.probe.leads.Lead
import dev.callstream.probe.leads.LeadRules
import dev.callstream.probe.leads.LeadTime
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The helper's view of the outbound run (see OutboundRun.toJson). */
data class OutboundState(val running: Boolean, val stopRequested: Boolean, val waiting: String, val currentId: Long, val version: Long) {
    companion object {
        val IDLE = OutboundState(false, false, "", -1, -1)
        fun parse(json: String): OutboundState = runCatching {
            val o = JSONObject(json)
            OutboundState(o.optBoolean("running"), o.optBoolean("stop_requested"), o.optString("waiting"), o.optLong("current_id", -1), o.optLong("version", -1))
        }.getOrDefault(IDLE)
    }
}

data class LeadQuery(val filter: LeadRules.Filter = LeadRules.Filter.ALL, val search: String = "", val limit: Int = PAGE) {
    fun toJson(): String = JSONObject().put("filter", filter.name.lowercase()).put("search", search).put("limit", limit).toString()
    companion object { const val PAGE = 100 }
}

/** Leads ticked for a bulk action: rows by id, or [all] that the current filter and search show (beyond the loaded page). */
data class LeadSelection(val ids: Set<Long> = emptySet(), val all: Boolean = false) {
    fun toJson(query: LeadQuery): String = if (all) JSONObject().put("filter", query.filter.name.lowercase()).put("search", query.search).toString()
        else JSONObject().put("ids", org.json.JSONArray(ids.toList())).toString()
}

/** One page of the lead sheet (see LeadStore.query). [interested]: row ids that qualify. */
data class LeadsPage(val version: Long, val total: Int, val counts: Map<String, Int>, val rows: List<Lead>,
                     val interested: Set<Long>, val calls: Map<Long, Int>) {
    companion object {
        val EMPTY = LeadsPage(-1, 0, emptyMap(), emptyList(), emptySet(), emptyMap())
        fun parse(json: String): LeadsPage {
            val o = JSONObject(json)
            val rows = o.optJSONArray("rows")
            val objects = (0 until (rows?.length() ?: 0)).map { rows!!.getJSONObject(it) }
            val counts = o.optJSONObject("counts")
            return LeadsPage(o.optLong("version"), o.optInt("total"),
                counts?.keys()?.asSequence()?.associateWith { counts.optInt(it) } ?: emptyMap(),
                objects.map { Lead.fromJson(it) },
                objects.filter { it.optBoolean("interested") }.map { it.optLong("id") }.toSet(),
                objects.filter { it.has("calls") }.associate { it.optLong("id") to it.optInt("calls") })
        }
    }
}

private val SHOWN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)
private fun shown(text: String?): String = LeadTime.parse(text)?.let { SHOWN.format(it) } ?: text.orEmpty()

/** The lead sheet: search, filters, the list, and the calling run (floating button). */
@Composable
fun LeadsScreen(state: UiState, actions: UiActions, onGate: (String) -> Unit = {}) {
    val ready = state.stage == AppStage.READY
    val spec = state.spec
    val page = state.leads
    val query = state.leadQuery
    val run = state.outbound
    val busy = state.busy
    // Selecting: long-press a lead or tap Select; then taps tick leads. Null when not selecting.
    var selection by remember { mutableStateOf<LeadSelection?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(query.filter, query.search) { selection = selection?.let { LeadSelection() } }
    val chosen = selection?.let { if (it.all) page.total else it.ids.size } ?: 0
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val sel = selection
                if (sel != null) SelectionBar(chosen, canSelectAll = !sel.all && page.total > sel.ids.size, total = page.total, busy = busy,
                    onSelectAll = { selection = LeadSelection(all = true) }, onDelete = { confirmDelete = true }, onClose = { selection = null })
                else ScreenHeader("Leads", (page.counts["all"] ?: page.total).let { "$it in the sheet" }) {
                    if (page.rows.isNotEmpty()) IconButton(onClick = { selection = LeadSelection() }, enabled = !busy) { Icon(Icons.Outlined.Checklist, "Select leads") }
                    IconButton(onClick = actions::uploadContacts, enabled = !busy) { Icon(Icons.Outlined.UploadFile, "Upload contacts") }
                    IconButton(onClick = { actions.openLead(Lead(number = "")) }, enabled = !busy) { Icon(Icons.Outlined.PersonAddAlt, "Add lead") }
                    if (spec.interested != null) IconButton(onClick = actions::exportInterested, enabled = !busy && (page.counts["interested"] ?: 0) > 0) {
                        Icon(Icons.Outlined.IosShare, "Export interested leads")
                    }
                }
            }
            item { CallingBanner(run, state.canCall, ready) { onGate("start calling") } }
            item {
                OutlinedTextField(value = query.search, onValueChange = { actions.queryLeads(query.copy(search = it, limit = LeadQuery.PAGE)) },
                    Modifier.fillMaxWidth(), singleLine = true, shape = CircleShape, placeholder = { Text("Search name, number or details") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.search.isNotEmpty()) IconButton(onClick = { actions.queryLeads(query.copy(search = "", limit = LeadQuery.PAGE)) }) {
                        Icon(Icons.Outlined.Close, "Clear search") } },
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Brand.colors.card, focusedContainerColor = Brand.colors.card,
                        unfocusedBorderColor = Brand.colors.cardBorder))
            }
            item {
                val chips = listOfNotNull(LeadRules.Filter.ALL to "All", (LeadRules.Filter.INTERESTED to "Interested").takeIf { spec.interested != null },
                    LeadRules.Filter.FOLLOW_UPS to "Callbacks", LeadRules.Filter.NOT_CALLED to "Not called")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips) { (filter, label) ->
                        val count = page.counts[filter.name.lowercase()]
                        FilterChip(selected = query.filter == filter, onClick = { actions.queryLeads(query.copy(filter = filter, limit = LeadQuery.PAGE)) },
                            label = { Text(if (count != null) "$label  $count" else label) }, shape = CircleShape,
                            leadingIcon = if (query.filter == filter) { { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } } else null)
                    }
                }
            }
            if (query.filter == LeadRules.Filter.INTERESTED) item {
                Text("One line per person: their latest call with a plot and visit, or a minute or more of talk. This is what the export contains.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (page.rows.isEmpty()) item {
                when {
                    page.version < 0 -> Row(Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Text("Reading the lead sheet…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    query.search.isNotEmpty() || query.filter != LeadRules.Filter.ALL ->
                        EmptyState(Icons.Outlined.SearchOff, "Nothing here", "No leads match this search or filter.")
                    else -> EmptyState(Icons.Outlined.Groups, "No leads yet",
                        "Upload a CSV with a number column (name optional). Call Stream calls each lead in turn, callbacks first.") {
                        Button(onClick = actions::uploadContacts, enabled = !busy) {
                            Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Upload contacts")
                        }
                    }
                }
            } else item {
                AppCard(padding = 6.dp) {
                    Column {
                        page.rows.forEachIndexed { i, lead ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            val sel = selection
                            val ticked = sel != null && (sel.all || lead.id in sel.ids)
                            LeadRow(spec, lead, lead.id in page.interested, page.calls[lead.id], selecting = sel != null, selected = ticked,
                                onLongClick = { if (sel == null) selection = LeadSelection(setOf(lead.id)) }) {
                                when {
                                    sel == null -> actions.openLead(lead)
                                    // From "all", unticking one turns it into the loaded rows minus that one.
                                    sel.all -> selection = LeadSelection(page.rows.map { it.id }.toSet() - lead.id)
                                    else -> selection = sel.copy(ids = if (lead.id in sel.ids) sel.ids - lead.id else sel.ids + lead.id)
                                }
                            }
                        }
                    }
                }
            }
            if (page.total > page.rows.size) item {
                TextButton(onClick = { actions.queryLeads(query.copy(limit = query.limit + LeadQuery.PAGE)) }, Modifier.fillMaxWidth()) {
                    Text("Show ${minOf(LeadQuery.PAGE, page.total - page.rows.size)} more")
                }
            }
        }
        val running = run.running
        if (selection == null && (running || (page.counts["all"] ?: page.total) > 0)) ExtendedFloatingActionButton(
            onClick = { when { running -> actions.stopCalling(); !ready -> onGate("start calling"); else -> actions.startCalling() } },
            expanded = true,
            icon = { Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.Call, null) },
            text = { Text(when { run.stopRequested -> "Stopping…"; running -> "Stop calling"; else -> "Start calling" }) },
            containerColor = if (running) Brand.colors.dangerContainer else MaterialTheme.colorScheme.primary,
            contentColor = if (running) Brand.colors.danger else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
                .alpha(if ((running && !run.stopRequested) || (!running && state.canCall && ready)) 1f else 0.5f),
        )
    }
    val sel = selection
    if (confirmDelete && sel != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${if (chosen == 1) "this lead" else "$chosen leads"}?") },
        text = { Text("Every call to ${if (chosen == 1) "this number" else "these numbers"} is removed from the sheet, with transcripts and details. " +
            "This can't be undone. A lead in a call right now is kept.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; selection = null; actions.deleteLeads(sel, query) }) {
            Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Replaces the Leads header while selecting. */
@Composable
private fun SelectionBar(chosen: Int, canSelectAll: Boolean, total: Int, busy: Boolean, onSelectAll: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Stop selecting") }
        Column(Modifier.weight(1f)) {
            Text(if (chosen == 0) "Select leads" else "$chosen selected", style = MaterialTheme.typography.titleLarge)
            if (chosen == 0) Text("Tap leads to tick them", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canSelectAll) TextButton(onClick = onSelectAll) { Text("Select all $total") }
        IconButton(onClick = onDelete, enabled = chosen > 0 && !busy) {
            Icon(Icons.Outlined.Delete, "Delete selected", tint = if (chosen > 0 && !busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the calling run is doing, or why it can't start ([ready]: setup is complete; [onSetup] shows what's missing). */
@Composable
private fun CallingBanner(run: OutboundState, canCall: Boolean, ready: Boolean, onSetup: () -> Unit) {
    val (tone, title, body) = when {
        run.running && run.stopRequested -> Triple(Tone.BUSY, "Stopping", "No more calls will be placed; the current call continues until it ends.")
        run.running && run.currentId >= 0 -> Triple(Tone.LIVE, "Calling now", "Your agent is on a call from this sheet.")
        run.running && run.waiting.isNotEmpty() -> Triple(Tone.BUSY, "Waiting", "Next call: ${run.waiting}.")
        run.running -> Triple(Tone.LIVE, "Calling", "Working through the sheet, callbacks first.")
        !ready -> Triple(Tone.BUSY, "Setup isn't finished", "Calling starts once setup is complete.")
        !canCall -> Triple(Tone.IDLE, "Calling is paused", "Turn the agent on from Home to start calling.")
        else -> return
    }
    val (bg, fg) = toneColors(tone)
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(bg).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val pulse by rememberInfiniteTransition(label = "dot").animateFloat(0.3f, 1f,
            infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot-alpha")
        Box(Modifier.size(10.dp).alpha(if (run.running) pulse else 1f).clip(CircleShape).background(fg))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = fg)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        if (!run.running && !ready) TextButton(onClick = onSetup) { Text("See what's left") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LeadRow(spec: ClientSpec, lead: Lead, interested: Boolean, calls: Int?, selecting: Boolean = false, selected: Boolean = false,
                    onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            selecting -> Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Outlined.Circle, if (selected) "Selected" else "Not selected", Modifier.size(28.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            lead.name.isNotEmpty() -> Avatar(lead.name, 44.dp)
            else -> IconBadge(Icons.Outlined.Person, Tone.IDLE, 44.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(lead.name.ifEmpty { lead.number }, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (lead.hasTranscript) Icon(Icons.Outlined.Forum, "Transcript saved", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val line = listOfNotNull(
                lead.number.takeIf { lead.name.isNotEmpty() },
                "call ${lead.callNo}".takeIf { lead.callNo > 1 },
                calls?.takeIf { it > 1 }?.let { "$it calls" },
                LeadRules.talkTime(lead.talkTime).takeIf { lead.talkTime > 0 }?.let { "talked $it" },
                lead.followUpAt?.let { "callback ${shown(it)}" },
            ).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = spec.fields.mapNotNull { f -> lead.field(f.key).takeIf { it.isNotBlank() }?.let { if (f.type == FieldType.DATETIME) shown(it) else it } }
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusPill(lead)
            if (interested) Pill("Interested", Tone.ACCENT)
        }
    }
}

@Composable
private fun StatusPill(lead: Lead) {
    val label = if (lead.status == Lead.PENDING) (if (lead.followUpAt != null) "Callback" else "To call")
        else lead.status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val tone = when (lead.status) {
        Lead.DIALING, Lead.RINGING, Lead.IN_CALL -> Tone.BUSY
        Lead.COMPLETED -> Tone.LIVE
        Lead.NO_ANSWER, Lead.NOT_CONNECTED, Lead.FAILED -> Tone.DANGER
        else -> Tone.IDLE
    }
    Pill(label, tone, dot = lead.active)
}

/** Add (id 0) or edit one lead, from the bottom of the screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadEditorSheet(
    spec: ClientSpec,
    lead: Lead,
    interested: Boolean,
    busy: Boolean,
    error: String?,
    onSave: (Lead) -> Unit,
    onDelete: (allForNumber: Boolean) -> Unit,
    onReset: () -> Unit,
    onTranscript: () -> Unit,
    onDismiss: () -> Unit,
) {
    val adding = lead.id == 0L
    val locked = lead.active
    var name by remember(lead.id) { mutableStateOf(lead.name) }
    var number by remember(lead.id) { mutableStateOf(lead.number) }
    var followUp by remember(lead.id) { mutableStateOf(lead.followUpAt.orEmpty()) }
    var talk by remember(lead.id) { mutableStateOf(if (lead.talkTime > 0) lead.talkTime.toString() else "") }
    var fields by remember(lead.id) { mutableStateOf(spec.fields.associate { it.key to lead.field(it.key) }) }
    var confirmDelete by remember(lead.id) { mutableStateOf(false) }
    var confirmReset by remember(lead.id) { mutableStateOf(false) }
    // Anything that has been dialed (or has a callback) can go back to not called.
    val resettable = !adding && !locked && (lead.status != Lead.PENDING || lead.attempts > 0 || lead.followUpAt != null)
    var deleteAll by remember(lead.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (adding) "Add lead" else lead.name.ifEmpty { lead.number }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (!adding) {
                val facts = listOfNotNull("call ${lead.callNo}", lead.status, "${lead.attempts} ${if (lead.attempts == 1) "try" else "tries"}".takeIf { lead.attempts > 0 },
                    lead.calledAt?.let { "last dialed ${shown(it)}" }, "interested".takeIf { interested && spec.interested != null })
                Text(facts.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (lead.detail.isNotEmpty()) Text(lead.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (lead.extraction.startsWith("failed") || lead.extraction == "no transcript")
                    Text("Couldn't fill in this call's details automatically (${lead.extraction}). Add them by hand if you know them.", fontSize = 13.sp)
            }
            if (lead.hasTranscript) OutlinedButton(onClick = onTranscript, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Forum, null, Modifier.size(18.dp)); Box(Modifier.size(8.dp)); Text("Read transcript")
            }
            if (locked) Text("This lead is in a call right now. Edit it after the call.", color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !locked, singleLine = true, label = { Text("Name") })
            OutlinedTextField(number, { number = it }, Modifier.fillMaxWidth(), enabled = !locked, singleLine = true, label = { Text("Number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            for (f in spec.fields) {
                val value = fields[f.key].orEmpty()
                if (f.type == FieldType.DATETIME) DateTimeField(f.label, value, !locked) { fields = fields + (f.key to it) }
                else OutlinedTextField(value, { fields = fields + (f.key to it) }, Modifier.fillMaxWidth(), enabled = !locked, singleLine = true, label = { Text(f.label) })
            }
            DateTimeField("Call back at", followUp, !locked) { followUp = it }
            if (!adding && lead.status != Lead.PENDING)
                Text("Setting a callback time adds the next call to the sheet; it's dialed when due, ahead of new leads.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            OutlinedTextField(talk, { t -> talk = t.filter { it.isDigit() }.take(6) }, Modifier.fillMaxWidth(), enabled = !locked, singleLine = true,
                label = { Text("Talk time (seconds)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (resettable) OutlinedButton(onClick = { confirmReset = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Mark as not called") }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    onSave(lead.copy(name = name.trim(), number = number.trim(), followUpAt = followUp.ifBlank { null },
                        talkTime = talk.toIntOrNull() ?: 0, fields = fields))
                }, enabled = !locked && !busy && number.isNotBlank()) { Text(if (adding) "Add" else "Save") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                if (!adding) {
                    Box(Modifier.weight(1f))
                    TextButton(onClick = { confirmDelete = true }, enabled = !locked && !busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Mark as not called?") },
        text = { Text("Call Stream will call ${lead.name.ifEmpty { lead.number }} again as a new lead. This call's status, tries, talk time and callback time are cleared; the name, number and other details stay.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Mark as not called") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this lead?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This removes call ${lead.callNo} to ${lead.number} from the sheet. It can't be undone.")
                Row(Modifier.clickable { deleteAll = !deleteAll }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deleteAll, { deleteAll = it })
                    Text("Also delete every other call to this number")
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(deleteAll) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** A date and time picked with the phone's own pickers, kept as "yyyy-MM-dd HH:mm" (Pakistan time). */
@Composable
private fun DateTimeField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(if (value.isEmpty()) "" else shown(value), {}, Modifier.fillMaxWidth(), readOnly = true, enabled = false,
                label = { Text(label) }, placeholder = { Text("Tap to pick") },
                colors = OutlinedTextFieldDefaults.colors(disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledLabelColor = MaterialTheme.colorScheme.secondary, disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.secondary))
            if (enabled) Box(Modifier.matchParentSize().clickable {
                val start = LeadTime.parse(value) ?: LeadTime.now().plusHours(1).withMinute(0)
                DatePickerDialog(context, { _, y, m, d ->
                    TimePickerDialog(context, { _, h, min ->
                        onChange(LeadTime.MINUTE.format(java.time.LocalDateTime.of(y, m + 1, d, h, min)))
                    }, start.hour, start.minute, false).show()
                }, start.year, start.monthValue - 1, start.dayOfMonth).show()
            })
        }
        if (enabled && value.isNotEmpty()) TextButton(onClick = { onChange("") }) { Text("Clear") }
    }
}

/** One call's transcript as a conversation: the agent on the left, the lead on the right. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptSheet(lead: Lead, lines: List<Pair<String, String>>, onDismiss: () -> Unit, onReport: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(lead.name.ifEmpty { lead.number }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull("call ${lead.callNo}", lead.calledAt?.let { shown(it) }, LeadRules.talkTime(lead.talkTime).takeIf { lead.talkTime > 0 })
                        .joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(lines.joinToString("\n") { "${it.first}: ${it.second}" }))
                    copied = true
                }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Box(Modifier.size(6.dp)); Text(if (copied) "Copied" else "Copy")
                }
                // Google Play: what the AI said can be reported from where it's shown (ReportUi).
                TextButton(onClick = onReport) {
                    Icon(Icons.Outlined.Flag, null, Modifier.size(18.dp)); Box(Modifier.size(6.dp)); Text("Report")
                }
            }
            Text("Speech recognition's text, so names and numbers may be spelled differently from what was said.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lines.isEmpty()) Text("No transcript was saved for this call.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((who, text) in lines) {
                            val agent = who == "Agent"
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (agent) Arrangement.Start else Arrangement.End) {
                                Surface(color = if (agent) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.widthIn(max = 300.dp)) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text(if (agent) "Agent" else lead.name.ifEmpty { "Lead" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                        Text(text, fontSize = 15.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
