package dev.callstream.probe.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppCallsTest {
    /** `dumpsys telecom` on the Pixel while a WhatsApp call rang (2026-10-25). */
    private fun dump(call: String) = """
        Init Path: On mainline
        CallsManager:
          mCalls:
            $call
          mCallAudioManager:
            Foreground call:
            $call
    """.trimIndent()

    private val whatsAppRinging = "[Call id=9c4eab83-d301-49ac-afef-48dac2d4200e, state=RINGING, tpac=ComponentInfo{com.whatsapp/com.whatsapp}, " +
        "***, UserHandle{0}, cmgr=null, handle=tel:*************, vidst=A, childs(0), has_parent(false), cap=[ hld sup_hld], prop=[ self_mng]], voip=true"

    @Test fun findsRingingWhatsApp() = assertEquals("com.whatsapp", WhatsAppCalls.ringingPackage(dump(whatsAppRinging)))

    @Test fun findsWhatsAppBusiness() =
        assertEquals("com.whatsapp.w4b", WhatsAppCalls.ringingPackage(dump(whatsAppRinging.replace("com.whatsapp/com.whatsapp", "com.whatsapp.w4b/com.whatsapp.w4b"))))

    @Test fun activeCallIsNotRinging() = assertNull(WhatsAppCalls.ringingPackage(dump(whatsAppRinging.replace("state=RINGING", "state=ACTIVE"))))

    @Test fun otherVoipAppIsIgnored() =
        assertNull(WhatsAppCalls.ringingPackage(dump(whatsAppRinging.replace("com.whatsapp/com.whatsapp", "com.discord/com.discord.telecom.service.TelecomConnectionService"))))

    @Test fun simCallIsIgnored() = assertNull(WhatsAppCalls.ringingPackage(dump(
        "[Call id=1, state=RINGING, tpac=ComponentInfo{com.android.phone/com.android.services.telephony.TelephonyConnectionService}, ***, prop=[]], voip=false")))

    @Test fun noCalls() = assertNull(WhatsAppCalls.ringingPackage(dump("")))

    @Test fun simRingIsNotVoip() = assertEquals(WhatsAppCalls.Ring("com.android.phone", voip = false), WhatsAppCalls.ringingCall(dump(
        "[Call id=1, state=RINGING, tpac=ComponentInfo{com.android.phone/com.android.services.telephony.TelephonyConnectionService}, ***, prop=[]], voip=false")))

    @Test fun otherVoipRingIsVoipNotWhatsApp() {
        val ring = WhatsAppCalls.ringingCall(dump(whatsAppRinging.replace("com.whatsapp/com.whatsapp", "com.discord/com.discord.telecom.service.TelecomConnectionService")))!!
        assertEquals("com.discord", ring.app); assertTrue(ring.voip); assertFalse(ring.whatsApp)
    }

    /** Telecom's isRinging can lead the dump: no ringing call in it yet means look again, not "SIM". */
    @Test fun dumpWithoutTheRingYet() = assertSame(WhatsAppCalls.NOT_YET, WhatsAppCalls.ringingCall(dump("")))

    /** dumpsys unavailable: the caller falls back to treating the ring as a SIM call. */
    @Test fun unreadableDump() = assertNull(WhatsAppCalls.ringingCall(""))
}
