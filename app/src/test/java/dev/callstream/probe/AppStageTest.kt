package dev.callstream.probe

import dev.callstream.probe.leads.ClientSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStageTest {
    private val agent = AgentOptions(listOf("acme" to ""), emptyMap(), emptyList(), "acme", "Leda", "", "app", "1234")
    private val granted = SetupStatus(emptyList(), "ready", "google", emptyList())

    private fun state(adb: AdbState, helperBound: Boolean, connecting: Boolean, agent: AgentOptions, setup: SetupStatus?,
                      stream: String = "enabled=false\nOFF", busy: Boolean = false) =
        UiState(ClientSpec.NONE, "0", helperBound, StreamView.parse(stream), null, busy, agent,
            setupSteps(adb, helperBound, setup), LeadsPage.EMPTY, LeadQuery(), OutboundState.IDLE, false, null, null, null, null,
            Diagnostics(null, "NORMAL", "", ""), adb = adb, connecting = connecting, setupChecked = setup != null)

    @Test fun eachStageFromWhatThePhoneReports() {
        val paired = AdbState(paired = true, canWriteSettings = true)
        assertEquals(AppStage.SETUP, state(AdbState(), false, false, AgentOptions.NONE, null).stage)
        // a set-up phone opening the app: connecting, not "something broke"
        assertEquals(AppStage.CONNECTING, state(paired, false, false, AgentOptions.NONE, null).stage)
        assertEquals(AppStage.CONNECTING, state(paired.copy(connectError = "No Wi-Fi"), false, true, AgentOptions.NONE, null).stage)
        assertEquals(AppStage.OFFLINE, state(paired.copy(connectError = "No Wi-Fi"), false, false, AgentOptions.NONE, null).stage)
        assertEquals(AppStage.LOADING, state(paired, true, false, AgentOptions.NONE, null).stage)
        assertEquals(AppStage.LOADING, state(paired, true, false, agent, null).stage) // permissions not checked yet
        assertEquals(AppStage.INCOMPLETE, state(paired.copy(usbDebugging = null), true, false, agent, granted.copy(usbDebugging = false)).stage)
        assertEquals(AppStage.READY, state(paired, true, false, agent, granted).stage)
    }

    @Test fun anAgentReadingThatFailedIsNotLoaded() {
        assertEquals(false, AgentOptions.parse("not json").loaded)
        assertEquals(true, AgentOptions.parse("""{"profiles":[],"voices":[],"key":{"source":"none"}}""").loaded)
    }

    @Test fun theGateNamesTheNextStep() {
        val steps = setupSteps(AdbState(), false, null)
        assertEquals("Finish setting up this phone to turn the agent on. Next step: Turn on Developer options.", setupGateText("turn the agent on", steps, AppStage.SETUP))
        val noUsb = setupSteps(AdbState(paired = true, canWriteSettings = true, usbDebugging = null), true, granted.copy(usbDebugging = false))
        assertTrue(setupGateText("turn the agent on", noUsb, AppStage.INCOMPLETE).endsWith("Next step: Turn on USB debugging."))
    }

    @Test fun offlineSaysWhy() {
        val s = state(AdbState(paired = true, connectError = "Connect to Wi-Fi."), false, false, AgentOptions.NONE, null)
        assertEquals(HeroText("Not connected", "Connect to Wi-Fi."), heroText(s.stage, s.stream, "Sebastian", true, s.adb.connectError))
    }
}
