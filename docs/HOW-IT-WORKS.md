# How Sebastian works

[Back to the README](../README.md)

```
 Your Android phone                                                        AssemblyAI
 ┌──────────────────────────────────────────────────────┐
 │ "Hey Sebastian, call Shahwaiz"  ───────────────────────► Streaming STT ──► WhatsApp call placed
 │                                                      │
 │ WhatsApp call                                        │
 │  their voice ──► Sebastian ──► earpiece             ─────► Streaming STT  (their words)
 │  your mic    ──► Sebastian ──► WhatsApp             ─────► Streaming STT  (your words)
 │                    ▲                                 │          │ "Sebastian, ...?"
 │                    │                                 │          ▼
 │                    │                                 │     LLM Gateway (tools) ──► sebastian-brain
 │                    │                                 │          │                  (web search, Calendar, Notion)
 │                    └── its voice, mixed into both ◄──────── Streaming TTS (aai-peter, 16 kHz)
 └──────────────────────────────────────────────────────┘
        │ call ends: transcript
        ▼
 sebastian-brain (Cloudflare Worker) ── LLM Gateway summary ──► WhatsApp + Notion page
```

- **Hearing "Hey Sebastian":** a simple level gate on the phone opens an AssemblyAI stream only while someone is talking, so nothing is sent in silence. The app never holds the key: the helper gives it a short-lived token.
- **Joining WhatsApp calls:** Android gives apps no way into another app's call audio. Sebastian's helper runs as Android's `shell` user and registers audio-policy loopback mixes on WhatsApp. Sebastian then relays the call both ways, transcribing each side and mixing in its own voice. SIM calls use the carrier's call-audio interception APIs instead.
- **Listening:** two Universal Streaming sessions (`universal-streaming-english`), one per side, so every line knows who said it. `keyterms_prompt` boosts "Sebastian" and the names on the call.
- **Deciding to speak:** a finished line with its name triggers it. "Hey Sebastian." alone waits for the next sentence. For a few seconds after it answers, follow-ups without its name go to it too, and the model replies `SILENT` when people are talking to each other or only mention it in passing.
- **Introducing itself:** it waits for the other side's first transcribed words before its join line, so a ring-back tone never fools him.
- **Thinking:** AssemblyAI LLM Gateway, OpenAI-compatible chat completions with tool calling, with the whole call so far as context.
- **Speaking:** AssemblyAI streaming TTS at `sample_rate=16000`, `pcm_s16le`, the same format as the call audio. While it speaks, both transcripts get silence so it doesn't hear itself. If someone talks over it, it sends `Cancel` and stops.
- **Slow tools:** it acknowledges first, then speaks the result.

## AssemblyAI APIs

| API | Where |
|---|---|
| Universal Streaming STT (`wss://streaming.assemblyai.com/v3/ws`) | live transcript of both sides; the "call ..." command |
| LLM Gateway (`/v1/chat/completions`, tool calling) | answers, tool use, post-call notes |
| Text-to-Speech, streaming (`wss://tts.us.assemblyai.com/v1/ws`, `assemblyai-tts-1`) | Sebastian's voice in the call |
| Text-to-Speech, HTTP (`/v1/speech`) | "Calling Shahwaiz." out of the speaker |

## Repo layout

| Path | What |
|---|---|
| `app/` | Android app and shell helper. Sebastian: `shell/Sebastian*.kt`, `shell/WhatsAppTap.kt`, `WakeService.kt` |
| `brain/` | `sebastian-brain` Cloudflare Worker: `/search`, `/event`, `/send-link`, `/notion/read`, `/call-end` |
| `bridge/push_config.py` | pushes `sebastian.json`, `contacts.json` and keys from `.env` to the phone |
| `bridge/brain_secrets.py` | uploads the brain's secrets to Cloudflare |
| `sebastian.json` | voice, models, join line, audio settings |
| `docs/DEMO.md` | the two-minute demo script |

## What's next

- Three-way calls: Sebastian dials two people and merges them.
- GitHub actions from a call ("Sebastian, open an issue for that bug").
- Urdu, once AssemblyAI TTS supports it.
