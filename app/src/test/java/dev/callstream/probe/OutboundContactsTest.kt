package dev.callstream.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OutboundContactsTest {
    private fun leads(csv: String) = LeadFile.read(csv.toByteArray()).let { t -> LeadFile.upload(t, LeadFile.detect(t)!!, true) }

    @Test fun readsNameAndNumberInAnyOrderAndCase() {
        assertEquals(listOf(Contact("Ali", "+923001234567"), Contact("Khan, Sara", "03017654321")),
            leads("Number,NAME\n+92 300 1234567,Ali\n0301-7654321,\"Khan, Sara\"\n").contacts)
    }

    @Test fun handlesBomCrlfQuotesAndSemicolons() {
        assertEquals(listOf(Contact("Sam \"S\"", "03001112222")), leads("\uFEFFname;number\r\n\"Sam \"\"S\"\"\";(0300) 111 2222\r\n").contacts)
    }

    @Test fun skipsInvalidRowsAndBlankLines() {
        val up = leads("name,number\nA,12\nB,not a number\n\nC,+1 555 0100\nD,\n")
        assertEquals(listOf(Contact("C", "+15550100")), up.contacts)
        assertEquals(3, up.skipped)
    }

    @Test fun normalizeNumber() {
        assertEquals("+923001234567", OutboundContacts.normalizeNumber(" +92-300-123.4567 "))
        assertNull(OutboundContacts.normalizeNumber("92+300"))
        assertNull(OutboundContacts.normalizeNumber("3.00123E+11"))
        assertNull(OutboundContacts.normalizeNumber("1234567890123456"))
    }

    @Test fun jsonRoundTrip() {
        val contacts = listOf(Contact("A", "+1"), Contact("", "0300"))
        assertEquals(contacts, OutboundContacts.fromJson(OutboundContacts.toJson(contacts)))
    }
}
