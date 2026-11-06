package dev.callstream.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Settings → Support. [testResult]: the last Test my agent, if any. */
data class SupportState(val testing: Boolean = false, val reporting: Boolean = false, val testResult: TestResult? = null)
data class TestResult(val pass: Boolean, val replyMs: Long, val message: String)

/** Lets a client check the agent themselves and send support what's needed to help, without a visit. */
@Composable
fun SupportCard(support: SupportState, canRun: Boolean, onTest: () -> Unit, onReport: () -> Unit, onReportAi: () -> Unit = {},
                /** Why Test and Send can't run (no helper), shown in their place; null when they can. */
                offline: String? = null) {
    AppCard(padding = 8.dp) {
        ListItemRow("Test my agent", offline ?: "A 30-second practice call. Uses a little Gemini credit.",
            Modifier.padding(horizontal = 8.dp), leading = { IconBadge(Icons.Outlined.GraphicEq, Tone.ACCENT) },
            trailing = { Busy(support.testing) { FilledTonalButton(onClick = onTest, enabled = canRun && !support.reporting) { Text("Test") } } })
        support.testResult?.let { r ->
            Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val (_, fg) = toneColors(if (r.pass) Tone.LIVE else Tone.DANGER)
                Icon(if (r.pass) Icons.Outlined.TaskAlt else Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = fg)
                Text(if (r.pass) "Your agent answered in %.1f s. It's ready for calls.".format(r.replyMs / 1000.0) else r.message,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        ListItemRow("Send report", offline ?: "Logs for support. No leads, keys or audio.",
            Modifier.padding(horizontal = 8.dp), leading = { IconBadge(Icons.Outlined.IosShare, Tone.IDLE) },
            trailing = { Busy(support.reporting) { FilledTonalButton(onClick = onReport, enabled = canRun && !support.testing) { Text("Send") } } })
        // Needs only the internet, not the helper: Google Play wants AI output reportable whatever state the phone is in.
        ListItemRow("Report the AI", "Flag offensive, harmful or untrue replies.",
            Modifier.padding(horizontal = 8.dp), leading = { IconBadge(Icons.Outlined.Flag, Tone.IDLE) },
            trailing = { FilledTonalButton(onClick = onReportAi) { Text("Report") } })
    }
}

@Composable
private fun Busy(busy: Boolean, content: @Composable () -> Unit) {
    if (busy) Column(Modifier.padding(horizontal = 20.dp)) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } else content()
}
