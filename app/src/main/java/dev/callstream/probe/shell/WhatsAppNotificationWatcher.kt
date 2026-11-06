package dev.callstream.probe.shell

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.callstream.probe.BuildConfig
import dev.callstream.probe.WhatsAppNotice

/**
 * Sees WhatsApp's call notifications from the helper, for phones where WhatsApp doesn't register its calls with
 * Telecom (the Redmi on HyperOS). Until 0.25.0 the app's own NotificationListenerService did this, but an app that
 * declares notification access is blocked outright by Play Protect's enhanced fraud protection when installed from a
 * browser or file manager (Redmi, 2026-10-31).
 *
 * The helper registers a listener the way SystemUI does: `NotificationListenerService.registerAsSystemService`, i.e.
 * `INotificationManager.registerListener`, which needs STATUS_BAR_SERVICE, and the shell holds it. No manifest entry,
 * no Settings → Notification access. Reading active notifications directly needs ACCESS_NOTIFICATIONS, which the
 * shell lacks (0.25.1 polled that way and got a SecurityException on the Redmi). Only WhatsApp's call notices are
 * looked at, nothing else is read.
 */
class WhatsAppNotificationWatcher(
    private val report: (pkg: String, callType: Int, answer: android.app.PendingIntent?, hangUp: android.app.PendingIntent?, actions: Int) -> Unit,
    private val note: (String) -> Unit,
) {
    private var listener: Listener? = null
    @Volatile var connectedAt = 0L; private set
    @Volatile var error: String? = null; private set

    @Synchronized fun start() {
        if (listener != null) return
        val l = Listener()
        skipFlagReads()
        runCatching {
            val ctx: Context = AudioTools.shellContext()
            NotificationListenerService::class.java
                .getMethod("registerAsSystemService", Context::class.java, ComponentName::class.java, Int::class.javaPrimitiveType)
                .invoke(l, ctx, ComponentName(BuildConfig.APPLICATION_ID, "dev.callstream.probe.shell.WhatsAppNotices"), USER_ALL)
            listener = l
            error = null
        }.onFailure { fail(it) }
    }

    @Synchronized fun stop() {
        val l = listener ?: return
        listener = null
        connectedAt = 0L
        runCatching { NotificationListenerService::class.java.getMethod("unregisterAsSystemService").invoke(l) }
            .onFailure { Log.w(TAG, "unregister: ${it.message}") }
    }

    fun status(): String = when {
        error != null -> "error ($error)"
        connectedAt > 0 -> "listening"
        listener != null -> "registered, not connected yet"
        else -> "not started"
    }

    /**
     * Unparcelling a Notification reads aconfig flags (android.app.Flags, e.g. secureAllowlistToken) through
     * DeviceConfig, i.e. the Settings provider, as this process's package. The helper's process is the system context
     * ("android") under uid 2000, so the provider refuses ("Given calling package android does not match caller's uid
     * 2000", Redmi Android 15, 2026-10-31) and every notification is dropped inside NotificationListenerWrapper. Marking
     * android.app's flag namespaces as already read keeps the flags at their built-in defaults and skips the read.
     */
    private fun skipFlagReads() {
        val marked = runCatching {
            val impl = Class.forName("android.app.FeatureFlagsImpl")
            impl.declaredFields.filter {
                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Boolean::class.javaPrimitiveType &&
                    (it.name.endsWith("_is_cached") || it.name == "isCached")
            }.map { f -> f.isAccessible = true; f.setBoolean(null, true); f.name }
        }
        note("WhatsApp notices: flag reads skipped ${marked.getOrNull() ?: "failed: ${marked.exceptionOrNull()?.javaClass?.simpleName}"}")
    }

    private fun fail(t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        val msg = "${cause.javaClass.simpleName}: ${cause.message}".take(200)
        if (msg != error) { note("WhatsApp notices unavailable: $msg"); Log.w(TAG, msg, cause) }
        error = msg
    }

    /** Callbacks arrive on the helper's main looper; [report] only stores and wakes the watch loop. */
    private inner class Listener : NotificationListenerService() {
        /** The call notice being reported (key|type|intents), so updates that change nothing aren't reported again. */
        private var last: String? = null
        private var lastKey: String? = null

        override fun onListenerConnected() {
            connectedAt = SystemClock.elapsedRealtime()
            // A call already ringing or up when the helper starts isn't posted again.
            val active = runCatching { activeNotifications.orEmpty().toList() }
            note("WhatsApp notices: listening (${active.getOrNull()?.size ?: "active unreadable: ${active.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}".take(160) }}"} active)")
            active.getOrNull()?.forEach { onNotificationPosted(it) }
        }

        override fun onListenerDisconnected() {
            connectedAt = 0L
            note("WhatsApp notices: listener disconnected")
        }

        override fun onNotificationPosted(sbn: StatusBarNotification) {
            val notice = runCatching { WhatsAppNotice.of(sbn) }.getOrNull() ?: return
            val sig = "${sbn.key}|${notice.callType}|${notice.answer != null}|${notice.hangUp != null}"
            if (sig == last) return
            last = sig; lastKey = sbn.key
            report(sbn.packageName, notice.callType, notice.answer, notice.hangUp, notice.actions)
        }

        override fun onNotificationRemoved(sbn: StatusBarNotification) {
            if (sbn.key != lastKey) return
            last = null; lastKey = null
            report(sbn.packageName, WhatsAppNotice.REMOVED, null, null, 0)
        }
    }

    companion object {
        private const val TAG = "CallStreamWaNotices"
        /** UserHandle.USER_ALL, as SystemUI registers. */
        private const val USER_ALL = -1
    }
}
