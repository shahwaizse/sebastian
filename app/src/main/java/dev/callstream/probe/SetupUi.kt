package dev.callstream.probe

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** What the helper granted and found (see shell/PhoneSetup.ensure). */
data class SetupStatus(val missingPermissions: List<String>, val bootStart: String, val manufacturer: String, val errors: List<String>,
                       /** Xiaomi's "USB debugging (Security settings)" as the helper read it; null on other phones. */
                       val xiaomiAdbInput: Boolean? = null,
                       /** USB debugging as the shell reads it (the helper switches it on); null from older helpers. */
                       val usbDebugging: Boolean? = null) {
    companion object {
        fun parse(json: String): SetupStatus? = runCatching {
            val o = JSONObject(json)
            val missing = o.getJSONArray("missing_permissions")
            val errors = o.optJSONArray("errors")
            SetupStatus((0 until missing.length()).map { missing.getString(it) }, o.optString("boot_start"),
                o.optString("manufacturer"), (0 until (errors?.length() ?: 0)).map { errors!!.getString(it) },
                if (o.has("xiaomi_adb_input")) o.optBoolean("xiaomi_adb_input") else null,
                if (o.has("usb_debugging")) o.optBoolean("usb_debugging") else null)
        }.getOrNull()
    }
}

/**
 * What the app itself can see of Developer options and its own ADB pairing (MainActivity reads it), before and
 * without the helper. [developerOptions] and [usbDebugging] are null when the phone won't say: Android 17 reports both
 * as off to apps whatever they are ([switchReading]).
 */
data class AdbState(val developerOptions: Boolean? = null, val usbDebugging: Boolean? = null, val wirelessDebugging: Boolean = false,
                    val paired: Boolean = false, val onWifi: Boolean = true,
                    /** The helper granted WRITE_SECURE_SETTINGS: the app switches Wireless debugging on by itself. */
                    val canWriteSettings: Boolean = false,
                    /** Why the last start of the helper failed (adb.AdbProblem's message), shown under Connect. */
                    val connectError: String? = null)

/** A Settings.Global switch as an app reads it: on, off, or null for Android 17+, which reports 0 to apps either way
 * (JemRec, docs: "will always return 0 for all third-party apps"; the Pixel from the September 2026 update). */
fun switchReading(value: Int, sdk: Int): Boolean? = when {
    value == 1 -> true
    sdk >= 37 -> null
    else -> false
}

enum class StepState { DONE, TODO, WAITING, INFO }
enum class StepAction { SETUP_GUIDE, OPEN_ABOUT_PHONE, OPEN_USB_DEBUGGING, OPEN_WIRELESS_DEBUGGING, PAIR, RECONNECT, CHECK_AGAIN }

/** [required]: setup isn't complete without it. INFO steps are advice the phone can't check. */
data class SetupStep(val title: String, val state: StepState, val detail: String = "", val action: StepAction? = null, val required: Boolean = true,
                     /** Also offer Show me how (the setup guide). */
                     val showGuide: Boolean = false)

/**
 * The checklist, in the order a person does it. The Developer options steps count as done once the helper runs (it
 * can only have started through them); steps after a missing connection wait for it.
 */
