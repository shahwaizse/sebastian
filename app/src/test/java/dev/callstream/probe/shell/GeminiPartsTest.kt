package dev.callstream.probe.shell

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class GeminiPartsTest {
    private fun sine(hz: Double, rate: Int, n: Int, amp: Double = 10_000.0) =
        ShortArray(n) { (amp * sin(2 * PI * hz * it / rate)).toInt().toShort() }
    private fun rms(x: ShortArray, from: Int = 0) = sqrt(x.drop(from).sumOf { it.toDouble() * it } / (x.size - from))

    @Test fun resamplerMakesTwoThirdsAndKeepsSpeech() {
        val out = Resampler24To16().process(sine(1_000.0, 24_000, 24_000))
        assertEquals(16_000, out.size)
        // Same tone, same level, once the filter has settled.
        assertEquals(10_000 / sqrt(2.0), rms(out, 100), 300.0)
        // The filter delays everything by 31 samples at 48 kHz.
        val worst = (200 until 15_000).maxOf { abs(out[it] - 10_000 * sin(2 * PI * 1_000.0 * (it / 16_000.0 - 31 / 48_000.0))) }
        assertTrue("tone shape off by $worst", worst < 300)
    }

    @Test fun resamplerRemovesWhatSixteenKilohertzCantCarry() {
        val out = Resampler24To16().process(sine(10_000.0, 24_000, 24_000))
        assertTrue(rms(out, 100) < 300)
    }

    @Test fun resamplerChunksMatchOneLongBuffer() {
        val input = sine(700.0, 24_000, 4_801)
        val whole = Resampler24To16().process(input)
        val r = Resampler24To16()
        val parts = ArrayList<Short>()
        var i = 0
        for (size in generateSequence(1) { it % 997 + 37 }) {
            if (i >= input.size) break
            parts += r.process(input.copyOfRange(i, minOf(i + size, input.size))).toList(); i += size
        }
        assertArrayEquals(whole, parts.toShortArray())
    }

    @Test fun setupCarriesVoicePromptThinkingAndHangUpTool() {
        val cfg = JSONObject().put("system_prompt", "prompt").put("gemini_voice", "Leda").put("vad_silence_ms", 250)
            .put("vad_start_sensitivity", "low")
        val setup = JSONObject(GeminiProtocol.setup(cfg)).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        val gen = setup.getJSONObject("generationConfig")
        assertEquals("Leda", gen.getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertEquals(0, gen.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertFalse(gen.has("enableAffectiveDialog"))
        assertTrue(JSONObject(GeminiProtocol.setup(JSONObject(cfg.toString()).put("affective_dialog", true)))
            .getJSONObject("setup").getJSONObject("generationConfig").getBoolean("enableAffectiveDialog"))
        assertEquals("prompt", setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("end_call", setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getString("name"))
        val vad = setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertEquals(250, vad.getInt("silenceDurationMs"))
        assertEquals("START_SENSITIVITY_LOW", vad.getString("startOfSpeechSensitivity"))
        assertFalse(vad.has("endOfSpeechSensitivity"))
        assertFalse(setup.getJSONObject("realtimeInputConfig").has("activityHandling"))
        val handled = JSONObject(GeminiProtocol.setup(JSONObject(cfg.toString()).put("activity_handling", "no_interruption")
            .put("turn_coverage", "turn_includes_all_input"))).getJSONObject("setup").getJSONObject("realtimeInputConfig")
        assertEquals("NO_INTERRUPTION", handled.getString("activityHandling"))
        assertEquals("TURN_INCLUDES_ALL_INPUT", handled.getString("turnCoverage"))
        // Phone turn detection: Gemini's own detection off, none of its tuning sent.
        val phone = JSONObject(GeminiProtocol.setup(JSONObject(cfg.toString()).put("turn_detection", "phone"))).getJSONObject("setup")
            .getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertTrue(phone.getBoolean("disabled"))
        assertFalse(phone.has("silenceDurationMs") || phone.has("startOfSpeechSensitivity"))
        assertTrue(JSONObject(GeminiProtocol.activityStart()).getJSONObject("realtimeInput").has("activityStart"))
        assertTrue(JSONObject(GeminiProtocol.activityEnd()).getJSONObject("realtimeInput").has("activityEnd"))
        // -1 leaves thinking to the model.
        assertFalse(JSONObject(GeminiProtocol.setup(cfg.put("thinking_budget", -1))).getJSONObject("setup").getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test fun audioMessageIsLittleEndianPcm() {
        val msg = JSONObject(GeminiProtocol.audio(shortArrayOf(1, -2, 0x1234)))
        val audio = msg.getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertArrayEquals(byteArrayOf(1, 0, -2, -1, 0x34, 0x12), Base64.getDecoder().decode(audio.getString("data")))
    }

    @Test fun parsesAudioInterruptionsToolCallsAndUsage() {
        val pcm = Base64.getEncoder().encodeToString(byteArrayOf(1, 0, -1, -1))
        val audio = GeminiProtocol.parse("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]}},
            "usageMetadata":{"promptTokenCount":2000,"promptTokensDetails":[{"modality":"TEXT","tokenCount":1000},{"modality":"AUDIO","tokenCount":1000}],
            "responseTokensDetails":[{"modality":"AUDIO","tokenCount":1000}]}}""")
        assertEquals(24_000, audio.audioRate)
        assertArrayEquals(shortArrayOf(1, -1), audio.audio.single())
        assertEquals((1000 * 0.75 + 1000 * 3.0 + 1000 * 12.0) / 1e6, GeminiProtocol.costUsd(audio.usage!!), 1e-12)
        assertTrue(GeminiProtocol.parse("""{"serverContent":{"interrupted":true}}""").interrupted)
        val call = GeminiProtocol.parse("""{"toolCall":{"functionCalls":[{"name":"end_call","args":{},"id":"c1"}]}}""")
        assertEquals("c1" to "end_call", call.functionCalls.single())
        assertTrue(GeminiProtocol.parse("""{"serverContent":{"outputTranscription":{"text":"\n<disconnect/>"}}}""").disconnectTag)
        val vad = GeminiProtocol.parse("""{"voiceActivity":{"type":"ACTIVITY_END","audioOffset":"4.560s"}}""")
        assertEquals("ACTIVITY_END", vad.voiceActivity); assertEquals("4.560s", vad.voiceActivityOffset)
        assertTrue(GeminiProtocol.parse("{}").empty)
        assertTrue(GeminiProtocol.parse("""{"setupComplete":{}}""").setupComplete)
    }

    @Test fun echoLevelFramesAreFlaggedButSpeechIsNot() {
        val d = DuckingCompensator()
        for (t in 0 until 100) d.agent(4_000.0, t * 10.0) // agent audible for 1 s
        // Echo at ~15% of 4,000, 150 ms later.
        assertTrue(d.isEcho(600.0, 900.0))
        assertFalse(d.isEcho(5_000.0, 900.0))
        // Long after the agent stopped, nothing is echo.
        assertFalse(d.isEcho(100.0, 5_000.0))
    }

    @Test fun callContextSaysWhoCalledTheNameAndPakistanTime() {
        val now = java.time.ZonedDateTime.of(2026, 10, 14, 15, 42, 0, 0, GeminiProtocol.PAKISTAN)
        assertEquals("[call connected] inbound: the caller phoned you. Now in Pakistan: Wednesday 14 October 2026, 3:42 PM. The next days: " + GeminiProtocol.week(now.toLocalDate()) + ".",
            GeminiProtocol.callContext("[call connected]", outbound = false, contactName = "Ali", now = now))
        assertEquals("[call connected] outbound: you phoned them; contact listed as: Ali Raza. Now in Pakistan: Wednesday 14 October 2026, 3:42 PM. The next days: " + GeminiProtocol.week(now.toLocalDate()) + ".",
            GeminiProtocol.callContext("[call connected]", outbound = true, contactName = " Ali Raza ", now = now))
        // No name on the list (or an old placeholder): the agent is told, so it asks for the name at the visit step.
        assertTrue(GeminiProtocol.callContext("[call connected]", outbound = true, contactName = "Contact 3", now = now).contains("no name on the list"))
        assertTrue(GeminiProtocol.callContext("[call connected]", outbound = true, contactName = "", now = now).contains("no name on the list"))
        // A callback carries what the earlier calls settled.
        assertEquals("[call connected] outbound: you phoned them; contact listed as: Ali; follow-up call 2, they asked to be called back at this time. Now in Pakistan: Wednesday 14 October 2026, 3:42 PM. The next days: " + GeminiProtocol.week(now.toLocalDate()) + ".",
            GeminiProtocol.callContext("[call connected]", outbound = true, contactName = "Ali", now = now, notes = "follow-up call 2, they asked to be called back at this time"))
    }

    @Test fun weekGivesEachDayItsUrduName() {
        val week = GeminiProtocol.week(java.time.LocalDate.of(2026, 10, 17))
        assertTrue(week.startsWith("today Saturday 17 October (ہفتہ); tomorrow (کل) Sunday 18 October (اتوار); day after tomorrow (پرسوں) Monday 19 October (پیر)"))
        assertTrue(week.contains("Friday 23 October (جمعہ)"))
        assertTrue(week.endsWith("Friday 23 October (جمعہ)"))
    }

    @Test fun transcriptionOnlyWhenExtractionIsConfigured() {
        val base = JSONObject().put("system_prompt", "x")
        assertFalse(JSONObject(GeminiProtocol.setup(base)).getJSONObject("setup").has("inputAudioTranscription"))
        val setup = JSONObject(GeminiProtocol.setup(JSONObject(base.toString()).put("extract_model", "gemini-3.1-flash-lite"))).getJSONObject("setup")
        assertTrue(setup.has("inputAudioTranscription") && setup.has("outputAudioTranscription"))
        val urdu = JSONObject(GeminiProtocol.setup(JSONObject(base.toString()).put("extract_model", "m").put("transcription_language", "ur-PK"))).getJSONObject("setup")
        assertEquals("ur-PK", urdu.getJSONObject("inputAudioTranscription").getJSONArray("languageCodes").getString(0))
    }

    @Test fun parsesBothSidesOfTheTranscript() {
        val m = GeminiProtocol.parse("""{"serverContent":{"inputTranscription":{"text":"جی بتائیں"},"outputTranscription":{"text":"السلام علیکم"}}}""")
        assertEquals("جی بتائیں", m.callerText)
        assertEquals("السلام علیکم", m.agentText)
        assertEquals(null, GeminiProtocol.parse("""{"serverContent":{"turnComplete":true}}""").callerText)
    }

    @Test fun interruptionNoteSaysWhatTheCallerHeardWithoutStartingAReply() {
        val reply = "ان کے علاوہ چار چھ ماہی قسطیں ایک لاکھ تیرہ ہزار کی ہیں، اور پوزیشن کی رقم دو لاکھ ہے۔"
        assertEquals("ان کے علاوہ چار چھ ماہی", GeminiProtocol.heardPart(reply, 0.3))
        assertEquals("", GeminiProtocol.heardPart(reply, 0.01))
        assertEquals(reply, GeminiProtocol.heardPart(reply, 1.0))
        val content = JSONObject(GeminiProtocol.interruptionNote(reply, 0.3, greeting = false)).getJSONObject("clientContent")
        assertFalse(content.getBoolean("turnComplete"))
        val turn = content.getJSONArray("turns").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        val text = turn.getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text.contains("\"ان کے علاوہ چار چھ ماہی…\"") && text.contains("don't continue it") && !text.contains("greeting"))
        val greeting = JSONObject(GeminiProtocol.interruptionNote("السلام علیکم، میں عائشہ", 0.0, greeting = true))
            .getJSONObject("clientContent").getJSONArray("turns").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(greeting.contains("right at the start") && greeting.contains("don't start it again"))
    }
}
