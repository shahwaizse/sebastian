<div align="center">

<img src="docs/assets/icon.png" width="132" alt="Sebastian app icon: a smiling character in a bowler hat">

<h1>Sebastian</h1>

<p><strong>An AI agent in your calls.</strong></p>

<p>Sebastian joins your WhatsApp and phone calls from your own Android phone.<br>
Say its name mid-call and Sebastian answers out loud, looks things up, and takes action.</p>

<p>
<img src="https://img.shields.io/badge/AssemblyAI-Streaming%20STT-8B5CF6?style=flat-square" alt="AssemblyAI Streaming STT">
<img src="https://img.shields.io/badge/AssemblyAI-LLM%20Gateway-8B5CF6?style=flat-square" alt="AssemblyAI LLM Gateway">
<img src="https://img.shields.io/badge/AssemblyAI-Streaming%20TTS-8B5CF6?style=flat-square" alt="AssemblyAI Streaming TTS">
<br>
<img src="https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
<img src="https://img.shields.io/badge/WhatsApp%20%2B%20SIM%20calls-25D366?style=flat-square&logo=whatsapp&logoColor=white" alt="WhatsApp and SIM calls">
<img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
<img src="https://img.shields.io/badge/Cloudflare%20Workers-F38020?style=flat-square&logo=cloudflare&logoColor=white" alt="Cloudflare Workers">
</p>

<p><em>Built for AssemblyAI's Voice hackathon week: Hack into speech</em></p>

<!-- Demo video: put the link here once it's rendered. -->

<img src="docs/assets/hero.png" width="860" alt="A WhatsApp call where Sebastian recalls the agreed price and books a follow-up out loud">

<sub>A call from our demo script</sub>

</div>

<br>

## <img src="docs/assets/icons/workflow.svg" width="30" height="30" align="top" alt=""> How it works

1. **Sebastian joins the call.** Start a call as usual, or say "Hey Sebastian, call Shahwaiz". Sebastian is on the line with you, with no second number or extra device, and the other person doesn't need to install anything.
2. **Sebastian listens.** Sebastian tells everyone it's there, then stays quiet and follows both sides of the conversation.
3. **Say its name and Sebastian acts.** Sebastian answers out loud so both of you hear it, and does the work while the call is still going.
4. **Hang up and it's written down.** A summary with decisions and action items comes to your WhatsApp, and the full notes go to Notion.

## <img src="docs/assets/icons/zap.svg" width="30" height="30" align="top" alt=""> What Sebastian does on a call

| Say | Sebastian |
|---|---|
| *"Sebastian, what did we say the price was?"* | Answers from the conversation |
| *"Sebastian, who's right about this?"* | Searches the web and tells you both |
| *"Sebastian, what's in our pricing doc?"* | Reads the page from your Notion |
| *"Sebastian, book a follow-up Friday 3 PM."* | Books it and sends the Google Meet link on WhatsApp |
| *"Sebastian, send them my booking link."* | Sends your Calendly link on WhatsApp |
| *"Sebastian, cut the call."* | Hangs up for you |

## <img src="docs/assets/icons/plug.svg" width="30" height="30" align="top" alt=""> Connect your own apps

Sebastian works with remote MCP servers, so it can use your CRM, knowledge base or any other tool mid-call. List a server in `sebastian.json` and its tools are ready on the next call:

```json
"mcp_servers": [
  { "name": "crm", "url": "https://mcp.example.com/mcp", "token_env": "CRM_MCP_TOKEN" }
]
```

Then say *"Sebastian, log this in the CRM."* Servers need to use Streamable HTTP, with a bearer token or no auth. [More on MCP servers](docs/MCP.md)

## <img src="docs/assets/icons/audio-lines.svg" width="30" height="30" align="top" alt=""> Built on AssemblyAI

| Service | What it does |
|---|---|
| **Universal Streaming** | Transcribes each side of the call live, and hears "Hey Sebastian" |
| **LLM Gateway** | Decides what to say and which tool to use, and writes the notes |
| **Text-to-Speech** | Speaks Sebastian's answers into the call |

<div align="center">
<br>
<img src="docs/assets/flow.png" width="860" alt="Your phone sends both voices to AssemblyAI, and Sebastian reaches web search, Calendar, Notion, WhatsApp and your MCP servers">
</div>

## <img src="docs/assets/icons/book-open.svg" width="30" height="30" align="top" alt=""> More

- [How it works under the hood](docs/HOW-IT-WORKS.md)
- [Run it yourself](docs/SETUP.md)
