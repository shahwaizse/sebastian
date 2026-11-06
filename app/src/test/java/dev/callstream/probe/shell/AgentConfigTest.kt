package dev.callstream.probe.shell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentConfigTest {
    private val config = JSONObject().put("gemini_api_key", "k").put("echo_gate", true).put("default_profile", "acme")
        .put("profiles", JSONObject()
            .put("acme", JSONObject().put("description", "d").put("system_prompt", "p1").put("gemini_voice", "Leda"))
            .put("fbi-prank", JSONObject().put("system_prompt", "p2").put("gemini_voice", "Orus").put("vad_silence_ms", 400)))

    @Test fun noPickRunsTheDefaultProfile() {
        val c = AgentConfig.resolve(config, null)
        assertEquals("acme", c.getString("profile")); assertEquals("p1", c.getString("system_prompt"))
        assertEquals("Leda", c.getString("gemini_voice")); assertEquals(true, c.getBoolean("echo_gate"))
        assertFalse(c.has("profiles")); assertFalse(c.has("description"))
    }

    @Test fun pickedProfileAndVoiceOverrideApply() {
        val c = AgentConfig.resolve(config, JSONObject().put("profile", "fbi-prank").put("voice", "Charon"))
        assertEquals("p2", c.getString("system_prompt")); assertEquals("Charon", c.getString("gemini_voice"))
        assertEquals(400, c.getInt("vad_silence_ms"))
    }

    @Test fun staleOrUnknownPicksFallBack() {
        val c = AgentConfig.resolve(config, JSONObject().put("profile", "gone").put("voice", "NotAVoice"))
        assertEquals("acme", c.getString("profile")); assertEquals("Leda", c.getString("gemini_voice"))
    }

    @Test fun configFromBeforeProfilesIsUsedAsIs() {
        val old = JSONObject().put("system_prompt", "p").put("gemini_voice", "Leda")
        assertEquals("p", AgentConfig.resolve(old, JSONObject().put("voice", "Orus")).getString("system_prompt"))
    }

    @Test fun optionsListProfilesVoicesAndThePick() {
        val o = JSONObject(AgentConfig.options(config, JSONObject().put("profile", "fbi-prank").put("voice", "")))
        assertEquals(2, o.getJSONArray("profiles").length()); assertEquals(30, o.getJSONArray("voices").length())
        assertEquals("fbi-prank", o.getString("profile")); assertEquals("Orus", o.getString("voice"))
    }

    @Test fun keyEnteredInTheAppWinsOverTheProvisionedOne() {
        assertEquals("from-app", AgentConfig.resolve(config, null, "from-app").getString("gemini_api_key"))
        assertEquals("k", AgentConfig.resolve(config, null, null).getString("gemini_api_key"))
        assertEquals("k", AgentConfig.resolve(config, null, " ").getString("gemini_api_key"))
    }

    @Test fun keyStatusNamesTheSourceAndNeverTheWholeKey() {
        val app = AgentConfig.keyStatus(config, "AIzaSyExampleExampleExample1234")
        assertEquals("app", app.getString("source")); assertEquals("1234", app.getString("hint"))
        assertEquals("provisioned", AgentConfig.keyStatus(config, null).getString("source"))
        assertEquals("", AgentConfig.keyStatus(config, null).getString("hint")) // too short to show any of it
        assertEquals("none", AgentConfig.keyStatus(JSONObject(), null).getString("source"))
        val o = JSONObject(AgentConfig.options(config, null, "AIzaSyExampleExampleExample1234"))
        assertFalse(o.toString().contains("AIzaSyExample"))
    }
}
