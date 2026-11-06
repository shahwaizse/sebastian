// sebastian-brain: the tools Sebastian uses mid-call and the notes he leaves after it.
//   POST /event        {title, start: "2026-11-06T15:00", minutes?, notes?, send_to?, other_name?}
//                        → Calendar event with a Google Meet link, WhatsApped to send_to (copy to Rayyan)
//   POST /send-link    {send_to?, other_name?}                               → Rayyan's Calendly link on WhatsApp
//   POST /notion/read  {query}                                               → the best-matching Notion page as text
//   POST /search       {query}                                               → a short answer from a live Google search
//   POST /call-end     {started_at, ended_at, owner, other_name, lines[]}     → Notion notes page + WhatsApp summary
// Every route needs "authorization: Bearer <SEBASTIAN_TOKEN>". The phone never holds Google or Notion credentials.

const json = (body, status = 200) => Response.json(body, { status });

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health') return new Response('sebastian-brain ok');
    if (request.method !== 'POST') return json({ error: 'not found' }, 404);
    if (!env.SEBASTIAN_TOKEN || request.headers.get('authorization') !== `Bearer ${env.SEBASTIAN_TOKEN}`) return json({ error: 'unauthorized' }, 401);
    let body;
    try { body = await request.json(); } catch { return json({ error: 'not JSON' }, 400); }
    try {
      if (url.pathname === '/event') return json(await bookEvent(env, body));
      if (url.pathname === '/send-link') return json(await sendBookingLink(env, body));
      if (url.pathname === '/notion/read') return json(await readNotion(env, body.query));
      if (url.pathname === '/search') return json(await webSearch(env, body.query));
      if (url.pathname === '/call-end') return json(await callEnd(env, body));
    } catch (e) {
      console.log(url.pathname, e.message);
      return json({ error: e.message.slice(0, 300) }, 502);
    }
    return json({ error: 'not found' }, 404);
  },
};

// ---------------------------------------------------------------- Google Calendar

let googleToken = null;
async function googleAccess(env) {
  if (googleToken && googleToken.exp > Date.now() + 60_000) return googleToken.value;
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: env.GOOGLE_CLIENT_ID, client_secret: env.GOOGLE_CLIENT_SECRET,
      refresh_token: env.GOOGLE_REFRESH_TOKEN, grant_type: 'refresh_token' }),
  });
  const t = await r.json();
  if (!r.ok) throw new Error(`Google token: ${JSON.stringify(t).slice(0, 200)}`);
  googleToken = { value: t.access_token, exp: Date.now() + t.expires_in * 1000 };
  return googleToken.value;
}

/** "2026-11-06T15:00" + 30 → "2026-11-06T15:30:00" (local wall time; Calendar applies the time zone). */
export function addMinutes(local, minutes) {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(local ?? '');
  if (!m) throw new Error(`start must look like 2026-11-06T15:00, got ${local}`);
  const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5] + minutes));
  return d.toISOString().slice(0, 19);
}

/** Spoken back by Sebastian: "Friday 6 November at 3:00 PM". */
export function spokenWhen(local) {
  const d = new Date(addMinutes(local, 0) + 'Z');
  const day = d.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' });
  const time = d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' });
  return `${day} at ${time}`;
}

