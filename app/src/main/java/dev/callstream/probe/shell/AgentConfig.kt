package dev.callstream.probe.shell

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Which agent a call gets. Provisioning writes every profile (prompt, voice, settings) into the on-phone
 * config; the app's dropdowns pick a profile and, optionally, a different voice. The pick is kept in its own
 * file so re-provisioning a prompt doesn't undo it, and it applies from the next call.
 *
 * The Gemini key can also be entered in the app's settings. It's kept in its own file too, and wins over the
 * provisioned key; bridge/provision_phone.py deletes it when it pushes a key, so whichever was set last is used.
 */
internal object AgentConfig {
    const val SELECTION_PATH = "/data/local/tmp/call-stream-selection.json"
    const val KEY_PATH = "/data/local/tmp/call-stream-key.json"
    /** The config before the last agent change (file, edit or reset), for one Undo: {"at", "what", "config"}. */
    const val PREV_PATH = "/data/local/tmp/call-stream-agent.prev.json"

    /** Gemini Live's prebuilt voices: Google's one-word style (ai.google.dev speech-generation) and gender (Google Cloud
     * Text-to-Speech lists the same voices as Chirp 3 HD with a gender), both checked 2026-10-14. Female first, then male. */
    val VOICES = listOf(
        Triple("Achernar", "Soft", "Female"), Triple("Aoede", "Breezy", "Female"), Triple("Autonoe", "Bright", "Female"),
        Triple("Callirrhoe", "Easy-going", "Female"), Triple("Despina", "Smooth", "Female"), Triple("Erinome", "Clear", "Female"),
        Triple("Gacrux", "Mature", "Female"), Triple("Kore", "Firm", "Female"), Triple("Laomedeia", "Upbeat", "Female"),
        Triple("Leda", "Youthful", "Female"), Triple("Pulcherrima", "Forward", "Female"), Triple("Sulafat", "Warm", "Female"),
        Triple("Vindemiatrix", "Gentle", "Female"), Triple("Zephyr", "Bright", "Female"),
        Triple("Achird", "Friendly", "Male"), Triple("Algenib", "Gravelly", "Male"), Triple("Algieba", "Smooth", "Male"),
        Triple("Alnilam", "Firm", "Male"), Triple("Charon", "Informative", "Male"), Triple("Enceladus", "Breathy", "Male"),
        Triple("Fenrir", "Excitable", "Male"), Triple("Iapetus", "Clear", "Male"), Triple("Orus", "Firm", "Male"),
        Triple("Puck", "Upbeat", "Male"), Triple("Rasalgethi", "Informative", "Male"), Triple("Sadachbia", "Lively", "Male"),
        Triple("Sadaltager", "Knowledgeable", "Male"), Triple("Schedar", "Even", "Male"), Triple("Umbriel", "Easy-going", "Male"),
        Triple("Zubenelgenubi", "Casual", "Male"),
    )

    /** The selected profile's name: the app's pick if it still exists, else the provisioned default. */
    fun selectedProfile(config: JSONObject, selection: JSONObject?): String? {
        val profiles = config.optJSONObject("profiles") ?: return null
        val picked = selection?.optString("profile").orEmpty()
        if (picked.isNotEmpty() && profiles.has(picked)) return picked
        val default = config.optString("default_profile")
        if (profiles.has(default)) return default
        return profiles.keys().asSequence().firstOrNull()
    }

    /** The settings one call runs with: shared settings, then the profile's, then the app's voice pick and key. */
    fun resolve(config: JSONObject, selection: JSONObject?, appKey: String? = null): JSONObject {
        val out = JSONObject(config.toString())
        out.remove("profiles"); out.remove("default_profile"); out.remove(AgentFile.CHANGES); out.remove("client")
        if (!appKey.isNullOrBlank()) out.put("gemini_api_key", appKey)
        val name = selectedProfile(config, selection) ?: return out // a config from before profiles
        val profile = config.getJSONObject("profiles").getJSONObject(name)
        for (key in profile.keys()) if (key != "description") out.put(key, profile.get(key))
        out.put("profile", name)
        val voice = selection?.optString("voice").orEmpty()
        if (voice.isNotEmpty() && VOICES.any { it.first == voice }) out.put("gemini_voice", voice)
        return out
    }

