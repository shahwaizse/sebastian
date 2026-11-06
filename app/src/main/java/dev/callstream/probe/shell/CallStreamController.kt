package dev.callstream.probe.shell

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.app.PendingIntent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.util.Log
import dev.callstream.probe.WhatsAppNotice
import dev.callstream.probe.leads.Lead
import dev.callstream.probe.leads.LeadStore
import java.io.File

/** Owns call automation (incoming calls, and outbound runs) independently of the Activity and individual AI sessions. */
internal class CallStreamController(private val agent: GeminiSession, private val leads: () -> LeadStore) {
    private val gate = Any()
    @Volatile private var enabled = false
    fun isEnabled(): Boolean = enabled
    @Volatile private var state = "OFF — calls are yours"
    private var monitor: Thread? = null
    private var audio: AudioManager? = null
    private var previousMute: Boolean? = null
    private var wake: PowerManager.WakeLock? = null
    private var telecom: TelecomManager? = null
    private var precise: PreciseCallMonitor? = null
    private var run: OutboundRun? = null
    /** Set when the outbound callee answers; opens the agent session's carrier audio. */
    @Volatile private var outboundAnswered = false
    /** The watch loop sleeps on this; call-state changes and the agent's first words wake it at once. */
    private val wakeSignal = Object()
    private var wakeups = 0L
    private var lastPrewarm = 0L
    /** The WhatsApp call being answered, or null for a SIM call. Its mixes live from the ring to the call's end. */
    private var voip: VoipCallAudio? = null
    private var whatsAppUids: List<Int> = emptyList()

    /** A WhatsApp call notification read by [notices] (WhatsAppNotice types); see [whatsAppNotification]. */
    private class Notified(val pkg: String, val callType: Int, val answer: PendingIntent?, val hangUp: PendingIntent?, val at: Long)
    @Volatile private var notified: Notified? = null
    private val notices = WhatsAppNotificationWatcher(::whatsAppNotification, ::note)

