package dev.callstream.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private fun megabytes(bytes: Long) = if (bytes <= 0) "" else " · %.1f MB".format(bytes / 1_048_576.0)

/** Home: shown only while an update is waiting. [viaHelper]: the helper installs it with no prompt; otherwise Android's
 * installer does (MainActivity.installUpdate). Either way it never waits on setup. */
@Composable
fun UpdateCard(updates: UpdateState, viaHelper: Boolean, enabled: Boolean, onInstall: () -> Unit) {
    val release = updates.available ?: return
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.SystemUpdate, Tone.ACCENT)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Update available", style = MaterialTheme.typography.titleMedium)
                Text("Call Stream ${release.versionName}${megabytes(release.sizeBytes)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (release.notes.isNotEmpty()) Text(release.notes, style = MaterialTheme.typography.bodyMedium)
        InstallButton(updates, viaHelper, enabled, onInstall)
    }
}

@Composable
private fun InstallButton(updates: UpdateState, viaHelper: Boolean, enabled: Boolean, onInstall: () -> Unit) {
    if (updates.installing) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(if (viaHelper) "Updating… Call Stream reopens by itself." else "Downloading the update…", style = MaterialTheme.typography.bodyMedium)
    } else {
        Button(onClick = onInstall, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Update now") }
        if (!viaHelper) Text("Android will ask once to allow installs from Call Stream, then show Install.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Settings in the Google Play build: Play updates the app, so only the version. */
@Composable
fun PlayVersionCard(version: String) {
    AppCard(padding = 8.dp) {
        ListItemRow("Call Stream $version", "Updates come from Google Play", Modifier.padding(horizontal = 8.dp),
            leading = { IconBadge(Icons.Outlined.Verified, Tone.LIVE) })
    }
}

/** Settings: this version, the check, and the beta switch for testing phones. */
@Composable
fun AppVersionCard(version: String, updates: UpdateState, viaHelper: Boolean, enabled: Boolean, onCheck: () -> Unit, onInstall: () -> Unit, onBeta: (Boolean) -> Unit) {
    AppCard(padding = 8.dp) {
        val available = updates.available
        ListItemRow("Call Stream $version",
            when {
                updates.checking -> "Checking for updates…"
                available != null -> "Version ${available.versionName} is ready to install"
                updates.status != null -> updates.status
                else -> "Up to date"
            }, Modifier.padding(horizontal = 8.dp),
            leading = { IconBadge(if (available != null) Icons.Outlined.SystemUpdate else Icons.Outlined.Verified, if (available != null) Tone.ACCENT else Tone.LIVE) },
            trailing = { if (available == null) FilledTonalButton(onClick = onCheck, enabled = !updates.checking && !updates.installing) { Text("Check") } })
        if (available != null) Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) { InstallButton(updates, viaHelper, enabled, onInstall) }
        ListItemRow("Beta updates", "Get new versions a day early. For testing phones, not clients.", Modifier.padding(horizontal = 8.dp),
            trailing = { Switch(checked = updates.beta, onCheckedChange = onBeta, enabled = !updates.installing) })
    }
}