async function bookEvent(env, { title, start, minutes = 30, notes = '', send_to, other_name }) {
  if (!title) throw new Error('title is required');
  const mins = Math.max(5, Math.min(480, Number(minutes) || 30));
  // conferenceDataVersion=1 + createRequest: Google attaches a fresh Meet link to the event.
  const r = await fetch(`https://www.googleapis.com/calendar/v3/calendars/${encodeURIComponent(env.CALENDAR_ID)}/events?conferenceDataVersion=1`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${await googleAccess(env)}` },
    body: JSON.stringify({
      summary: title, description: `${notes}\n\nBooked by Sebastian during a call.`.trim(),
      start: { dateTime: addMinutes(start, 0), timeZone: env.TIMEZONE },
      end: { dateTime: addMinutes(start, mins), timeZone: env.TIMEZONE },
      conferenceData: { createRequest: { requestId: crypto.randomUUID(), conferenceSolutionKey: { type: 'hangoutsMeet' } } },
    }),
  });
  const ev = await r.json();
  if (!r.ok) throw new Error(`Calendar: ${JSON.stringify(ev).slice(0, 200)}`);
  const when = spokenWhen(start);
  const meet = ev.hangoutLink || ev.conferenceData?.entryPoints?.find((e) => e.entryPointType === 'video')?.uri || null;
  const sent = meet
    ? await shareLink(env, send_to, other_name, meetForThem(when, meet), meetForOwner(title, when, meet))
    : 'no Meet link came back';
  return { booked: true, title, when, minutes: mins, meet_link: meet, sent };
}

// ---------------------------------------------------------------- meeting links on WhatsApp

/** "+92 300 1234567", "03001234567" → "923001234567"; anything else → null. */
export function waNumber(raw) {
  const d = String(raw ?? '').replace(/\D/g, '');
  const n = d.startsWith('0') ? '92' + d.slice(1) : d;
  return /^92\d{10}$/.test(n) ? n : null;
}

/** A name to greet: the contact's, or "there" for a caller Sebastian has no name for. */
export function greetName(other) {
  const n = String(other ?? '').trim();
  return !n || n === 'Them' ? 'there' : n;
}

export const meetForThem = (when, meet) => (name) =>
  `Hi ${name}, this is Sebastian, Rayyan's AI assistant. Your meeting with Rayyan is booked for ${when} (Pakistan time).\nGoogle Meet: ${meet}`;
export const meetForOwner = (title, when, meet) => (name, delivered) =>
  `Sebastian booked "${title}" for ${when}.\nGoogle Meet: ${meet}\n` +
  (delivered ? `Sent to ${name} on WhatsApp.` : `I didn't have ${name}'s number, so forward it to them.`);
export const calendlyForThem = (url) => (name) =>
  `Hi ${name}, this is Sebastian, Rayyan's AI assistant. Pick a time that suits you to meet Rayyan: ${url}`;
export const calendlyForOwner = (url) => (name, delivered) =>
  delivered ? `Sebastian sent your Calendly link to ${name} on WhatsApp.\n${url}`
    : `Sebastian promised ${name} your Calendly link, but I didn't have their number, so forward it to them.\n${url}`;

async function waSend(env, to, text) {
  const bot = env.WA.get(env.WA.idFromName('0315-bot'));
  const r = await bot.fetch('https://wa/send', { method: 'POST', body: JSON.stringify({ to, text }) });
  if (!r.ok) throw new Error(`WhatsApp: ${(await r.text()).slice(0, 120)}`);
}

/** Sends a link to the other person when their number is known, and always a copy to Rayyan. Says what happened. */
async function shareLink(env, sendTo, otherName, forThem, forOwner) {
  const to = waNumber(sendTo);
  const name = greetName(otherName);
  let delivered = false;
  if (to && to !== env.OWNER_WA) {
    try { await waSend(env, to, forThem(name)); delivered = true; } catch (e) { console.log('share', e.message); }
  }
  try { await waSend(env, env.OWNER_WA, forOwner(name === 'there' ? 'them' : name, delivered)); } catch (e) { console.log('share copy', e.message); }
  return delivered ? `sent to ${name} on WhatsApp, with a copy to Rayyan` : `sent only to Rayyan, because there's no WhatsApp number for ${name}`;
}

async function sendBookingLink(env, { send_to, other_name }) {
  if (!env.CALENDLY_URL) throw new Error('no Calendly link set up');
  const sent = await shareLink(env, send_to, other_name, calendlyForThem(env.CALENDLY_URL), calendlyForOwner(env.CALENDLY_URL));
  return { link: env.CALENDLY_URL, sent };
}

// ---------------------------------------------------------------- Notion

async function notion(env, path, body, method = 'POST') {
  if (!env.NOTION_TOKEN) throw new Error('Notion is not connected yet');
  const r = await fetch(`https://api.notion.com/v1${path}`, {
    method,
    headers: { authorization: `Bearer ${env.NOTION_TOKEN}`, 'notion-version': env.NOTION_VERSION, 'content-type': 'application/json' },
    body: method === 'GET' ? undefined : JSON.stringify(body),
  });
  const out = await r.json();
  if (!r.ok) throw new Error(`Notion ${path}: ${out.message ?? r.status}`);
  return out;
}

const plain = (rich) => (rich ?? []).map((t) => t.plain_text).join('');
export function pageTitle(page) {
  for (const p of Object.values(page.properties ?? {})) if (p.type === 'title') return plain(p.title);
  return 'Untitled';
}

/** The text of a block list, one line per block (headings, paragraphs, bullets, to-dos, tables' cells). */
export function blocksText(blocks) {
  return blocks.map((b) => {
    const v = b[b.type] ?? {};
    if (b.type === 'table_row') return (v.cells ?? []).map(plain).join(' | ');
    const text = plain(v.rich_text);
    if (b.type === 'to_do') return `${v.checked ? '[x]' : '[ ]'} ${text}`;
    if (b.type.startsWith('heading')) return `# ${text}`;
    if (b.type.endsWith('list_item')) return `- ${text}`;
    return text;
  }).filter((l) => l.trim()).join('\n');
}

async function readNotion(env, query) {
  if (!query) throw new Error('query is required');
  const found = await notion(env, '/search', { query, filter: { property: 'object', value: 'page' }, page_size: 5 });
  const page = found.results?.[0];
  if (!page) return { found: false, message: `No Notion page matches "${query}"` };
  const blocks = await notion(env, `/blocks/${page.id}/children?page_size=100`, null, 'GET');
  let items = blocks.results ?? [];
  // Tables keep their rows one level down.
  for (const t of items.filter((b) => b.type === 'table' && b.has_children)) {
    const rows = await notion(env, `/blocks/${t.id}/children?page_size=100`, null, 'GET');
    items = items.flatMap((b) => (b.id === t.id ? rows.results : [b]));
  }
  return { found: true, title: pageTitle(page), text: blocksText(items).slice(0, 6000) };
}

// ---------------------------------------------------------------- web search (Gemini + Google Search)

export function searchAnswer(out) {
  const c = out.candidates?.[0];
  const text = (c?.content?.parts ?? []).map((p) => p.text ?? '').join('').replace(/[*_#`]/g, '').trim();
  const sources = (c?.groundingMetadata?.groundingChunks ?? []).map((g) => g.web?.title).filter(Boolean).slice(0, 3);
  return { answer: text, sources };
}

async function webSearch(env, query) {
  if (!query) throw new Error('query is required');
  if (!env.GEMINI_API_KEY) throw new Error('web search is not set up yet');
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${env.SEARCH_MODEL}:generateContent`, {
    method: 'POST',
    headers: { 'x-goog-api-key': env.GEMINI_API_KEY, 'content-type': 'application/json' },
    body: JSON.stringify({
      contents: [{ parts: [{ text: `Search the web and answer in at most three short sentences that sound natural when spoken on a phone call. Plain text, no lists, no dashes. Question: ${query}` }] }],
      tools: [{ google_search: {} }],
    }),
  });
  const out = await r.json();
  if (!r.ok) throw new Error(`Search ${r.status}: ${out.error?.message ?? ''}`.slice(0, 200));
  return searchAnswer(out);
}

// ---------------------------------------------------------------- after the call

async function llm(env, messages, maxTokens = 1200) {
  const r = await fetch('https://llm-gateway.assemblyai.com/v1/chat/completions', {
    method: 'POST',
    headers: { authorization: env.ASSEMBLYAI_API_KEY, 'content-type': 'application/json' },
    body: JSON.stringify({ model: env.SUMMARY_MODEL, messages, max_tokens: maxTokens }),
  });
  const out = await r.json();
  if (!r.ok) throw new Error(`LLM Gateway ${r.status}: ${JSON.stringify(out).slice(0, 200)}`);
  return (out.choices ?? []).map((c) => c.message?.content ?? '').join('');
}

export function transcriptText(lines) {
  return lines.map((l) => {
    const s = Math.floor((l.t_ms ?? 0) / 1000);
    return `[${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}] ${l.who}: ${l.text}`;
  }).join('\n');
}

/** The model's JSON, even when it wraps it in a code fence or adds a sentence around it. */
export function parseNotes(text) {
  const start = text.indexOf('{'), end = text.lastIndexOf('}');
  if (start < 0 || end < start) throw new Error('summary was not JSON');
  const n = JSON.parse(text.slice(start, end + 1));
  return {
    title: String(n.title ?? 'Call notes').slice(0, 120),
    summary: String(n.summary ?? ''),
    decisions: Array.isArray(n.decisions) ? n.decisions.map(String) : [],
    action_items: Array.isArray(n.action_items) ? n.action_items.map((a) => ({
      owner: String(a.owner ?? 'Unassigned'), task: String(a.task ?? ''), due: a.due ? String(a.due) : '' })).filter((a) => a.task) : [],
  };
}

const para = (text) => ({ object: 'block', type: 'paragraph', paragraph: { rich_text: [{ type: 'text', text: { content: text.slice(0, 1900) } }] } });
const heading = (text) => ({ object: 'block', type: 'heading_2', heading_2: { rich_text: [{ type: 'text', text: { content: text } }] } });
const bullet = (text) => ({ object: 'block', type: 'bulleted_list_item', bulleted_list_item: { rich_text: [{ type: 'text', text: { content: text.slice(0, 1900) } }] } });
const todo = (text) => ({ object: 'block', type: 'to_do', to_do: { checked: false, rich_text: [{ type: 'text', text: { content: text.slice(0, 1900) } }] } });

export function notesBlocks(notes, lines, meta) {
  const blocks = [para(meta), heading('Summary'), para(notes.summary || 'No summary.')];
  blocks.push(heading('Decisions'), ...(notes.decisions.length ? notes.decisions.map(bullet) : [para('None recorded.')]));
  blocks.push(heading('Action items'), ...(notes.action_items.length
    ? notes.action_items.map((a) => todo(`${a.owner}: ${a.task}${a.due ? ` (by ${a.due})` : ''}`)) : [para('None.')]));
  blocks.push(heading('Transcript'));
  // Notion takes 100 blocks per request; the transcript goes in as paragraphs of up to ~1900 characters.
  let chunk = '';
  for (const line of transcriptText(lines).split('\n')) {
    if (chunk.length + line.length > 1800) { blocks.push(para(chunk)); chunk = ''; }
    chunk += (chunk ? '\n' : '') + line;
  }
  if (chunk) blocks.push(para(chunk));
  return blocks;
}

export function whatsAppText(notes, other, url) {
  const parts = [`*Sebastian: call with ${other}*`, notes.summary];
  if (notes.decisions.length) parts.push('*Decided*\n' + notes.decisions.map((d) => `• ${d}`).join('\n'));
  if (notes.action_items.length) parts.push('*To do*\n' + notes.action_items.map((a) => `• ${a.owner}: ${a.task}${a.due ? ` (${a.due})` : ''}`).join('\n'));
  if (url) parts.push(`Notes: ${url}`);
  return parts.filter(Boolean).join('\n\n').slice(0, 3900);
}

async function callEnd(env, call) {
  const lines = (call.lines ?? []).filter((l) => l && l.text);
  if (!lines.length) return { skipped: 'empty call' };
  const owner = call.owner || 'Rayyan';
  const other = call.other_name || 'the other person';
  const started = new Date(call.started_at ?? Date.now());
  const minutes = Math.max(1, Math.round(((call.ended_at ?? Date.now()) - started.getTime()) / 60000));
  const date = started.toLocaleString('en-GB', { timeZone: env.TIMEZONE, weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });

  const notes = parseNotes(await llm(env, [
    { role: 'system', content: `You write meeting notes for a phone call between ${owner} and ${other}. "Sebastian" is their AI note taker. Reply with JSON only:
{"title": "short title naming the topic", "summary": "2 to 4 sentences", "decisions": ["..."], "action_items": [{"owner": "name", "task": "...", "due": "when, if said"}]}
Use only what was said. Keep exact numbers and dates. No em dashes.` },
    { role: 'user', content: transcriptText(lines) },
  ]));

  let notionUrl = null;
  // A Notion failure must not cost Rayyan his WhatsApp summary.
  if (env.NOTION_TOKEN && env.NOTION_PARENT_PAGE_ID) try {
    const blocks = notesBlocks(notes, lines, `${date} · ${minutes} min · ${owner} and ${other} · notes by Sebastian`);
    const page = await notion(env, '/pages', {
      parent: { page_id: env.NOTION_PARENT_PAGE_ID },
      properties: { title: { title: [{ type: 'text', text: { content: notes.title } }] } },
      children: blocks.slice(0, 100),
    });
    for (let i = 100; i < blocks.length; i += 100) await notion(env, `/blocks/${page.id}/children`, { children: blocks.slice(i, i + 100) }, 'PATCH');
    notionUrl = page.url;
  } catch (e) { console.log('notion', e.message); }

  let whatsapp = 'skipped';
  try {
    const bot = env.WA.get(env.WA.idFromName('0315-bot'));
    const r = await bot.fetch('https://wa/send', { method: 'POST', body: JSON.stringify({ to: env.OWNER_WA, text: whatsAppText(notes, other, notionUrl) }) });
    whatsapp = r.ok ? 'sent' : `failed: ${(await r.text()).slice(0, 120)}`;
  } catch (e) { whatsapp = `failed: ${e.message}`; }

  return { notion_url: notionUrl, whatsapp, title: notes.title };
}
