package dev.callstream.probe.shell

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sebastian's settings: one JSON file the laptop pushes (bridge/push_config.py builds it from .env and
 * contacts.json). It lives in /data/local/tmp, which only the shell user (the helper) can read.
 */
internal object SebastianConfig {
    const val DIR = "/data/local/tmp/sebastian"
    const val PATH = "$DIR/config.json"
    const val ENABLED_PATH = "$DIR/enabled"
    const val ROUTE_PATH = "$DIR/uplink_route"
    const val OUTBOX = "$DIR/outbox"

    fun read(): JSONObject {
        val f = File(PATH)
        check(f.canRead()) { "No Sebastian config on this phone yet: run bridge/push_config.py" }
        return JSONObject(f.readText())
    }

    data class Contact(val name: String, val number: String)

    /** "Shahwaiz", "shahwaiz bhai" → the contacts.json entry whose name or alias matches; null if none. */
    fun findContact(cfg: JSONObject, spoken: String): Contact? {
        val want = normalize(spoken)
        if (want.isEmpty()) return null
        val list = cfg.optJSONArray("contacts") ?: JSONArray()
        var best: Contact? = null
        for (i in 0 until list.length()) {
            val c = list.getJSONObject(i)
            val name = c.optString("name")
            val names = mutableListOf(name)
            c.optJSONArray("aliases")?.let { a -> for (j in 0 until a.length()) names += a.getString(j) }
            for (n in names.map(::normalize).filter { it.isNotEmpty() }) {
                if (n == want) return Contact(name, c.getString("number"))
                if (best == null && (want.contains(n) || n.contains(want))) best = Contact(name, c.getString("number"))
            }
        }
        return best
    }

    fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
