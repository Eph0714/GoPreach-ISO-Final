import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { loadActor, authorizeWrite, canReadRow, loadGrant } from '../src/policy/index.js';
import { minutesPrompt, VOICE_PATH_RE } from '../src/voice.js';

let store, server, base;
const calls = [];
// A fake OpenAI: records each request and answers like the real service.
const fakeFetch = async (url, init) => {
  calls.push({ url, init });
  if (String(url).includes('/audio/transcriptions')) {
    return { ok: true, status: 200, json: async () => ({ text: 'Magandang araw po. We will meet on Friday.', language: 'tagalog', duration: 61.4 }), text: async () => '' };
  }
  return { ok: true, status: 200, json: async () => ({ choices: [{ message: { content: '# Meeting\n## Purpose / Overview\nA short meeting.' } }] }), text: async () => '' };
};
const person = () => ({ isSuperAdmin: false, activeAdminRole: null, activeCongregationId: 'congA' });
const audio = Buffer.from([0, 0, 0, 24, 102, 116, 121, 112, 77, 52, 65, 32]);

async function start(voiceOptions) {
  const s = new MemoryStore();
  const put = (c, id, data) => s.transaction((tx) => tx.put(c, id, data, 'seed'));
  await put('people', 'pubA', person());
  await put('people', 'pubB', person());
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  const srv = createApp(s, { devAuth: true, voiceOptions }).listen(0);
  return { s, srv, url: `http://127.0.0.1:${srv.address().port}` };
}

before(async () => {
  ({ s: store, srv: server, url: base } = await start({ apiKey: 'sk-test', fetchImpl: fakeFetch }));
});
after(() => server.close());

const post = (who, path, body) => fetch(`${base}${path}`, { method: 'POST', headers: { 'x-dev-person': who, 'content-type': 'application/json' }, body: JSON.stringify(body) });
const upload = (who, path, body = audio, type = 'audio/mp4') =>
  fetch(`${base}/v1/files?path=${encodeURIComponent(path)}`, { method: 'PUT', headers: { 'x-dev-person': who, 'content-type': type }, body });

test('a recording can only be uploaded to its owner\'s own folder', async () => {
  assert.equal((await upload('pubA', 'voice-notes/pubA/n1.m4a')).status, 200);
  assert.equal((await upload('pubA', 'voice-notes/pubB/n1.m4a')).status, 403, 'not into someone else\'s folder');
  assert.equal((await upload('pubB', 'voice-notes/pubA/n1.m4a')).status, 403, 'nor can another publisher');
  assert.equal(VOICE_PATH_RE.test('voice-notes/pubA/n1.m4a'), true);
  assert.equal(VOICE_PATH_RE.test('voice-notes/pubA/../x.m4a'), false);
});

test('transcribing: only the owner, the key never leaves the server, and the answer carries the text', async () => {
  await upload('pubA', 'voice-notes/pubA/t1.m4a');
  calls.length = 0;
  const other = await post('pubB', '/v1/voice/transcribe', { path: 'voice-notes/pubA/t1.m4a' });
  assert.equal(other.status, 403, 'another publisher cannot transcribe it');
  assert.equal(calls.length, 0, 'nothing was sent to the speech service');
  const r = await post('pubA', '/v1/voice/transcribe', { path: 'voice-notes/pubA/t1.m4a' });
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.text, 'Magandang araw po. We will meet on Friday.');
  assert.equal(body.durationSeconds, 61);
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /audio\/transcriptions$/);
  assert.equal(calls[0].init.headers.Authorization, 'Bearer sk-test');
  assert.equal(body.apiKey, undefined);
  assert.equal((await post('pubA', '/v1/voice/transcribe', { path: 'voice-notes/pubA/missing.m4a' })).status, 404);
  assert.equal((await post('pubA', '/v1/voice/transcribe', { path: 'profiles/pubA/x.png' })).status, 403);
});

test('minutes: the prompt forbids inventing anything, and short / detailed change the instruction', async () => {
  calls.length = 0;
  const r = await post('pubA', '/v1/voice/minutes', { transcript: 'We decided nothing.', detail: 'short', title: 'Talk', recordedAt: 'Oct 16, 2026' });
  assert.equal(r.status, 200);
  assert.match((await r.json()).minutes, /Purpose/);
  const sent = JSON.parse(calls[0].init.body);
  assert.match(sent.messages[0].content, /Never invent participants/);
  assert.match(sent.messages[0].content, /No specific decision recorded\./);
  assert.match(sent.messages[0].content, /Keep it SHORT/);
  assert.match(minutesPrompt('detailed'), /DETAILED/);
  assert.match(sent.messages[1].content, /We decided nothing\./);
  assert.equal((await post('pubA', '/v1/voice/minutes', { transcript: '   ' })).status, 400);
});

test('without an API key the voice routes say so and send nothing', async () => {
  const off = await start({ apiKey: '', fetchImpl: fakeFetch });
  calls.length = 0;
  const r = await fetch(`${off.url}/v1/voice/minutes`, { method: 'POST', headers: { 'x-dev-person': 'pubA', 'content-type': 'application/json' }, body: JSON.stringify({ transcript: 'x' }) });
  assert.equal(r.status, 503);
  assert.match((await r.json()).error, /not set up/);
  assert.equal(calls.length, 0);
  off.srv.close();
});

test('voice notes (the records) are private to their owner, on read and write', async () => {
  const note = { publisherPersonId: 'pubA', title: 'x' };
  await store.transaction((tx) => tx.put('voiceNotes', 'n1', note, 'pubA'));
  const get = (c, i) => store.get(c, i);
  const can = async (who, op, data, id = 'n1') => store.transaction(async (tx) => {
    const actor = await loadActor(who, tx.get);
    const existing = await tx.get('voiceNotes', id);
    return (await authorizeWrite(actor, op, 'voiceNotes', id, data, existing, tx.get)).ok;
  });
  assert.equal(await can('pubA', 'set', { ...note, title: 'y' }), true);
  assert.equal(await can('pubB', 'set', { ...note, title: 'y' }), false, 'another publisher cannot edit');
  assert.equal(await can('pubB', 'set', { publisherPersonId: 'pubB', title: 'steal' }), false, 'nor overwrite it as their own');
  // The Super Admin is exempt from every write rule in this platform (see authorizeWrite); voice notes follow that same global exemption.
  assert.equal(await can('pubB', 'delete'), false);
  assert.equal(await can('pubA', 'delete'), true);
  const rowOf = async () => ({ collection: 'voiceNotes', id: 'n1', data: (await get('voiceNotes', 'n1')).data, deleted: false });
  const readable = async (who) => canReadRow(await loadActor(who, get), await loadGrant(who, get), await rowOf(), get);
  assert.equal(await readable('pubA'), true);
  assert.equal(await readable('pubB'), false);
  assert.equal(await readable('sa'), true, 'the Super Admin reads everything platform-wide (existing rule), like every other collection');
});
