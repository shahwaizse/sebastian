package dev.callstream.probe

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppNoticeTest {
    private fun action(title: String, semantic: Int = Notification.Action.SEMANTIC_ACTION_NONE) = WhatsAppNotice.Action(title, semantic, null)

    /** The Pixel's WhatsApp ring (2026-10-25): CallStyle, category call, two actions. */
    @Test fun callStyleRingIsIncoming() {
        val n = WhatsAppNotice.parse("call", WhatsAppNotice.INCOMING, null, null, listOf(action("Decline"), action("Answer")))!!
        assertEquals(WhatsAppNotice.INCOMING, n.callType); assertEquals(2, n.actions)
    }

    @Test fun callStyleOngoing() =
        assertEquals(WhatsAppNotice.ONGOING, WhatsAppNotice.parse("call", WhatsAppNotice.ONGOING, null, null, listOf(action("Hang up")))!!.callType)

    @Test fun plainCallNotificationWithAnswerIsRinging() =
        assertEquals(WhatsAppNotice.INCOMING, WhatsAppNotice.parse("call", 0, null, null, listOf(action("Decline"), action("Answer")))!!.callType)

    @Test fun urduAnswerIsRinging() =
        assertEquals(WhatsAppNotice.INCOMING, WhatsAppNotice.parse("call", 0, null, null, listOf(action("مسترد کریں"), action("جواب دیں")))!!.callType)

    @Test fun semanticCallActionIsRinging() = assertEquals(WhatsAppNotice.INCOMING,
        WhatsAppNotice.parse("call", 0, null, null, listOf(action("✕"), action("✓", Notification.Action.SEMANTIC_ACTION_CALL)))!!.callType)

    @Test fun plainCallNotificationWithoutAnswerIsOngoing() =
        assertEquals(WhatsAppNotice.ONGOING, WhatsAppNotice.parse("call", 0, null, null, listOf(action("Hang up"), action("Speaker")))!!.callType)

    @Test fun messagesAreIgnored() = assertNull(WhatsAppNotice.parse("msg", 0, null, null, listOf(action("Reply"), action("Mark as read"))))

    @Test fun noCategoryIsIgnored() = assertNull(WhatsAppNotice.parse(null, 0, null, null, emptyList()))
}
