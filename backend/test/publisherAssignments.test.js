import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

/**
 * Publishers Assignment and Monthly Report Snapshots, through the same push / pull endpoints every client uses: the Overseer, Servant and Assistant of an FS Group manage the
 * assignments and save the snapshots of THEIR OWN group only; the congregation-wide roles read every snapshot of their congregation (never of another); a saved snapshot never changes.
 */
let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId: 'c1', ...extra });

const call = async (who, path, body) => {
  const r = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'content-type': 'application/json', 'x-dev-person': who }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, body: await r.json() };
};
const push = async (who, op) => (await call(who, '/v1/sync/push', { ops: [op] })).body.results[0];
const setDoc = (who, collection, id, data) => push(who, { collection, id, op: 'set', data });
const delDoc = (who, collection, id) => push(who, { collection, id, op: 'delete' });
const visible = async (who, collection, id) => (await call(who, `/v1/sync/pull?since=0&collections=${collection}`)).body.changes.some((c) => c.id === id && !c.deleted);

const NOW = 1_700_000_000_000;
const assignment = (publisher, group, by, extra = {}) => ({
  congregationId: 'c1', groupId: group, publisherPersonId: publisher, title: 'Cart witnessing', type: 'CART', date: NOW, description: '', remarks: '', status: 'ASSIGNED',
  assignedByPersonId: by, createdAt: NOW, updatedAt: NOW, history: [{ at: NOW, byPersonId: by, status: 'ASSIGNED', note: 'Created' }], ...extra,
});
const snapshot = (group, by, extra = {}) => ({
  congregationId: 'c1', congregationName: 'Alpha', groupId: group, groupName: 'Group', year: 2023, month: 9, savedByPersonId: by, savedByName: by, savedAt: NOW,
  report: { totals: { publishers: 2, hours: 10 }, lines: [] }, ...extra,
});

