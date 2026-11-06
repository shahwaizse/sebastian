package dev.callstream.probe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A lead file the person opened, waiting for them to check the columns (LeadFile picks them first). */
data class LeadImport(val fileName: String, val table: LeadTable? = null, val columns: LeadColumns? = null, val fixPlusOne: Boolean = true,
                      val error: String? = null, /** Adding failed; shown in the sheet, which covers the app's snackbar. */ val addError: String? = null) {
    val upload: LeadUpload? = if (table != null && columns != null) LeadFile.upload(table, columns, fixPlusOne) else null

    companion object {
        fun of(fileName: String, table: LeadTable): LeadImport {
            val columns = LeadFile.detect(table)
                ?: return LeadImport(fileName, error = "No phone numbers found in this file. Leads need a column of phone numbers.")
            return LeadImport(fileName, table, columns)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadImportSheet(import: LeadImport, busy: Boolean, stage: AppStage, onChange: (LeadImport) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit,
                    onGoToSetup: () -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LeadImportContent(import, busy, stage, onChange, onAdd, onDismiss, onGoToSetup)
    }
}

/** The sheet's content, apart so it can be rendered on its own (ScreenShots). */
@Composable
fun LeadImportContent(import: LeadImport, busy: Boolean, stage: AppStage, onChange: (LeadImport) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit,
                      onGoToSetup: () -> Unit = {}) {
    val helperBound = stage.helperReady
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val table = import.table
        val columns = import.columns
        val upload = import.upload
        val error = import.error
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(if (error != null) Icons.Outlined.ErrorOutline else Icons.Outlined.PersonAdd, if (error != null) Tone.DANGER else Tone.ACCENT, 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (error != null) "Can't use this file" else "Add leads", style = MaterialTheme.typography.headlineSmall)
                Text(import.fileName + (table?.sheet?.takeIf { it.isNotEmpty() }?.let { " · sheet $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (error != null || table == null || columns == null || upload == null) {
            Text(error ?: "Reading the file…", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Close") }
            return@Column
        }
        val all = (0 until table.width).toList()
        val label = { c: Int -> LeadFile.columnLabel(table, columns.header, c) }
        AppCard(padding = 16.dp) {
            Text("Check the columns", style = MaterialTheme.typography.titleMedium)
            ColumnPicker("Phone number", label(columns.number), all.map { it to label(it) }) {
                onChange(import.copy(columns = columns.copy(number = it)))
            }
            ColumnPicker("Name", if (columns.name.isEmpty()) "None" else columns.name.joinToString(" + ") { label(it) },
                listOf(-1 to "None") + all.filter { it != columns.number }.map { it to label(it) }) {
                onChange(import.copy(columns = columns.copy(name = if (it < 0) emptyList() else listOf(it))))
            }
            Text("Only the name and number are added. Other columns stay in your file.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${upload.contacts.size} ${if (upload.contacts.size == 1) "lead" else "leads"}", style = MaterialTheme.typography.titleMedium)
            upload.contacts.take(PREVIEW_ROWS).forEach { c ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(c.name.ifEmpty { "No name" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (c.name.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text(c.number, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (upload.contacts.size > PREVIEW_ROWS) Text("and ${upload.contacts.size - PREVIEW_ROWS} more", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val notes = buildList {
            if (upload.skipped > 0) add("${rows(upload.skipped)} without a phone number ${if (upload.skipped == 1) "is" else "are"} skipped.")
            if (upload.repeats > 0) add("${rows(upload.repeats)} repeat${if (upload.repeats == 1) "s" else ""} a number above and ${if (upload.repeats == 1) "is" else "are"} added once.")
            if (upload.namesDropped > 0) add("${upload.namesDropped} ${if (upload.namesDropped == 1) "name doesn't" else "names don't"} look like a name " +
                "(too long, or has digits) and ${if (upload.namesDropped == 1) "is" else "are"} left blank: the agent asks for it.")
            if (upload.foreign > 0) add("${upload.foreign} ${if (upload.foreign == 1) "number is" else "numbers are"} outside Pakistan and will be dialled internationally.")
        }
        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (upload.plusOne > 0) AppCard(padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Call +1 numbers as Pakistani", style = MaterialTheme.typography.titleSmall)
                    Text("${upload.plusOne} ${if (upload.plusOne == 1) "number starts" else "numbers start"} with +1 (USA) but the rest is a Pakistani mobile, " +
                        "usually a wrong country code on the form: +1 325… is called as 0325….", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(import.fixPlusOne, { onChange(import.copy(fixPlusOne = it)) })
            }
        }
        import.addError?.let { Text("Couldn't add the leads: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        // The sheet is kept by the helper: before setup reaches it, the way there instead of a wait that won't end.
        if (!helperBound && !stage.waiting) {
            Text("Finish setting up this phone first: leads are kept on it once it's connected. Then open the file again.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onGoToSetup, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Go to setup") }
        } else {
            Button(onClick = onAdd, enabled = !busy && helperBound && upload.contacts.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Add ${upload.contacts.size} ${if (upload.contacts.size == 1) "lead" else "leads"}")
            }
            Text(if (helperBound) "Numbers already in the sheet aren't added again." else "Waiting for this phone to connect…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

private fun rows(n: Int) = if (n == 1) "1 row" else "$n rows"

@Composable
private fun ColumnPicker(label: String, current: String, options: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(0.6f)) {
            OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
                Text(current, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ArrowDropDown, null)
            }
            DropdownMenu(open, { open = false }) {
                options.forEach { (column, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(column) }) }
            }
        }
    }
}

private const val PREVIEW_ROWS = 5
