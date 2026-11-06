package dev.callstream.probe

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Reads WhatsApp's call notification (a CallStyle notification), which Sebastian can't get any other way:
 *  - who the call is with, for calls Sebastian didn't dial himself (incoming, or dialed by Rayyan),
 *  - its hang-up button, for "Sebastian, cut the call" (WhatsApp calls don't go through Android's Telecom).
 * Needs notification access (Settings, Notification access, Sebastian).
 */
class CallNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    private fun callNotification(): StatusBarNotification? =
        runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName == "com.whatsapp" }
            .firstOrNull { it.notification.extras.containsKey(Notification.EXTRA_CALL_PERSON) || isCallStyle(it) }

    private fun isCallStyle(sbn: StatusBarNotification) =
        sbn.notification.extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("CallStyle") == true

    /** Presses the call's hang-up button. Returns what happened. */
    fun hangUp(): String {
        val sbn = callNotification()
        if (sbn != null) {
            @Suppress("DEPRECATION")
            val hang = sbn.notification.extras.getParcelable<PendingIntent>(Notification.EXTRA_HANG_UP_INTENT)
            if (hang != null) { hang.send(); return "pressed hang up" }
        }
        val notes = runCatching { activeNotifications }.getOrNull().orEmpty().filter { it.packageName == "com.whatsapp" }
        for (n in notes) {
            val action = n.notification.actions?.firstOrNull { a -> HANG_UP.matches(a.title?.toString()?.trim().orEmpty()) } ?: continue
            action.actionIntent.send()
            return "pressed \"${action.title}\""
        }
        return "no WhatsApp call notification with a hang-up button (${notes.size} WhatsApp notifications)"
    }

    /** Who the WhatsApp call is with: "name<TAB>number". Number empty when the notification doesn't carry one. */
    fun callWith(): String? {
        val sbn = callNotification() ?: return null
        val extras: Bundle = sbn.notification.extras
        @Suppress("DEPRECATION")
        val person = extras.getParcelable<Person>(Notification.EXTRA_CALL_PERSON)
        val name = person?.name?.toString()?.takeIf { it.isNotBlank() } ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val number = listOfNotNull(person?.uri, person?.key).firstNotNullOfOrNull { numberIn(it) }.orEmpty()
        Log.i("SebastianCall", "call with name=$name uri=${person?.uri} key=${person?.key}")
        return "${name.replace('\t', ' ')}\t$number"
    }

    companion object {
        @Volatile var instance: CallNotificationListener? = null
        private val HANG_UP = Regex("(?i)hang up|end call|end|leave|leave call|decline")

        /** "tel:+923001234567", "923001234567@s.whatsapp.net" → "+923001234567"; anything else → null. */
        fun numberIn(s: String): String? {
            val t = s.removePrefix("tel:")
            if (!Regex("^\\+?[0-9 ()-]{8,}(@s\\.whatsapp\\.net)?$").matches(t)) return null
            val d = t.substringBefore('@').filter { it.isDigit() }
            return if (d.length >= 10) "+$d" else null
        }
    }
}

/** The helper asks for a hang-up: `am broadcast -n dev.rawaiz.sebastian/dev.callstream.probe.HangupReceiver`. */
class HangupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = CallNotificationListener.instance?.hangUp() ?: "notification access is off"
        Log.i("SebastianHangup", result)
        resultCode = if (result.startsWith("pressed")) 1 else 0
        resultData = result
    }
}

/** The helper asks who the WhatsApp call is with: result data "name<TAB>number" (result code 1), or code 0. */
class CallInfoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val info = CallNotificationListener.instance?.callWith()
        resultCode = if (info != null) 1 else 0
        resultData = info ?: (if (CallNotificationListener.instance == null) "notification access is off" else "no call notification")
    }
}
