package dev.callstream.probe.leads

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CallExtractorTest {
    private val spec = ClientSpec("t", fields = listOf(LeadField("plot_type", "Plot", FieldType.TEXT, "the plot"),
        LeadField("visit_date_time", "Visit", FieldType.DATETIME, "the visit")), extractionGuide = "Guide line.")
    private val ended = LocalDateTime.of(2026, 10, 17, 15, 42)
    private fun reply(json: String) = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content",
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", json)))))).toString()

    @Test fun requestAsksForEveryFieldWithTheCallTimeAndTranscript() {
        val body = JSONObject(CallExtractor.requestBody(spec, listOf("Agent" to "السلام علیکم", "Lead" to "جی"), ended, 70, ""))
        val schema = body.getJSONObject("generationConfig").getJSONObject("responseSchema").getJSONObject("properties")
        assertEquals(setOf("name", "follow_up_at", "plot_type", "visit_date_time"), schema.keys().asSequence().toSet())
        val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system.contains("- visit_date_time: the visit") && system.contains("Guide line."))
        val text = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text.contains("Saturday 17 October 2026, 3:42 PM (2026-10-17 15:42)"))
        assertTrue(text.contains("Talk time: 1:10.") && text.contains("Name on our list: none.") && text.contains("Agent: السلام علیکم\nLead: جی"))
    }

    @Test fun parsesAndChecksTheAnswer() {
        val r = CallExtractor.parse(spec, reply("""{"name":"Imran","follow_up_at":null,"plot_type":"5 marla residential","visit_date_time":"2026-10-19 16:00"}"""), ended)
        assertEquals("Imran", r.name)
        assertNull(r.followUpAt)
        assertEquals(mapOf("plot_type" to "5 marla residential", "visit_date_time" to "2026-10-19 16:00"), r.fields)
    }

    @Test fun dropsBadDatesAndCallbacksInThePast() {
        val r = CallExtractor.parse(spec, reply("""{"name":null,"follow_up_at":"2026-10-17 15:00","plot_type":"","visit_date_time":"Sunday"}"""), ended)
        assertNull(r.name)
        assertNull(r.followUpAt)
        assertTrue(r.fields.isEmpty())
        assertEquals("2026-10-18 10:00", CallExtractor.parse(spec, reply("""{"follow_up_at":"2026-10-18 10:00"}"""), ended).followUpAt)
    }
}
