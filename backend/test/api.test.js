import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

let store, server, base;

const person = (activeAdminRole, activeCongregationId = 'congA', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, ...extra });

async function seed() {
  const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
  await put('people', 'overseer', person('REGULAR_ELDER'));
  await put('people', 'servant', person(null));
  await put('people', 'plain', person(null));
  await put('people', 'coord', person('COORDINATOR_ELDER'));
  await put('people', 'adminA', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'coordB', person('COORDINATOR_ELDER', 'congB'));
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('groups', 'g1', { congregationId: 'congA', overseerPersonId: 'overseer', servantPersonId: 'servant' });
  await put('groups', 'g2', { congregationId: 'congA', overseerPersonId: 'someone' });
  await put('territoryAssignmentBarangays', 'congA_t1', { congregationId: 'congA', groupId: 'g1' });
  await put('territoryAssignmentBarangays', 'congA_t2', { congregationId: 'congA', groupId: 'g2' });
  await put('territoryBounds', 'congA_t1', { congregationId: 'congA', groupId: 'g1', minLat: 10, maxLat: 11, minLng: 120, maxLng: 121 });
  await put('interestedPeople', 'rvA', { congregationId: 'congA', name: 'Juan' });
  await put('interestedPeople', 'rvB', { congregationId: 'congB', name: 'Pedro' });
}

const polygon = JSON.stringify({ type: 'Polygon', coordinates: [[[120.2, 10.2], [120.4, 10.2], [120.4, 10.4], [120.2, 10.2]]] });
const drawing = (who, o = {}) => ({
  geometryJson: polygon, fillColor: '#43A047', fillOpacity: 0.1, borderColor: '#1B5E20', status: 'FINISHED', remarks: '', name: '',
  userId: who, updatedByUserId: who, congregationId: 'congA', groupId: 'g1', territoryId: 'congA_t1',
  minLat: 10.2, maxLat: 10.4, minLng: 120.2, maxLng: 120.4, createdAt: 1, updatedAt: 1, ...o,
});

