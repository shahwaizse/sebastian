package dev.callstream.probe.leads

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One row of the lead sheet: one call to one number. A callback the lead asked for is a new row for the
 * same number with [callNo] one higher, so every call keeps its own talk time and answers. Times are
 * Pakistan local time as text ("yyyy-MM-dd HH:mm"), which sorts and reads the same in the exported sheet.
 */
data class Lead(
    val id: Long = 0,
    val number: String,
    val name: String = "",
    val callNo: Int = 1,
    val status: String = PENDING,
    val detail: String = "",
    val attempts: Int = 0,
    /** When this row was last dialed ("yyyy-MM-dd HH:mm:ss"). */
    val calledAt: String? = null,
    val followUpAt: String? = null,
    /** Seconds from answer to hang-up. 0 until answered. */
    val talkTime: Int = 0,
    /** The client's own columns (ClientSpec.fields), by key. */
    val fields: Map<String, String> = emptyMap(),
    /** Post-call extraction: "", "done", or why it didn't run. */
    val extraction: String = "",
    val createdAt: String = "",
    /** The call's transcript is saved (read it with the helper's leadTranscript). */
    val hasTranscript: Boolean = false,
) {
    fun field(key: String) = fields[key].orEmpty()
    val active get() = status in ACTIVE

    fun toJson(): JSONObject = JSONObject().put("id", id).put("number", number).put("name", name).put("call_no", callNo)
        .put("status", status).put("detail", detail).put("attempts", attempts).put("called_at", calledAt ?: "")
        .put("follow_up_at", followUpAt ?: "").put("talk_time", talkTime).put("extraction", extraction).put("created_at", createdAt)
        .put("has_transcript", hasTranscript)
        .put("fields", JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } })

    companion object {
        const val PENDING = "pending"
        const val DIALING = "dialing"
        const val RINGING = "ringing"
        const val IN_CALL = "in call"
        const val COMPLETED = "completed"
        const val NO_ANSWER = "no answer"
        const val NOT_CONNECTED = "not connected"
        const val FAILED = "failed"
        const val TAKEN_OVER = "taken over"
        val ACTIVE = setOf(DIALING, RINGING, IN_CALL)
        /** Unanswered for a reason a later try may fix. */
        val RETRYABLE = setOf(NO_ANSWER, NOT_CONNECTED, FAILED)

        fun fromJson(o: JSONObject) = Lead(
            id = o.optLong("id"), number = o.optString("number"), name = o.optString("name"), callNo = o.optInt("call_no", 1),
            status = o.optString("status", PENDING), detail = o.optString("detail"), attempts = o.optInt("attempts"),
            calledAt = o.optString("called_at").ifEmpty { null }, followUpAt = o.optString("follow_up_at").ifEmpty { null },
            talkTime = o.optInt("talk_time"), extraction = o.optString("extraction"), createdAt = o.optString("created_at"), hasTranscript = o.optBoolean("has_transcript"),
            fields = o.optJSONObject("fields")?.let { f -> f.keys().asSequence().associateWith { f.optString(it) } } ?: emptyMap())

        fun listFromJson(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { fromJson(a!!.getJSONObject(it)) }
    }
}

enum class FieldType { TEXT, DATETIME }

/** A client column: stored, editable in the app, filled after each call by extraction, exported. */
data class LeadField(val key: String, val label: String, val type: FieldType, val extractHint: String)

/**
 * Who this phone works for, and what that client's lead sheet adds to the core one. It arrives as data, in the
 * agent file's "client" block (clients/<client>/client.json, pushed by provision_phone.py or opened from a
 * .callstream file), so one APK serves every client. A phone with none has the plain sheet.
 *
 * [features] are opt-in switches for behaviour one client asked for that others don't want yet (see [has]). A
 * feature everyone gets needs no flag.
 */
