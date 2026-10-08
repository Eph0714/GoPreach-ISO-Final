import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

let server, base;
const person = (activeCongregationId, isSuperAdmin = false) => ({ isSuperAdmin, activeAdminRole: null, activeCongregationId });

before(async () => {
  const store = new MemoryStore();
  const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
  await put('people', 'sa', person(null, true));
  await put('people', 'a1', person('congA'));
  await put('people', 'a2', person('congA'));
  await put('people', 'b1', person('congB'));
  server = createApp(store, { devAuth: true }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const post = (who, body) =>
  fetch(`${base}/v1/presence`, { method: 'POST', headers: { 'x-dev-person': who, 'content-type': 'application/json' }, body: JSON.stringify(body) });
const ids = async (who, body = {}) => ((await (await post(who, body)).json()).online.map((o) => o.personId)).sort();

test('the Super-Admin sees everyone who has reported in; others only their own congregation', async () => {
  await post('a1', { beat: true });
  await post('b1', { beat: true });
  await post('sa', { beat: true });
  assert.deepEqual(await ids('sa'), ['a1', 'b1', 'sa']);
  assert.deepEqual(await ids('a2'), ['a1'], 'a2 never reported in but still sees the list for congregation A');
  assert.deepEqual(await ids('b1'), ['b1']);
});

test('reading the list does not make you online, and it needs a known login', async () => {
  assert.ok(!(await ids('sa')).includes('a2'));
  assert.equal((await fetch(`${base}/v1/presence`, { method: 'POST' })).status, 401);
  assert.equal((await post('ghost', { beat: true })).status, 403);
});
