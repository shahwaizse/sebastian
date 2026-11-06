<div align="center">

<img src="docs/assets/icon.png" width="132" alt="Sebastian app icon: a smiling character in a bowler hat">

<h1>Sebastian</h1>

<p><strong>Every meeting bot listens. Sebastian talks back.</strong></p>

<p>Your AI buddy on every WhatsApp and phone call.<br>
He takes notes, fetches info, books meetings, and even settles arguments, out loud, while you're still talking.</p>

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

## Why Sebastian

<table>
<tr>
<td width="33%" valign="top">
<img src="docs/assets/icons/phone-call.svg" width="44" alt=""><br><br>
<strong>Real calls, not a browser demo</strong><br><br>
He runs on your own Android phone and joins your WhatsApp and cellular calls. No second number, no extra device, and the other person needs no app, link or invite.
</td>
<td width="33%" valign="top">
<img src="docs/assets/icons/messages.svg" width="44" alt=""><br><br>
<strong>He talks back</strong><br><br>
Meeting bots only listen. Say his name and Sebastian answers out loud, inside the call, so both of you hear him.
</td>
<td width="33%" valign="top">
<img src="docs/assets/icons/zap.svg" width="44" alt=""><br><br>
<strong>He does things</strong><br><br>
Search the web, book the meeting, pull up the pricing doc. It happens mid-sentence, not in a follow-up email next week.
</td>
</tr>
</table>

## How a call goes

<table>
<tr>
<td width="56" valign="top"><img src="docs/assets/icons/mic.svg" width="40" alt=""></td>
<td><strong>"Hey Sebastian, call Shahwaiz."</strong><br>Say it hands-free and he places the WhatsApp call. Or just start a call yourself: he joins on his own. Don't need him? Turn him off with one tap.</td>
</tr>
<tr>
<td valign="top"><img src="docs/assets/icons/ear.svg" width="40" alt=""></td>
<td><strong>He introduces himself, then goes quiet</strong><br>Once the other side says hello, he tells everyone he's taking notes. From then on he listens to both sides and knows who said what.</td>
</tr>
<tr>
<td valign="top"><img src="docs/assets/icons/speaker.svg" width="40" alt=""></td>
<td><strong>Say his name, get an answer</strong><br>He answers out loud, with the whole call as context. Talk over him and he stops, like a person would.</td>
</tr>
<tr>
<td valign="top"><img src="docs/assets/icons/notes.svg" width="40" alt=""></td>
<td><strong>Hang up, and the notes are already there</strong><br>A summary with decisions and action items lands on your WhatsApp, and the full notes land in Notion.</td>
</tr>
</table>

## What you can ask him

<table>
<tr>
<td width="56"><img src="docs/assets/icons/sparkles.svg" width="36" alt=""></td>
<td><em>"Sebastian, what did we say the price was?"</em></td>
<td>Answers from the call so far</td>
</tr>
<tr>
<td><img src="docs/assets/icons/search.svg" width="36" alt=""></td>
<td><em>"Sebastian, settle this: who's right?"</em></td>
<td>Checks the web and gives the verdict out loud</td>
</tr>
<tr>
<td><img src="docs/assets/icons/search.svg" width="36" alt=""></td>
<td><em>"Sebastian, search what AssemblyAI launched recently."</em></td>
<td>Live web search</td>
</tr>
<tr>
<td><img src="docs/assets/icons/calendar.svg" width="36" alt=""></td>
<td><em>"Sebastian, book a follow-up Friday 3 PM."</em></td>
<td>Calendar event with a Google Meet link, sent on WhatsApp</td>
</tr>
<tr>
<td><img src="docs/assets/icons/link.svg" width="36" alt=""></td>
<td><em>"Sebastian, send them my booking link."</em></td>
<td>Your Calendly link on WhatsApp</td>
</tr>
<tr>
<td><img src="docs/assets/icons/file.svg" width="36" alt=""></td>
<td><em>"Sebastian, what's in our pricing doc?"</em></td>
<td>Reads the page from your Notion</td>
</tr>
<tr>
<td><img src="docs/assets/icons/hangup.svg" width="36" alt=""></td>
<td><em>"Sebastian, cut the call."</em></td>
<td>Hangs up for you</td>
</tr>
</table>

## Powered by AssemblyAI

One API key covers everything Sebastian hears, thinks and says.

<table>
<tr>
<td width="56"><img src="docs/assets/icons/ear.svg" width="36" alt=""></td>
<td><strong>Universal Streaming</strong></td>
<td>A live transcript of each side of the call, and the "Hey Sebastian" command</td>
</tr>
<tr>
<td><img src="docs/assets/icons/brain.svg" width="36" alt=""></td>
<td><strong>LLM Gateway</strong></td>
<td>Answers, tool calling and the notes after the call</td>
</tr>
<tr>
<td><img src="docs/assets/icons/speaker.svg" width="36" alt=""></td>
<td><strong>Text-to-Speech</strong></td>
<td>His voice in the call, streamed in the same audio format as the call itself</td>
</tr>
</table>

<br>

<div align="center">
<img src="docs/assets/flow.png" width="860" alt="The phone sends both voices to AssemblyAI, which reaches Sebastian's brain for web search, Calendar, Notion and WhatsApp">
</div>

<br>

## Your call, your keys

<table>
<tr>
<td width="56" valign="top"><img src="docs/assets/icons/shield.svg" width="36" alt=""></td>
<td>Sebastian tells everyone on the call that he's there. Your AssemblyAI key stays on your phone, where only his helper can read it, and your Google and Notion logins stay in your own Cloudflare Worker.</td>
</tr>
</table>

## Built on Call Stream

<table>
<tr>
<td width="56" valign="top"><img src="docs/assets/icons/layers.svg" width="36" alt=""></td>
<td>Sebastian's way into call audio comes from <strong>Call Stream</strong>, our earlier AI phone agent for Pakistani businesses, so that project's code is still in this repo, including its Gemini agent, lead sheets and their screens. Sebastian doesn't use them: everything he hears, thinks and says runs on AssemblyAI. The one exception is web search, which uses Gemini with Google Search grounding. What's new for this hackathon is mainly the <code>Sebastian*</code> code, <code>WakeService</code>, <code>WhatsAppTap</code> and <code>brain/</code>.</td>
</tr>
</table>

## Learn more

<table>
<tr>
<td width="56"><img src="docs/assets/icons/map.svg" width="36" alt=""></td>
<td><a href="docs/HOW-IT-WORKS.md"><strong>How it works</strong></a></td>
<td>Getting into a WhatsApp call, deciding when to speak, and the repo layout</td>
</tr>
<tr>
<td><img src="docs/assets/icons/terminal.svg" width="36" alt=""></td>
<td><a href="docs/SETUP.md"><strong>Run it yourself</strong></a></td>
<td>Build the app, deploy the brain, turn him on</td>
</tr>
<tr>
<td><img src="docs/assets/icons/rocket.svg" width="36" alt=""></td>
<td><strong>What's next</strong></td>
<td>Three-way calls that he dials and merges, GitHub actions from a call, and Urdu once AssemblyAI TTS supports it</td>
</tr>
</table>

<br>

<div align="center">
<sub>Made in Lahore by <strong>Rawaiz Tech</strong></sub>
</div>
