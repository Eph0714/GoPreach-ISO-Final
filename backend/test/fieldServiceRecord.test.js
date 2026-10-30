import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

/**
 * Field Service Record: the Group Overseer, Group Servant and Group Assistant add, edit and delete the records of the publishers of THEIR OWN FS Group only, through the same
 * push endpoint every client uses; and a deleted record opens its month again for the publisher to send a new one, with never two live reports for one publisher and month.
 */
let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId: 'c1', ...extra });

const call = async (who, path, body) => {
  const r = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'content-type': 'application/json', 'x-dev-person': who }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, body: await r.json() };
};
const push = async (who, op) => (await call(who, '/v1/sync/push', { ops: [op] })).body.results[0];
const set = (who, id, data) => push(who, { collection: 'monthlyReports', id, op: 'set', data });
const remove = (who, id) => push(who, { collection: 'monthlyReports', id, op: 'delete' });
const live = async (id) => { const r = await store.get('monthlyReports', id); return !!r && !r.deleted; };

// September 2023: long past, so the sending window is open for publishers themselves
const SEP = Date.UTC(2023, 8, 1) - 8 * 3600_000;
const OCT = Date.UTC(2023, 9, 1) - 8 * 3600_000;
const record = (publisher, assignment, extra = {}) => ({
  congregationId: 'c1', publisherPersonId: publisher, periodMonth: SEP, status: 'SUBMITTED', source: 'MANUAL', category: 'REGULAR_PUBLISHER',
  bibleStudiesCount: 1, participatedInPreaching: true, groupRoleAssignmentId: assignment, ...extra,
});

