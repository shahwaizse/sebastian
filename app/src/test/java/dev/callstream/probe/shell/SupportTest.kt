package dev.callstream.probe.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTest {
    @Test fun longFilesKeepTheirNewestWholeLines() {
        val text = (1..1000).joinToString("\n") { "line $it" }
        val cut = Support.tail(text, 100)
        assertTrue(cut.startsWith("… (earlier lines cut)\n"))
        assertTrue(cut.endsWith("line 1000"))
        assertTrue(cut.lines().drop(1).all { it.startsWith("line ") }) // no half line at the cut
        assertEquals("short", Support.tail("short", 100))
    }
}
