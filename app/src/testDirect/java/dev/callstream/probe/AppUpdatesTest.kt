package dev.callstream.probe

import dev.callstream.probe.shell.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdatesTest {
    private fun entry(code: Int, name: String) =
        """{"version_code": $code, "version_name": "$name", "url": "https://get.shahwaiz.dev/callstream-$name.apk", "sha256": "${"a".repeat(64)}", "size": 3400000}"""
    private val both = """{"stable": ${entry(77, "0.19.0")}, "beta": ${entry(78, "0.20.0")}}"""

    @Test fun clientsFollowStableAndTestersTakeTheNewestOfBoth() {
        assertEquals("0.19.0", AppUpdates.pick(both, beta = false)!!.versionName)
        assertEquals("0.20.0", AppUpdates.pick(both, beta = true)!!.versionName)
        // a promoted stable newer than an old beta still reaches beta phones
        val promoted = """{"stable": ${entry(79, "0.21.0")}, "beta": ${entry(78, "0.20.0")}}"""
        assertEquals("0.21.0", AppUpdates.pick(promoted, beta = true)!!.versionName)
        assertNull(AppUpdates.pick("{}", beta = true))
    }

    @Test fun helperOnlyTakesOurHostAndAChecksum() {
        Updater.check("https://get.shahwaiz.dev/callstream-0.20.0.apk", "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { Updater.check("https://evil.example/callstream.apk", "a".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { Updater.check("https://get.shahwaiz.dev.evil.example/x.apk", "a".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { Updater.check("https://get.shahwaiz.dev/../x.apk", "a".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { Updater.check("https://get.shahwaiz.dev/x.apk", "") }
    }
}