before(async () => {
  store = new MemoryStore();
  await put('congregations', 'c1', { name: 'Alpha' });
  // Group 1: overseer (a Regular Elder acting in the group), servant (a Ministerial Servant) and assistant (an ordinary publisher)
  await put('people', 'ovr', person('REGULAR_ELDER', { activeGroupId: 'g1' }));
  await put('people', 'srv', person('MINISTERIAL_SERVANT', { activeGroupId: 'g1' }));
  await put('people', 'ast', person(null));
  await put('people', 'ovr2', person('REGULAR_ELDER', { activeGroupId: 'g2' }));
  await put('people', 'p1', person(null));
  await put('people', 'p2', person(null));
  await put('people', 'plain', person(null));
  await put('groups', 'g1', { congregationId: 'c1', overseerPersonId: 'ovr', servantPersonId: 'srv', assistantPersonId: 'ast', status: 'ACTIVE' });
  await put('groups', 'g2', { congregationId: 'c1', overseerPersonId: 'ovr2', status: 'ACTIVE' });
  await put('roleAssignments', 'raP1', { personId: 'p1', congregationId: 'c1', groupId: 'g1', status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  await put('roleAssignments', 'raP2', { personId: 'p2', congregationId: 'c1', groupId: 'g2', status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  server = createApp(store, { devAuth: true }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

for (const [who, label, n] of [['ovr', 'Group Overseer', 0], ['srv', 'Group Servant', 1], ['ast', 'Group Assistant', 2]]) {
  test(`${label}: adds, edits and deletes the records of publishers of its own FS Group`, async () => {
    const id = `p1_202309_${who}`, month = Date.UTC(2022, n, 1) - 8 * 3600_000;   // one month per role: a publisher has one live report a month
    assert.equal((await set(who, id, record('p1', 'raP1', { periodMonth: month }))).status, 'ok', 'add');
    assert.equal((await set(who, id, record('p1', 'raP1', { periodMonth: month, bibleStudiesCount: 5 }))).status, 'ok', 'edit');
    assert.equal((await remove(who, id)).status, 'ok', 'delete');
    assert.equal(await live(id), false);
  });

  test(`${label}: never touches a publisher of another FS Group, however the request is shaped`, async () => {
    // a record of the other group's publisher, made by that group's own overseer
    const m = Date.UTC(2021, n, 1) - 8 * 3600_000, other = (extra = {}) => record('p2', 'raP2', { periodMonth: m, ...extra });
    assert.equal((await set('ovr2', `p2_202309_${who}`, other())).status, 'ok', 'the overseer of the other group records its own publisher');
    assert.equal((await set(who, `new_${who}`, other())).status, 'denied', 'add for a publisher of another group');
    assert.equal((await set(who, `new2_${who}`, record('p2', 'raP1', { periodMonth: m }))).status, 'denied', 'add for a publisher of another group with the own group assignment id');
    assert.equal((await set(who, `new3_${who}`, record('p1', 'raP2', { periodMonth: m }))).status, 'denied', 'add with the assignment id of the other group');
    assert.equal((await set(who, `new4_${who}`, record('p1', null, { periodMonth: m }))).status, 'denied', 'add without proving the group');
    assert.equal((await set(who, `p2_202309_${who}`, other({ bibleStudiesCount: 9 }))).status, 'denied', 'edit a record of another group');
    assert.equal((await set(who, `p2_202309_${who}`, record('p2', 'raP1', { periodMonth: m, bibleStudiesCount: 9 }))).status, 'denied', 'edit it after re-stamping it with the own group assignment');
    assert.equal((await remove(who, `p2_202309_${who}`)).status, 'denied', 'delete a record of another group');
    assert.equal(await live(`p2_202309_${who}`), true, 'it is still there');
  });
}

test('a publisher or a member without a slot cannot manage records, and the other group\'s overseer cannot manage this group\'s', async () => {
  assert.equal((await set('plain', 'x1', record('p1', 'raP1'))).status, 'denied');
  assert.equal((await set('ovr2', 'x2', record('p1', 'raP1'))).status, 'denied', 'the overseer of Group 2 and a publisher of Group 1');
});

test('after an officer deletes a submitted record, the publisher sends a new one for the same month; never two live reports for one month', async () => {
  const id = 'p1_202309';
  assert.equal((await set('p1', id, { ...record('p1', null, { source: undefined }), status: 'SUBMITTED' })).status, 'ok', 'the publisher sends the month');
  assert.equal((await set('p1', id, { ...record('p1', null, { source: undefined, bibleStudiesCount: 2 }), status: 'SUBMITTED' })).status, 'denied', 'a sent report is locked for the publisher');
  // the officer deletes it: stamps the publisher\'s group first (as the apps do), then deletes
  assert.equal((await set('ovr', id, record('p1', 'raP1'))).status, 'ok');
  assert.equal((await remove('ovr', id)).status, 'ok');
  assert.equal(await live(id), false);
  // the month is open again: the publisher sends a replacement (the same document id, as the apps use)
  assert.equal((await set('p1', id, { ...record('p1', null, { source: undefined, bibleStudiesCount: 3 }), status: 'SUBMITTED' })).status, 'ok', 'resubmitted');
  assert.equal(await live(id), true);
  assert.equal((await store.get('monthlyReports', id)).data.bibleStudiesCount, 3);
  // a second live report for the same month under another id is refused, for the publisher and for the officer alike
  assert.equal((await set('p1', 'p1_other_id', { ...record('p1', null, { source: undefined }), status: 'SUBMITTED' })).status, 'denied', 'duplicate by the publisher');
  assert.equal((await set('ovr', 'p1_other_id', record('p1', 'raP1'))).status, 'denied', 'duplicate by the officer');
  // another month is a different report
  assert.equal((await set('ovr', 'p1_202310', record('p1', 'raP1', { periodMonth: OCT }))).status, 'ok');
});

test('a deleted record shows up as deleted in the pull, so every device drops it and the month opens on the publisher\'s screen', async () => {
  const id = 'p1_202308';
  const AUG = Date.UTC(2023, 7, 1) - 8 * 3600_000;
  await set('ovr', id, record('p1', 'raP1', { periodMonth: AUG }));
  const before = (await call('p1', '/v1/sync/pull?since=0&collections=monthlyReports')).body;
  assert.ok(before.changes.some((c) => c.id === id && !c.deleted), 'the publisher reads the record');
  await remove('ovr', id);
  const after = (await call('p1', `/v1/sync/pull?since=${before.cursor}&collections=monthlyReports`)).body;
  const change = after.changes.find((c) => c.id === id);
  assert.ok(change && change.deleted === true, 'the deletion reaches the publisher');
});
