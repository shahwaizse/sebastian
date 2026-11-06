package dev.callstream.probe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Where a guide step's button goes (MainActivity opens it). */
enum class GuideLink { ABOUT_PHONE, DEVELOPER_OPTIONS, USB_DEBUGGING, WIRELESS_DEBUGGING }

/**
 * Settings opens a page scrolled to one row, highlighted, when given that row's key (the ":settings:fragment_args_key"
 * extra Settings search uses). Keys are the Settings app's own: stock Android's checked on the Pixel 2026-10-23; makers
 * rename theirs, and an unknown key just opens the page at the top. Read a phone's keys with bridge/settings_keys.py.
 */
object SettingKeys {
    const val BUILD_NUMBER = "build_number"
    const val USB_DEBUGGING = "enable_adb"
    const val WIRELESS_DEBUGGING = "toggle_adb_wireless"

    /** The maker's own row for its step 2 (USB debugging (Security settings), Disable permission monitoring, …). Null
     * until read from a real phone of that maker: the link then opens Developer options at the top. */
    fun brand(family: PhoneFamily): String? = when (family) {
        PhoneFamily.XIAOMI -> null
        PhoneFamily.COLOROS -> null
        PhoneFamily.MEIZU, PhoneFamily.HUAWEI, PhoneFamily.OTHER -> null
    }

    /** The row a guide link highlights on this phone. */
    fun forLink(link: GuideLink, family: PhoneFamily): String? = when (link) {
        GuideLink.ABOUT_PHONE -> BUILD_NUMBER
        GuideLink.USB_DEBUGGING -> USB_DEBUGGING
        GuideLink.WIRELESS_DEBUGGING -> WIRELESS_DEBUGGING
        GuideLink.DEVELOPER_OPTIONS -> brand(family)
    }
}

/**
 * Phone makers that limit what adb may do until a setting of theirs is changed (Shizuku's guide,
 * https://shizuku.rikka.app/guide/setup/, "the permission of adb is limited"; it applies to Sebastian's own adb the
 * same way). Everyone else needs nothing extra.
 */
enum class PhoneFamily(val label: String) { XIAOMI("Xiaomi"), COLOROS("OPPO, OnePlus and realme"), MEIZU("Meizu"), HUAWEI("Huawei and Honor"), OTHER("") }

fun phoneFamily(manufacturer: String, brand: String): PhoneFamily {
    val names = setOf(manufacturer.lowercase(), brand.lowercase())
    return when {
        names.any { it in setOf("xiaomi", "redmi", "poco") } -> PhoneFamily.XIAOMI
        names.any { it in setOf("oppo", "oneplus", "realme") } -> PhoneFamily.COLOROS
        names.any { it == "meizu" } -> PhoneFamily.MEIZU
        names.any { it in setOf("huawei", "honor") } -> PhoneFamily.HUAWEI
        else -> PhoneFamily.OTHER
    }
}

/** The family's own step: the setting that lifts its limit. Pictures marked (web) are from public guides (README, "Guide images"). */
private fun familyStep(family: PhoneFamily): GuideStep? = when (family) {
    PhoneFamily.XIAOMI -> GuideStep("Turn on USB debugging (Security settings)",
        "Xiaomi, Redmi and POCO phones block Sebastian's setup until a second switch is on. Go to Settings → Additional " +
            "settings → Developer options and turn on USB debugging (Security settings). It's a different switch from plain " +
            "USB debugging. Turn on Install via USB too.\n\nIt needs you signed in to a Mi account, usually with a SIM card " +
            "in the phone. Each warning has a countdown: wait for it, tick the box if there is one, then tap OK or Accept.",
        listOf(R.drawable.guide_xiaomi_developer_options, R.drawable.guide_xiaomi_security_settings, R.drawable.guide_xiaomi_warning),
        GuideLink.DEVELOPER_OPTIONS, "Open Developer options")
    PhoneFamily.COLOROS -> GuideStep("Turn off permission monitoring",
        "OPPO, OnePlus and realme phones block Sebastian's setup through permission monitoring. In Developer options, turn on " +
            "Disable permission monitoring (some versions call it Permission monitoring: turn that off). Then restart the phone." +
            "\n\nColorOS 16 and newer only show it with the phone's language set to English, named Disable system optimization: " +
            "switch the language to English, turn it on, and switch back.",
        listOf(R.drawable.guide_oneplus_permission_monitoring), GuideLink.DEVELOPER_OPTIONS, "Open Developer options")
    PhoneFamily.MEIZU -> GuideStep("Turn off Flyme payment protection",
        "Meizu phones block Sebastian's setup through payment protection. In Developer options, turn off Flyme payment protection.",
        link = GuideLink.DEVELOPER_OPTIONS, linkLabel = "Open Developer options")
    PhoneFamily.HUAWEI, PhoneFamily.OTHER -> null
}