const call = async (who, path, body) => {
  const r = await fetch(base + path, {
    method: body ? 'POST' : 'GET',
    headers: { 'content-type': 'application/json', 'x-dev-person': who },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: r.status, body: await r.json() };
};
const push = (who, ...ops) => call(who, '/v1/sync/push', { ops });
const set = (collection, id, data) => ({ collection, id, op: 'set', data });
const statusOf = async (who, op) => (await push(who, op)).body.results[0].status;

before(async () => {
  store = new MemoryStore();
  await seed();
  const app = createApp(store, { devAuth: true });
  await new Promise((res) => { server = app.listen(0, res); });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test('requests without a token are rejected', async () => {
  const r = await fetch(base + '/v1/sync/pull');
  assert.equal(r.status, 401);
});

test('an unknown person cannot pull or push', async () => {
  assert.equal((await call('ghost', '/v1/sync/pull')).status, 403);
  assert.equal(await statusOf('ghost', set('interestedPeople', 'x', { congregationId: 'congA' })), 'denied');
});

// ---- drawings: the same matrix firestore.rules enforces -----------------------------------------------------------

test('group-level users draw inside their own territory only', async () => {
  assert.equal(await statusOf('overseer', set('territoryDrawings', 'd1', drawing('overseer'))), 'ok');
  assert.equal(await statusOf('servant', set('territoryDrawings', 'd2', drawing('servant'))), 'ok');
  // Beyond the published bounds
  assert.equal(await statusOf('overseer', set('territoryDrawings', 'd3', drawing('overseer', { maxLat: 12 }))), 'denied');
  // Another group's territory
  assert.equal(await statusOf('overseer', set('territoryDrawings', 'd4', drawing('overseer', { groupId: 'g2', territoryId: 'congA_t2' }))), 'denied');
  // Own territory id but another group's name
  assert.equal(await statusOf('overseer', set('territoryDrawings', 'd5', drawing('overseer', { groupId: 'g2' }))), 'denied');
  // Unassigned area is for the wide roles
  assert.equal(await statusOf('overseer', set('territoryDrawings', 'd6', drawing('overseer', { groupId: '', territoryId: '' }))), 'denied');
});

test('a member with no group slot cannot draw', async () => {
  assert.equal(await statusOf('plain', set('territoryDrawings', 'p1', drawing('plain'))), 'denied');
});

test('congregation-wide roles draw anywhere in their congregation, including unassigned areas', async () => {
  assert.equal(await statusOf('coord', set('territoryDrawings', 'c1', drawing('coord', { maxLat: 12 }))), 'ok');
  assert.equal(await statusOf('coord', set('territoryDrawings', 'c2', drawing('coord', { groupId: 'g2', territoryId: 'congA_t2' }))), 'ok');
  assert.equal(await statusOf('adminA', set('territoryDrawings', 'c3', drawing('adminA', { groupId: '', territoryId: '' }))), 'ok');
  // never another congregation
  assert.equal(await statusOf('coordB', set('territoryDrawings', 'c4', drawing('coordB'))), 'denied');
  assert.equal(await statusOf('coordB', set('territoryDrawings', 'c5', drawing('coordB', { groupId: '', territoryId: '' }))), 'denied');
  // the Super Admin may
  assert.equal(await statusOf('sa', set('territoryDrawings', 'c6', drawing('sa', { congregationId: 'congB', groupId: '', territoryId: '' }))), 'ok');
});

test('status and color must agree, and fields are validated', async () => {
  assert.equal(await statusOf('coord', set('territoryDrawings', 'v1', drawing('coord', { status: 'TO_DO' }))), 'denied');
  assert.equal(await statusOf('coord', set('territoryDrawings', 'v2', drawing('coord', { status: 'TO_DO', fillColor: '#E53935' }))), 'ok');
  assert.equal(await statusOf('coord', set('territoryDrawings', 'v3', drawing('coord', { status: 'COMPLETED' }))), 'denied');
  assert.equal(await statusOf('coord', set('territoryDrawings', 'v4', drawing('coord', { remarks: 'x'.repeat(1001) }))), 'denied');
  assert.equal(await statusOf('coord', set('territoryDrawings', 'v5', drawing('coord', { geometryJson: 'not json' }))), 'denied');
});

test('the author cannot be spoofed and edits cannot rewrite history', async () => {
  assert.equal(await statusOf('overseer', set('territoryDrawings', 's1', drawing('coord'))), 'denied'); // userId != caller
  assert.equal(await statusOf('overseer', set('territoryDrawings', 's2', drawing('overseer', { updatedByUserId: 'coord' }))), 'denied');
  // an overseer may edit a drawing in their own group made by someone else…
  assert.equal(await statusOf('coord', set('territoryDrawings', 'e1', drawing('coord'))), 'ok');
  assert.equal(await statusOf('overseer', set('territoryDrawings', 'e1', drawing('coord', { updatedByUserId: 'overseer', status: 'TO_DO', fillColor: '#E53935' }))), 'ok');
  // …but not rewrite its author, and not touch another group's drawing
  assert.equal(await statusOf('overseer', set('territoryDrawings', 'e1', drawing('overseer'))), 'denied');
  assert.equal(await statusOf('coord', set('territoryDrawings', 'e2', drawing('coord', { groupId: 'g2', territoryId: 'congA_t2' }))), 'ok');
  assert.equal(await statusOf('overseer', set('territoryDrawings', 'e2', drawing('coord', { groupId: 'g2', territoryId: 'congA_t2', updatedByUserId: 'overseer' }))), 'denied');
});

test('deleting: own group yes, other group no, already-missing is harmless', async () => {
  assert.equal(await statusOf('overseer', { collection: 'territoryDrawings', id: 'e1', op: 'delete' }), 'ok');
  assert.equal(await statusOf('overseer', { collection: 'territoryDrawings', id: 'e2', op: 'delete' }), 'denied');
  assert.equal(await statusOf('overseer', { collection: 'territoryDrawings', id: 'never', op: 'delete' }), 'ok');
});

test('territory bounds are written only by the wide roles; audits are append-only', async () => {
  const bounds = { congregationId: 'congA', groupId: 'g1', minLat: 0, maxLat: 90, minLng: 0, maxLng: 180 };
  assert.equal(await statusOf('overseer', set('territoryBounds', 'congA_t1', bounds)), 'denied');
  assert.equal(await statusOf('coord', set('territoryBounds', 'congA_t1', bounds)), 'ok');
  const audit = { drawingId: 'd1', action: 'CREATED', userId: 'overseer', congregationId: 'congA', groupId: 'g1', territoryId: 'congA_t1', at: 1 };
  assert.equal(await statusOf('overseer', set('territoryDrawingAudits', 'a1', audit)), 'ok');
  assert.equal(await statusOf('overseer', set('territoryDrawingAudits', 'a1', { ...audit, action: 'DELETED' })), 'denied');
  assert.equal(await statusOf('overseer', { collection: 'territoryDrawingAudits', id: 'a1', op: 'delete' }), 'denied');
});

// ---- default policy for the other collections -------------------------------------------------------------------

test('other collections: own congregation only, and no self-promotion', async () => {
  assert.equal(await statusOf('overseer', set('interestedPeople', 'new1', { congregationId: 'congA', name: 'Maria' })), 'ok');
  assert.equal(await statusOf('overseer', set('interestedPeople', 'new2', { congregationId: 'congB', name: 'Maria' })), 'denied');
  assert.equal(await statusOf('overseer', set('interestedPeople', 'rvB', { congregationId: 'congB', name: 'hijack' })), 'denied');
  assert.equal(await statusOf('plain', set('people', 'plain', person(null, 'congA', { isSuperAdmin: true }))), 'denied');
  assert.equal(await statusOf('plain', set('roleAssignments', 'r1', { congregationId: 'congA', roleType: 'ADMIN:SUPER_ADMIN' })), 'denied');
});

// ---- sync: pull ------------------------------------------------------------------------------------------------

test('pull returns only what the caller may read, with a cursor, and tombstones for deletes', async () => {
  const first = await call('overseer', '/v1/sync/pull?since=0&limit=1000');
  assert.equal(first.status, 200);
  const ids = first.body.changes.map((c) => `${c.collection}/${c.id}`);
  assert.ok(ids.includes('interestedPeople/rvA'));
  assert.ok(!ids.includes('interestedPeople/rvB'), 'other congregation must not leak');
  assert.ok(!ids.some((i) => i.startsWith('territoryDrawingAudits/')), 'audit trail is for the wide roles');
  // the Super Admin sees both congregations
  const all = (await call('sa', '/v1/sync/pull?since=0&limit=1000')).body.changes.map((c) => `${c.collection}/${c.id}`);
  assert.ok(all.includes('interestedPeople/rvB'));
  assert.ok((await call('coord', '/v1/sync/pull?since=0&limit=1000')).body.changes.some((c) => c.collection === 'territoryDrawingAudits'));

  // a delete arrives as a tombstone after the cursor
  await push('coord', { collection: 'territoryDrawings', id: 'c1', op: 'delete' });
  const next = await call('overseer', `/v1/sync/pull?since=${first.body.cursor}&limit=1000`);
  const tomb = next.body.changes.find((c) => c.id === 'c1');
  assert.ok(tomb && tomb.deleted === true && tomb.data === null);
  assert.ok(next.body.cursor > first.body.cursor);
});

test('pull pages through a long history without skipping or repeating', async () => {
  let cursor = 0;
  const seen = new Set();
  for (let i = 0; i < 100; i++) {
    const r = await call('sa', `/v1/sync/pull?since=${cursor}&limit=7`);
    for (const c of r.body.changes) { assert.ok(!seen.has(`${c.collection}/${c.id}/${c.seq}`)); seen.add(`${c.collection}/${c.id}/${c.seq}`); }
    cursor = r.body.cursor;
    if (!r.body.hasMore) break;
  }
  assert.ok(seen.size > 20);
});

test('a bad batch cannot poison the good ops in it', async () => {
  const r = await push('coord',
    set('interestedPeople', 'b1', { congregationId: 'congA' }),
    { collection: 'bad name!', id: 'x', op: 'set', data: {} },
    set('interestedPeople', 'b2', { congregationId: 'congB' }),
    set('interestedPeople', 'b3', { congregationId: 'congA' }));
  assert.deepEqual(r.body.results.map((x) => x.status), ['ok', 'invalid', 'denied', 'ok']);
});
