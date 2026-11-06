package dev.callstream.probe

import dev.callstream.probe.leads.Lead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReportsTest {
    private val lead = Lead(id = 7, number = "+923001234567", name = "Bilal", callNo = 2, calledAt = "2026-10-24 11:05", talkTime = 64)
    private val call = AiReportState(lead, listOf("Agent" to "السلام علیکم", "Bilal" to "جی"))

    @Test fun neverSendsTheLeadsNumberOrName() {
        val text = AiReports.payload(call, AiReportReason.OFFENSIVE, "rude", withTranscript = true, JSONObject()).toString()
        assertFalse("+923001234567" in text)
        assertFalse("Bilal" in text)
        val lines = JSONObject(text).getJSONObject("call").getJSONArray("transcript")
        assertEquals("agent", lines.getJSONObject(0).getString("who"))
        assertEquals("caller", lines.getJSONObject(1).getString("who"))
    }

    @Test fun transcriptOnlyWhenAsked() {
        val o = AiReports.payload(call, AiReportReason.MISLEADING, "", withTranscript = false, JSONObject())
        assertFalse(o.getJSONObject("call").has("transcript"))
        assertEquals(7, o.getJSONObject("call").getLong("lead_id"))
    }

    @Test fun generalReportHasNoCall() {
        val o = AiReports.payload(AiReportState(), AiReportReason.OTHER, " x ".repeat(2000), withTranscript = true, JSONObject())
        assertFalse(o.has("call"))
        assertEquals("other", o.getString("reason"))
        assertTrue(o.getString("note").length <= 2000)
    }
}