    /** For the app: profiles, voices, the current pick, and where the key comes from (never the key itself). */
    fun options(config: JSONObject, selection: JSONObject?, appKey: String? = null, undo: JSONObject? = null): String {
        val profiles = JSONArray()
        config.optJSONObject("profiles")?.let { all ->
            for (name in all.keys()) {
                val p = all.getJSONObject(name)
                profiles.put(JSONObject().put("name", name).put("description", p.optString("description"))
                    .put("voice", p.optString("gemini_voice", GeminiProtocol.DEFAULT_VOICE)))
            }
        }
        val voices = JSONArray()
        VOICES.forEach { (name, style, gender) -> voices.put(JSONObject().put("name", name).put("style", style).put("gender", gender)) }
        val effective = resolve(config, selection)
        return JSONObject().put("profiles", profiles).put("voices", voices)
            .put("profile", effective.optString("profile"))
            .put("voice", effective.optString("gemini_voice", GeminiProtocol.DEFAULT_VOICE))
            .put("voice_override", selection?.optString("voice").orEmpty())
            .put("key", keyStatus(config, appKey))
            .put("changes", config.optJSONObject(AgentFile.CHANGES) ?: JSONObject())
            .put("client", config.optJSONObject("client") ?: JSONObject())
            .apply { undo?.let { put("undo", JSONObject().put("at", it.optString("at")).put("what", it.optString("what"))) } }
            .toString()
    }

    /** source: "app", "provisioned" or "none"; hint: the key's last 4 characters. */
    fun keyStatus(config: JSONObject, appKey: String?): JSONObject {
        val provisioned = config.optString("gemini_api_key")
        val (source, key) = when {
            !appKey.isNullOrBlank() -> "app" to appKey
            provisioned.isNotBlank() -> "provisioned" to provisioned
            else -> "none" to ""
        }
        return JSONObject().put("source", source).put("hint", if (key.length >= 12) key.takeLast(4) else "")
    }

    /** A phone that was never provisioned has no config yet; the app's settings still work. */
    fun readConfig(): JSONObject = File(GeminiSession.CONFIG_PATH).takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
    /** Who this phone works for (the config's "client" block); re-read only when the config file changes. */
    fun clientSpec(): dev.callstream.probe.leads.ClientSpec {
        val file = File(GeminiSession.CONFIG_PATH)
        val stamp = file.lastModified() to file.length()
        cachedSpec?.let { (at, spec) -> if (at == stamp) return spec }
        val spec = runCatching { dev.callstream.probe.leads.ClientSpec.fromJson(readConfig().optJSONObject("client")) }
            .getOrDefault(dev.callstream.probe.leads.ClientSpec.NONE)
        cachedSpec = stamp to spec
        return spec
    }
    @Volatile private var cachedSpec: Pair<Pair<Long, Long>, dev.callstream.probe.leads.ClientSpec>? = null

    fun readSelection(): JSONObject? = runCatching { JSONObject(File(SELECTION_PATH).readText()) }.getOrNull()
    fun readKey(): String? = runCatching { JSONObject(File(KEY_PATH).readText()).optString("gemini_api_key") }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Saves the key entered in the app after Google accepts it; an empty [key] removes it (back to the provisioned
     * key, if any). Returns the new options. */
    fun setKey(key: String): String {
        val clean = key.trim()
        if (clean.isEmpty()) File(KEY_PATH).delete()
        else {
            require(clean.none { it.isWhitespace() }) { "The key can't contain spaces" }
            verifyKey(clean)
            writePrivate(File(KEY_PATH), JSONObject().put("gemini_api_key", clean).toString())
        }
        return options()
    }

