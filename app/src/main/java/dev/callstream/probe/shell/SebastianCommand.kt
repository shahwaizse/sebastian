package dev.callstream.probe.shell

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The "Hey Sebastian, ..." command outside a call: AssemblyAI streaming STT on the recorded clip, a parse
 * ("call Vigotel"), and a spoken confirmation through the phone's speaker (AssemblyAI TTS).
 */
internal object SebastianCommand {
    /** "call Vigotel", "please call shahwaiz on WhatsApp" → "Vigotel" / "shahwaiz". */
    fun callee(text: String): String? {
        val m = Regex("\\b(?:call(?:ed|ing)?|ring|dial|phone)\\s+(.+)", RegexOption.IGNORE_CASE).find(text) ?: return null
        return m.groupValues[1]
            .replace(Regex("\\b(on|via|over|using)\\s+whats ?app\\b.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(please|now|for me|right now)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { null }
    }

    /** The clip's words. The clip is sent at ~5x real time, then the turn is forced to end. */
    fun transcribe(cfg: JSONObject, pcm: ByteArray, names: List<String>): String {
        val key = cfg.getString("assemblyai_api_key")
        val q = linkedMapOf(
            "sample_rate" to "16000", "encoding" to "pcm_s16le",
            "speech_model" to cfg.optString("stt_model", "universal-streaming-english"), "format_turns" to "true",
            "keyterms_prompt" to JSONArray(listOf("Sebastian", "call") + names).toString(),
        )
        val url = "wss://streaming.assemblyai.com/v3/ws?" + q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val words = StringBuilder()
        val partial = StringBuilder()
        val open = CountDownLatch(1)
        val done = CountDownLatch(1)
        var failure: String? = null
        val ws = SebastianSession.CLIENT.newWebSocket(Request.Builder().url(url).header("Authorization", key).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = open.countDown()
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (m.optString("type")) {
                    "Turn" -> if (m.optBoolean("end_of_turn") && m.optBoolean("turn_is_formatted")) {
                        words.append(' ').append(m.optString("transcript")); partial.setLength(0)
                    } else { partial.setLength(0); partial.append(m.optString("transcript")) }
                    "Termination" -> done.countDown()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = "${t.message} http=${response?.code}"; open.countDown(); done.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = done.countDown()
        })
        check(open.await(8, TimeUnit.SECONDS)) { "speech-to-text didn't connect" }
        failure?.let { error("speech-to-text: $it") }
        var i = 0
        while (i < pcm.size) {
            val end = minOf(pcm.size, i + 3_200) // 100 ms
            ws.send(pcm.copyOfRange(i, end).toByteString())
            i = end
            Thread.sleep(20)
        }
        ws.send(ByteArray(16_000).toByteString()) // 0.5 s of silence closes the last word
        ws.send("""{"type":"ForceEndpoint"}""")
        ws.send("""{"type":"Terminate"}""")
        done.await(8, TimeUnit.SECONDS)
        runCatching { ws.close(1000, null) }
        return (words.toString().trim().ifEmpty { partial.toString() }).trim()
    }

    /** Says [text] out of the phone's speaker, waiting until it's done. */
    fun speak(cfg: JSONObject, text: String) = runCatching {
        val body = JSONObject().put("text", text).put("voice_id", cfg.optString("voice_id", "aai-peter")).put("container", "wav")
        val req = Request.Builder().url("https://tts.us.assemblyai.com/v1/speech")
            .header("Authorization", cfg.getString("assemblyai_api_key"))
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val wav = SebastianSession.CLIENT.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "TTS HTTP ${r.code}" }
            r.body!!.bytes()
        }
        // WAV: the sample rate sits at byte 24, the samples after the "data" chunk header.
        val rate = (wav[24].toInt() and 255) or ((wav[25].toInt() and 255) shl 8) or ((wav[26].toInt() and 255) shl 16)
        var off = 12
        while (off + 8 < wav.size && String(wav, off, 4) != "data") {
            off += 8 + ((wav[off + 4].toInt() and 255) or ((wav[off + 5].toInt() and 255) shl 8) or ((wav[off + 6].toInt() and 255) shl 16))
        }
        val pcm = wav.copyOfRange(off + 8, wav.size)
        // Shell-attributed (a plain AudioTrack in the helper is refused on newer Android; see AudioTools).
        val track = AudioTools.openShellTrack(AudioAttributes.USAGE_MEDIA, rate)
        track.play()
        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        Thread.sleep(300)
        track.stop(); track.release()
    }
}