    /**
     * What the controller saw around calls (modes, rings, WhatsApp notices, decisions), newest last, for Send report:
     * HyperOS filters our logcat, and a call's trace starts only once a ring is recognized. No numbers or names.
     */
    private val events = ArrayDeque<String>()
    private val clock = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    private fun note(msg: String) {
        Log.i(TAG, msg)
        synchronized(events) {
            events.addLast("${clock.format(java.util.Date())} $msg")
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    fun events(): String = synchronized(events) { events.joinToString("\n") } +
        "\nwhatsapp_notices=${notices.status()} whatsapp_uids=${whatsAppUids.size}"

    /** Lab (adb whatsapp_hangup): presses WhatsApp's Hang-up as the agent's goodbye would. */
    fun labWhatsAppHangUp(): String {
        val n = notified ?: return "no WhatsApp call notice"
        val hangUp = n.hangUp ?: return "the notice (type ${n.callType}) has no Hang-up"
        return "pressed=${pressWhatsApp(dev.callstream.probe.WhatsAppCallActivity.ACTION_HANG_UP, hangUp)} notice type=${n.callType}"
    }

    /** For WhatsAppCallActivity: the current notice's Answer or Hang-up, which it fires from its visible window. */
    fun whatsAppIntent(action: String): PendingIntent? =
        notified?.let { if (action == dev.callstream.probe.WhatsAppCallActivity.ACTION_HANG_UP) it.hangUp else it.answer }

    /** From [notices] (its own thread). Wakes the watch loop at once. */
    private fun whatsAppNotification(pkg: String, callType: Int, answer: PendingIntent?, hangUp: PendingIntent?, actions: Int) {
        val now = SystemClock.elapsedRealtime()
        note("WhatsApp notice $pkg type=$callType answer=${answer != null} hangup=${hangUp != null} actions=$actions")
        notified = when {
            callType == WhatsAppNotice.REMOVED -> null
            // An ongoing call's notice keeps the ring's answer intent unless it brings its own hang-up.
            else -> Notified(pkg, callType, answer ?: notified?.answer, hangUp ?: notified?.hangUp, now)
        }
        poke()
    }

    init { agent.onReady = ::poke }

    private var appCheckedAt = 0L

    /**
     * The helper is a shell process, so uninstalling the app doesn't kill it: it kept answering calls from the deleted
     * APK (Pixel, 2026-11-01). Checked before each ring is answered or lead dialed ([urgent], at most every 2 s) and
     * about once a minute otherwise. Only "package not found" counts; any other failure keeps the helper running.
     */
    private fun appRemoved(now: Long, urgent: Boolean): Boolean {
        if (now - appCheckedAt < if (urgent) APP_CHECK_URGENT_MS else APP_CHECK_IDLE_MS) return false
        appCheckedAt = now
        return runCatching {
            AudioTools.shellContext().packageManager.getPackageInfo(dev.callstream.probe.BuildConfig.APPLICATION_ID, 0)
            false
        }.getOrElse { it is android.content.pm.PackageManager.NameNotFoundException }
    }

    /** The app is gone: leave Call Stream OFF for good (a reinstall starts OFF) and end the helper. */
    private fun quitAppRemoved() {
        note("App ${dev.callstream.probe.BuildConfig.APPLICATION_ID} is uninstalled; helper exiting")
        runCatching { File(STATE_PATH).writeText("off") }
        runCatching { File(OUTBOUND_PATH).writeText("off") }
        runCatching { agent.stop() }
        runCatching { restoreMic() }
        System.exit(0)
    }

    private fun poke() = synchronized(wakeSignal) { wakeups++; wakeSignal.notifyAll() }

    /** Sleeps up to [ms] unless poked. */
    private fun pause(ms: Long) = synchronized(wakeSignal) {
        val seen = wakeups
        val deadline = SystemClock.elapsedRealtime() + ms
        while (wakeups == seen) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            (wakeSignal as Object).wait(left)
        }
    }

    /** DNS for Gemini, kept fresh while waiting for calls (see GeminiSession.prewarm). */
    private fun prewarmSoon(now: Long) {
        if (now - lastPrewarm < PREWARM_EVERY_MS) return
        lastPrewarm = now
        Thread({ GeminiSession.prewarm() }, "callstream-prewarm").apply { isDaemon = true; start() }
    }

    fun status(): String = synchronized(gate) {
        "enabled=$enabled\n$state\nmicMuted=${audio?.isMicrophoneMute ?: false} whatsapp=${if (whatsAppUids.isNotEmpty()) "on" else "not installed"}" +
            " outbound=${if (precise != null) "available precise=${PreciseCallMonitor.name(precise!!.foreground)}" else "unavailable"}\n${agent.status()}"
    }

    /** Turns Call Stream back ON after a helper restart (e.g. reboot) if the user left it ON, and the outbound run with it. */
    fun restorePersisted(): String? {
        val wanted = runCatching { File(STATE_PATH).readText().trim() == "on" }.getOrDefault(false)
        if (!wanted) return null
        Log.i(TAG, "Restoring Call Stream ON after helper restart")
        val result = runCatching { setEnabled(true, persist = false) }.getOrElse { "restore failed: ${it.javaClass.simpleName}: ${it.message}" }
        if (enabled && runCatching { File(OUTBOUND_PATH).readText().trim() == "on" }.getOrDefault(false))
            runCatching { startOutbound("[]") }.onFailure { Log.w(TAG, "Outbound run not restored: ${it.message}") }
        return result
    }

    /**
     * [persist] = this is the user's choice (app switch, stream_on/off), kept across helper restarts and
     * reboots. Shutdowns, helper replacement and lab operations pass false so they never overwrite it.
     */
    fun setEnabled(value: Boolean, persist: Boolean = true): String {
        if (persist) runCatching { File(STATE_PATH).writeText(if (value) "on" else "off") }
        if (!value) {
            synchronized(gate) { enabled = false; state = "OFF — calls are yours" }
            // Stop injection immediately; do not end the carrier call.
            agent.stop()
            monitor?.join(1500)
            synchronized(gate) {
                restoreMic(); releaseWake()
                run?.let { it.end(Lead.TAKEN_OVER, "Call Stream turned off; the call was left with you", SystemClock.elapsedRealtime(), 0); it.stop() }
                if (persist) runCatching { File(OUTBOUND_PATH).writeText("off") }
            }
            return status()
        }
        if (!enabled) agent.stop()
        synchronized(gate) {
            if (enabled) return status()
            check(monitor?.isAlive != true) { "Still stopping; try again shortly" }
            check(File(GeminiSession.CONFIG_PATH).canRead()) { "No agent profiles on this phone yet: open the app once" }
            val ctx = object : ContextWrapper(AudioTools.shellContext()) {
                override fun getApplicationContext(): Context = this
                override fun getOpPackageName(): String = "com.android.shell"
                override fun getPackageName(): String = "com.android.shell"
                override fun getAttributionSource() = baseContext.attributionSource
            }
            // Service registry caches managers using the original app context. Construct
            // these two with the shell context so Telecom/AppOps see the real binder UID.
            audio = AudioManager::class.java.getConstructor(Context::class.java).newInstance(ctx)
            val telecom = TelecomManager::class.java.getConstructor(Context::class.java).newInstance(ctx)
            this.telecom = telecom
            whatsAppUids = WhatsAppCalls.uids(ctx.packageManager)
            // Outbound needs to know when the callee answers; incoming calls work without it.
            precise = runCatching { PreciseCallMonitor(ctx, ::poke).also { it.start() } }
                .onFailure { Log.w(TAG, "Precise call state unavailable; outbound disabled: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            // Permission/attribution preflight without answering or changing a call.
            val ringingNow = isRinging(telecom)
            if (!ringingNow && !telecom.isInCall) {
                val oldMute = audio!!.isMicrophoneMute
                try {
                    audio!!.isMicrophoneMute = true
                    check(audio!!.isMicrophoneMute) { "Microphone mute is unavailable" }
                } finally { audio!!.isMicrophoneMute = oldMute }
                check(audio!!.isMicrophoneMute == oldMute) { "Microphone restoration failed" }
                Log.i(TAG, "Idle microphone mute/restore preflight passed")
            }
            wake = ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callstream:incoming").apply { acquire() }
            // Build the HTTP client now rather than at the first ring.
            Thread({ GeminiSession.preload() }, "callstream-preload").apply { isDaemon = true; start() }
            lastPrewarm = 0L
            enabled = true
            state = "READY — AI answers once the agent is connected; mic muted during AI calls"
            monitor = Thread({ watch(telecom) }, "callstream-incoming").also { it.start() }
            if (whatsAppUids.isNotEmpty()) notices.start()
        }
        return status()
    }

    /**
     * Starts dialing the lead sheet, one call at a time, until stopped (it stays armed for callbacks). Any
     * [contactsJson] (`[{"name", "number"}, …]`) is added to the sheet first. Call Stream must be ON.
     */
    fun startOutbound(contactsJson: String): String = synchronized(gate) {
        check(enabled) { "Turn Call Stream on first" }
        check(precise != null) { "Outbound isn't available: this phone doesn't report when a call is answered" }
        if (contactsJson.isNotBlank() && contactsJson.trim() != "[]") leads().import(contactsJson)
        if (run?.finished == false) return outboundStatus()
        run = OutboundRun(leads())
        runCatching { File(OUTBOUND_PATH).writeText("on") }
        Log.i(TAG, "Outbound run started")
        outboundStatus()
    }

    /** Places no further calls; a call in progress continues to its end. */
    fun stopOutbound(): String = synchronized(gate) {
        run?.stop()
        runCatching { File(OUTBOUND_PATH).writeText("off") }
        outboundStatus()
    }

    fun outboundStatus(): String = synchronized(gate) {
        run?.toJson()?.toString() ?: OutboundRun.idleJson(runCatching { leads().version }.getOrDefault(0L))
    }

    private fun placeCall(telecom: TelecomManager, number: String) {
        val extras = Bundle()
        // Dual-SIM phones would otherwise ask which SIM to use.
        (telecom.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL) ?: telecom.callCapablePhoneAccounts.firstOrNull())
            ?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        telecom.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras)
    }

    private fun isRinging(telecom: TelecomManager): Boolean =
        TelecomManager::class.java.getMethod("isRinging").invoke(telecom) as Boolean

    private fun closeVoip() { voip?.close(); voip = null }

    private enum class Phase { IDLE, PREPARING, DIALING, ANSWERED }

    /**
     * IDLE → PREPARING: an incoming call rings; connect the agent while it keeps ringing.
     * PREPARING → ANSWERED: answer once the Gemini session is set up and the greeting requested
     * (its first audio comes ~0.6 s later, about when the answered call goes active; it's held until
     * carrier playback), or after MAX_PREPARE_MS if still connecting. If the agent fails, or the
     * caller hangs up, or the owner answers by hand, cancel and leave the call alone.
     * ANSWERED → IDLE: carrier call ends (or never became active); restore the mic. If the agent
     * hangs up or its session dies mid-call, the carrier call is ended first.
     *
     * Outbound: IDLE → DIALING when the lead sheet has a lead due and the line is free: mute the mic,
     * connect the agent and place the call. Its greeting is held while the callee's phone rings.
     * DIALING → ANSWERED when the precise call state turns ACTIVE: the held greeting plays from
     * its first word. No answer within RING_TIMEOUT_MS, a call that ends unanswered, or an agent
     * failure ends that lead's call; the next is dialed OUTBOUND_GAP_MS after the line is free.
     * An answered call's talk time is saved, and its transcript is read into the sheet afterwards.
     */
    @Suppress("DEPRECATION")
    private fun watch(telecom: TelecomManager) {
        var phase = Phase.IDLE
        var connected = false
        var hungUp = false
        var hungUpAt = 0L
        var hangUpRetried = false
        var ringingAt = 0L
        var answeredAt = 0L
        var dialAt = 0L
        var outbound = false
        var sawCall = false
        var sawDialing = false
        var callTrace: CallLatencyTrace? = null
        /** The ringing call as Telecom described it (looked up once per ring); null until known. */
        var ring: WhatsAppCalls.Ring? = null
        var ringLookups = 0
        var lastMode = -99
        var lastTelecomRinging = false
        fun reset(next: String) {
            callTrace = null; phase = Phase.IDLE; connected = false; hungUp = false; hangUpRetried = false
            outbound = false; outboundAnswered = false
            closeVoip()
            state = next
        }
        fun readyState() = if (run?.finished == false) "OUTBOUND — next call shortly" else "READY — waiting for next incoming call"
        /** Ends the current outbound lead's call before it was answered. */
        fun endUnanswered(status: String, note: String, now: Long) {
            agent.stop(); restoreMic()
            callTrace?.finish("outbound_${status.replace(' ', '_')}")
            run?.end(status, note, now, OUTBOUND_GAP_MS)
            reset(readyState())
            Log.i(TAG, "Outbound call ended unanswered: $status")
        }
        try {
            var skipCurrent = telecom.isInCall // Never attach AI to an existing/outgoing call.
            while (enabled) {
                synchronized(gate) {
                    if (!enabled) return@synchronized
                    val am = audio!!
                    val inCall = telecom.isInCall
                    val now = SystemClock.elapsedRealtime()
                    // Telecom's isRinging covers self-managed VoIP calls too (WhatsApp, Discord…), and it turns true before
                    // the mode is MODE_RINGTONE (2026-10-25: a WhatsApp ring went down the SIM path). Which app rings
                    // comes from Telecom, once per ring.
                    val ringtone = am.mode == AudioManager.MODE_RINGTONE
                    val telecomRinging = isRinging(telecom)
                    if (am.mode != lastMode || telecomRinging != lastTelecomRinging) {
                        note("mode=${am.mode} telecomRinging=$telecomRinging inCall=$inCall phase=$phase")
                        lastMode = am.mode; lastTelecomRinging = telecomRinging
                    }
                    if (!ringtone && !telecomRinging) { ring = null; ringLookups = 0 }
                    else if ((ring == null || ring === WhatsAppCalls.NOT_YET) && phase == Phase.IDLE) {
                        // An unreadable dump, or ~1 s without the call in it: treat it as a SIM call (the old behaviour).
                        val found = WhatsAppCalls.ringingCall()
                        ring = when {
                            found == null -> WhatsAppCalls.Ring("", voip = false)
                            found === WhatsAppCalls.NOT_YET && ++ringLookups >= 10 -> WhatsAppCalls.Ring("", voip = false)
                            else -> found
                        }
                        if (ring !== WhatsAppCalls.NOT_YET) note("Ringing (Telecom): ${ring!!.app.ifEmpty { "unknown" }}${if (ring!!.voip) " (VoIP)" else ""}")
                    }
                    // Lab switch: ignore Telecom for WhatsApp, as on phones where WhatsApp doesn't use it (the Redmi).
                    val telecomForWhatsApp = !File(NOTIFICATION_ONLY_PATH).exists()
                    // The notification path: WhatsApp's own incoming-call notice (any phone, Telecom or not).
                    val notifiedRing = notified?.takeIf { it.callType == WhatsAppNotice.INCOMING && now - it.at < NOTICE_RING_MS }
                    val telecomWhatsApp = ring?.whatsApp == true && telecomForWhatsApp
                    val whatsAppRing = telecomWhatsApp || notifiedRing != null
                    // A VoIP app's ring is never answered as a SIM call; nor one not identified yet.
                    val ringing = telecomRinging && ring != null && ring !== WhatsAppCalls.NOT_YET && !ring!!.voip
                    if (phase == Phase.IDLE && appRemoved(now, urgent = ringing || whatsAppRing || run != null)) quitAppRemoved()
                    if (skipCurrent) {
                        if (!inCall && !telecomRinging && !ringtone && notifiedRing == null && am.mode != AudioManager.MODE_IN_COMMUNICATION) skipCurrent = false
                    } else when (phase) {
                        Phase.IDLE -> {
                            // The sheet is read again before each call, so a callback that fell due meanwhile goes first.
                            // A WhatsApp ring keeps the line busy too: no lead is dialed over it (Telecom's isInCall lags it).
                            val lineFree = !ringing && !inCall && !whatsAppRing
                            val lead = if (lineFree) run?.begin(now) else null
                            if (lead == null && lineFree) {
                                prewarmSoon(now)
                                run?.let { r ->
                                    if (r.finished) { run = null; if (state.startsWith("OUTBOUND")) state = "READY — outbound stopped; waiting for incoming calls" }
                                    else if (r.waiting.isNotEmpty()) state = "OUTBOUND — waiting: ${r.waiting}"
                                }
                            } else if (lead != null) {
                                callTrace = CallLatencyTrace().also { it.mark("outbound_dial_start", mapOf("lead_id" to lead.id, "call_no" to lead.callNo)) }
                                previousMute = am.isMicrophoneMute
                                am.isMicrophoneMute = true // before the call exists: nothing from this room goes out
                                outboundAnswered = false
                                // The session opens carrier audio the moment the precise state says answered (its own
                                // 2 ms check of these volatiles), not on this loop's next pass.
                                val monitor = precise!!
                                monitor.armDialing()
                                agent.arm(trace = callTrace, prepare = true, contactName = lead.name, contactNotes = run?.notes(lead), carrierGate = {
                                    outboundAnswered || (monitor.sawDialing && monitor.foreground == PreciseCallMonitor.ACTIVE)
                                })
                                try {
                                    placeCall(telecom, lead.number)
                                    callTrace?.mark("outbound_call_placed")
                                    outbound = true; sawCall = false; sawDialing = false
                                    dialAt = now; phase = Phase.DIALING
                                    state = "OUTBOUND — calling ${lead.name.ifEmpty { lead.number }}" + if (lead.callNo > 1) " (call ${lead.callNo})" else ""
                                    Log.i(TAG, "Outbound call placed (lead ${lead.id}, call ${lead.callNo}); agent preparing")
                                } catch (t: Throwable) {
                                    endUnanswered(Lead.FAILED, "Couldn't place the call: ${t.message}", now)
                                }
                            } else if (whatsAppRing && !simCallUp() && whatsAppUids.isNotEmpty()) {
                                val app = if (telecomWhatsApp) ring!!.app else notifiedRing!!.pkg
                                callTrace = CallLatencyTrace().also { it.mark("ringing_observed", mapOf("call_path" to "whatsapp", "app" to app,
                                    "seen_by" to if (telecomWhatsApp) "telecom" else "notification")) }
                                // Before answering: WhatsApp opens its mic and player at answer, straight into these.
                                // Registered alongside the Gemini connection; the answer waits for both.
                                voip = VoipCallAudio.openAsync(whatsAppUids)
                                agent.arm(trace = callTrace, prepare = true, voip = voip)
                                ringingAt = now; phase = Phase.PREPARING
                                state = "PREPARING — WhatsApp call; connecting agent while ringing"
                                note("WhatsApp call ringing (${if (telecomWhatsApp) "Telecom" else "notification"}); preparing agent before answer")
                            } else if (ringing && am.mode !in ACTIVE_MODES) {
                                callTrace = CallLatencyTrace().also { it.mark("ringing_observed") }
                                agent.arm(trace = callTrace, prepare = true)
                                ringingAt = now; phase = Phase.PREPARING
                                state = "PREPARING — connecting agent while ringing"
                                Log.i(TAG, "Incoming call ringing; preparing agent before answer")
                            }
                        }
                        Phase.PREPARING -> when {
                            if (voip != null) !ringtone && !telecomRinging && notifiedRing == null else !ringing -> {
                                // Caller hung up, or the owner picked up by hand: the call isn't ours.
                                agent.stop()
                                callTrace?.mark(if (inCall) "answered_manually_while_preparing" else "ringing_ended_while_preparing")
                                skipCurrent = inCall
                                reset("READY — waiting for next incoming call")
                                note("Ringing ended before answer; agent session cancelled (mode ${am.mode})")
                            }
                            voip?.let { it.finished && !it.ready } == true -> {
                                callTrace?.mark("voip_audio_failed", mapOf("error" to (voip?.error ?: "")))
                                agent.stop()
                                skipCurrent = true
                                reset("READY — WhatsApp audio unavailable; last call left ringing for you")
                                note("WhatsApp call audio unavailable (${voip?.error}); call left ringing")
                            }
                            !agent.isLive() -> {
                                // Don't answer into silence; leave the call ringing for a person.
                                callTrace?.mark("prepare_failed_left_ringing")
                                agent.stop()
                                skipCurrent = true
                                reset("READY — agent unavailable; last call left ringing for you")
                                Log.w(TAG, "Agent session failed while ringing; call left for manual answer")
                            }
                            (agent.readyToAnswer() || now - ringingAt > MAX_PREPARE_MS) && voip?.ready != false -> {
                                voip?.let { callTrace?.mark("voip_audio_registered", mapOf("extractor" to (it.extractorError ?: "ok"))) }
                                callTrace?.mark("answer_ready", mapOf("agent_ready" to agent.readyToAnswer(), "prepare_ms" to now - ringingAt))
                                // WhatsApp: the injector already stands in for its mic; the room never reaches the call.
                                if (voip == null) {
                                    previousMute = am.isMicrophoneMute
                                    am.isMicrophoneMute = true // Mute before answer, then enforce after Telecom resets.
                                    check(am.isMicrophoneMute) { "Pixel microphone could not be muted" }
                                }
                                callTrace?.mark("answer_request_start")
                                // WhatsApp: Telecom's answer where WhatsApp uses Telecom, else its notification's Answer.
                                val notice = notified
                                val viaNotice = voip != null && !telecomWhatsApp
                                if (viaNotice) {
                                    if (notice?.answer == null) note("WhatsApp notice has no Answer intent")
                                    else pressWhatsApp(dev.callstream.probe.WhatsAppCallActivity.ACTION_ANSWER, notice.answer)
                                } else telecom.acceptRingingCall()
                                callTrace?.mark("answer_requested", mapOf("via" to if (viaNotice) "notification" else "telecom"))
                                answeredAt = now; connected = false; phase = Phase.ANSWERED
                                state = "ANSWERING — agent ready"
                                note(if (voip != null) "WhatsApp call auto-answer requested (${if (viaNotice) "notification" else "Telecom"})"
                                    else "Incoming call auto-answer requested; microphone muted")
                            }
                        }
                        Phase.DIALING -> {
                            if (!am.isMicrophoneMute) am.isMicrophoneMute = true
                            if (inCall) sawCall = true
                            val callState = precise?.foreground ?: PreciseCallMonitor.NOT_VALID
                            if (callState == PreciseCallMonitor.DIALING || callState == PreciseCallMonitor.ALERTING) sawDialing = true
                            when {
                                sawCall && sawDialing && callState == PreciseCallMonitor.ACTIVE -> {
                                    outboundAnswered = true
                                    callTrace?.mark("outbound_answered", mapOf("dial_to_answer_ms" to now - dialAt, "agent_ready" to agent.readyToAnswer()))
                                    run?.update(Lead.IN_CALL)
                                    answeredAt = now; connected = false; phase = Phase.ANSWERED
                                    state = "AI CALL — outbound; mic muted"
                                    Log.i(TAG, "Outbound call answered")
                                }
                                sawCall && !inCall -> endUnanswered(Lead.NOT_CONNECTED, "Declined, busy or unreachable", now)
                                !sawCall && now - dialAt > PLACE_TIMEOUT_MS -> endUnanswered(Lead.FAILED, "The call didn't start", now)
                                now - dialAt > RING_TIMEOUT_MS -> {
                                    callTrace?.mark("outbound_ring_timeout")
                                    runCatching { telecom.endCall() }
                                    endUnanswered(Lead.NO_ANSWER, "No answer after ${RING_TIMEOUT_MS / 1000} s", now)
                                }
                                !agent.isLive() -> {
                                    // Don't let the callee pick up to silence.
                                    runCatching { telecom.endCall() }
                                    endUnanswered(Lead.FAILED, "Agent unavailable", now)
                                }
                                callState == PreciseCallMonitor.ALERTING -> run?.update(Lead.RINGING)
                            }
                        }
                        Phase.ANSWERED -> {
                            val viaWhatsApp = voip != null
                            if (!viaWhatsApp) {
                                if (!am.isMicrophoneMute) am.isMicrophoneMute = true
                                check(am.isMicrophoneMute) { "Pixel microphone mute was lost" }
                            }
                            if (if (viaWhatsApp) am.mode == AudioManager.MODE_IN_COMMUNICATION else am.mode in ACTIVE_MODES) {
                                if (!connected) {
                                    callTrace?.mark("carrier_active_controller")
                                    if (viaWhatsApp) checkWhatsAppMic(am, callTrace)
                                }
                                connected = true
                                state = (if (viaWhatsApp) "AI CALL — WhatsApp; room mic out of the call" else "AI CALL — Pixel mic muted") +
                                    "\n${agent.status().lineSequence().first()}"
                            }
                            // The agent said goodbye, or the session died (error, network, time limit): end the carrier
                            // call too, so the caller isn't left in silence with the mic muted.
                            val agentEnded = agent.endedByAgent()
                            // A WhatsApp call is up while the mode is IN_COMMUNICATION (Telecom's isInCall isn't needed).
                            val callUp = if (viaWhatsApp) am.mode == AudioManager.MODE_IN_COMMUNICATION else inCall
                            if (connected && !hungUp && callUp && (agentEnded || !agent.isLive())) {
                                hungUp = true; hungUpAt = now
                                callTrace?.mark("carrier_hangup_requested", mapOf("reason" to if (agentEnded) "agent_end_call" else "session_ended"))
                                // WhatsApp: its notification's hang-up where we have one (Telecom's endCall may not reach it).
                                val hangUp = if (viaWhatsApp) notified?.hangUp else null
                                val ended = if (hangUp != null) pressWhatsApp(dev.callstream.probe.WhatsAppCallActivity.ACTION_HANG_UP, hangUp) || telecom.endCall()
                                    else telecom.endCall()
                                callTrace?.mark("carrier_hangup_returned", mapOf("ended" to ended))
                                note("Hangup requested (agent=$agentEnded, ended=$ended${if (viaWhatsApp) ", whatsapp via ${if (hangUp != null) "notification" else "Telecom"}" else ""})")
                            }
                            // Still up 5 s after the hang-up: once more, both ways. A call nobody ends would run until the caller
                            // gives up, holding the phone (the agent session is already over).
                            if (hungUp && !hangUpRetried && callUp && now - hungUpAt > HANG_UP_RETRY_MS) {
                                hangUpRetried = true
                                val retryHangUp = if (viaWhatsApp) notified?.hangUp else null
                                val pressed = retryHangUp?.let { pressWhatsApp(dev.callstream.probe.WhatsAppCallActivity.ACTION_HANG_UP, it) } ?: false
                                val ended = runCatching { telecom.endCall() }.getOrDefault(false)
                                callTrace?.mark("carrier_hangup_retried", mapOf("pressed" to pressed, "ended" to ended))
                                note("Call still up ${HANG_UP_RETRY_MS / 1000} s after hang-up; retried (whatsapp=$pressed, telecom=$ended)")
                            }
                            if ((connected && !callUp && (viaWhatsApp || !ringing)) || (!connected && now - answeredAt > 10_000)) {
                                agent.stop(); restoreMic()
                                if (!connected) callTrace?.finish("answer_did_not_become_active")
                                // A WhatsApp call that didn't connect still rings with the same notice: leave it to a person.
                                if (!connected && viaWhatsApp) { skipCurrent = true; note("WhatsApp answer didn't connect; call left for you") }
                                callTrace?.mark("microphone_restored")
                                if (outbound) {
                                    val talk = if (connected) ((now - answeredAt) / 1000).toInt() else 0
                                    run?.let { r ->
                                        r.end(if (connected) Lead.COMPLETED else Lead.NOT_CONNECTED, "", now, OUTBOUND_GAP_MS, talk)
                                            ?.let { lead -> if (connected) r.extractLater(lead, agent.takeTranscript(), talk, agent.callConfig()) }
                                    }
                                }
                                reset(readyState())
                                note("Call finished; ready again")
                            }
                        }
                    }
                }
                // Poll faster while a call is being set up; a ring, an answer or the agent's greeting wakes it early.
                pause(if (phase == Phase.IDLE) 100 else 20)
            }
        } catch (t: Throwable) {
            synchronized(gate) {
                enabled = false
                state = "ERROR — ${t.javaClass.simpleName}: ${t.message}"
            }
            Log.e(TAG, state)
        } finally {
            agent.stop()
            synchronized(gate) {
                restoreMic(); releaseWake(); closeVoip()
                precise?.stop(); precise = null
                notices.stop(); notified = null
                callTrace?.mark("microphone_restored")
                if (phase == Phase.PREPARING) callTrace?.finish("controller_stopped_before_answer")
            }
        }
    }

    /**
     * Presses WhatsApp's Answer or Hang-up ([action]) through WhatsAppCallActivity, started with `am start`; [fallback]
     * is sent from here if that can't start. Sent from the helper itself, the Answer (which opens WhatsApp's call screen)
     * was blocked as a background activity start on Android 17 even with the opt-in (BAL_BLOCK, 2026-10-25).
     */
    private fun pressWhatsApp(action: String, fallback: PendingIntent): Boolean {
        val component = "${dev.callstream.probe.BuildConfig.APPLICATION_ID}/dev.callstream.probe.WhatsAppCallActivity"
        val out = runCatching {
            val p = ProcessBuilder("am", "start", "-n", component, "--es", "action", action).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { it.readText() }.trim().also { p.waitFor() }
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        val started = !out.contains("Error") && !out.contains("Exception")
        note("WhatsApp $action via screen: ${if (started) "started" else out.take(200)}")
        if (started) return true
        return runCatching { sendFromBackground(fallback); true }
            .onFailure { note("WhatsApp $action intent failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)
    }

    /** Fires a WhatsApp notification intent from the helper, opted in to background activity starts. */
    private fun sendFromBackground(intent: PendingIntent) {
        val options = if (android.os.Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
        intent.send(null, 0, null, null, null, null, options)
    }

    /** A WhatsApp ring while a SIM call is up is left alone. */
    private fun simCallUp() = (audio?.mode ?: 0) in ACTIVE_MODES

    /**
     * Once a WhatsApp call is up, records where its recorder is (it should be our injector, the remote submix). Log
     * only: muting the mic here also silenced the agent's injected audio (2026-10-25), and a recorder can report no
     * device (-1) while on the injector.
     */
    private fun checkWhatsAppMic(am: AudioManager, trace: CallLatencyTrace?) {
        // getClientUid is a system API; the shell sees real uids.
        val uidOf = android.media.AudioRecordingConfiguration::class.java.getMethod("getClientUid")
        val recs = runCatching { am.activeRecordingConfigurations.filter { (uidOf.invoke(it) as Int) in whatsAppUids } }.getOrDefault(emptyList())
        val devices = recs.map { it.audioDevice?.type ?: -1 }
        trace?.mark("whatsapp_mic_checked", mapOf("recorders" to recs.size, "device_types" to devices.joinToString(",")))
        // HyperOS reports 15 (built-in mic) here while the policy dump shows the recorder on our injector (Redmi
        // 2026-10-25), so this is a hint only; `dumpsys media.audio_policy` (Inputs) is the truth.
        note("WhatsApp recorder device reported as $devices (hint only; the policy dump decides)")
    }

    private fun restoreMic() {
        previousMute?.let { old ->
            // Keep restoration pending if it fails, so another OFF can retry it.
            audio!!.isMicrophoneMute = old
            previousMute = null
            Log.i(TAG, "Microphone restored to previous state")
        }
    }
    private fun releaseWake() { wake?.let { if (it.isHeld) it.release() }; wake = null }
    companion object {
        private const val TAG = "CallStreamControl"
        private const val STATE_PATH = "/data/local/tmp/call-stream-enabled"
        /** "on" while an outbound run is wanted: it resumes after a helper restart. */
        private const val OUTBOUND_PATH = "/data/local/tmp/call-stream-outbound"
        private val ACTIVE_MODES = intArrayOf(2, 4, 5)
        // Answer anyway if the agent is still connecting; carriers divert to voicemail after ~20–30 s.
        private const val MAX_PREPARE_MS = 10_000L
        /** Carriers usually divert unanswered calls to voicemail or drop them by ~30–60 s. */
        private const val RING_TIMEOUT_MS = 45_000L
        private const val PLACE_TIMEOUT_MS = 10_000L
        private const val OUTBOUND_GAP_MS = 3_000L
        private const val PREWARM_EVERY_MS = 45_000L
        /** Lab: this file present makes the helper ignore Telecom for WhatsApp (tests the notification path on a Pixel). */
        const val NOTIFICATION_ONLY_PATH = "/data/local/tmp/call-stream-whatsapp-notification-only"
        /** A WhatsApp ring notice older than this is stale (WhatsApp stops ringing after ~45-60 s). */
        private const val NOTICE_RING_MS = 90_000L
        private const val MAX_EVENTS = 200
        private const val APP_CHECK_URGENT_MS = 2_000L
        private const val APP_CHECK_IDLE_MS = 60_000L
        private const val HANG_UP_RETRY_MS = 5_000L
    }
}
