package dev.callstream.probe.shell

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.PreciseCallState
import android.telephony.TelephonyManager
import java.util.concurrent.Executors

/**
 * The foreground call's precise state (dialing, alerting, active…). Public APIs report OFFHOOK from
 * the moment an outgoing call is dialed; only the precise state says when the callee answers.
 * Needs READ_PRECISE_PHONE_STATE, which the shell uid holds. PreciseCallState and the listener
 * callback are system APIs: compiled against the :hidden-api stub, dispatched by the framework.
 */
@Suppress("DEPRECATION")
internal class PreciseCallMonitor(ctx: Context, private val onChange: () -> Unit = {}, subId: Int? = null) {
    @Volatile var foreground = NOT_VALID
        private set
    /** Ringing-call state: [INCOMING] while an incoming call rings. */
    @Volatile var ringing = NOT_VALID
        private set
    /** Outbound: the foreground call has been DIALING or ALERTING since [armDialing]. */
    @Volatile var sawDialing = false
        private set
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "callstream-precise").apply { isDaemon = true } }
    private val telephony = run {
        ensureTelephonyFramework()
        val base = TelephonyManager::class.java.getConstructor(Context::class.java).newInstance(ctx)
        // Dual SIM: the plain manager follows one default subscription; a call on the other SIM never shows up.
        if (subId != null) base.createForSubscriptionId(subId) else base
    }

    /** The active SIMs' subscription ids (empty if they can't be read). */
    fun subscriptionIds(): List<Int> = runCatching {
        val n = telephony.activeModemCount
        (0 until n).mapNotNull { slot ->
            runCatching {
                android.telephony.SubscriptionManager::class.java.getMethod("getSubscriptionId", Int::class.javaPrimitiveType)
                    .invoke(null, slot) as Int
            }.getOrNull()?.takeIf { it >= 0 }
        }.distinct()
    }.getOrDefault(emptyList())
    private val listener = object : PhoneStateListener(executor) {
        // Same name and signature as the hidden PhoneStateListener method, so the framework calls it.
        @Suppress("unused")
        fun onPreciseCallStateChanged(state: PreciseCallState) {
            val fg = state.foregroundCallState
            foreground = fg
            ringing = runCatching { state.ringingCallState }.getOrDefault(NOT_VALID)
            if (fg == DIALING || fg == ALERTING) sawDialing = true
            onChange() // wakes the controller now instead of on its next poll
        }
    }

    /** Clears [sawDialing] before placing a call. */
    fun armDialing() { sawDialing = false }

    fun start() = telephony.listen(listener, LISTEN_PRECISE_CALL_STATE)
    fun stop() { runCatching { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }; executor.shutdown() }

    companion object {
        /** ActivityThread registers telephony's service manager in normal app processes; our hand-built
         * helper process skips that, so TelephonyManager would find no services. Register it once. */
        private fun ensureTelephonyFramework() {
            val initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer")
            if (initializer.getMethod("getTelephonyServiceManager").invoke(null) != null) return
            val manager = Class.forName("android.os.TelephonyServiceManager").getConstructor().newInstance()
            initializer.getMethod("setTelephonyServiceManager", manager.javaClass).invoke(null, manager)
        }

        private const val LISTEN_PRECISE_CALL_STATE = 0x00000800
        const val NOT_VALID = -1
        const val IDLE = 0
        const val ACTIVE = 1
        const val DIALING = 3
        const val ALERTING = 4
        const val INCOMING = 5
        const val DISCONNECTED = 7
        const val DISCONNECTING = 8
        fun name(state: Int) = when (state) {
            IDLE -> "idle"; ACTIVE -> "active"; 2 -> "holding"; DIALING -> "dialing"; ALERTING -> "alerting"
            INCOMING -> "incoming"; 6 -> "waiting"; DISCONNECTED -> "disconnected"; DISCONNECTING -> "disconnecting"; else -> "unknown"
        }
    }
}
