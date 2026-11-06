package dev.callstream.probe.shell

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sebastian's call watcher. While ON, every SIM call on this phone gets Sebastian once it's connected (one he
 * dialed, one Rayyan dialed himself, or one Rayyan answered). He never answers, mutes or hangs up anything:
 * the calls stay Rayyan's. When the call ends, its transcript goes to sebastian-brain for the notes and the
 * WhatsApp summary.
 */
internal class SebastianController {
    @Volatile private var enabled = false
    @Volatile private var state = "OFF"
    private var monitor: Thread? = null
    private var audio: AudioManager? = null
    private var telecom: TelecomManager? = null
    private var precise: PreciseCallMonitor? = null
    /** WhatsApp's call audio hooks, held while Sebastian is ON (see WhatsAppTap). */
    @Volatile private var tap: WhatsAppTap? = null
    private var lastSeen = ""
    @Volatile private var session: SebastianSession? = null
    /** Who Sebastian dialed, for the session's labels; cleared when the call ends. */
    @Volatile private var dialed: SebastianConfig.Contact? = null
    @Volatile private var dialedAt = 0L
    /** Who a call Sebastian didn't dial is with (incoming, or dialed by Rayyan), read from WhatsApp's notification. */
    @Volatile private var identified: SebastianConfig.Contact? = null
    private val events = ArrayDeque<String>()
    private val wake = Object()

    fun log(msg: String) {
        Log.i(TAG, msg)
        synchronized(events) { events.addLast("${System.currentTimeMillis() / 1000 % 100000} $msg"); while (events.size > 60) events.removeFirst() }
        // Android's log buffer loses a call's lines within minutes: keep them in a file too (a debugging aid).
        runCatching {
            val f = File(LOG_FILE).also { it.parentFile?.mkdirs() }
            if (f.length() > 1_500_000) { File("$LOG_FILE.1").delete(); f.renameTo(File("$LOG_FILE.1")) }
            f.appendText("${java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg\n")
        }
    }

    /** Saves the settings file the app's "Import agent file" built from a Sebastian setup file plus the person's own key. */
    fun setConfig(json: String): String {
        val o = JSONObject(json)
        val key = o.optString("assemblyai_api_key").trim()
        require(key.length >= 16) { "That doesn't look like an AssemblyAI API key" }
        checkKey(key) // like Call Stream's Gemini key: checked with the provider before it's saved
        o.put("assemblyai_api_key", key)
        o.remove("sebastian_setup")
        File(SebastianConfig.DIR).mkdirs()
        File(SebastianConfig.PATH).writeText(o.toString())
        shell("chmod", "600", SebastianConfig.PATH)
        log("settings saved from a setup file")   // the key itself is never logged
        return "OK: saved. Turn Sebastian ON."
    }