before(async () => {
  store = new MemoryStore();
  await put('congregations', 'c1', { name: 'Alpha' });
  await put('congregations', 'c2', { name: 'Beta' });
  await put('people', 'ovr', person('REGULAR_ELDER', { activeGroupId: 'g1' }));
  await put('people', 'srv', person('MINISTERIAL_SERVANT', { activeGroupId: 'g1' }));
  await put('people', 'ast', person(null));
  await put('people', 'ovr2', person('REGULAR_ELDER', { activeGroupId: 'g2' }));
  await put('people', 'p1', person(null));
  await put('people', 'p2', person(null));
  await put('people', 'coord', person('COORDINATOR_ELDER'));
  await put('people', 'sec', person('SECRETARY'));
  await put('people', 'svc', person('SERVICE_OVERSEER'));
  await put('people', 'adm', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'elder', person('REGULAR_ELDER'));
  await put('people', 'coord2', person('COORDINATOR_ELDER', { activeCongregationId: 'c2' }));
  await put('groups', 'g1', { congregationId: 'c1', overseerPersonId: 'ovr', servantPersonId: 'srv', assistantPersonId: 'ast', status: 'ACTIVE', name: 'Group One' });
  await put('groups', 'g2', { congregationId: 'c1', overseerPersonId: 'ovr2', status: 'ACTIVE', name: 'Group Two' });
  await put('roleAssignments', 'raP1', { personId: 'p1', congregationId: 'c1', groupId: 'g1', status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  await put('roleAssignments', 'raP2', { personId: 'p2', congregationId: 'c1', groupId: 'g2', status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  server = createApp(store, { devAuth: true }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

for (const [who, label] of [['ovr', 'Group Overseer'], ['srv', 'Group Servant'], ['ast', 'Group Assistant']]) {
  test(`${label}: creates, edits, changes the status of and removes the assignments of its own group`, async () => {
    const id = `a_${who}`;
    assert.equal((await setDoc(who, 'publisherAssignments', id, assignment('p1', 'g1', who))).status, 'ok', 'create');
    assert.ok(await visible('p1', 'publisherAssignments', id), 'the publisher sees their own assignment');
    const first = assignment('p1', 'g1', who);
    const edited = { ...first, title: 'Public witnessing', status: 'IN_PROGRESS', updatedAt: NOW + 1, history: [...first.history, { at: NOW + 1, byPersonId: who, status: 'IN_PROGRESS', note: 'Started' }] };
    assert.equal((await setDoc(who, 'publisherAssignments', id, edited)).status, 'ok', 'edit and status change');
    assert.equal((await delDoc(who, 'publisherAssignments', id)).status, 'ok', 'remove');
  });

  test(`${label}: cannot manage the assignments of another FS Group, however the request is shaped`, async () => {
    assert.equal((await setDoc(who, 'publisherAssignments', `x_${who}`, assignment('p2', 'g2', who))).status, 'denied', 'create in another group');
    assert.equal((await setDoc(who, 'publisherAssignments', `x2_${who}`, assignment('p2', 'g1', who))).status, 'denied', 'a publisher of another group named in the own group');
    assert.equal((await setDoc(who, 'publisherAssignments', `x3_${who}`, assignment('p1', 'g1', 'ovr2'))).status, 'denied', 'made in the name of someone else');
    assert.equal((await setDoc(who, 'publisherAssignments', `x4_${who}`, assignment('p1', 'g1', who, { congregationId: 'c2' }))).status, 'denied', 'wrong congregation');
    assert.equal((await setDoc(who, 'publisherAssignments', `x5_${who}`, assignment('p1', 'g1', who, { status: 'DONE' }))).status, 'denied', 'unknown status');
    await setDoc('ovr2', 'publisherAssignments', `other_${who}`, assignment('p2', 'g2', 'ovr2'));
    assert.equal((await setDoc(who, 'publisherAssignments', `other_${who}`, { ...assignment('p2', 'g2', 'ovr2'), status: 'COMPLETED' })).status, 'denied', 'edit in another group');
    assert.equal((await delDoc(who, 'publisherAssignments', `other_${who}`)).status, 'denied', 'remove in another group');
    assert.equal(await visible(who, 'publisherAssignments', `other_${who}`), false, 'cannot even read it');
  });
}

test('a publisher, an elder or a congregation role without a slot manages no assignment; the group, publisher and owner never change; the history only grows', async () => {
  for (const who of ['p1', 'elder', 'coord', 'sec', 'svc', 'adm']) {
    assert.equal((await setDoc(who, 'publisherAssignments', `n_${who}`, assignment('p1', 'g1', who))).status, 'denied', who);
  }
  await setDoc('ovr', 'publisherAssignments', 'fixed', assignment('p1', 'g1', 'ovr'));
  assert.equal((await setDoc('ovr', 'publisherAssignments', 'fixed', assignment('p2', 'g1', 'ovr'))).status, 'denied', 'publisher cannot change');
  assert.equal((await setDoc('ovr', 'publisherAssignments', 'fixed', assignment('p1', 'g2', 'ovr'))).status, 'denied', 'group cannot change');
  assert.equal((await setDoc('ovr', 'publisherAssignments', 'fixed', assignment('p1', 'g1', 'srv'))).status, 'denied', 'owner cannot change');
  assert.equal((await setDoc('ovr', 'publisherAssignments', 'fixed', assignment('p1', 'g1', 'ovr', { history: [] }))).status, 'denied', 'history cannot be erased');
  assert.equal((await setDoc('p1', 'publisherAssignments', 'fixed', assignment('p1', 'g1', 'ovr', { status: 'COMPLETED' }))).status, 'denied', 'the publisher cannot edit their own assignment');
  assert.equal(await visible('coord', 'publisherAssignments', 'fixed'), false, 'a congregation role does not read the group assignments');
  assert.equal(await visible('p2', 'publisherAssignments', 'fixed'), false, 'another publisher does not read it');
});

test('a publisher who moved to another FS Group is no longer managed by the old group', async () => {
  await put('people', 'mover', person(null));
  await put('roleAssignments', 'raMover', { personId: 'mover', congregationId: 'c1', groupId: 'g1', status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  assert.equal((await setDoc('ovr', 'publisherAssignments', 'mv', assignment('mover', 'g1', 'ovr'))).status, 'ok');
  await put('roleAssignments', 'raMover', { personId: 'mover', congregationId: 'c1', groupId: 'g2', status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  assert.equal((await setDoc('ovr', 'publisherAssignments', 'mv2', assignment('mover', 'g1', 'ovr'))).status, 'denied', 'no new assignment for someone who left');
  assert.ok(await visible('ovr', 'publisherAssignments', 'mv'), 'the history stays with the group that made it');
});

for (const [who, label] of [['ovr', 'Group Overseer'], ['srv', 'Group Servant'], ['ast', 'Group Assistant']]) {
  test(`${label}: saves the monthly snapshot of its own group once, and it can never be changed`, async () => {
    const month = { 'ovr': 9, 'srv': 8, 'ast': 7 }[who];
    const id = `g1_2023${String(month).padStart(2, '0')}`;
    assert.equal((await setDoc(who, 'fsGroupReportSnapshots', id, snapshot('g1', who, { month }))).status, 'ok', 'save');
    assert.equal((await setDoc(who, 'fsGroupReportSnapshots', id, snapshot('g1', who, { month, report: { totals: { hours: 99 } } }))).status, 'denied', 'a second save for the same month');
    assert.equal((await setDoc('ovr', 'fsGroupReportSnapshots', id, snapshot('g1', 'ovr', { month, report: { totals: { hours: 99 } } }))).status, 'denied', 'overwrite by another officer');
    assert.equal((await delDoc(who, 'fsGroupReportSnapshots', id)).status, 'denied', 'delete');
    const stored = (await store.get('fsGroupReportSnapshots', id)).data;
    assert.equal(stored.report.totals.hours, 10, 'the saved values are untouched');
  });
}

test('a snapshot is saved only for the own group, as oneself, with a consistent id', async () => {
  assert.equal((await setDoc('ovr', 'fsGroupReportSnapshots', 'g2_202309', snapshot('g2', 'ovr'))).status, 'denied', 'another group');
  assert.equal((await setDoc('ovr', 'fsGroupReportSnapshots', 'g1_202306', snapshot('g1', 'srv', { month: 6 }))).status, 'denied', 'in the name of someone else');
  assert.equal((await setDoc('ovr', 'fsGroupReportSnapshots', 'whatever', snapshot('g1', 'ovr', { month: 6 }))).status, 'denied', 'id not the group and month');
  assert.equal((await setDoc('ovr', 'fsGroupReportSnapshots', 'g1_202306', snapshot('g1', 'ovr', { month: 6, congregationId: 'c2' }))).status, 'denied', 'wrong congregation');
  assert.equal((await setDoc('ovr', 'fsGroupReportSnapshots', 'g1_202313', snapshot('g1', 'ovr', { month: 13 }))).status, 'denied', 'bad month');
  for (const who of ['p1', 'elder', 'coord', 'sec', 'svc', 'adm', 'ovr2']) {
    assert.equal((await setDoc(who, 'fsGroupReportSnapshots', 'g1_202305', snapshot('g1', who, { month: 5 }))).status, 'denied', `${who} cannot save`);
  }
});

test('who reads which snapshot: own group officers, the congregation-wide roles of that congregation, nobody else', async () => {
  await setDoc('ovr2', 'fsGroupReportSnapshots', 'g2_202309', snapshot('g2', 'ovr2', { groupName: 'Group Two' }));
  const mine = 'g1_202309', theirs = 'g2_202309';
  for (const who of ['ovr', 'srv', 'ast']) {
    assert.ok(await visible(who, 'fsGroupReportSnapshots', mine), `${who} reads the own group`);
    assert.equal(await visible(who, 'fsGroupReportSnapshots', theirs), false, `${who} does not read another group`);
  }
  for (const who of ['coord', 'sec', 'svc', 'adm']) {
    assert.ok(await visible(who, 'fsGroupReportSnapshots', mine), `${who} reads all of the congregation`);
    assert.ok(await visible(who, 'fsGroupReportSnapshots', theirs), `${who} reads all of the congregation`);
  }
  for (const who of ['p1', 'p2', 'elder', 'coord2']) {
    assert.equal(await visible(who, 'fsGroupReportSnapshots', mine), false, `${who} reads nothing`);
  }
});