data class GuideStep(val title: String, val body: String, val images: List<Int> = emptyList(),
                     val link: GuideLink? = null, val linkLabel: String = "")

/**
 * Pairing Sebastian with the phone's own Wireless debugging, once, without a computer (adb/LocalAdb). Pictures are
 * from a Pixel (stock Android, the closest to every other skin; captured 2026-10-23 with private details blurred);
 * brand notes cover where Samsung and Xiaomi differ.
 */
fun setupGuide(family: PhoneFamily = PhoneFamily.OTHER): List<GuideStep> = listOfNotNull(
    GuideStep("Turn on Developer options",
        "Open About phone, scroll to the bottom and tap Build number seven times, until it says you are now a developer. " +
            "Done this before? Skip to the next step.\n\nSamsung: About phone → Software information → Build number. " +
            "Xiaomi, Redmi, Poco: About phone → tap the OS version (HyperOS or MIUI) seven times.",
        listOf(R.drawable.guide_build_number), GuideLink.ABOUT_PHONE, "Open About phone"),
    familyStep(family),
    GuideStep("Turn on USB debugging",
        "In Developer options, turn on USB debugging and confirm. No cable is needed: it keeps Sebastian running when Wi-Fi " +
            "drops. Some phones switch it off by themselves; Sebastian switches it back on while it runs." +
            if (family == PhoneFamily.HUAWEI) "\n\nHuawei and Honor: also turn on Allow ADB debugging in charge only mode." else "",
        link = GuideLink.USB_DEBUGGING, linkLabel = "Open Developer options"),
    GuideStep("Turn on Wireless debugging",
        "It's just below USB debugging. Turn it on and allow it on this Wi-Fi network when asked: choose Always allow on " +
            "this network. It only works on Wi-Fi.",
        listOf(R.drawable.guide_wireless_debugging), GuideLink.WIRELESS_DEBUGGING, "Open Wireless debugging"),
    GuideStep("Tap Pair in Sebastian",
        "Back in Sebastian's setup, tap Pair. A Pair Sebastian notification appears with a box for the code, and Call " +
            "Stream opens Developer options for you."),
    GuideStep("Get a pairing code",
        "Tap the words Wireless debugging (not just the switch) to open its page, then tap Pair device with pairing code. " +
            "Leave the six-digit code on the screen.",
        listOf(R.drawable.guide_pair_with_code, R.drawable.guide_pairing_dialog)),
    GuideStep("Type the code into the notification",
        "Pull down your notifications. On Pair Sebastian, tap Enter code, type the six digits and send. Sebastian pairs " +
            "and starts; the notification says Sebastian is connected. Then come back to Sebastian." +
            if (family == PhoneFamily.XIAOMI) "\n\nXiaomi: if there's no box to type in, set Settings → Notifications (& Control " +
                "center) → Notification style to Android, then tap Pair again." else "",
        listOf(R.drawable.guide_pairing_notification, R.drawable.guide_pairing_connected)),
    GuideStep("That's all",
        "You pair once. Once Sebastian is running it keeps running without Wi-Fi. After a restart it starts itself as soon " +
            "as the phone is on Wi-Fi, as long as Wireless debugging is allowed on that Wi-Fi." + when (family) {
                PhoneFamily.XIAOMI -> "\n\nXiaomi: don't run the scan in Xiaomi's Security app; it can turn these switches off."
                PhoneFamily.HUAWEI -> "\n\nHuawei and Honor: Settings → Battery → App launch → Sebastian → Manage manually, " +
                    "with Auto-launch on. Without it, open Sebastian once after every restart."
                else -> ""
            }),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupGuideSheet(family: PhoneFamily, onLink: (GuideLink) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        SetupGuideContent(family, onLink)
    }
}

/** The guide's content, apart so it can be rendered on its own (ScreenShots). */
@Composable
fun SetupGuideContent(family: PhoneFamily = PhoneFamily.OTHER, onLink: (GuideLink) -> Unit) {
    val steps = setupGuide(family)
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pair Sebastian", style = MaterialTheme.typography.headlineSmall)
                Text("One-time setup, about three minutes, no computer needed. Needs Wi-Fi. Pictures are from a Google Pixel unless a step is for your phone's brand; other phones use the same names.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(steps) { i, step -> StepCard(i + 1, step, onLink) }
    }
}

@Composable
private fun StepCard(number: Int, step: GuideStep, onLink: (GuideLink) -> Unit) {
    AppCard(padding = 16.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(30.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(step.title, style = MaterialTheme.typography.titleMedium)
        }
        Text(step.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        step.images.forEach { res ->
            Image(painterResource(res), null, Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .border(BorderStroke(1.dp, Brand.colors.cardBorder), MaterialTheme.shapes.medium), contentScale = ContentScale.FillWidth)
        }
        step.link?.let { link -> FilledTonalButton(onClick = { onLink(link) }, contentPadding = PaddingValues(horizontal = 20.dp)) { Text(step.linkLabel) } }
    }
}