    /** Asks AssemblyAI for a 60 s streaming token with [key]: throws when the key is refused or AssemblyAI can't be reached. */
    private fun checkKey(key: String) {
        val body = JSONObject().put("product", "streaming").put("expires_in_seconds", 60).put("max_session_duration_seconds", 60)
        val req = okhttp3.Request.Builder().url("https://token.assemblyai.com/v1/token")
            .header("authorization", key)
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString())).build()
        val code = try { SebastianSession.CLIENT.newCall(req).execute().use { it.code } }
            catch (e: Exception) { throw IllegalStateException("Couldn't reach AssemblyAI to check the key. Check the Wi-Fi and try again.") }
        if (code == 401 || code == 403) throw IllegalStateException("AssemblyAI says that key isn't valid.")
        check(code in 200..299) { "AssemblyAI answered HTTP $code when checking the key." }
    }

    fun status(): String = "enabled=$enabled\n$state\nsession=${session?.status() ?: "none"}\n" +
        synchronized(events) { events.joinToString("\n") }

    fun restorePersisted(): String? = if (File(SebastianConfig.ENABLED_PATH).exists()) setEnabled(true) else null

    @Synchronized fun setEnabled(on: Boolean): String {
        if (on == enabled) return status()
        if (!on) {
            enabled = false
            File(SebastianConfig.ENABLED_PATH).delete()
            synchronized(wake) { wake.notifyAll() }
            monitor?.join(3_000)
            endSession("turned off")
            precise?.stop(); precise = null
            tap?.close(); tap = null
            state = "OFF"
            return status()
        }
        SebastianConfig.read() // fails early without a config
        val ctx = object : ContextWrapper(AudioTools.shellContext()) {
            override fun getApplicationContext(): Context = this
            override fun getOpPackageName(): String = "com.android.shell"
            override fun getPackageName(): String = "com.android.shell"
            override fun getAttributionSource() = baseContext.attributionSource
        }
        audio = AudioManager::class.java.getConstructor(Context::class.java).newInstance(ctx)
        telecom = TelecomManager::class.java.getConstructor(Context::class.java).newInstance(ctx)
        precise = runCatching { PreciseCallMonitor(ctx, { synchronized(wake) { wake.notifyAll() } }).also { it.start() } }
            .onFailure { log("precise call state unavailable, using the audio mode: ${it.message}") }.getOrNull()
        val observer = SebastianConfig.read().optBoolean("observer", false)
        tap = runCatching {
            val uids = WhatsAppCalls.uids(ctx.packageManager)
            if (observer) WhatsAppTap.openObserver(uids) else WhatsAppTap.open(uids)
        }.onFailure { log("WhatsApp hooks unavailable, SIM calls only: ${it.javaClass.simpleName}: ${it.cause?.message ?: it.message}") }.getOrNull()
        if (tap != null) log(if (observer) "WhatsApp observer ready (WhatsApp keeps its mic)" else "WhatsApp hooks ready")
        enabled = true
        File(SebastianConfig.DIR).mkdirs()
        File(SebastianConfig.ENABLED_PATH).writeText("1")
        state = "READY — waiting for a call"
        monitor = Thread(::watch, "sebastian-watch").apply { isDaemon = true; start() }
        Thread(::flushOutbox, "sebastian-outbox").apply { isDaemon = true; start() }
        return status()
    }

    /** After "Hey Sebastian": the recorded command, transcribed and acted on. Returns what happened, for the notification. */
    fun command(pcm: ByteArray): String {
        check(enabled) { "Sebastian is off" }
        val cfg = SebastianConfig.read()
        val names = (cfg.optJSONArray("contacts") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }
        val text = SebastianCommand.transcribe(cfg, pcm, names)
        return commandText(text)
    }

    /** A transcribed command ("Hey Sebastian, call Vigotel."): find the contact, say so, place the call. */
    fun commandText(text: String): String {
        check(enabled) { "Sebastian is off" }
        // The "Hey Sebastian, call …" listener is for when no call is on: in a call the session answers, and this would talk over it.
        if (session != null || audio?.mode != AudioManager.MODE_NORMAL) {
            log("command ignored, a call is on: $text")
            return "Ignored: in a call"
        }
        val cfg = SebastianConfig.read()
        log("command heard: $text")
        val name = SebastianCommand.callee(text)
            ?: run { SebastianCommand.speak(cfg, "Sorry, I didn't understand. Can you please repeat that?"); return "Didn't understand: $text" }
        val who = SebastianConfig.findContact(cfg, name) ?: phoneContact(name)
            ?: run { SebastianCommand.speak(cfg, "Sorry, I couldn't find $name in your contacts. Can you please repeat the name?"); return "Didn't understand: no contact \"$name\"" }
        SebastianCommand.speak(cfg, "Calling ${who.name}.")
        return dial(who.name)
    }

    /** A short-lived AssemblyAI streaming token (the app never holds the key), plus contact names to boost. */
    fun sttToken(): String {
        val cfg = SebastianConfig.read()
        val body = JSONObject().put("product", "streaming").put("expires_in_seconds", 600).put("max_session_duration_seconds", 900)
        val req = okhttp3.Request.Builder().url("https://token.assemblyai.com/v1/token")
            .header("authorization", cfg.getString("assemblyai_api_key"))
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString())).build()
        val token = SebastianSession.CLIENT.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            check(r.isSuccessful) { "token HTTP ${r.code}: ${raw.take(120)}" }
            JSONObject(raw).getString("token")
        }
        val names = JSONArray()
        (cfg.optJSONArray("contacts") ?: JSONArray()).let { a -> for (i in 0 until a.length()) names.put(a.getJSONObject(i).optString("name")) }
        return JSONObject().put("token", token).put("names", names).toString()
    }

    /** "Shahwaiz" → finds the number (contacts.json first, then the phone's contacts) and calls it on WhatsApp
     * (config dial_via "sim" for a SIM call). */
    fun dial(spoken: String): String {
        check(enabled) { "Sebastian is off" }
        val cfg = SebastianConfig.read()
        val who = SebastianConfig.findContact(cfg, spoken) ?: phoneContact(spoken) ?: error("No contact called \"$spoken\"")
        check(!(telecom!!.isInCall) && audio!!.mode == AudioManager.MODE_NORMAL) { "Already in a call" }
        dialed = who; dialedAt = SystemClock.elapsedRealtime()
        if (cfg.optString("dial_via", "whatsapp") == "whatsapp") {
            val row = whatsAppCallRow(who.number) ?: error("${who.name} isn't on WhatsApp in this phone's contacts")
            // WhatsApp drops a call started on a dark or locked screen: wake it, and wait for the owner to unlock.
            shell("input", "keyevent", "KEYCODE_WAKEUP")
            if (locked()) {
                shell("wm", "dismiss-keyguard")
                SebastianCommand.speak(cfg, "Please unlock your phone and I'll place the call.")
                val until = SystemClock.elapsedRealtime() + 20_000
                while (locked() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(500)
                if (locked()) return "Didn't call: the phone stayed locked"
                SystemClock.sleep(1_200) // let the unlock animation finish first
            }
            // WhatsApp sometimes opens its landing screen and closes it again without calling (seen when it was idle):
            // check the call screen really came up, and try once more if not.
            var said = ""
            for (attempt in 1..2) {
                val out = shell("am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", "content://com.android.contacts/data/$row",
                    "-t", "vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "-p", "com.whatsapp")
                said = out.lines().filter { it.isNotBlank() }.joinToString(" | ").take(200)
                val until = SystemClock.elapsedRealtime() + 4_000
                while (SystemClock.elapsedRealtime() < until) {
                    if (inWhatsAppCall()) { log("WhatsApp calling ${who.name} (try $attempt)"); return "Calling ${who.name} on WhatsApp (try $attempt)" }
                    SystemClock.sleep(300)
                }
                log("WhatsApp didn't start the call (try $attempt): $said")
            }
            SebastianCommand.speak(cfg, "Sorry, WhatsApp didn't start the call. Can you please try again?")
            return "Didn't understand: WhatsApp didn't start the call ($said)"
        }
        val extras = Bundle()
        (telecom!!.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL) ?: telecom!!.callCapablePhoneAccounts.firstOrNull())
            ?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        telecom!!.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, who.number, null), extras)
        log("dialing ${who.name}")
        return "Calling ${who.name}"
    }

    /** Lab: speak a line into the live call now. */
    fun say(text: String): String {
        val s = session ?: return "No call with Sebastian in it"
        s.say(text)
        return "said"
    }

    private fun watch() {
        var idleSince = 0L
        while (enabled) {
            try {
                val am = audio!!
                val fg = precise?.foreground
                // Connected: the precise state says ACTIVE (a call that rings first never shows ACTIVE before it's
                // answered). Without it, the in-call audio mode (which also covers dialing; good enough as a fallback).
                val mode = am.mode
                // A WhatsApp call puts the phone in communication mode from the first ring-back: Sebastian has to
                // relay from that moment (the hooks hold WhatsApp's audio), and waits for a "hello" to introduce himself.
                val voip = mode == AudioManager.MODE_IN_COMMUNICATION && tap != null
                val connected = voip || if (fg != null && fg != PreciseCallMonitor.NOT_VALID) fg == PreciseCallMonitor.ACTIVE
                    else mode == AudioManager.MODE_IN_CALL
                val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || telecom!!.isInCall
                val seen = "mode=$mode precise=${fg ?: "none"} telecomInCall=${telecom!!.isInCall}"
                if (seen != lastSeen) { lastSeen = seen; log(seen) }
                val now = SystemClock.elapsedRealtime()
                if (session == null && connected) startSession(voip)
                if (session != null) {
                    if (inCall) idleSince = 0L
                    else if (idleSince == 0L) idleSince = now
                    else if (now - idleSince > 1_200) { endSession("call ended"); idleSince = 0L }
                } else if (!inCall && dialed != null && now - dialedAt > 90_000) dialed = null
                if (session != null && !session!!.isLive()) endSession("session stopped")
            } catch (t: Throwable) {
                log("watch: ${t.javaClass.simpleName}: ${t.message}")
                SystemClock.sleep(1_000)
            }
            synchronized(wake) { wake.wait(100) }
        }
    }

    private fun startSession(voip: Boolean) {
        val cfg = runCatching { SebastianConfig.read() }.getOrElse { log("no config: ${it.message}"); return }
        val known = dialed ?: (if (voip) identify(cfg) else null)?.also { identified = it }
        val other = known?.name?.ifBlank { null } ?: "Them"
        val s = SebastianSession(cfg, other, ::log, if (voip) tap else null,
            { audio!!.mode == AudioManager.MODE_IN_COMMUNICATION }, known?.number?.ifBlank { null })
        session = s
        state = "IN CALL (${if (voip) "WhatsApp" else "SIM"}) — with $other"
        runCatching { s.start() }.onFailure {
            log("couldn't join the call: ${it.javaClass.simpleName}: ${it.message}")
            // Keep the failed session until the call ends, so it isn't retried every 100 ms.
        }
    }

    private fun endSession(why: String) {
        val s = session ?: return
        session = null
        val who = dialed ?: identified; dialed = null; identified = null
        val lines = s.stop()
        state = if (enabled) "READY — waiting for a call" else "OFF"
        log("left the call ($why): ${lines.size} lines")
        if (lines.none { it.who != "Sebastian" }) return
        val payload = JSONObject()
            .put("started_at", s.startedAt).put("ended_at", System.currentTimeMillis())
            .put("owner", SebastianConfig.read().optString("owner_name", "Rayyan"))
            .put("other_name", who?.name ?: JSONObject.NULL).put("other_number", who?.number ?: JSONObject.NULL)
            .put("lines", JSONArray().apply { lines.forEach { put(JSONObject().put("t_ms", it.atMs).put("who", it.who).put("text", it.text)) } })
        File(SebastianConfig.OUTBOX).mkdirs()
        File(SebastianConfig.OUTBOX, "${s.startedAt}.json").writeText(payload.toString())
        Thread(::flushOutbox, "sebastian-outbox").apply { isDaemon = true; start() }
    }

    /** Sends every finished call waiting in the outbox; a failed one stays for the next try. */
    @Synchronized private fun flushOutbox() {
        val files = File(SebastianConfig.OUTBOX).listFiles()?.sortedBy { it.name } ?: return
        val cfg = runCatching { SebastianConfig.read() }.getOrNull() ?: return
        for (f in files) {
            val r = runCatching { SebastianTools.post(cfg, "/call-end", JSONObject(f.readText())) }
                .getOrElse { JSONObject().put("error", it.message) }
            if (r.has("error")) { log("notes not sent yet: ${r.optString("error").take(120)}"); return }
            f.delete()
            log("notes sent: ${r.optString("notion_url", "ok")}")
        }
    }

    /** The contacts row WhatsApp registers for "Voice call +92 ..." on this number, or null if WhatsApp doesn't know it. */
    private fun whatsAppCallRow(number: String): Long? {
        val digits = number.filter { it.isDigit() }.let { if (it.startsWith("0")) "92" + it.drop(1) else it }
        val out = shell("content", "query", "--uri", "content://com.android.contacts/data", "--projection", "_id:data1",
            "--where", "mimetype='vnd.android.cursor.item/vnd.com.whatsapp.voip.call' AND data1 LIKE '%${digits.takeLast(10)}@%'")
        return Regex("_id=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** WhatsApp's call screen is on top, or the phone is already in call audio. */
    private fun inWhatsAppCall(): Boolean = audio!!.mode == AudioManager.MODE_IN_COMMUNICATION ||
        shell("dumpsys", "activity", "activities").lineSequence().firstOrNull { "topResumedActivity" in it }?.contains("Voip") == true

    private fun locked(): Boolean = shell("dumpsys", "window").contains("isKeyguardShowing=true")

    private fun shell(vararg cmd: String): String = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }.getOrElse { "Error: ${it.message}" }

    /**
     * Who the WhatsApp call is with, from WhatsApp's call notification (the app's notification listener): the name
     * WhatsApp shows, and the number from the notification or else from contacts. Waits up to 2 s for the notification.
     * Name blank and number set: an unsaved caller.
     */
    private fun identify(cfg: JSONObject): SebastianConfig.Contact? {
        val until = SystemClock.elapsedRealtime() + 3_000
        var rebound = false
        while (true) {
            val out = shell("am", "broadcast", "-n", "dev.rawaiz.sebastian/dev.callstream.probe.CallInfoReceiver")
            val fromListener = Regex("result=1, data=\"(.*)\"\\s*$", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)
            // Notification access is often off (HyperOS): the helper reads the same notification from the system's dump.
            val data = fromListener ?: CallerFromDump.read()
            if (data != null) {
                if (fromListener == null) { log("caller read from the notification dump: ${data.replace("\t", " | ")}"); CallerFromDump.saveDebug("found") }
                var name = data.substringBefore('\t').trim()
                var number = data.substringAfter('\t', "").trim()
                // WhatsApp shows the number itself for someone not in contacts.
                if (number.isEmpty() && Regex("^\\+?[0-9 ()-]{8,}$").matches(name)) { number = "+" + name.filter { it.isDigit() }; name = "" }
                if (number.isEmpty() && name.isNotEmpty())
                    number = (SebastianConfig.findContact(cfg, name) ?: phoneContact(name))?.number.orEmpty()
                log("call is with ${name.ifEmpty { "an unsaved number" }}${if (number.isNotEmpty()) " ($number)" else " (no number found)"}")
                return SebastianConfig.Contact(name, number)
            }
            if (data == null && "notification access is off" in out && !rebound) {
                // Android drops the listener after the app is reinstalled or restarted: switch it off and on to bind it again.
                rebound = true
                val c = "dev.rawaiz.sebastian/dev.callstream.probe.CallNotificationListener"
                shell("cmd", "notification", "disallow_listener", c); SystemClock.sleep(500)
                shell("cmd", "notification", "allow_listener", c); SystemClock.sleep(1_500)
                log("notification listener was not bound: bound it again")
                continue
            }
            if (SystemClock.elapsedRealtime() > until) {
                CallerFromDump.saveDebug("none")
                log("couldn't tell who the call is with: ${Regex("data=\"(.*)\"").find(out)?.groupValues?.get(1) ?: out.trim().take(100)}")
                return null
            }
            SystemClock.sleep(300)
        }
    }

    /** Looks [spoken] up in the phone's own contacts (the shell's `content` command). */
    private fun phoneContact(spoken: String): SebastianConfig.Contact? {
        val want = SebastianConfig.normalize(spoken)
        val out = runCatching {
            val p = ProcessBuilder("content", "query", "--uri", "content://com.android.contacts/data/phones",
                "--projection", "display_name:data1").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrDefault("")
        val rows = Regex("display_name=(.*?), data1=([+0-9 ()-]+)").findAll(out).map { it.groupValues[1].trim() to it.groupValues[2].filter { c -> c.isDigit() || c == '+' } }.toList()
        val exact = rows.firstOrNull { SebastianConfig.normalize(it.first) == want }
        val loose = exact ?: rows.firstOrNull { SebastianConfig.normalize(it.first).split(' ').contains(want) || SebastianConfig.normalize(it.first).contains(want) }
        return loose?.let { SebastianConfig.Contact(it.first, it.second) }
    }

    companion object {
        private const val TAG = "Sebastian"
        private const val LOG_FILE = "/data/local/tmp/sebastian-rec/sebastian.log"
    }
}
