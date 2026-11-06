package dev.callstream.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStepsTest {
    private val granted = SetupStatus(emptyList(), "ready", "samsung", emptyList())
    private val newPhone = AdbState()
    private val paired = AdbState(paired = true, canWriteSettings = true)

    private fun incomplete(steps: List<SetupStep>) = steps.filter { it.required && it.state != StepState.DONE }.map { it.title }

    @Test fun freshPhoneStartsWithDeveloperOptionsAndLaterStepsWait() {
        val steps = setupSteps(newPhone, false, null)
        assertEquals(listOf("Turn on Developer options", "Turn on USB debugging", "Turn on Wireless debugging", "Pair Sebastian"),
            steps.takeWhile { it.state == StepState.TODO }.map { it.title })
        assertTrue(steps.drop(4).all { it.state == StepState.WAITING })
        assertEquals(StepAction.OPEN_ABOUT_PHONE, steps[0].action)
    }

    @Test fun switchesThePhoneReportsOnAreTicked() {
        val steps = setupSteps(AdbState(developerOptions = true, usbDebugging = true, wirelessDebugging = true), false, null)
        assertEquals("Pair Sebastian", nextSetupStep(steps)!!.title)
        assertEquals(StepAction.PAIR, nextSetupStep(steps)!!.action)
        assertTrue(nextSetupStep(steps)!!.showGuide)
    }

    /** Android 17 tells apps 0 for Developer options and USB debugging: unknown is a step with a way past it. */
    @Test fun switchesThePhoneHidesSayAlreadyOn() {
        val steps = setupSteps(AdbState(developerOptions = null, usbDebugging = null), false, null)
        assertTrue(steps[0].detail.contains("Already on?") && steps[1].detail.contains("Already on?"))
        val off = setupSteps(AdbState(developerOptions = false, usbDebugging = false), false, null)
        assertTrue(off.none { it.detail.contains("Already on?") })
        assertEquals(null, switchReading(0, 37)); assertEquals(false, switchReading(0, 36)); assertEquals(true, switchReading(1, 37))
    }

    @Test fun wirelessDebuggingWithoutWifiSaysSo() {
        val step = setupSteps(AdbState(onWifi = false), false, null).first { it.title == "Turn on Wireless debugging" }
        assertTrue(step.detail.startsWith("Connect to Wi-Fi first"))
    }

    @Test fun pairedButNotRunningShowsWhyUnderConnect() {
        val steps = setupSteps(paired.copy(connectError = "Connect to Wi-Fi. Sebastian needs Wi-Fi to start (only to start)."), false, null)
        val next = nextSetupStep(steps)!!
        assertEquals("Connect to the phone helper", next.title)
        assertEquals(StepAction.RECONNECT, next.action)
        assertTrue(next.detail.startsWith("Connect to Wi-Fi"))
        // Wireless debugging off after a restart isn't the person's job once the app may switch it on itself
        assertEquals(StepState.DONE, steps.first { it.title == "Turn on Wireless debugging" }.state)
    }

    /** Being off is a choice, not unfinished setup: the switch is the orb, not a step. */
    @Test fun aPhoneThatIsOffIsStillSetUp() {
        val steps = setupSteps(paired, true, granted)
        assertEquals(null, nextSetupStep(steps))
    }

    @Test fun everythingDoneIsComplete() {
        assertEquals(emptyList<String>(), incomplete(setupSteps(paired, true, granted)))
    }

    @Test fun helperReadingOfUsbDebuggingWins() {
        val steps = setupSteps(paired.copy(usbDebugging = null), true, granted.copy(usbDebugging = false))
        assertEquals(listOf("Turn on USB debugging"), incomplete(steps))
        assertEquals(true, SetupStatus.parse("""{"missing_permissions":[],"boot_start":"ready","manufacturer":"google","errors":[],"usb_debugging":true}""")!!.usbDebugging)
    }

    @Test fun refusedPermissionOffersCheckAgain() {
        val refused = granted.copy(missingPermissions = listOf("RECORD_AUDIO"), errors = listOf("RECORD_AUDIO: denied"))
        val step = setupSteps(paired, true, refused).first { it.title == "Phone permissions" }
        assertEquals(StepState.TODO, step.state); assertEquals(StepAction.CHECK_AGAIN, step.action)
    }

    @Test fun xiaomiAndHonorGetAdviceThatDoesNotBlockSetup() {
        val xiaomi = setupSteps(paired, true, granted.copy(manufacturer = "xiaomi"))
        assertTrue(xiaomi.any { it.title == "Xiaomi battery settings" && !it.required })
        assertEquals(emptyList<String>(), incomplete(xiaomi))
        val honor = setupSteps(paired, true, granted.copy(manufacturer = "honor"))
        assertTrue(honor.any { it.title.startsWith("Honor") && it.detail.contains("Manage manually") && !it.required })
    }

    @Test fun restartStepFollowsTheHelpersGrant() {
        assertEquals(StepState.DONE, setupSteps(paired, true, granted).first { it.title == "Starts again after a restart" }.state)
        val missing = setupSteps(paired, true, granted.copy(bootStart = "missing")).first { it.title == "Starts again after a restart" }
        assertEquals(StepState.TODO, missing.state); assertEquals(false, missing.required)
    }

    @Test fun setupGuidePairsCallStreamItself() {
        val steps = setupGuide()
        assertEquals(listOf("Turn on Developer options", "Turn on USB debugging", "Turn on Wireless debugging", "Tap Pair in Sebastian",
            "Get a pairing code", "Type the code into the notification", "That's all"), steps.map { it.title })
        assertTrue(steps.all { it.link == null || it.linkLabel.isNotEmpty() })
        assertTrue(steps.none { "Shizuku" in it.title || "Shizuku" in it.body })
    }

    @Test fun phoneFamiliesFromMakerOrBrand() {
        assertEquals(PhoneFamily.XIAOMI, phoneFamily("Xiaomi", "Redmi"))
        assertEquals(PhoneFamily.XIAOMI, phoneFamily("Xiaomi", "POCO"))
        assertEquals(PhoneFamily.COLOROS, phoneFamily("OnePlus", "OnePlus"))
        assertEquals(PhoneFamily.COLOROS, phoneFamily("realme", "realme"))
        assertEquals(PhoneFamily.COLOROS, phoneFamily("OPPO", "OPPO"))
        assertEquals(PhoneFamily.MEIZU, phoneFamily("Meizu", "meizu"))
        assertEquals(PhoneFamily.HUAWEI, phoneFamily("HONOR", "HONOR"))
        assertEquals(PhoneFamily.OTHER, phoneFamily("Google", "google"))
        assertEquals(PhoneFamily.OTHER, phoneFamily("samsung", "samsung"))
    }

    @Test fun eachLimitingBrandGetsItsSettingRightAfterDeveloperOptions() {
        fun second(f: PhoneFamily) = setupGuide(f)[1].title
        assertEquals("Turn on USB debugging (Security settings)", second(PhoneFamily.XIAOMI))
        assertEquals("Turn off permission monitoring", second(PhoneFamily.COLOROS))
        assertEquals("Turn off Flyme payment protection", second(PhoneFamily.MEIZU))
        assertEquals("Turn on USB debugging", second(PhoneFamily.OTHER))
        assertEquals("Turn on USB debugging", second(PhoneFamily.HUAWEI))
        assertTrue(setupGuide(PhoneFamily.HUAWEI)[1].body.contains("charge only"))
        assertTrue(setupGuide(PhoneFamily.HUAWEI).last().body.contains("App launch"))
        assertTrue(setupGuide(PhoneFamily.COLOROS)[1].body.contains("Disable system optimization"))
        assertEquals(3, setupGuide(PhoneFamily.XIAOMI)[1].images.size)
    }

    @Test fun refusedGrantsOnALimitingBrandPointAtItsSetting() {
        val refused = SetupStatus(listOf("RECORD_AUDIO"), "missing", "xiaomi",
            listOf("android.permission.RECORD_AUDIO: java.lang.SecurityException: grantRuntimePermission: Neither user 2000 nor current process has android.permission.GRANT_RUNTIME_PERMISSIONS."))
        val step = setupSteps(paired, true, refused, PhoneFamily.XIAOMI).first { it.title == "Phone permissions" }
        assertTrue(step.detail.contains("Xiaomi phone is blocking Sebastian") && step.showGuide)
    }

    @Test fun guideLinksHighlightTheirRow() {
        assertEquals("build_number", SettingKeys.forLink(GuideLink.ABOUT_PHONE, PhoneFamily.OTHER))
        assertEquals("enable_adb", SettingKeys.forLink(GuideLink.USB_DEBUGGING, PhoneFamily.OTHER))
        assertEquals("toggle_adb_wireless", SettingKeys.forLink(GuideLink.WIRELESS_DEBUGGING, PhoneFamily.XIAOMI))
        // brand rows wait for keys read from a real phone (bridge/settings_keys.py)
        assertEquals(null, SettingKeys.forLink(GuideLink.DEVELOPER_OPTIONS, PhoneFamily.XIAOMI))
        val steps = setupGuide()
        assertEquals(GuideLink.USB_DEBUGGING, steps.first { it.title == "Turn on USB debugging" }.link)
        assertEquals(GuideLink.WIRELESS_DEBUGGING, steps.first { it.title == "Turn on Wireless debugging" }.link)
    }

    @Test fun xiaomiGetsARealStepForItsSecuritySwitch() {
        val title = "Turn on USB debugging (Security settings)"
        // off, or unknown: a step to do first, with the guide
        val off = setupSteps(paired, true, granted, PhoneFamily.XIAOMI, xiaomiAdbInput = false)
        assertEquals(title, off.first().title); assertEquals(StepState.TODO, off.first().state); assertTrue(off.first().showGuide)
        assertEquals(title, nextSetupStep(off)!!.title)
        assertEquals(StepState.TODO, setupSteps(newPhone, false, null, PhoneFamily.XIAOMI).first().state)
        // on (as the app reads it before the helper runs): ticked, and setup can complete
        val on = setupSteps(paired, true, granted, PhoneFamily.XIAOMI, xiaomiAdbInput = true)
        assertEquals(StepState.DONE, on.first().state); assertEquals(null, nextSetupStep(on))
        // the helper's reading wins over the app's
        val helperSaysOff = setupSteps(paired, true, granted.copy(xiaomiAdbInput = false), PhoneFamily.XIAOMI, xiaomiAdbInput = true)
        assertEquals(StepState.TODO, helperSaysOff.first().state)
        // other phones never see it
        assertTrue(setupSteps(paired, true, granted, PhoneFamily.COLOROS).none { it.title == title })
    }

    @Test fun helperReportsXiaomisSwitchOnlyOnXiaomi() {
        assertEquals(true, SetupStatus.parse("""{"missing_permissions":[],"boot_start":"ready","manufacturer":"xiaomi","errors":[],"xiaomi_adb_input":true}""")!!.xiaomiAdbInput)
        assertEquals(null, SetupStatus.parse("""{"missing_permissions":[],"boot_start":"ready","manufacturer":"google","errors":[]}""")!!.xiaomiAdbInput)
    }
}
