import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

// The change feed (/v1/sync/watch) and the opt-in version checks of /v1/sync/push.
let store, server, base;
const person = (activeAdminRole, activeCongregationId = 'congA', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, ...extra });
const call = async (who, path, body) => {
  const r = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'content-type': 'application/json', 'x-dev-person': who }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, body: await r.json() };
};
const push = (who, ...ops) => call(who, '/v1/sync/push', { ops });
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));

before(async () => {
  store = new MemoryStore();
  await put('people', 'adminA', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'adminB', person('ADMIN_PER_CONGREGATION', 'congB'));
  await put('people', 'coordA', person('COORDINATOR_ELDER'));
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('interestedPeople', 'rvA', { congregationId: 'congA', name: 'Juan' });
  const app = createApp(store, { devAuth: true });
  await new Promise((res) => { server = app.listen(0, res); });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test('watch since=head answers at once with where the feed is now', async () => {
  const r = await call('adminA', '/v1/sync/watch?since=head');
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.changed, []);
  assert.equal(r.body.cursor, await store.headSeq());
});

test('a change the caller may read is reported (the collection and ids, never the contents) and one they may not read is not', async () => {
  const head = (await call('adminA', '/v1/sync/watch?since=head')).body.cursor;
  await put('interestedPeople', 'rvA2', { congregationId: 'congA', name: 'Secret name' });
  const a = await call('adminA', `/v1/sync/watch?since=${head}`);
  assert.equal(a.body.changed.length, 1);
  assert.equal(a.body.changed[0].collection, 'interestedPeople');
  assert.deepEqual(a.body.changed[0].ids, ['rvA2']);
  assert.ok(!JSON.stringify(a.body).includes('Secret name'), 'the feed never carries record contents');
  const b = await call('adminB', `/v1/sync/watch?since=${head}`);
  assert.deepEqual(b.body.changed, [], 'another congregation is told nothing');
  assert.ok(b.body.cursor >= a.body.cursor, 'its position still moves forward');
});

test('a held request is answered as soon as a change is committed', async () => {
  const head = (await call('adminA', '/v1/sync/watch?since=head')).body.cursor;
  const started = Date.now();
  const waiting = call('adminA', `/v1/sync/watch?since=${head}&timeout=20`);
  await new Promise((r) => setTimeout(r, 300));
  const pushed = await push('adminA', { collection: 'interestedPeople', id: 'rvLive', op: 'set', data: { congregationId: 'congA', name: 'Live' } });
  assert.equal(pushed.body.results[0].status, 'ok');
  const r = await waiting;
  assert.ok(Date.now() - started < 4000, 'answered within seconds, not after the 20 s limit');
  assert.deepEqual(r.body.changed.map((c) => c.ids).flat(), ['rvLive']);
});

test('a deletion is reported as a deletion', async () => {
  const head = (await call('adminA', '/v1/sync/watch?since=head')).body.cursor;
  await push('adminA', { collection: 'interestedPeople', id: 'rvA', op: 'delete' });
  const r = await call('adminA', `/v1/sync/watch?since=${head}`);
  assert.equal(r.body.changed[0].deletes, 1);
});

test('a watch with nothing new gives up at its time limit and returns the same position', async () => {
  const head = (await call('adminA', '/v1/sync/watch?since=head')).body.cursor;
  const started = Date.now();
  const r = await call('adminA', `/v1/sync/watch?since=${head}&timeout=1`);
  assert.deepEqual(r.body.changed, []);
  assert.equal(r.body.cursor, head);
  assert.ok(Date.now() - started >= 900);
});

test('watch needs a login and a valid position', async () => {
  assert.equal((await fetch(base + '/v1/sync/watch?since=head')).status, 401);
  assert.equal((await call('adminA', '/v1/sync/watch?since=-5')).status, 400);
});

test('an edit that names a version older than the stored one is a conflict and changes nothing', async () => {
  const first = await push('adminA', { collection: 'interestedPeople', id: 'rvV', op: 'set', data: { congregationId: 'congA', name: 'v1' } });
  const v1 = first.body.results[0].version;
  await push('coordA', { collection: 'interestedPeople', id: 'rvV', op: 'set', baseVersion: v1, data: { congregationId: 'congA', name: 'v2 by another device' } });
  const stale = await push('adminA', { collection: 'interestedPeople', id: 'rvV', op: 'set', baseVersion: v1, data: { congregationId: 'congA', name: 'stale overwrite' } });
  assert.equal(stale.body.results[0].status, 'conflict');
  assert.equal(stale.body.results[0].current.version, v1 + 1);
  assert.equal((await store.get('interestedPeople', 'rvV')).data.name, 'v2 by another device');
  // an edit built on the newest version goes through
  const ok = await push('adminA', { collection: 'interestedPeople', id: 'rvV', op: 'set', baseVersion: v1 + 1, data: { congregationId: 'congA', name: 'v3' } });
  assert.equal(ok.body.results[0].status, 'ok');
});

test('a deleted record is not brought back by a stale device, but an edit without a version works as before', async () => {
  const made = await push('adminA', { collection: 'interestedPeople', id: 'rvD', op: 'set', data: { congregationId: 'congA', name: 'to delete' } });
  const v = made.body.results[0].version;
  await push('coordA', { collection: 'interestedPeople', id: 'rvD', op: 'delete' });
  const stale = await push('adminA', { collection: 'interestedPeople', id: 'rvD', op: 'set', baseVersion: v, data: { congregationId: 'congA', name: 'zombie' } });
  assert.equal(stale.body.results[0].status, 'gone');
  assert.equal((await store.get('interestedPeople', 'rvD')).deleted, true, 'still deleted');
  const restore = await push('adminA', { collection: 'interestedPeople', id: 'rvD', op: 'set', data: { congregationId: 'congA', name: 'restored on purpose' } });
  assert.equal(restore.body.results[0].status, 'ok', 'a deliberate restore (no version) is still allowed');
});

test('the version check does not lift the permission check', async () => {
  const r = await push('adminB', { collection: 'interestedPeople', id: 'rvV', op: 'set', baseVersion: 99, data: { congregationId: 'congA', name: 'not mine' } });
  assert.notEqual(r.body.results[0].status, 'ok');
});
