import { test } from 'node:test';
import assert from 'node:assert/strict';
import { searchAnswer, addMinutes, spokenWhen, parseNotes, blocksText, transcriptText, whatsAppText, notesBlocks, waNumber, greetName, meetForThem, meetForOwner, calendlyForThem, calendlyForOwner } from '../src/worker.js';

test('addMinutes keeps local wall time and rolls over days', () => {
  assert.equal(addMinutes('2026-11-06T15:00', 30), '2026-11-06T15:30:00');
  assert.equal(addMinutes('2026-11-06T23:45', 30), '2026-11-07T00:15:00');
  assert.throws(() => addMinutes('Friday 3pm', 30));
});

test('spokenWhen reads like a person', () => {
  assert.equal(spokenWhen('2026-11-06T15:00'), 'Friday 6 November at 3:00 PM');
});

test('parseNotes survives code fences and fills defaults', () => {
  const n = parseNotes('Here:\n```json\n{"title":"Acme pricing","summary":"Agreed.","decisions":["Rs 25,000 a month"],"action_items":[{"owner":"Shahwaiz","task":"Send the contract"},{"task":""}]}\n```');
  assert.equal(n.title, 'Acme pricing');
  assert.deepEqual(n.decisions, ['Rs 25,000 a month']);
  assert.deepEqual(n.action_items, [{ owner: 'Shahwaiz', task: 'Send the contract', due: '' }]);
  assert.throws(() => parseNotes('no json here'));
});

test('blocksText flattens Notion blocks', () => {
  const rt = (t) => ({ rich_text: [{ plain_text: t }] });
  assert.equal(blocksText([
    { type: 'heading_1', heading_1: rt('Pricing') },
    { type: 'bulleted_list_item', bulleted_list_item: rt('Starter Rs 15,000') },
    { type: 'to_do', to_do: { ...rt('Call Acme'), checked: true } },
    { type: 'table_row', table_row: { cells: [[{ plain_text: 'Growth' }], [{ plain_text: '25,000' }]] } },
    { type: 'paragraph', paragraph: rt('') },
  ]), '# Pricing\n- Starter Rs 15,000\n[x] Call Acme\nGrowth | 25,000');
});

test('transcript, WhatsApp text and Notion blocks', () => {
  const lines = [{ t_ms: 65_000, who: 'Rayyan', text: 'Price is 25k.' }, { t_ms: 70_000, who: 'Sebastian', text: 'Noted.' }];
  assert.equal(transcriptText(lines), '[1:05] Rayyan: Price is 25k.\n[1:10] Sebastian: Noted.');
  const notes = { title: 'T', summary: 'S', decisions: ['D'], action_items: [{ owner: 'R', task: 'X', due: 'Friday' }] };
  const wa = whatsAppText(notes, 'Shahwaiz', 'https://notion.so/x');
  assert.match(wa, /call with Shahwaiz/); assert.match(wa, /R: X \(Friday\)/); assert.match(wa, /notion\.so/);
  assert.ok(!wa.includes('—'));
  const blocks = notesBlocks(notes, lines, 'meta');
  assert.equal(blocks.at(-1).paragraph.rich_text[0].text.content, transcriptText(lines));
});

test('searchAnswer strips markdown and keeps a few source titles', () => {
  const out = { candidates: [{ content: { parts: [{ text: 'AssemblyAI makes **speech** APIs.' }] },
    groundingMetadata: { groundingChunks: [{ web: { title: 'assemblyai.com' } }, { web: { title: 'docs' } }] } }] };
  assert.deepEqual(searchAnswer(out), { answer: 'AssemblyAI makes speech APIs.', sources: ['assemblyai.com', 'docs'] });
});

test('waNumber turns Pakistani numbers into WhatsApp ids and rejects the rest', () => {
  assert.equal(waNumber('+92 300 1234567'), '923001234567');
  assert.equal(waNumber('03001234567'), '923001234567');
  assert.equal(waNumber('923001234567'), '923001234567');
  assert.equal(waNumber('+1 415 555 0100'), null);
  assert.equal(waNumber(undefined), null);
});

test('meeting link messages greet by name, say who got it, and use no em dashes', () => {
  assert.equal(greetName('Them'), 'there');
  assert.equal(greetName('Vigotel'), 'Vigotel');
  const them = meetForThem('Friday 6 November at 3:00 PM', 'https://meet.google.com/abc-defg-hij')('Vigotel');
  assert.match(them, /^Hi Vigotel, this is Sebastian/);
  assert.match(them, /meet\.google\.com\/abc-defg-hij/);
  assert.match(meetForOwner('Demo', 'Friday', 'https://meet.google.com/x')('Vigotel', true), /Sent to Vigotel on WhatsApp/);
  assert.match(meetForOwner('Demo', 'Friday', 'https://meet.google.com/x')('them', false), /forward it/);
  assert.match(calendlyForThem('https://calendly.com/example/30min')('there'), /^Hi there,.*calendly\.com\/example\/30min$/);
  assert.match(calendlyForOwner('https://calendly.com/a')('Vigotel', true), /to Vigotel on WhatsApp/);
  for (const t of [them, calendlyForThem('u')('x'), meetForOwner('a', 'b', 'c')('d', false), calendlyForOwner('u')('x', false)]) {
    assert.ok(!/[—–]/.test(t), t);
  }
});
