package dev.callstream.probe.shell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileTest {
    private val setup = JSONObject().put("id", "northwind").put("name", "NORTHWIND")
    private val config = JSONObject().put("gemini_api_key", "k").put("default_profile", "northwind").put("echo_gate", false).put("client", setup)
        .put("profiles", JSONObject()
            .put("northwind", JSONObject().put("system_prompt", "old prompt").put("gemini_voice", "Leda").put("vad_silence_ms", 200))
            .put("northwind-devanagari", JSONObject().put("system_prompt", "dev prompt").put("gemini_voice", "Leda")))

    private fun file(client: JSONObject = setup, profiles: JSONObject = JSONObject().put("northwind",
        JSONObject().put("system_prompt", "new prompt!").put("gemini_voice", "Kore").put("vad_silence_ms", 300).put("future_knob", 1))) =
        sign(JSONObject().put("client", client).put("profiles", profiles).toString())

    /** A throwaway signing key for these tests; the app's real keys are in AgentFile.PUBLIC_KEYS. */
    private val keys = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val trusted = listOf(java.util.Base64.getEncoder().encodeToString(keys.public.encoded))
    private fun sign(content: String, version: Int = 3): String {
        val sig = java.security.Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(content.toByteArray()); sign() }
        return JSONObject().put("format", "callstream-agent").put("version", version).put("signed", content)
            .put("signature", java.util.Base64.getEncoder().encodeToString(sig)).toString()
    }
    private fun parse(text: String) = AgentFile.parse(text, trusted)

    @Test fun rejectsWhatIsNotAUsableAgentFile() {
        assertEquals("This isn't a Call Stream agent file.", assertThrows(IllegalArgumentException::class.java) { parse("%PDF-1.7") }.message)
        val noClient = sign(JSONObject().put("profiles", JSONObject().put("x", JSONObject().put("system_prompt", "p"))).toString())
        assertTrue(assertThrows(IllegalArgumentException::class.java) { parse(noClient) }.message!!.contains("who it's for"))
        val older = JSONObject(file()).put("version", 2).toString()
        assertTrue(assertThrows(IllegalArgumentException::class.java) { parse(older) }.message!!.contains("older"))
        val empty = JSONObject().put("northwind", JSONObject().put("system_prompt", " "))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { parse(file(profiles = empty)) }.message!!.contains("no instructions"))
        val badVoice = JSONObject().put("northwind", JSONObject().put("system_prompt", "p").put("gemini_voice", "Nobody"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { parse(file(profiles = badVoice)) }.message!!.contains("unknown voice"))
        val newer = JSONObject(file()).put("version", 9).toString()
        assertTrue(assertThrows(IllegalArgumentException::class.java) { parse(newer) }.message!!.contains("newer"))
    }

    @Test fun onlyFilesSignedByATrustedKeyAreTaken() {
        val good = file()
        parse(good) // signed by the trusted test key
        val tampered = JSONObject(good).let { it.put("signed", it.getString("signed").replace("new prompt!", "evil prompt")) }.toString()
        assertTrue(assertThrows(IllegalArgumentException::class.java) { parse(tampered) }.message!!.contains("changed after it was made"))
        val unsigned = JSONObject(good).apply { remove("signature") }.toString()
        assertThrows(IllegalArgumentException::class.java) { parse(unsigned) }
        // the app's real key didn't sign this one
        assertThrows(IllegalArgumentException::class.java) { AgentFile.parse(good) }
        assertEquals(1, AgentFile.PUBLIC_KEYS.size)
    }

    @Test fun unknownSettingsAreSkippedAndListed() {
        val parsed = parse(file())
        assertEquals(listOf("future_knob"), parsed.ignored)
        assertFalse(parsed.profiles.getJSONObject("northwind").has("future_knob"))
    }

    @Test fun previewSaysWhatChanges() {
        val p = AgentFile.preview(parse(file()), config).getJSONArray("profiles").getJSONObject(0)
        assertEquals("northwind", p.getString("name")); assertFalse(p.getBoolean("new"))
        assertTrue(p.getBoolean("prompt_changed")); assertEquals(11, p.getInt("prompt_chars")); assertEquals(10, p.getInt("prev_prompt_chars"))
        assertEquals("Kore", p.getString("voice")); assertEquals("Leda", p.getString("prev_voice"))
        assertEquals("vad_silence_ms", p.getJSONArray("other_changes").getString(0))
    }

    @Test fun applyReplacesOnlyTheFilesProfilesAndKeepsTheRest() {
        val out = AgentFile.apply(parse(file()), config, "2026-10-22 14:02")
        assertEquals("new prompt!", out.getJSONObject("profiles").getJSONObject("northwind").getString("system_prompt"))
        assertEquals("dev prompt", out.getJSONObject("profiles").getJSONObject("northwind-devanagari").getString("system_prompt"))
        assertEquals("k", out.getString("gemini_api_key")); assertEquals(false, out.getBoolean("echo_gate"))
        assertEquals("file", out.getJSONObject(AgentFile.CHANGES).getJSONObject("northwind").getString("source"))
        // the call itself never sees the bookkeeping
        assertFalse(AgentConfig.resolve(out, null).has(AgentFile.CHANGES))
        assertEquals("new prompt!", AgentConfig.resolve(out, null).getString("system_prompt"))
    }

    @Test fun aNewProfileIsAdded() {
        val extra = JSONObject().put("northwind-eid", JSONObject().put("system_prompt", "eid offer"))
        val out = AgentFile.apply(parse(file(profiles = extra)), config, "t")
        assertEquals(3, out.getJSONObject("profiles").length())
        assertTrue(AgentFile.preview(parse(file(profiles = extra)), config).getJSONArray("profiles").getJSONObject(0).getBoolean("new"))
    }

    @Test fun editRecordsTheChange() {
        val edited = AgentFile.editPrompt(config, "northwind", "fixed number", "t")
        assertEquals("fixed number", edited.getJSONObject("profiles").getJSONObject("northwind").getString("system_prompt"))
        assertEquals("edit", edited.getJSONObject(AgentFile.CHANGES).getJSONObject("northwind").getString("source"))
        assertThrows(IllegalArgumentException::class.java) { AgentFile.editPrompt(config, "northwind", "  ", "t") }
    }

    @Test fun aFileForAnotherClientSwitchesThePhone() {
        val other = JSONObject().put("id", "acme").put("name", "Acme").put("lead_fields", org.json.JSONArray().put(JSONObject().put("key", "budget")))
        val f = parse(file(client = other, profiles = JSONObject().put("acme", JSONObject().put("system_prompt", "acme prompt"))))
        val preview = AgentFile.preview(f, config)
        assertEquals("NORTHWIND", preview.getString("switch_from")); assertEquals(2, preview.getInt("removes"))
        val out = AgentFile.apply(f, config, "t")
        assertEquals(setOf("acme"), out.getJSONObject("profiles").keys().asSequence().toSet())
        assertEquals("acme", out.getJSONObject("client").getString("id")); assertEquals("acme", out.getString("default_profile"))
        assertEquals("k", out.getString("gemini_api_key"))
    }

    @Test fun aFirstFileSetsThePhoneUp() {
        val fresh = JSONObject().put("gemini_api_key", "k")
        val preview = AgentFile.preview(parse(file()), fresh)
        assertTrue(preview.getBoolean("first_setup")); assertTrue(preview.isNull("switch_from"))
        assertEquals("northwind", AgentFile.apply(parse(file()), fresh, "t").getJSONObject("client").getString("id"))
    }

    @Test fun undoNeverBringsBackAnOldKey() {
        val old = JSONObject(config.toString()).put("gemini_api_key", "old-key")
        assertEquals("k", AgentConfig.withKeyOf(old, config).getString("gemini_api_key"))
        assertFalse(AgentConfig.withKeyOf(old, JSONObject()).has("gemini_api_key"))
    }

    @Test fun optionsReportChangesAndUndo() {
        val changed = AgentFile.apply(parse(file()), config, "2026-10-22 14:02")
        val o = JSONObject(AgentConfig.options(changed, null, null, JSONObject().put("at", "2026-10-22 14:02").put("what", "Update from file: Northwind").put("config", config)))
        assertEquals("file", o.getJSONObject("changes").getJSONObject("northwind").getString("source"))
        assertEquals("Update from file: Northwind", o.getJSONObject("undo").getString("what"))
        assertFalse(o.toString().contains("old prompt")) // the app gets the summary, not the saved config
    }
}
