import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

// Ministry History: every ministry action is recorded by the server when it is saved, readable only by who may see the record, never writable by a client.

let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, activeCongregationId = 'congA', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, firstName: 'X', lastName: 'Y', ...extra });
const hdr = (who) => ({ 'content-type': 'application/json', 'x-dev-person': who });
const push = async (who, ops) => (await (await fetch(`${base}/v1/sync/push`, { method: 'POST', headers: hdr(who), body: JSON.stringify({ ops }) })).json()).results;
const history = async (who, q = '') => (await (await fetch(`${base}/v1/ministry/history?${q}`, { headers: hdr(who) })).json());
const rec = (extra = {}) => ({ congregationId: 'congA', publisherPersonId: 'pub1', name: 'JUAN CRUZ', pipelineStage: 'SEARCHING', status: 'ACTIVE', province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Quezon', createdAt: 1, createdByPersonId: 'pub1', ...extra });
const types = (r) => r.entries.map((e) => e.type);

before(async () => {
  store = new MemoryStore();
  await put('congregations', 'congA', { name: 'Alpha' });
  await put('congregations', 'congB', { name: 'Bravo' });
  await put('people', 'pub1', person(null, 'congA', { firstName: 'Pat', lastName: 'One' }));
  await put('people', 'pub2', person(null, 'congA', { firstName: 'Olive', lastName: 'Two' }));
  await put('people', 'adm', person('ADMIN_PER_CONGREGATION', 'congA', { firstName: 'Ada', lastName: 'Admin' }));
  await put('roleAssignments', 'ra_adm', { personId: 'adm', roleType: 'ADMIN:ADMIN_PER_CONGREGATION', congregationId: 'congA', status: 'ACTIVE' });
  await put('people', 'pubB', person(null, 'congB'));
  await put('roleAssignments', 'ra1', { personId: 'pub1', roleType: 'PUBLISHER:REGULAR_PUBLISHER', congregationId: 'congA', groupId: 'g1', status: 'ACTIVE' });
  await put('roleAssignments', 'ra2', { personId: 'pub2', roleType: 'PUBLISHER:REGULAR_PUBLISHER', congregationId: 'congA', groupId: 'g1', status: 'ACTIVE' });
  // from before the History existed (the one-time backfill)
  await put('interestedPeople', 'old1', rec({ name: 'MARIA OLD', createdAt: 1700000000000 }));
  await put('interestedPeople/old1/visits', 'ov1', { interestedPersonId: 'old1', visitDate: 1700100000000, visitTime: 1700100000000, outcome: 'RETURN_VISIT_ARRANGED', createdAt: 1700100000000, createdByPersonId: 'pub1' });
  const app = createApp(store, { devAuth: true });
  await new Promise((res) => { server = app.listen(0, res); });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test('the records and visits that exist from before are listed once, with their original dates', async () => {
  const r = await history('pub1');
  assert.deepEqual(types(r).sort(), ['ENROLLED', 'VISIT_LOGGED']);
  assert.equal(r.entries.find((e) => e.type === 'ENROLLED').at, 1700000000000);
  assert.equal(r.entries.find((e) => e.type === 'VISIT_LOGGED').at, 1700100000000);
  assert.equal((await history('pub1')).entries.length, 2, 'the backfill runs once');
});

test('enrolling, logging, editing and deleting a visit are recorded with their details', async () => {
  assert.equal((await push('pub1', [{ collection: 'interestedPeople', id: 'r1', op: 'set', data: rec() }]))[0].status, 'ok');
  let r = await history('pub1', 'type=ENROLLED');
  const enrolled = r.entries.find((e) => e.recordId === 'r1');
  assert.equal(enrolled.summary, 'Enrolled a new Searching record');
  assert.equal(enrolled.actorName, 'Pat One');
  assert.ok(enrolled.details.some((d) => d.label === 'Barangay' && d.value === 'Quezon'));

  const visit = { interestedPersonId: 'r1', visitDate: 1790000000000, visitTime: 1790000000000, outcome: 'BUSY', topicDiscussed: 'Kingdom hope', timeConsumedMinutes: 75, createdAt: 1790000000000, createdByPersonId: 'pub1' };
  await push('pub1', [{ collection: 'interestedPeople/r1/visits', id: 'v1', op: 'set', data: visit }]);
  r = await history('pub1', 'type=VISIT');
  const logged = r.entries.find((e) => e.visitId === 'v1');
  assert.equal(logged.type, 'VISIT_LOGGED');
  assert.deepEqual(logged.details.map((d) => d.label), ['Visit date', 'Outcome', 'Topic discussed', 'Time spent']);
  assert.equal(logged.details.find((d) => d.label === 'Time spent').value, '1h 15m');
  assert.equal(logged.recordName, 'JUAN CRUZ');

  await push('pub1', [{ collection: 'interestedPeople/r1/visits', id: 'v1', op: 'set', data: { ...visit, outcome: 'INTERESTED', topicDiscussed: 'Resurrection' } }]);
  const edited = (await history('pub1', 'type=VISIT')).entries.find((e) => e.type === 'VISIT_EDITED');
  assert.ok(edited.details.some((d) => d.label === 'Changed: Outcome' && d.value === 'Busy → Interested'));
  await push('pub1', [{ collection: 'interestedPeople/r1/visits', id: 'v1', op: 'delete' }]);
  assert.ok(types(await history('pub1', 'type=VISIT')).includes('VISIT_DELETED'));
});

test('moves, assignment, location, edits and the Recycle Bin are recorded; a save that changes nothing is not', async () => {
  const cur = rec();
  const save = (data) => push('pub1', [{ collection: 'interestedPeople', id: 'r1', op: 'set', data }]);
  await save({ ...cur, pipelineStage: 'RETURN_VISIT' });
  await save({ ...cur, pipelineStage: 'RETURN_VISIT', gpsLat: 16.5, gpsLng: 121.1 });
  await save({ ...cur, pipelineStage: 'RETURN_VISIT', gpsLat: 16.5, gpsLng: 121.1, name: 'JUAN DELA CRUZ', contact: '0917' });
  await save({ ...cur, pipelineStage: 'RETURN_VISIT', gpsLat: 16.5, gpsLng: 121.1, name: 'JUAN DELA CRUZ', contact: '0917', publisherPersonId: 'pub2' });
  await save({ ...cur, pipelineStage: 'RETURN_VISIT', gpsLat: 16.5, gpsLng: 121.1, name: 'JUAN DELA CRUZ', contact: '0917', publisherPersonId: 'pub2', status: 'INACTIVE' });
  const before = (await history('adm', 'type=CHANGE')).entries.length;
  await save({ ...cur, pipelineStage: 'RETURN_VISIT', gpsLat: 16.5, gpsLng: 121.1, name: 'JUAN DELA CRUZ', contact: '0917', publisherPersonId: 'pub2', status: 'INACTIVE', updatedAt: 99 });
  const all = (await history('adm', 'type=CHANGE')).entries;
  assert.equal(all.length, before, 'nothing meaningful changed');
  const t = all.map((e) => e.type);
  for (const x of ['MOVED', 'LOCATION', 'EDITED', 'ASSIGNED', 'ARCHIVED']) assert.ok(t.includes(x), x);
  assert.equal(all.find((e) => e.type === 'MOVED').summary, 'Moved from Searching to Return Visit');
  assert.equal(all.find((e) => e.type === 'ASSIGNED').summary, 'Assigned to Olive Two');
  assert.ok(all.find((e) => e.type === 'EDITED').details.some((d) => d.label === 'Name' && d.value === 'JUAN CRUZ → JUAN DELA CRUZ'));
});

test('who sees what: the scope drop-down, other publishers, other congregations', async () => {
  const mine = await history('pub1', 'scope=0');
  assert.ok(mine.entries.length >= 5);
  assert.equal((await history('pub2', 'scope=0')).entries.some((e) => e.recordId === 'old1'), false, 'not their record, not shared');
  assert.ok((await history('pub2', 'scope=0')).entries.some((e) => e.recordId === 'r1'), 'their record now (assigned to them)');
  assert.ok((await history('adm', 'scope=2')).entries.length >= mine.entries.length);
  assert.equal((await history('pubB', 'scope=2')).entries.length, 0, 'another congregation');
  await put('publisherVisibilitySettings', 'congA', { showOthersSearching: true });
  assert.ok((await history('pub2', 'scope=2')).entries.some((e) => e.recordId === 'old1'), 'the congregation shares Searching');
  await put('publisherVisibilitySettings', 'congA', {});
  assert.ok((await history('pub2', 'scope=1')).entries.some((e) => e.actorPersonId === 'pub1'), 'my FS Group: the same group as pub1');
});

test('search, type filter, paging and the record link', async () => {
  const q = await history('adm', 'q=maria');
  assert.ok(q.entries.length > 0 && q.entries.every((e) => e.recordName === 'MARIA OLD'));
  assert.ok((await history('adm', 'type=VISIT')).entries.every((e) => e.category === 'VISIT'));
  const first = await history('adm', 'limit=3');
  assert.equal(first.entries.length, 3);
  assert.equal(first.hasMore, true);
  const next = await history('adm', `limit=3&before=${first.entries[2].at}`);
  assert.ok(next.entries.every((e) => e.at < first.entries[2].at));
  assert.equal(first.entries.every((e) => e.recordExists), true);
  const sorted = [...first.entries].sort((a, b) => b.at - a.at);
  assert.deepEqual(first.entries.map((e) => e.id), sorted.map((e) => e.id));
});

test('a client can neither write nor pull the history', async () => {
  const r = await push('pub1', [{ collection: 'ministryHistory', id: 'x', op: 'set', data: { congregationId: 'congA', summary: 'forged' } }]);
  assert.equal(r[0].status, 'denied');
  const pull = await (await fetch(`${base}/v1/sync/pull?since=0&collections=ministryHistory`, { headers: hdr('adm') })).json();
  assert.equal(pull.changes.length, 0);
});