fun setupSteps(adb: AdbState, helperBound: Boolean, setup: SetupStatus?,
               family: PhoneFamily = PhoneFamily.OTHER, xiaomiAdbInput: Boolean? = null): List<SetupStep> {
    val limited = family != PhoneFamily.OTHER && family != PhoneFamily.HUAWEI
    val started = helperBound || adb.paired
    // Xiaomi: the switch that lifts its limit on adb, checked for real. The helper's reading wins; before the helper runs
    // the app reads it itself ([xiaomiAdbInput]); unknown counts as off. First, because adb's grants need it from the start.
    val xiaomiSwitch = if (family != PhoneFamily.XIAOMI) null else (setup?.xiaomiAdbInput ?: xiaomiAdbInput ?: false).let { on ->
        SetupStep("Turn on USB debugging (Security settings)", if (on) StepState.DONE else StepState.TODO,
            if (on) "" else "Xiaomi phones block Sebastian's setup until this switch in Developer options is on. It's a " +
                "different switch from USB debugging, and needs a Mi account, usually with a SIM in the phone. Xiaomi can turn it " +
                "off after a restart: if this step comes back, switch it on again.", showGuide = !on)
    }
    val maybeOn = "Already on? Go to the next step."
    val developer = started || adb.developerOptions == true
    // The helper's reading wins (it switches USB debugging on itself); before it, the app's, where the phone tells it.
    val usb = if (helperBound && setup?.usbDebugging != null) setup.usbDebugging else adb.usbDebugging ?: adb.paired
    val wireless = helperBound || adb.wirelessDebugging || (adb.paired && adb.canWriteSettings)
    fun waits(done: Boolean) = if (!helperBound) StepState.WAITING else if (done) StepState.DONE else StepState.TODO
    val steps = listOfNotNull(xiaomiSwitch).toMutableList()
    steps += listOf(
        SetupStep("Turn on Developer options", if (developer) StepState.DONE else StepState.TODO,
            if (developer) "" else "Tap Build number seven times in About phone." + if (adb.developerOptions == null) " $maybeOn" else "",
            StepAction.OPEN_ABOUT_PHONE.takeIf { !developer }),
        SetupStep("Turn on USB debugging", if (usb) StepState.DONE else StepState.TODO,
            if (usb) "" else "In Developer options. No cable needed: it keeps Sebastian running when Wi-Fi drops." +
                if (adb.usbDebugging == null) " $maybeOn" else "",
            StepAction.OPEN_USB_DEBUGGING.takeIf { !usb }),
        SetupStep("Turn on Wireless debugging", if (wireless) StepState.DONE else StepState.TODO,
            when {
                wireless -> ""
                !adb.onWifi -> "Connect to Wi-Fi first: Wireless debugging only works on Wi-Fi."
                else -> "Just below USB debugging. Turn it on and choose Always allow on this network."
            }, StepAction.OPEN_WIRELESS_DEBUGGING.takeIf { !wireless }),
        SetupStep("Pair Sebastian", if (started) StepState.DONE else StepState.TODO,
            if (started) "" else "Tap Pair, then in Wireless debugging tap Pair device with pairing code and type the code into Sebastian's notification.",
            StepAction.PAIR.takeIf { !started }, showGuide = !started),
        SetupStep("Connect to the phone helper", if (helperBound) StepState.DONE else if (adb.paired) StepState.TODO else StepState.WAITING,
            if (helperBound || !adb.paired) "" else adb.connectError ?: "Connecting… If this stays, tap Reconnect.",
            StepAction.RECONNECT.takeIf { adb.paired && !helperBound }),
    )
    val missing = setup?.missingPermissions.orEmpty()
    steps += SetupStep("Phone permissions", if (helperBound && setup == null) StepState.TODO else waits(missing.isEmpty()),
        when {
            !helperBound -> "Granted automatically once connected."
            setup == null -> "Checking…"
            missing.isEmpty() -> ""
            // Android refusing the shell's own grants is the brand limit at work (Shizuku's guide; Octoclip's notes)
            limited && setup.errors.any { "SecurityException" in it || "not allowed" in it.lowercase() } ->
                "Your ${family.label} phone is blocking Sebastian. Change the setting in Show me how (step 2), then Check again."
            else -> "Couldn't grant ${missing.joinToString()}. ${setup.errors.firstOrNull().orEmpty()}".trim()
        }, StepAction.CHECK_AGAIN.takeIf { helperBound && missing.isNotEmpty() }, showGuide = limited && helperBound && missing.isNotEmpty())
    when (setup?.bootStart) {
        "ready" -> steps += SetupStep("Starts again after a restart", StepState.DONE,
            "Once the phone is on Wi-Fi. Keep Wireless debugging allowed on your Wi-Fi (Always allow on this network).", required = false)
        "missing" -> steps += SetupStep("Starts again after a restart", StepState.TODO,
            "Couldn't let Sebastian switch Wireless debugging on by itself. ${setup.errors.firstOrNull().orEmpty()}".trim(),
            StepAction.CHECK_AGAIN, required = false)
    }
    when (setup?.manufacturer) {
        "xiaomi", "redmi", "poco" -> steps += SetupStep("Xiaomi battery settings", StepState.INFO,
            "For Sebastian: Battery saver → No restrictions, and turn on Autostart. Check again after an update.", required = false)
        "huawei", "honor" -> steps += SetupStep("Honor and Huawei: app launch", StepState.INFO,
            "Settings → Battery → App launch → Sebastian → Manage manually, with Auto-launch on, so it starts after a restart.", required = false)
    }
    return steps
}

