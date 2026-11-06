package dev.callstream.probe.leads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LeadRulesTest {
    private val noon = LocalDateTime.of(2026, 10, 17, 12, 0)
    private fun lead(id: Long, status: String = Lead.PENDING, followUp: String? = null, attempts: Int = 0, calledAt: String? = null, number: String = "0300$id") =
        Lead(id = id, number = number, status = status, followUpAt = followUp, attempts = attempts, calledAt = calledAt)
    private fun call(next: LeadRules.Next) = (next as LeadRules.Next.Call)
    private fun wait(next: LeadRules.Next) = (next as LeadRules.Next.Wait)

    @Test fun dueCallbackGoesBeforeNewLeads() {
        val leads = listOf(lead(1), lead(2), lead(3, followUp = "2026-10-17 11:58"))
        assertEquals(3L, call(LeadRules.pickNext(leads, noon)).lead.id)
    }

    @Test fun earliestDueCallbackFirstAndFutureOnesWait() {
        val leads = listOf(lead(1, followUp = "2026-10-17 11:50"), lead(2, followUp = "2026-10-17 11:30"), lead(3, followUp = "2026-10-17 12:30"), lead(4))
        assertEquals(2L, call(LeadRules.pickNext(leads, noon)).lead.id)
        // A callback not yet due doesn't hold up new leads.
        assertEquals(4L, call(LeadRules.pickNext(listOf(lead(3, followUp = "2026-10-17 12:30"), lead(4)), noon)).lead.id)
    }

    @Test fun newLeadsInUploadOrderThenRetries() {
        val leads = listOf(lead(5, Lead.NO_ANSWER, attempts = 1, calledAt = "2026-10-17 08:00:00"), lead(7), lead(6))
        assertEquals(6L, call(LeadRules.pickNext(leads, noon)).lead.id)
        assertEquals("retry", call(LeadRules.pickNext(leads.take(1), noon)).why)
    }

    @Test fun retriesWaitTwoHoursAndStopAfterThreeTries() {
        val recent = lead(1, Lead.NOT_CONNECTED, attempts = 1, calledAt = "2026-10-17 11:00:00")
        val w = wait(LeadRules.pickNext(listOf(recent), noon))
        assertEquals(LocalDateTime.of(2026, 10, 17, 13, 0), w.until)
        val spent = lead(2, Lead.NO_ANSWER, attempts = 3, calledAt = "2026-10-17 06:00:00")
        assertNull(wait(LeadRules.pickNext(listOf(spent, lead(3, Lead.COMPLETED)), noon)).until)
    }

    @Test fun callingHoursAreNineToNineButCallbacksGoWhenAsked() {
        val night = LocalDateTime.of(2026, 10, 17, 21, 0)
        val w = wait(LeadRules.pickNext(listOf(lead(1)), night))
        assertEquals(LocalDateTime.of(2026, 10, 18, 9, 0), w.until)
        assertEquals("calling hours start", w.why)
        val early = LocalDateTime.of(2026, 10, 17, 8, 59)
        assertEquals(LocalDateTime.of(2026, 10, 17, 9, 0), wait(LeadRules.pickNext(listOf(lead(1)), early)).until)
        assertEquals(2L, call(LeadRules.pickNext(listOf(lead(1), lead(2, followUp = "2026-10-17 22:00")), LocalDateTime.of(2026, 10, 17, 22, 1))).lead.id)
        assertTrue(LeadRules.inHours(LocalDateTime.of(2026, 10, 17, 20, 59)))
    }

    @Test fun waitsForTheSoonestThingAndStaysArmed() {
        val leads = listOf(lead(1, followUp = "2026-10-17 15:00"), lead(2, Lead.COMPLETED))
        val w = wait(LeadRules.pickNext(leads, noon))
        assertEquals(LocalDateTime.of(2026, 10, 17, 15, 0), w.until)
        assertEquals("next callback", w.why)
    }

    @Test fun interestedIsOnePersonPerNumberFromTheirLatestQualifyingCall() {
        val rule: (Lead) -> Boolean = { it.talkTime >= 60 }
        val rows = listOf(
            Lead(id = 1, number = "0300", callNo = 1, talkTime = 90),
            Lead(id = 2, number = "0300", callNo = 2, talkTime = 75),
            Lead(id = 3, number = "0300", callNo = 3, talkTime = 10),
            Lead(id = 4, number = "0301", talkTime = 59),
        )
        val out = LeadRules.interested(rows, rule)
        assertEquals(listOf(2L), out.map { it.first.id })
        assertEquals(3, out.single().second)
    }

    @Test fun pakistaniNumbersAreOneLead() {
        assertEquals("03001234567", LeadRules.canonicalNumber("+923001234567"))
        assertEquals("03001234567", LeadRules.canonicalNumber("923001234567"))
        assertEquals("03001234567", LeadRules.canonicalNumber("00923001234567"))
        assertEquals("03001234567", LeadRules.canonicalNumber("03001234567"))
        assertEquals("+15550100", LeadRules.canonicalNumber("+15550100"))
    }

    @Test fun uploadSkipsKnownNumbersAndFillsMissingNames() {
        val plan = LeadRules.planImport(mapOf("03001234567" to false, "03007654321" to true),
            listOf("Ali" to "+923001234567", "Sara" to "03007654321", "" to "03111111111", "Omar" to "03111111111", "Bilal" to "03222222222"))
        assertEquals(listOf("Omar" to "03111111111", "Bilal" to "03222222222"), plan.inserts)
        assertEquals(mapOf("03001234567" to "Ali"), plan.nameFills)
        assertEquals(3, plan.duplicates)
    }

    @Test fun exportHasBomHeaderAndSafeFields() {
        val spec = ClientSpec("t", fields = listOf(LeadField("plot_type", "Plot", FieldType.TEXT, "")), interested = { true })
        val csv = LeadRules.exportCsv(listOf(Lead(id = 1, number = "03001234567", name = "Khan, Sara", talkTime = 83,
            fields = mapOf("plot_type" to "=HYPERLINK(1)")) to 2), spec)
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertTrue(csv.startsWith("﻿"))
        assertEquals("name,number,plot_type,follow_up_at,talk_time,interested,call_no,calls,called_at", lines[0])
        assertEquals("\"Khan, Sara\",03001234567,'=HYPERLINK(1),,1:23,yes,1,2,", lines[1])
        // Phone numbers that start with + stay numbers.
        assertEquals("+923001234567", LeadRules.csvField("+923001234567"))
    }

    @Test fun datesAreCheckedAndNormalised() {
        assertEquals("2026-10-19 16:00", LeadTime.clean("2026-10-19 16:00:00"))
        assertEquals("2026-10-19", LeadTime.clean("2026-10-19"))
        assertNull(LeadTime.clean("Sunday 4 pm"))
        assertNull(LeadTime.clean("2026-13-01 10:00"))
    }

    @Test fun planDeleteTakesEveryCallOfEachNumberAndSkipsOnesInACall() {
        val all = listOf(
            Lead(id = 1, number = "03001111111"), Lead(id = 2, number = "03001111111", callNo = 2, status = Lead.COMPLETED),
            Lead(id = 3, number = "03002222222"), Lead(id = 4, number = "03003333333", status = Lead.IN_CALL),
            Lead(id = 5, number = "03004444444"))
        val plan = LeadRules.planDelete(all, setOf("03001111111", "03002222222", "03003333333"))
        assertEquals(listOf(1L, 2L, 3L), plan.ids.sorted())
        assertEquals(2, plan.people)
        assertEquals(1, plan.inCall)
    }
}
