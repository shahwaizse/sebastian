package dev.callstream.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Hey Sebastian, call Vigotel": AssemblyAI streaming speech-to-text listens, but only while someone is talking. A
 * simple on-phone level gate opens a stream when speech starts (with the last 1.5 s of audio, so the first word
 * isn't lost) and closes it after a few seconds of quiet, so nothing is sent in silence. A finished line with
 * "Sebastian" and "call <name>" goes to the helper, which says "Calling <name>." and places the WhatsApp call.
 * "Hey Sebastian." alone beeps and waits a few seconds for the rest. Paused during any call (Sebastian is in it).
 * The app never holds the AssemblyAI key: the helper hands it a 10-minute token per stream.
 */
class WakeService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val io = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    // Stream state (listen thread + socket callbacks).
    @Volatile private var ws: WebSocket? = null
    @Volatile private var wsOpen = false
    @Volatile private var connecting = false
    /** Bumped on every open and close, so a stream that's shutting down can't touch the state of the next one. */
    @Volatile private var streamGen = 0
    private val pending = ArrayDeque<ByteArray>()
    @Volatile private var lastSpeechMs = 0L
    @Volatile private var waitingUntilMs = 0L
    @Volatile private var acting = false
    @Volatile private var openedAtMs = 0L
    /** A token fetched ahead of time (they're good for 10 minutes): token, boost terms, fetched-at. */
    @Volatile private var cached: Triple<String, JSONArray, Long>? = null
    @Volatile private var lastPartial = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Sebastian", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTE_ID, note("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        running = true
        io.execute { runCatching { token() } }
        thread = Thread(::loop, "sebastian-wake").apply { start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        closeStream("service stopped")
        thread?.join(1_000)
        super.onDestroy()
    }

    private fun loop() {
        val am = getSystemService(AudioManager::class.java)
        var rec: AudioRecord? = null
        val frame = ShortArray(RATE / 10) // 100 ms
        val preroll = ArrayDeque<ByteArray>()
        var noise = 300.0
        var levelSum = 0.0; var levelPeak = 0.0; var levelFrames = 0
        try {
            while (running) {
                if (am.mode != AudioManager.MODE_NORMAL) {
                    closeStream("call started")
                    rec?.let { it.stop(); it.release() }; rec = null
                    update("In a call: Sebastian is in it")
                    SystemClock.sleep(1_000)
                    continue
                }
                if (rec == null) {
                    rec = openMic()
                    if (rec == null) { update("No microphone"); SystemClock.sleep(2_000); continue }
                    rec.startRecording()
                    update("Say \"Hey Sebastian, call …\"")
                }
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) { rec.release(); rec = null; continue }
                var sum = 0.0
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = frame[i].toInt(); sum += v.toDouble() * v
                    bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
                }
                val level = Math.sqrt(sum / n)
                val speech = level > maxOf(SPEECH_MIN, noise * 2.5)
                if (!speech) noise = noise * 0.97 + level * 0.03
                val now = SystemClock.elapsedRealtime()
                if (speech) lastSpeechMs = now
                levelSum += level; levelPeak = maxOf(levelPeak, level)
                if (++levelFrames >= 50) {
                    Log.i(TAG, "mic avg ${(levelSum / levelFrames).toInt()} peak ${levelPeak.toInt()} noise ${noise.toInt()} stream=$wsOpen")
                    levelSum = 0.0; levelPeak = 0.0; levelFrames = 0
                }
                if (acting) continue
                if (!(wsOpen || connecting)) {
                    preroll.addLast(bytes); while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                    if (speech) { openStream(preroll.toList()); preroll.clear() }
                } else {
                    send(bytes)
                    // Quiet for a while and not waiting for the rest of a command: stop streaming.
                    if (wsOpen && now - openedAtMs > MIN_STREAM_MS && now - lastSpeechMs > QUIET_CLOSE_MS && now > waitingUntilMs) closeStream("quiet")
                }
            }
        } finally {
            rec?.let { runCatching { it.stop() }; it.release() }
        }
    }

    @Suppress("MissingPermission")
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
        }.getOrNull()
        return r?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: run { r?.release(); null }
    }

    // ------------------------------------------------------------------ AssemblyAI stream

    private fun openStream(preroll: List<ByteArray>) {
        val gen = ++streamGen
        connecting = true
        synchronized(pending) { pending.clear(); preroll.forEach { pending.addLast(it) } }
        runCatching { dump?.close(); dump = java.io.FileOutputStream(java.io.File(cacheDir, "last_stream.pcm")).also { d -> preroll.forEach { d.write(it) } } }
        io.execute {
            try {
                val (token, terms) = token()
                cached = null // one stream per token; fetch the next one in the background
                io.execute { runCatching { token() } }
                val q = linkedMapOf("sample_rate" to "$RATE", "encoding" to "pcm_s16le", "speech_model" to "universal-streaming-english",
                    "format_turns" to "true", "keyterms_prompt" to terms.toString(), "token" to token)
                val url = "wss://streaming.assemblyai.com/v3/ws?" + q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
                ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (gen != streamGen) { webSocket.close(1000, null); return }
                        synchronized(pending) { while (pending.isNotEmpty()) webSocket.send(pending.removeFirst().toByteString()); wsOpen = true }
                        connecting = false
                        openedAtMs = SystemClock.elapsedRealtime()
                        Log.i(TAG, "stream open")
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) = onTurn(text)  // the closing one may still finish a sentence
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "stream failed: ${t.message} http=${response?.code}")
                        if (gen == streamGen) { wsOpen = false; connecting = false; ws = null }
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (gen == streamGen) { wsOpen = false; connecting = false } }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "couldn't open stream: ${t.message}")
                if (gen == streamGen) connecting = false
                SystemClock.sleep(2_000)
            }
        }
    }

    /** The cached token if it's fresh (under 8 minutes old), else a new one from the helper. */
    private fun token(): Pair<String, JSONArray> {
        cached?.let { (t, terms, at) -> if (SystemClock.elapsedRealtime() - at < 8 * 60_000) return t to terms }
        val j = JSONObject(helper().sebastianSttToken())
        val token = j.optString("token").ifBlank { error(j.optString("error", "no token")) }
        val names = j.optJSONArray("names") ?: JSONArray()
        val terms = JSONArray().put("Sebastian").put("Hey Sebastian").put("call")
        for (i in 0 until names.length()) terms.put(names.getString(i))
        cached = Triple(token, terms, SystemClock.elapsedRealtime())
        return token to terms
    }

    /** Debug: the audio of the last stream, as sent (cache/last_stream.pcm, 16 kHz mono PCM16). */
    private var dump: java.io.FileOutputStream? = null

    private fun send(bytes: ByteArray) {
        runCatching { dump?.write(bytes) }
        synchronized(pending) {
            if (wsOpen) ws?.send(bytes.toByteString()) else { pending.addLast(bytes); while (pending.size > 100) pending.removeFirst() }
        }
    }

    /** Ends the stream, but first asks AssemblyAI to finish the sentence in progress and waits a moment for it. */
    private fun closeStream(why: String) {
        val w = ws ?: return
        streamGen++
        ws = null; wsOpen = false; connecting = false
        runCatching { w.send("""{"type":"ForceEndpoint"}""") }
        Thread {  // not on io: that's where the next stream is opened
            SystemClock.sleep(1_500)
            runCatching { w.send("""{"type":"Terminate"}""") }
            SystemClock.sleep(500)
            runCatching { w.close(1000, null) }
        }.start()
        Log.i(TAG, "stream closing ($why)")
    }

    private fun onTurn(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (m.optString("type") != "Turn") return
        if (!m.optBoolean("end_of_turn") || !m.optBoolean("turn_is_formatted")) {
            val partial = m.optString("transcript")
            if (partial.isNotBlank() && partial != lastPartial) { lastPartial = partial; Log.i(TAG, "hearing: $partial") }
            return
        }
        val said = m.optString("transcript").trim()
        if (said.isEmpty() || acting) return
        Log.i(TAG, "heard: $said")
        val now = SystemClock.elapsedRealtime()
        val named = NAME.containsMatchIn(said)
        val hasCall = CALL.containsMatchIn(said)
        when {
            named && hasCall -> act(said)
            hasCall && now < waitingUntilMs -> act("Sebastian, $said")
            // Answering "who should I call?" with just the name.
            !named && now < waitingUntilMs && said.trim().split(" ").size <= 3 -> act("Sebastian, call $said")
            // "Hey Sebastian, what's the weather": named, but not something he can do from here.
            named && said.trim().split(" ").size > 3 -> act(said)
            named -> {
                val until = now + 6_000
                waitingUntilMs = until; beep(); update("Yes? Say \"call …\"")
                // Nothing usable followed: say so instead of going quiet.
                Thread { SystemClock.sleep(6_500); if (waitingUntilMs == until && !acting) act(said) }.start()
            }
        }
    }

    private fun act(said: String) {
        acting = true
        waitingUntilMs = 0
        closeStream("command")
        beep()
        update("On it: $said")
        io.execute {
            val result = try { helper().sebastianCommandText(said) } catch (t: Throwable) { "Couldn't reach Sebastian: ${t.message}" }
            Log.i(TAG, "command: $result")
            update(result.take(120))
            SystemClock.sleep(1_500)
            acting = false
            // He asked them to repeat: listen for the answer without needing "Hey Sebastian" again.
            if (result.startsWith("Didn't understand")) waitingUntilMs = SystemClock.elapsedRealtime() + 8_000
        }
    }

    private fun helper(): IProbeService = IProbeService.Stub.asInterface(HelperConnector.obtain(this, 9_000))

    private fun beep() = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_BEEP, 150) }

    private fun update(text: String) = getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(text))

    private fun note(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Sebastian")
        .setContentText(text)
        .setOngoing(true)
        .build()

    companion object {
        private const val TAG = "SebastianWake"
        private const val CHANNEL = "sebastian_wake"
        private const val NOTE_ID = 4711
        private const val RATE = 16_000
        private const val SPEECH_MIN = 500.0
        private const val PREROLL_FRAMES = 15        // 1.5 s
        private const val QUIET_CLOSE_MS = 3_000L
        private const val MIN_STREAM_MS = 6_000L
        private val NAME = Regex("\\b(sebastian|sebastien|sebastián|sabastian|sebastion|bastian)\\b", RegexOption.IGNORE_CASE)
        private val CALL = Regex("\\b(call|called|calling|ring|dial|phone)\\s+\\w+", RegexOption.IGNORE_CASE)

        fun start(ctx: Context) {
            // A microphone foreground service started before RECORD_AUDIO is granted crashes the app ("Sebastian keeps
            // stopping" on a fresh install, Pixel 6 Pro 2026-11-05). MainActivity calls this again once it is granted.
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) { Log.i(TAG, "wake service waits for the microphone permission"); return }
            runCatching { ctx.startForegroundService(Intent(ctx, WakeService::class.java)) }
                .onFailure { Log.w(TAG, "wake service not started: ${it.message}") }
        }
    }
}