data class ClientSpec(
    /** "acme", …; empty: no client set up yet. */
    val id: String = "",
    /** Shown next to the app title, e.g. "ACME". */
    val title: String = "",
    val fields: List<LeadField> = emptyList(),
    /** Who goes to the client's own callers; null: no Interested filter or export. */
    val interested: ((Lead) -> Boolean)? = null,
    /** Extra guidance for the post-call extraction (the fields' own hints are added automatically). */
    val extractionGuide: String = "",
    val features: Set<String> = emptySet(),
    /** The block it was read from, to hand back unchanged (to the app, into the config). */
    val source: JSONObject = JSONObject(),
) {
    fun has(feature: String) = feature in features

    companion object {
        val NONE = ClientSpec()

        /** Throws IllegalArgumentException with a readable message on a malformed block. */
        fun fromJson(o: JSONObject?): ClientSpec {
            if (o == null) return NONE
            val id = o.optString("id")
            require(id.matches(Regex("[a-z0-9-]+"))) { "The client id \"$id\" isn't valid." }
            val fieldsJson = o.optJSONArray("lead_fields") ?: JSONArray()
            val fields = (0 until fieldsJson.length()).map { i ->
                val f = fieldsJson.getJSONObject(i)
                val key = f.optString("key")
                require(key.matches(Regex("[a-z_]+")) && key !in CORE_COLUMNS) { "The lead sheet column \"$key\" isn't allowed." }
                val type = when (f.optString("type", "text")) {
                    "text" -> FieldType.TEXT
                    "datetime" -> FieldType.DATETIME
                    else -> throw IllegalArgumentException("Column $key has an unknown type, ${f.optString("type")}.")
                }
                LeadField(key, f.optString("label", key), type, f.optString("hint"))
            }
            require(fields.map { it.key }.toSet().size == fields.size) { "A lead sheet column is listed twice." }
            val rule = o.optJSONObject("interested")?.let { rule(it, fields.map { f -> f.key }.toSet()) }
            val featuresJson = o.optJSONArray("features") ?: JSONArray()
            return ClientSpec(id, o.optString("name"), fields, rule, o.optString("extraction_guide"),
                (0 until featuresJson.length()).map { featuresJson.getString(it) }.toSet(), JSONObject(o.toString()))
        }

        /** {"any": [...]}, {"all": [...]}, {"filled": [column, ...]}, {"talk_seconds_at_least": n}. */
        private fun rule(o: JSONObject, columns: Set<String>): (Lead) -> Boolean {
            require(o.length() == 1) { "Each part of the interested rule has exactly one condition." }
            val kind = o.keys().next()
            fun parts() = o.getJSONArray(kind).let { a -> (0 until a.length()).map { rule(a.getJSONObject(it), columns) } }
            return when (kind) {
                "any" -> parts().let { p -> { lead -> p.any { it(lead) } } }
                "all" -> parts().let { p -> { lead -> p.all { it(lead) } } }
                "filled" -> o.getJSONArray(kind).let { a -> (0 until a.length()).map { a.getString(it) } }.also { keys ->
                    keys.forEach { require(it in columns) { "The interested rule names an unknown column, $it." } }
                }.let { keys -> { lead -> keys.all { lead.field(it).isNotBlank() } } }
                "talk_seconds_at_least" -> o.getInt(kind).let { n -> { lead -> lead.talkTime >= n } }
                else -> throw IllegalArgumentException("The interested rule has an unknown condition, $kind.")
            }
        }

        /** Columns the core sheet already has; a client column can't reuse one. */
        private val CORE_COLUMNS = setOf("id", "number", "name", "call_no", "status", "detail", "attempts", "called_at",
            "follow_up_at", "talk_time", "extraction", "transcript", "created_at", "has_transcript")
    }
}

object LeadTime {
    val PAKISTAN: ZoneId = ZoneId.of("Asia/Karachi")
    val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val SECOND: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun now(): LocalDateTime = LocalDateTime.now(PAKISTAN).withNano(0)

    /** "yyyy-MM-dd HH:mm[:ss]", or a bare date (start of day). Null if it is neither. */
    fun parse(text: String?): LocalDateTime? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return runCatching { LocalDateTime.parse(t, SECOND) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t, MINUTE) }.getOrNull()
            ?: runCatching { LocalDate.parse(t, DATE).atStartOfDay() }.getOrNull()
    }

    /** Normalised "yyyy-MM-dd HH:mm" (or the bare date it was given), or null if it isn't a date. */
    fun clean(text: String?): String? {
        val t = text?.trim().orEmpty()
        val parsed = parse(t) ?: return null
        return if (t.length == 10) t else MINUTE.format(parsed)
    }
}
