import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

/**
 * Publisher Assignment (a Searching / Return Visit / Bible Study record given to a publisher) and Monthly Report Snapshots, through the same push / pull endpoints every client uses.
 * The Overseer, Servant and Assistant of an FS Group give records only between the publishers of THEIR OWN group and save only THEIR group's snapshots; the congregation-wide roles
 * read every snapshot of their congregation (never of another); a saved snapshot never changes.
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
const record = (owner, extra = {}) => ({ congregationId: 'c1', pipelineStage: 'SEARCHING', name: 'Someone', status: 'ACTIVE', publisherPersonId: owner, ...extra });
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
  await put('groups', 'g1', { id: 'g1', congregationId: 'c1', overseerPersonId: 'ovr', servantPersonId: 'srv', assistantPersonId: 'ast', status: 'ACTIVE', name: 'Group One' });
  await put('groups', 'g2', { id: 'g2', congregationId: 'c1', overseerPersonId: 'ovr2', status: 'ACTIVE', name: 'Group Two' });
  await put('roleAssignments', 'raP1', { personId: 'p1', congregationId: 'c1', groupId: 'g1', status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  await put('roleAssignments', 'raP2', { personId: 'p2', congregationId: 'c1', groupId: 'g2', status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  server = createApp(store, { devAuth: true }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

for (const [who, label] of [['ovr', 'Group Overseer'], ['srv', 'Group Servant'], ['ast', 'Group Assistant']]) {
  test(`${label}: gives records between the publishers of its own group, and nowhere else`, async () => {
    const id = `r_${who}`;
    await put('interestedPeople', id, record('p1'));
    assert.equal((await setDoc(who, 'interestedPeople', id, record('ast'))).status, 'ok', 'to another publisher of the group');
    assert.equal((await setDoc(who, 'interestedPeople', id, record(null))).status, 'ok', 'to nobody');
    assert.equal((await setDoc(who, 'interestedPeople', id, record('p1'))).status, 'ok', 'an unassigned record to a publisher of the group');
    assert.equal((await setDoc(who, 'interestedPeople', id, record('p2'))).status, 'denied', 'to a publisher of another group');
    await put('interestedPeople', id + 'x', record('p2'));
    assert.equal((await setDoc(who, 'interestedPeople', id + 'x', record('p1'))).status, 'denied', 'a record held by another group, taken into this one');
    assert.equal((await setDoc(who, 'interestedPeople', id + 'x', record(null))).status, 'denied', 'a record held by another group, released');
    assert.equal((await setDoc(who, 'interestedPeople', id + 'x', record('p2', { name: 'Edited' }))).status, 'ok', 'editing a record without changing who holds it is not an assignment');
  });
}

test('the congregation roles still assign freely; owners hand over their own; others cannot', async () => {
  for (const who of ['coord', 'sec', 'svc', 'adm']) {
    await put('interestedPeople', 'free', record('p2'));
    assert.equal((await setDoc(who, 'interestedPeople', 'free', record('p1'))).status, 'ok', who);
  }
  await put('interestedPeople', 'own', record('p1'));
  assert.equal((await setDoc('p1', 'interestedPeople', 'own', record('p2'))).status, 'ok', 'an owner hands their own record to someone');
  await put('interestedPeople', 'taken', record(null));
  assert.equal((await setDoc('p2', 'interestedPeople', 'taken', record('p2'))).status, 'ok', 'anyone takes an unassigned record for themselves');
  await put('interestedPeople', 'theirs', record('p1'));
  assert.equal((await setDoc('elder', 'interestedPeople', 'theirs', record('ast'))).status, 'denied', 'a Regular Elder without a group slot does not assign');
  assert.equal((await setDoc('p2', 'interestedPeople', 'theirs', record('ast'))).status, 'denied', 'an ordinary publisher cannot move someone elses record');
  assert.equal((await setDoc('ovr2', 'interestedPeople', 'theirs', record('p2'))).status, 'denied', 'the officer of another group cannot take it from this group');
});

for (const [who, label] of [['ovr', 'Group Overseer'], ['srv', 'Group Servant'], ['ast', 'Group Assistant']]) {
  test(`${label}: saves the monthly snapshot of its own group once, and it can never be changed`, async () => {
    const month = { ovr: 9, srv: 8, ast: 7 }[who];
    const id = `g1_2023${String(month).padStart(2, '0')}`;
    assert.equal((await setDoc(who, 'fsGroupReportSnapshots', id, snapshot('g1', who, { month }))).status, 'ok', 'save');
    assert.equal((await setDoc(who, 'fsGroupReportSnapshots', id, snapshot('g1', who, { month, report: { totals: { hours: 99 } } }))).status, 'denied', 'a second save for the same month');
    assert.equal((await setDoc('ovr', 'fsGroupReportSnapshots', id, snapshot('g1', 'ovr', { month, report: { totals: { hours: 99 } } }))).status, 'denied', 'overwrite by another officer');
    assert.equal((await delDoc('ovr2', 'fsGroupReportSnapshots', id)).status, 'denied', 'the officer of another group cannot delete it');
    assert.equal((await delDoc('p1', 'fsGroupReportSnapshots', id)).status, 'denied', 'a publisher cannot delete it');
    const stored = (await store.get('fsGroupReportSnapshots', id)).data;
    assert.equal(stored.report.totals.hours, 10, 'the saved values are untouched');
  });
}

test('an officer of the group deletes the saved Field Service Record of one month, and only that one', async () => {
  const keep = 'g1_202301', gone = 'g1_202302';
  assert.equal((await setDoc('ovr', 'fsGroupReportSnapshots', keep, snapshot('g1', 'ovr', { month: 1 }))).status, 'ok');
  assert.equal((await setDoc('srv', 'fsGroupReportSnapshots', gone, snapshot('g1', 'srv', { month: 2 }))).status, 'ok');
  assert.equal((await delDoc('ast', 'fsGroupReportSnapshots', gone)).status, 'ok', 'any officer of the group may delete');
  assert.equal((await store.get('fsGroupReportSnapshots', gone))?.deleted ?? !(await store.get('fsGroupReportSnapshots', gone)), true, 'it is gone');
  assert.equal(!!(await store.get('fsGroupReportSnapshots', keep)) && !(await store.get('fsGroupReportSnapshots', keep)).deleted, true, 'the other month is untouched');
  assert.equal((await setDoc('srv', 'fsGroupReportSnapshots', gone, snapshot('g1', 'srv', { month: 2 }))).status, 'ok', 'the month can be saved again afterwards');
});

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
