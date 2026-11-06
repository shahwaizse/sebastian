package dev.callstream.probe.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneSetupTest {
    private val app = dev.callstream.probe.BuildConfig.APPLICATION_ID

    @Test fun grantsOnlyWhatIsMissingAndReportsBootStart() {
        val granted = mutableSetOf("android.permission.RECORD_AUDIO" to app)
        val asked = mutableListOf<Pair<String, String>>()
        val s = PhoneSetup.ensure(34, usbDebugging = true, wirelessDebugging = false,
            granted = { p, pkg -> (p to pkg) in granted }, grant = { p, pkg -> asked += p to pkg; granted += p to pkg; null })
        // WRITE_SECURE_SETTINGS goes to the app itself now (it switches Wireless debugging on after a restart)
        assertEquals(listOf("android.permission.READ_PHONE_STATE" to app, "android.permission.POST_NOTIFICATIONS" to app,
            "android.permission.WRITE_SECURE_SETTINGS" to app), asked)
        assertEquals(0, s.getJSONArray("missing_permissions").length()); assertEquals("ready", s.getString("boot_start"))
        assertEquals(true, s.getBoolean("usb_debugging"))
    }

    @Test fun aRefusedGrantIsReportedWithItsError() {
        val s = PhoneSetup.ensure(34, usbDebugging = false, wirelessDebugging = true,
            granted = { _, _ -> false }, grant = { _, _ -> "Operation not allowed" })
        assertEquals("RECORD_AUDIO", s.getJSONArray("missing_permissions").getString(0))
        assertEquals("missing", s.getString("boot_start"))
        assertEquals(false, s.getBoolean("usb_debugging"))
        assertEquals("android.permission.RECORD_AUDIO: Operation not allowed", s.getJSONArray("errors").getString(0))
    }

    @Test fun android12HasNoNotificationPermission() {
        val asked = mutableListOf<String>()
        val s = PhoneSetup.ensure(31, usbDebugging = true, wirelessDebugging = false,
            granted = { _, _ -> false }, grant = { p, _ -> asked += p; null })
        assertEquals(listOf("android.permission.RECORD_AUDIO", "android.permission.READ_PHONE_STATE",
            "android.permission.WRITE_SECURE_SETTINGS"), asked)
        assertEquals("missing", s.getString("boot_start")) // the fake never really grants
    }
}
