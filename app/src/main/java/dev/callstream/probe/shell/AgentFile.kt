package dev.callstream.probe.shell

import dev.callstream.probe.leads.ClientSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * How a client's setup reaches a phone without the laptop. `bridge/provision_phone.py --export` writes a `.callstream`
 * file: the client block (who the phone works for, their lead sheet columns, interested rule; see ClientSpec) and their
 * agent profiles (prompt, voice, settings); never the key. It is signed with the Call Stream key on the developer's
 * laptop (~/.callstream/agent-file-key.pem), and the app only takes files a key in [PUBLIC_KEYS] verifies: only we can
 * make agent files, so a file is also the client's licence to use the app. The client opens it and the app shows [preview] before
 * [apply]. The APK itself carries no agent: a new phone gets its first agent from a file. The app's hidden prompt
 * editor ([editPrompt]) changes the same config. Each change is recorded per profile under "agent_changes", and the
 * config before it is kept for one Undo (see AgentConfig).
 */
internal object AgentFile {
    const val FORMAT = "callstream-agent"
    const val VERSION = 3
    const val CHANGES = "agent_changes"
    private const val MAX_PROMPT = 60_000

    /**
     * Public halves of the keys that sign agent files (ECDSA P-256, X.509 DER, base64). To rotate: add the new key here,
     * ship that build, sign new files with it, and drop the old key once every client has a new file.
     */
    val PUBLIC_KEYS = listOf(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2RFz0qhpjPwqz4SOq4JvUzlNyy3Qow3hp1dN8PVOvLFRJb/CeBHeQcS7/NYvltxcOu9F4INcFXjjFIKrdJtm+w==",
    )

    /** On-phone profile keys a file may set (provision_phone.py PROFILE_KEYS, plus the description). */
    val PROFILE_KEYS = setOf(
        "description", "system_prompt", "gemini_voice", "gemini_model", "thinking_budget", "temperature", "vad_silence_ms",
        "vad_start_sensitivity", "vad_end_sensitivity", "vad_prefix_padding_ms", "kickoff_text", "affective_dialog", "kickoff_mode",
        "api_version", "extract_model", "transcription_language", "activity_handling", "turn_coverage", "turn_detection",
    )

    /** A checked file: its client, its profiles (known keys only) and the keys this app version ignores. */
    class Parsed(val client: ClientSpec, val profiles: JSONObject, val ignored: List<String>)

    /**
     * The file is `{"format", "version", "signed": "<the content, as JSON text>", "signature": "<base64 DER>"}`; the
     * signature covers the exact UTF-8 bytes of "signed". Throws with a message a client can read when the file isn't a
     * usable agent file.
     */
    fun parse(text: String, trusted: List<String> = PUBLIC_KEYS): Parsed {
        val envelope = runCatching { JSONObject(text.trim().removePrefix("\uFEFF")) }.getOrNull()
        require(envelope != null && envelope.optString("format") == FORMAT) { "This isn't a Call Stream agent file." }
        val version = envelope.optInt("version", 0)
        require(version <= VERSION) { "This agent file is from a newer Call Stream. Update the app first." }
        require(version == VERSION) { "This agent file is from an older Call Stream. Ask for a new one." }
        val signed = envelope.optString("signed")
        require(verified(signed, envelope.optString("signature"), trusted)) {
            "This agent file isn't from Call Stream, or it was changed after it was made. Ask for a new one."
        }
        val o = runCatching { JSONObject(signed) }.getOrElse { throw IllegalArgumentException("This agent file is damaged.") }
        val client = runCatching { ClientSpec.fromJson(o.optJSONObject("client")) }
            .getOrElse { throw IllegalArgumentException("This agent file's setup is damaged: ${it.message}") }
        require(client.id.isNotEmpty()) { "This agent file doesn't say who it's for." }
        val source = o.optJSONObject("profiles")
        require(source != null && source.length() > 0) { "This agent file has no agents in it." }
        val profiles = JSONObject()
        val ignored = sortedSetOf<String>()
        for (name in source.keys()) {
            val p = source.optJSONObject(name) ?: throw IllegalArgumentException("Agent \"$name\" in this file is damaged.")
            val prompt = p.optString("system_prompt")
            require(prompt.isNotBlank()) { "Agent \"$name\" in this file has no instructions." }
            require(prompt.length <= MAX_PROMPT) { "Agent \"$name\" has instructions longer than $MAX_PROMPT characters." }
            p.optString("gemini_voice").takeIf { it.isNotEmpty() }?.let { voice ->
                require(AgentConfig.VOICES.any { it.first == voice }) { "Agent \"$name\" asks for an unknown voice, $voice." }
            }
            val clean = JSONObject()
            for (key in p.keys()) if (key in PROFILE_KEYS) clean.put(key, p.get(key)) else ignored += key
            profiles.put(name, clean)
        }
        return Parsed(client, profiles, ignored.toList())
    }

