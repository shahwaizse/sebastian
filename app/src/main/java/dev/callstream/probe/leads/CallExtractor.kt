package dev.callstream.probe.leads

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * After an outbound call: a small text model reads the call's transcript (Gemini Live's own transcription of
 * both sides) and fills in the lead's row: name, callback time and the client's fields. It runs after the
 * call has ended, so it adds nothing to the call itself. Building and parsing are pure (JVM-tested).
 */
object CallExtractor {
    data class Result(val name: String?, val followUpAt: String?, val fields: Map<String, String>)

    private val SPOKEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH)

    private val SYSTEM = """
        You read the transcript of one phone call that a voice agent made to a sales lead, and fill in the lead's record.
        The transcript comes from speech recognition and may have errors. The agent usually reads details back to confirm
        them; a detail the lead confirmed is the most reliable. Use null for anything not clearly stated. Never guess.
        Work out dates such as "kal", "parson" or a weekday from the call time given. All times are Pakistan time.
        Fields:
        - name: the lead's own name if they said it in this call, in English letters. Null if they didn't.
        - follow_up_at: if the lead asked to be called back later, when, as yyyy-MM-dd HH:mm. Use the time the lead
          confirmed when the agent read it back; if they corrected it, the corrected one. A time from now ("do minute baad",
          "aadhe ghante mein", "in 10 minutes", "ek ghante baad") counts from when the call ended: two minutes means the
          call end plus two minutes, never hours. A day without a time: 11:00 that day. Only if no day, time or duration
          was mentioned at all: two hours after the call ended if that falls between 09:00 and 21:00, otherwise 11:00 the
          next day. Null if they didn't ask for a callback, or asked not to be called again.
    """.trimIndent()

    fun requestBody(spec: ClientSpec, transcript: List<Pair<String, String>>, endedAt: LocalDateTime, talkSeconds: Int, listedName: String): String {
        val system = buildString {
            append(SYSTEM)
            spec.fields.forEach { append("\n- ").append(it.key).append(": ").append(it.extractHint) }
            if (spec.extractionGuide.isNotEmpty()) append("\n").append(spec.extractionGuide)
        }
        val text = buildString {
            append("Call ended: ").append(SPOKEN.format(endedAt)).append(" (").append(LeadTime.MINUTE.format(endedAt)).append(").\n")
            append("Talk time: ").append(LeadRules.talkTime(talkSeconds)).append(".\n")
            append("Name on our list: ").append(listedName.ifBlank { "none" }).append(".\n\nTranscript:\n")
            transcript.forEach { (who, said) -> append(who).append(": ").append(said.trim()).append('\n') }
        }
        val properties = JSONObject()
        (listOf("name", "follow_up_at") + spec.fields.map { it.key }).forEach { properties.put(it, JSONObject().put("type", "STRING").put("nullable", true)) }
        val schema = JSONObject().put("type", "OBJECT").put("properties", properties)
            .put("required", JSONArray().apply { properties.keys().forEach { put(it) } })
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json").put("responseSchema", schema))
            .toString()
    }

    /** The model's answer, checked: bad dates are dropped, and a callback must be after the call ended. */
    fun parse(spec: ClientSpec, response: String, endedAt: LocalDateTime): Result {
        val parts = JSONObject(response).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val text = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        val o = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
        fun str(key: String, max: Int) = o.optString(key).takeIf { !o.isNull(key) }?.trim()?.take(max)?.takeIf { it.isNotEmpty() && it != "null" }
        val followUp = LeadTime.clean(str("follow_up_at", 40))?.takeIf { LeadTime.parse(it)!!.isAfter(endedAt) }
        val fields = spec.fields.mapNotNull { f ->
            val v = str(f.key, 120) ?: return@mapNotNull null
            val clean = if (f.type == FieldType.DATETIME) LeadTime.clean(v) else v
            clean?.let { f.key to it }
        }.toMap()
        return Result(str("name", 80), followUp, fields)
    }

    private val CLIENT by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    /** Blocking; off the call threads. Throws on HTTP or network errors. */
    fun call(apiKey: String, model: String, body: String): String {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        CLIENT.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "HTTP ${r.code}: ${text.take(200)}" }
            return text
        }
    }
}
