import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { parseReading } from '../src/vision.js';

let server, base, calls = 0, reply = '{"name":"Juan Dela Cruz","lat":14.5995,"lng":120.9842}';
const fakeFetch = async () => { calls++; return { ok: true, json: async () => ({ choices: [{ message: { content: reply } }] }) }; };
const jpeg = Buffer.alloc(300, 7).toString('base64');

before(async () => {
  const store = new MemoryStore();
  await store.transaction((tx) => tx.put('people', 'member', { isSuperAdmin: false, activeAdminRole: null, activeCongregationId: 'c1' }, 'seed'));
  server = createApp(store, { devAuth: true, voiceOptions: { vision: { apiKey: 'k', fetchImpl: fakeFetch } } }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());
const post = (body, who = 'member') => fetch(`${base}/v1/vision/read-place`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-dev-person': who }, body: JSON.stringify(body) });

test('the AI reader returns the name and coordinates it was given by the model', async () => {
  const r = await post({ imageBase64: jpeg });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { name: 'Juan Dela Cruz', lat: 14.5995, lng: 120.9842 });
});
test('it needs a login and a picture', async () => {
  assert.equal((await fetch(`${base}/v1/vision/read-place`, { method: 'POST' })).status, 401);
  assert.equal((await post({ imageBase64: 'x' })).status, 400);
});
test('anything outside the valid range, or a name with digits, is dropped', () => {
  assert.deepEqual(parseReading('{"name":"Brgy 5","lat":999,"lng":10}'), { name: null, lat: null, lng: null });
  assert.deepEqual(parseReading('not json'), { name: null, lat: null, lng: null });
});
