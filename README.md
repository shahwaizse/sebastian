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

## How it works

<table>
<tr>
<td width="56" valign="top"><img src="docs/assets/icons/phone-call.svg" width="40" alt=""></td>
<td><strong>Sebastian joins the call</strong><br>Start a call as usual, or say "Hey Sebastian, call Shahwaiz". Sebastian is on the line with you, with no second number or extra device, and the other person doesn't need to install anything.</td>
</tr>
<tr>
<td valign="top"><img src="docs/assets/icons/ear.svg" width="40" alt=""></td>
<td><strong>Sebastian listens</strong><br>Sebastian tells everyone it's there, then stays quiet and follows both sides of the conversation.</td>
</tr>
<tr>
<td valign="top"><img src="docs/assets/icons/speaker.svg" width="40" alt=""></td>
<td><strong>Say its name and Sebastian acts</strong><br>Sebastian answers out loud so both of you hear it, and does the work while the call is still going.</td>
</tr>
<tr>
<td valign="top"><img src="docs/assets/icons/notes.svg" width="40" alt=""></td>
<td><strong>Hang up and it's written down</strong><br>A summary with decisions and action items comes to your WhatsApp, and the full notes go to Notion.</td>
</tr>
</table>

## What Sebastian does on a call

<table>
<tr>
<td width="56"><img src="docs/assets/icons/messages.svg" width="36" alt=""></td>
<td><em>"Sebastian, what did we say the price was?"</em></td>
<td>Answers from the conversation</td>
</tr>
<tr>
<td><img src="docs/assets/icons/search.svg" width="36" alt=""></td>
<td><em>"Sebastian, who's right about this?"</em></td>
<td>Searches the web and tells you both</td>
</tr>
<tr>
<td><img src="docs/assets/icons/file.svg" width="36" alt=""></td>
<td><em>"Sebastian, what's in our pricing doc?"</em></td>
<td>Reads the page from your Notion</td>
</tr>
<tr>
<td><img src="docs/assets/icons/calendar.svg" width="36" alt=""></td>
<td><em>"Sebastian, book a follow-up Friday 3 PM."</em></td>
<td>Books it and sends the Google Meet link on WhatsApp</td>
</tr>
<tr>
<td><img src="docs/assets/icons/link.svg" width="36" alt=""></td>
<td><em>"Sebastian, send them my booking link."</em></td>
<td>Sends your Calendly link on WhatsApp</td>
</tr>
<tr>
<td><img src="docs/assets/icons/link.svg" width="36" alt=""></td>
<td><em>"Sebastian, log this in the CRM."</em></td>
<td>Uses your apps' tools through <a href="docs/MCP.md">MCP</a></td>
</tr>
<tr>
<td><img src="docs/assets/icons/hangup.svg" width="36" alt=""></td>
<td><em>"Sebastian, cut the call."</em></td>
<td>Hangs up for you</td>
</tr>
</table>

## Built on AssemblyAI

<table>
<tr>
<td width="56"><img src="docs/assets/icons/ear.svg" width="36" alt=""></td>
<td><strong>Universal Streaming</strong></td>
<td>Transcribes each side of the call live, and hears "Hey Sebastian"</td>
</tr>
<tr>
<td><img src="docs/assets/icons/brain.svg" width="36" alt=""></td>
<td><strong>LLM Gateway</strong></td>
<td>Decides what to say and which tool to use, and writes the notes</td>
</tr>
<tr>
<td><img src="docs/assets/icons/speaker.svg" width="36" alt=""></td>
<td><strong>Text-to-Speech</strong></td>
<td>Speaks Sebastian's answers into the call</td>
</tr>
</table>

<br>

<div align="center">
<img src="docs/assets/flow.png" width="860" alt="The phone sends both voices to AssemblyAI, which reaches Sebastian's brain for web search, Calendar, Notion and WhatsApp">
</div>

<br>

## More

- [How it works under the hood](docs/HOW-IT-WORKS.md)
- [Run it yourself](docs/SETUP.md)
- [Connect MCP servers](docs/MCP.md)
