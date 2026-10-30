import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

/** Replacing a Monthly Report Snapshot: the next version, with a reason, by an officer of the group, and with the replaced copy kept unchanged in its history. */
let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId: 'c1', ...extra });
const call = async (who, path, body) => {
  const r = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'content-type': 'application/json', 'x-dev-person': who }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, body: await r.json() };
};
const setDoc = async (who, id, data) => (await call(who, '/v1/sync/push', { ops: [{ collection: 'fsGroupReportSnapshots', id, op: 'set', data }] })).body.results[0];
const ID = 'g1_202609';
const first = (extra = {}) => ({
  congregationId: 'c1', congregationName: 'Alpha', groupId: 'g1', groupName: 'Group One', year: 2026, month: 9, periodMonth: 1, savedByPersonId: 'ovr', savedByName: 'Gil', savedByRole: 'Group Overseer',
  savedAt: 1000, report: { totals: { studies: 3 } }, ...extra,
});
/** The replacement of [old] by [who]: version + 1, the old copy appended to the history. */
const replacement = (old, who, report, extra = {}) => ({
  ...old, savedByPersonId: who, savedByName: who, savedByRole: 'Group Servant', savedAt: 2000, report, version: (old.version ?? 1) + 1, replaceReason: 'A report was corrected', replacedAt: 2000, replacedByPersonId: who,
  replacedByName: who, versions: [...(old.versions ?? []), { version: old.version ?? 1, savedAt: old.savedAt, savedByPersonId: old.savedByPersonId, savedByName: old.savedByName, savedByRole: old.savedByRole, report: old.report }], ...extra,
});

before(async () => {
  store = new MemoryStore();
  await put('congregations', 'c1', { name: 'Alpha' });
  await put('people', 'ovr', person('REGULAR_ELDER', { activeGroupId: 'g1' }));
  await put('people', 'srv', person('MINISTERIAL_SERVANT', { activeGroupId: 'g1' }));
  await put('people', 'ovr2', person('REGULAR_ELDER', { activeGroupId: 'g2' }));
  await put('people', 'sec', person('SECRETARY'));
  await put('groups', 'g1', { id: 'g1', congregationId: 'c1', overseerPersonId: 'ovr', servantPersonId: 'srv', status: 'ACTIVE', name: 'Group One' });
  await put('groups', 'g2', { id: 'g2', congregationId: 'c1', overseerPersonId: 'ovr2', status: 'ACTIVE', name: 'Group Two' });
  server = createApp(store, { devAuth: true }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test('an officer of the group replaces a snapshot with the next version and the old copy kept; it can be replaced again', async () => {
  const r1 = await setDoc('ovr', ID, first()); assert.equal(r1.status, 'ok', 'the first save ' + r1.reason);
  const v1 = first();
  const v2 = replacement(v1, 'srv', { totals: { studies: 9 } });
  const r2 = await setDoc('srv', ID, v2); assert.equal(r2.status, 'ok', 'a different officer replaces it ' + r2.reason);
  const stored = (await store.get('fsGroupReportSnapshots', ID)).data;
  assert.equal(stored.version, 2);
  assert.equal(stored.report.totals.studies, 9);
  assert.equal(stored.versions.length, 1);
  assert.equal(stored.versions[0].report.totals.studies, 3, 'the replaced copy is kept as it was');
  assert.equal(stored.versions[0].savedByName, 'Gil');
  const v3 = replacement(stored, 'ovr', { totals: { studies: 11 } });
  assert.equal((await setDoc('ovr', ID, v3)).status, 'ok', 'replaced again');
  assert.equal((await store.get('fsGroupReportSnapshots', ID)).data.versions.length, 2);
});

test('a replacement without its history, with a changed history, without a reason, skipping a version or in another name is refused', async () => {
  const cur = (await store.get('fsGroupReportSnapshots', ID)).data;
  const good = replacement(cur, 'srv', { totals: { studies: 20 } });
  assert.equal((await setDoc('srv', ID, { ...good, versions: cur.versions })).status, 'denied', 'the replaced copy is not in the history');
  assert.equal((await setDoc('srv', ID, { ...good, versions: [] })).status, 'denied', 'the history is erased');
  assert.equal((await setDoc('srv', ID, { ...good, versions: [{ ...good.versions[0], report: { totals: { studies: 0 } } }, ...good.versions.slice(1)] })).status, 'denied', 'an older copy is changed');
  assert.equal((await setDoc('srv', ID, { ...good, versions: [...good.versions.slice(0, -1), { ...good.versions.at(-1), report: { totals: { studies: 0 } } }] })).status, 'denied', 'the copy kept is not the copy replaced');
  assert.equal((await setDoc('srv', ID, { ...good, replaceReason: '  ' })).status, 'denied', 'no reason');
  assert.equal((await setDoc('srv', ID, { ...good, version: cur.version + 2 })).status, 'denied', 'a version skipped');
  assert.equal((await setDoc('srv', ID, { ...good, version: cur.version })).status, 'denied', 'the same version again');
  assert.equal((await setDoc('srv', ID, { ...good, savedByPersonId: 'ovr' })).status, 'denied', 'saved in the name of someone else');
  assert.equal((await setDoc('srv', ID, { ...good, replacedByPersonId: 'ovr' })).status, 'denied', 'replaced in the name of someone else');
  assert.equal((await setDoc('srv', ID, { ...good, month: 8 })).status, 'denied', 'a different month');
  assert.equal((await store.get('fsGroupReportSnapshots', ID)).data.version, cur.version, 'nothing changed');
});

test('only the officers of that group replace it; nobody removes a snapshot; a new one cannot start with a history', async () => {
  const cur = (await store.get('fsGroupReportSnapshots', ID)).data;
  for (const who of ['ovr2', 'sec']) assert.equal((await setDoc(who, ID, replacement(cur, who, { totals: {} }))).status, 'denied', who);
  const del = (await call('ovr', '/v1/sync/push', { ops: [{ collection: 'fsGroupReportSnapshots', id: ID, op: 'delete' }] })).body.results[0];
  assert.equal(del.status, 'denied');
  assert.equal((await setDoc('ovr', 'g1_202608', first({ month: 8, version: 2, versions: [{ version: 1 }] }))).status, 'denied', 'a new snapshot with an invented history');
  assert.equal((await setDoc('ovr', 'g1_202608', first({ month: 8 }))).status, 'ok');
});