    /** Lists one model with the key: cheap, free, and fails the same way a call would with a bad key. */
    private fun verifyKey(key: String) {
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
            .header("x-goog-api-key", key).build()
        val (code, body) = runCatching { HTTP.newCall(request).execute().use { it.code to it.body?.string().orEmpty() } }
            .getOrElse { throw IllegalStateException("Couldn't reach Google to check the key: ${it.message}") }
        when {
            code == 200 -> return
            code == 400 && "API_KEY_INVALID" in body -> error("Google rejected this key. Copy it again from AI Studio.")
            code == 403 -> error("This key isn't allowed to use the Gemini API (HTTP 403). Check its restrictions in Google Cloud.")
            else -> error("Google answered HTTP $code: ${body.take(160)}")
        }
    }

    private val HTTP by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

    /** Shell-owned, mode 600: only the helper can read it. Written whole and renamed, so a call starting now never
     * reads half a file. */
    fun writePrivate(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.setReadable(false, false); tmp.setWritable(false, false)
        tmp.setReadable(true, true); tmp.setWritable(true, true)
        check(tmp.renameTo(file)) { "couldn't write ${file.name}" }
    }

    // Agent changes from the app (AgentFile). Each keeps the config before it for one Undo. Returns the new options.

    fun previewFile(text: String): String = AgentFile.preview(AgentFile.parse(text), readConfig()).toString()

    fun applyFile(text: String): String {
        val file = AgentFile.parse(text)
        val names = file.profiles.keys().asSequence().map { profileName(it) }.joinToString()
        return change("Update from file: $names") { AgentFile.apply(file, it, now()) }
    }

    fun editPrompt(profile: String, prompt: String): String = change("Edited ${profileName(profile)}") { AgentFile.editPrompt(it, profile, prompt, now()) }

    /** The config before the last change comes back, with today's key: an Undo never brings back an old key. */
    fun undo(): String {
        val prev = readUndo() ?: error("Nothing to undo")
        val restored = withKeyOf(prev.getJSONObject("config"), readConfig())
        writePrivate(File(GeminiSession.CONFIG_PATH), restored.toString())
        File(PREV_PATH).delete()
        return options()
    }

    /** One profile's instructions, for the app's read-only view, and where they came from. */
    fun prompt(profile: String): String {
        val config = readConfig()
        val p = config.optJSONObject("profiles")?.optJSONObject(profile) ?: throw IllegalArgumentException("Unknown agent $profile")
        val change = config.optJSONObject(AgentFile.CHANGES)?.optJSONObject(profile)
        return JSONObject().put("profile", profile).put("prompt", p.optString("system_prompt"))
            .put("source", change?.optString("source") ?: "").put("at", change?.optString("at") ?: "").toString()
    }

    fun withKeyOf(config: JSONObject, current: JSONObject): JSONObject {
        val out = JSONObject(config.toString())
        out.remove("gemini_api_key")
        current.optString("gemini_api_key").takeIf { it.isNotBlank() }?.let { out.put("gemini_api_key", it) }
        return out
    }

    private fun change(what: String, transform: (JSONObject) -> JSONObject): String {
        val before = readConfig()
        val after = transform(before)
        writePrivate(File(PREV_PATH), JSONObject().put("at", now()).put("what", what).put("config", before).toString())
        writePrivate(File(GeminiSession.CONFIG_PATH), after.toString())
        return options()
    }

    private fun readUndo(): JSONObject? = runCatching { JSONObject(File(PREV_PATH).readText()) }.getOrNull()?.takeIf { it.has("config") }
    private fun now() = dev.callstream.probe.leads.LeadTime.MINUTE.format(dev.callstream.probe.leads.LeadTime.now())
    private fun profileName(name: String) = name.split('-', '_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /** Saves the app's pick. An empty [voice] means the profile's own voice. Returns the new options. */
    fun select(profile: String, voice: String): String {
        val config = readConfig()
        require(config.optJSONObject("profiles")?.has(profile) == true) { "unknown profile $profile" }
        require(voice.isEmpty() || VOICES.any { it.first == voice }) { "unknown voice $voice" }
        val selection = JSONObject().put("profile", profile).put("voice", voice)
        File(SELECTION_PATH).writeText(selection.toString())
        return options(config, selection)
    }

    fun options(): String = options(readConfig(), readSelection(), readKey(), readUndo())
}