/** What to do now: the first required step that isn't done and can be done (not one still waiting on another). */
fun nextSetupStep(steps: List<SetupStep>): SetupStep? =
    steps.firstOrNull { it.required && it.state == StepState.TODO } ?: steps.firstOrNull { it.required && it.state != StepState.DONE }

/** The checklist rows; the card around them is the caller's (Home while incomplete, Settings always). */
@Composable
fun SetupList(steps: List<SetupStep>, busy: Boolean, onAction: (StepAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var number = 0
        steps.forEach { step ->
            if (step.state != StepState.INFO) number++
            StepRow(step, number, busy, onAction)
        }
    }
}

@Composable
private fun StepRow(step: SetupStep, number: Int, busy: Boolean, onAction: (StepAction) -> Unit) {
    val brand = Brand.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val marker = Modifier.size(28.dp).clip(CircleShape)
        when (step.state) {
            StepState.DONE -> Box(marker.background(brand.live), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, "Done", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.surface)
            }
            StepState.TODO -> Box(marker.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            StepState.WAITING -> Box(marker.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StepState.INFO -> Box(marker.background(brand.idleContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = brand.idle)
            }
        }
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(step.title, style = MaterialTheme.typography.titleSmall,
                color = if (step.state == StepState.WAITING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (step.detail.isNotEmpty()) Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (step.action) {
                StepAction.OPEN_ABOUT_PHONE -> Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAction(StepAction.SETUP_GUIDE) }) { Text("Show me how") }
                    TextButton(onClick = { onAction(step.action) }) { Text("Open About phone") }
                }
                StepAction.OPEN_USB_DEBUGGING, StepAction.OPEN_WIRELESS_DEBUGGING ->
                    FilledTonalButton(onClick = { onAction(step.action) }, modifier = Modifier.padding(top = 4.dp)) { Text("Open Developer options") }
                StepAction.PAIR -> Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAction(step.action) }, enabled = !busy) { Text("Pair") }
                    TextButton(onClick = { onAction(StepAction.SETUP_GUIDE) }) { Text("Show me how") }
                }
                StepAction.SETUP_GUIDE -> {}
                StepAction.RECONNECT -> FilledTonalButton(onClick = { onAction(step.action) }, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) { Text("Reconnect") }
                StepAction.CHECK_AGAIN -> FilledTonalButton(onClick = { onAction(step.action) }, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) { Text("Check again") }
                null -> {}
            }
            if (step.showGuide && step.action == null) Button(onClick = { onAction(StepAction.SETUP_GUIDE) }, modifier = Modifier.padding(top = 4.dp)) { Text("Show me how") }
            else if (step.showGuide && step.action != StepAction.PAIR) TextButton(onClick = { onAction(StepAction.SETUP_GUIDE) }) { Text("Show me how") }
        }
    }
}

/** "Setup isn't finished", where something that depends on setup was tapped: what's next, and the way there. */
@Composable
fun SetupGateDialog(what: String, steps: List<SetupStep>, stage: AppStage, onGoToSetup: () -> Unit, onDismiss: () -> Unit) {
    val offline = stage == AppStage.OFFLINE
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(if (offline) Icons.Outlined.SyncProblem else Icons.Outlined.Checklist, null) },
        title = { Text(if (offline) "Not connected" else "Setup isn't finished") }, text = { Text(setupGateText(what, steps, stage)) },
        confirmButton = { Button(onClick = { onDismiss(); onGoToSetup() }) { Text(if (offline) "See what to fix" else "Go to setup") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } })
}

/** In place of a screen whose data lives in the helper: progress while it connects, else the way to setup. */
@Composable
fun NeedsSetup(stage: AppStage, steps: List<SetupStep>, what: String, onGoToSetup: () -> Unit) {
    if (stage.waiting) {
        Row(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Text("Connecting to this phone…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val offline = stage == AppStage.OFFLINE
    EmptyState(if (offline) Icons.Outlined.SyncProblem else Icons.Outlined.Checklist, if (offline) "Not connected" else "Setup isn't finished",
        setupGateText(what, steps, stage)) {
        Button(onClick = onGoToSetup) { Text(if (offline) "See what to fix" else "Go to setup") }
    }
}
