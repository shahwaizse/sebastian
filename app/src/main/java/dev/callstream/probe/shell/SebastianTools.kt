package dev.callstream.probe.shell

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Sebastian can do mid-call. The work happens in sebastian-brain (Cloudflare), which holds the Google and
 * Notion credentials, so the phone only ever carries the AssemblyAI key and the brain's token.
 */
internal object SebastianTools {
    private val JSON = "application/json".toMediaType()

    fun schema(): JSONArray = JSONArray()
        .put(fn("book_calendar_event", "Book a meeting on Rayyan's Google Calendar at an exact agreed day and time. " +
            "It adds a Google Meet link and WhatsApps it to the other person on the call (and to Rayyan).",
            JSONObject()
                .put("title", prop("string", "Short event title, e.g. 'Follow-up with Shahwaiz'"))
                .put("start", prop("string", "Local start time as ISO 8601 without offset, e.g. 2026-11-06T15:00"))
                .put("minutes", prop("integer", "Length in minutes, default 30"))
                .put("notes", prop("string", "One line on what it's about")),
            listOf("title", "start")))
        .put(fn("send_booking_link", "WhatsApp the other person Rayyan's Calendly link so they can pick a meeting time " +
            "themselves. Use when they want to meet but no exact day and time was agreed.",
            JSONObject(), listOf()))
        .put(fn("web_search", "Search the web (Google) for facts, news or anything not said in the call or in Notion.",
            JSONObject().put("query", prop("string", "The question to look up, e.g. 'what does AssemblyAI do'")),
            listOf("query")))
        .put(fn("read_notion_doc", "Read one of the team's Notion pages, e.g. the pricing doc, to answer a question from it.",
            JSONObject().put("query", prop("string", "Words from the page title, e.g. 'pricing'")),
            listOf("query")))

    /** [otherName]/[otherNumber]: who's on the call, so meeting links reach them (number known only when Sebastian dialed). */
    fun run(cfg: JSONObject, name: String, args: JSONObject, otherName: String = "Them", otherNumber: String? = null): JSONObject {
        val path = when (name) {
            "book_calendar_event" -> "/event"
            "send_booking_link" -> "/send-link"
            "read_notion_doc" -> "/notion/read"
            "web_search" -> "/search"
            else -> return JSONObject().put("error", "unknown tool $name")
        }
        if (path == "/event" || path == "/send-link") {
            args.put("other_name", otherName)
            otherNumber?.let { args.put("send_to", it) }
        }
        return post(cfg, path, args)
    }

    fun post(cfg: JSONObject, path: String, body: JSONObject): JSONObject {
        val base = cfg.optString("brain_url").trimEnd('/')
        if (!base.startsWith("https://")) return JSONObject().put("error", "sebastian-brain isn't set up yet")
        val req = Request.Builder().url(base + path)
            .header("authorization", "Bearer ${cfg.optString("brain_token")}")
            .post(body.toString().toRequestBody(JSON)).build()
        SebastianSession.CLIENT.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", "HTTP ${r.code}: ${raw.take(200)}") }
                .also { if (!r.isSuccessful && !it.has("error")) it.put("error", "HTTP ${r.code}") }
        }
    }

    private fun fn(name: String, description: String, props: JSONObject, required: List<String>) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))))

    private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)
}