    private fun verified(signed: String, signature: String, trusted: List<String>): Boolean {
        if (signed.isEmpty() || signature.isEmpty()) return false
        val sig = runCatching { java.util.Base64.getDecoder().decode(signature) }.getOrNull() ?: return false
        return trusted.any { key ->
            runCatching {
                val publicKey = java.security.KeyFactory.getInstance("EC")
                    .generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(key)))
                java.security.Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey); update(signed.toByteArray(Charsets.UTF_8)); verify(sig)
                }
            }.getOrDefault(false)
        }
    }

    /** The phone works for another client: applying replaces its agents instead of adding to them. */
    fun switches(file: Parsed, config: JSONObject): Boolean {
        val current = config.optJSONObject("client")?.optString("id").orEmpty()
        return current.isNotEmpty() && current != file.client.id
    }

    /** What applying [file] would change, per profile, for the confirmation sheet. */
    fun preview(file: Parsed, config: JSONObject): JSONObject {
        val switching = switches(file, config)
        val current = if (switching) JSONObject() else config.optJSONObject("profiles") ?: JSONObject()
        val items = JSONArray()
        for (name in file.profiles.keys()) {
            val next = file.profiles.getJSONObject(name)
            val prev = current.optJSONObject(name)
            val other = JSONArray()
            if (prev != null) for (key in next.keys()) {
                if (key in setOf("system_prompt", "gemini_voice", "description")) continue
                if (prev.opt(key)?.toString() != next.opt(key)?.toString()) other.put(key)
            }
            items.put(JSONObject().put("name", name).put("new", prev == null)
                .put("prompt_chars", next.optString("system_prompt").length)
                .put("prev_prompt_chars", prev?.optString("system_prompt")?.length ?: 0)
                .put("prompt_changed", prev?.optString("system_prompt") != next.optString("system_prompt"))
                .put("voice", next.optString("gemini_voice", prev?.optString("gemini_voice").orEmpty()))
                .put("prev_voice", prev?.optString("gemini_voice").orEmpty())
                .put("other_changes", other))
        }
        val was = config.optJSONObject("client")
        val removed = if (switching) config.optJSONObject("profiles")?.length() ?: 0 else 0
        return JSONObject().put("client", file.client.id).put("client_name", file.client.title).put("profiles", items)
            .put("ignored", JSONArray(file.ignored))
            .put("switch_from", if (switching) was?.optString("name")?.ifEmpty { was.optString("id") } else null)
            .put("removes", removed)
            .put("first_setup", was == null || was.optString("id").isEmpty())
            .put("setup_changed", was == null || was.toString() != file.client.source.toString())
    }

    /** The config with the file's client and profiles in place (same name replaced, new ones added; all replaced when
     * the phone switches clients); everything else kept. */
    fun apply(file: Parsed, config: JSONObject, at: String): JSONObject {
        val out = JSONObject(config.toString())
        if (switches(file, config)) { out.remove("profiles"); out.remove(CHANGES) }
        out.put("client", JSONObject(file.client.source.toString()))
        val profiles = out.optJSONObject("profiles") ?: JSONObject().also { out.put("profiles", it) }
        for (name in file.profiles.keys()) {
            profiles.put(name, JSONObject(file.profiles.getJSONObject(name).toString()))
            record(out, name, "file", at)
        }
        if (!profiles.has(out.optString("default_profile"))) out.put("default_profile", file.profiles.keys().next())
        return out
    }

    fun editPrompt(config: JSONObject, profile: String, prompt: String, at: String): JSONObject {
        require(prompt.isNotBlank()) { "The instructions can't be empty." }
        require(prompt.length <= MAX_PROMPT) { "The instructions are longer than $MAX_PROMPT characters." }
        val out = JSONObject(config.toString())
        val p = out.optJSONObject("profiles")?.optJSONObject(profile) ?: throw IllegalArgumentException("Unknown agent $profile")
        p.put("system_prompt", prompt)
        record(out, profile, "edit", at)
        return out
    }

    private fun record(config: JSONObject, profile: String, source: String, at: String) {
        val changes = config.optJSONObject(CHANGES) ?: JSONObject().also { config.put(CHANGES, it) }
        changes.put(profile, JSONObject().put("source", source).put("at", at))
    }
}
