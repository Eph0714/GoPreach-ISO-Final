import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

let store, server, base;

const person = (activeAdminRole, activeCongregationId = 'congA', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, ...extra });
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));

before(async () => {
  store = new MemoryStore();
  await put('people', 'adminA', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'soA', person('SERVICE_OVERSEER'));
  await put('people', 'pubA', person(null));
  await put('people', 'adminB', person('ADMIN_PER_CONGREGATION', 'congB'));
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('people', 'maria', { ...person(null), username: 'maria.santos', createdByPersonId: 'adminA', firstName: 'Maria' });
  const app = createApp(store, { devAuth: true, publicLimits: { lookup: { max: 6 }, reset: { max: 3 } } });
  await new Promise((res) => { server = app.listen(0, res); });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const post = async (path, body, who) => {
  const r = await fetch(base + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(who ? { 'x-dev-person': who } : {}) },
    body: JSON.stringify(body),
  });
  return { status: r.status, body: await r.json() };
};
const live = async (collection, congregationId) => (await store.list(collection, congregationId));

const save = (who, o = {}) => post('/v1/territory/save', {
  congregationId: 'congA', groupId: 'g1', groupName: 'Group One', provinceId: 10, provinceName: 'Cebu',
  municipalities: [{ muncityId: 100, muncityName: 'Cebu City', barangays: [{ id: 1, name: 'Alpha' }, { id: 2, name: 'Beta' }] }],
  ...o,
}, who);

// ---- territory ----------------------------------------------------------------------------------------------------

test('a congregation-wide role claims barangays: one assignment, one claim per barangay', async () => {
  const r = await save('soA');
  assert.equal(r.status, 200);
  assert.equal(r.body.status, 'success');
  const assignments = await live('territoryAssignments', 'congA');
  assert.equal(assignments.length, 1);
  assert.equal(assignments[0].data.groupId, 'g1');
  assert.equal(assignments[0].data.createdByPersonId, 'soA');
  const claims = await live('territoryAssignmentBarangays', 'congA');
  assert.deepEqual(claims.map((c) => c.id).sort(), ['congA_1', 'congA_2']);
  assert.equal(claims[0].data.assignmentId, assignments[0].id);
});

test('a barangay already taken is a conflict, and the failed save writes nothing at all', async () => {
  const r = await save('adminA', {
    groupId: 'g2', groupName: 'Group Two',
    municipalities: [{ muncityId: 100, muncityName: 'Cebu City', barangays: [{ id: 3, name: 'Gamma' }, { id: 2, name: 'Beta' }] }],
  });
  assert.equal(r.status, 409);
  assert.equal(r.body.status, 'conflict');
  assert.equal(r.body.barangayName, 'Beta');
  assert.equal(r.body.takenByGroupName, 'Group One');
  assert.equal((await store.get('territoryAssignmentBarangays', 'congA_3')), null, 'Gamma must not be claimed by the failed save');
  assert.equal((await live('territoryAssignments', 'congA')).filter((a) => a.data.groupId === 'g2').length, 0);
});

test('two admins claiming the same barangay at the same moment: exactly one wins', async () => {
  const body = (groupId, groupName) => ({
    groupId, groupName, congregationId: 'congA', provinceId: 20, provinceName: 'Bohol',
    municipalities: [{ muncityId: 200, muncityName: 'Tagbilaran', barangays: [{ id: 50, name: 'Race' }] }],
  });
  const [a, b] = await Promise.all([
    post('/v1/territory/save', body('gx', 'Group X'), 'adminA'),
    post('/v1/territory/save', body('gy', 'Group Y'), 'soA'),
  ]);
  assert.deepEqual([a.status, b.status].sort(), [200, 409]);
  const claim = await store.get('territoryAssignmentBarangays', 'congA_50');
  assert.ok(['gx', 'gy'].includes(claim.data.groupId));
  assert.equal((await live('territoryAssignmentBarangays', 'congA')).filter((c) => c.id === 'congA_50').length, 1);
});

test('only congregation-wide roles of that congregation (or the Super Admin) may claim', async () => {
  const other = { municipalities: [{ muncityId: 300, muncityName: 'Mandaue', barangays: [{ id: 70, name: 'Solo' }] }], provinceId: 30, provinceName: 'Cebu 2' };
  assert.equal((await save('pubA', other)).status, 403);
  assert.equal((await save('adminB', other)).status, 403, 'another congregation');
  assert.equal((await save('ghost', other)).status, 403, 'unknown user');
  assert.equal((await post('/v1/territory/save', { ...other, groupId: 'g1', groupName: 'G', congregationId: 'congA' })).status, 401, 'no login');
  assert.equal((await save('sa', other)).status, 200);
});

test('editing a save: barangays added and removed, a dropped municipality disappears, renames flow into the claims', async () => {
  const r = await post('/v1/territory/save', {
    congregationId: 'congA', groupId: 'g1', groupName: 'Group One (renamed)', provinceId: 10, provinceName: 'Cebu',
    municipalities: [{ muncityId: 100, muncityName: 'Cebu City', barangays: [{ id: 2, name: 'Beta' }, { id: 4, name: 'Delta' }] }],
  }, 'adminA');
  assert.equal(r.body.status, 'success');
  assert.equal(await store.get('territoryAssignmentBarangays', 'congA_1').then((x) => x.deleted), true, 'Alpha released');
  assert.equal((await store.get('territoryAssignmentBarangays', 'congA_4')).data.barangayName, 'Delta');
  assert.equal((await store.get('territoryAssignmentBarangays', 'congA_2')).data.groupName, 'Group One (renamed)');
  const assignments = (await live('territoryAssignments', 'congA')).filter((a) => a.data.provinceId === 10);
  assert.equal(assignments.length, 1, 'same assignment is reused, not duplicated');

  // Now the group holds only a different municipality in that province: the old one is dropped entirely.
  const r2 = await post('/v1/territory/save', {
    congregationId: 'congA', groupId: 'g1', groupName: 'Group One (renamed)', provinceId: 10, provinceName: 'Cebu',
    municipalities: [{ muncityId: 101, muncityName: 'Lapu-Lapu', barangays: [{ id: 9, name: 'Nine' }] }],
  }, 'adminA');
  assert.equal(r2.body.status, 'success');
  assert.equal(await store.get('territoryAssignmentBarangays', 'congA_2').then((x) => x.deleted), true);
  assert.equal(await store.get('territoryAssignmentBarangays', 'congA_4').then((x) => x.deleted), true);
  const now = (await live('territoryAssignments', 'congA')).filter((a) => a.data.provinceId === 10);
  assert.deepEqual(now.map((a) => a.data.muncityId), [101]);
});

test('bad requests are rejected before anything is touched', async () => {
  assert.equal((await save('adminA', { municipalities: [] })).status, 400, 'nothing selected');
  assert.equal((await save('adminA', { provinceId: 'ten' })).status, 400);
  assert.equal((await save('adminA', { congregationId: 'a/b' })).status, 400);
  const many = Array.from({ length: 401 }, (_, i) => ({ id: 1000 + i, name: 'b' + i }));
  const r = await save('adminA', { municipalities: [{ muncityId: 100, muncityName: 'Cebu City', barangays: many }] });
  assert.equal(r.status, 400);
});

test('removing a group\'s province and removing a single assignment release their barangays', async () => {
  await save('adminA', {
    groupId: 'g5', groupName: 'Five', provinceId: 40, provinceName: 'Leyte',
    municipalities: [{ muncityId: 400, muncityName: 'Tacloban', barangays: [{ id: 81, name: 'A' }, { id: 82, name: 'B' }] }],
  });
  const denied = await post('/v1/territory/remove-group', { congregationId: 'congA', groupId: 'g5', provinceId: 40 }, 'pubA');
  assert.equal(denied.status, 403);
  const r = await post('/v1/territory/remove-group', { congregationId: 'congA', groupId: 'g5', provinceId: 40 }, 'adminA');
  assert.equal(r.status, 200);
  assert.equal(r.body.removedBarangays, 2);
  assert.equal((await store.get('territoryAssignmentBarangays', 'congA_81')).deleted, true);

  await save('adminA', {
    groupId: 'g6', groupName: 'Six', provinceId: 50, provinceName: 'Samar',
    municipalities: [{ muncityId: 500, muncityName: 'Catbalogan', barangays: [{ id: 91, name: 'C' }] }],
  });
  const assignment = (await live('territoryAssignments', 'congA')).find((a) => a.data.groupId === 'g6');
  assert.equal((await post('/v1/territory/remove', { assignmentId: assignment.id }, 'adminB')).status, 403, 'another congregation cannot remove it');
  assert.equal((await post('/v1/territory/remove', { assignmentId: assignment.id }, 'soA')).status, 200);
  assert.equal((await store.get('territoryAssignmentBarangays', 'congA_91')).deleted, true);
  assert.equal((await post('/v1/territory/remove', { assignmentId: assignment.id }, 'soA')).status, 200, 'removing twice is harmless');
});

// ---- public (no login) endpoints ---------------------------------------------------------------------------------

test('username lookup needs no login, returns the person with their id, and says nothing for an unknown name', async () => {
  const found = await post('/v1/public/lookup-username', { username: 'maria.santos' });
  assert.equal(found.status, 200);
  assert.equal(found.body.person.id, 'maria');
  assert.equal(found.body.person.firstName, 'Maria');
  assert.equal((await post('/v1/public/lookup-username', { username: 'Maria.Santos' })).body.person, null, 'case-sensitive, like before');
  assert.equal((await post('/v1/public/lookup-username', { username: 'nobody' })).body.person, null);
  assert.equal((await post('/v1/public/lookup-username', {})).status, 400);
});

test('username lookup is rate limited per client', async () => {
  let last;
  for (let i = 0; i < 8; i++) last = await post('/v1/public/lookup-username', { username: 'x' + i });
  assert.equal(last.status, 429);
});

test('a password reset request is built by the server and never reveals whether the username exists', async () => {
  const known = await post('/v1/public/password-reset-request', { username: 'maria.santos' });
  assert.equal(known.status, 202);
  const unknown = await post('/v1/public/password-reset-request', { username: 'nobody-here' });
  assert.equal(unknown.status, 202);
  assert.deepEqual(known.body, unknown.body);
  const requests = await live('passwordResetRequests');
  const mine = requests.find((r) => r.data.requestedUsername === 'maria.santos');
  assert.equal(mine.data.personId, 'maria');
  assert.equal(mine.data.targetPersonId, 'adminA');
  assert.equal(mine.data.status, 'PENDING');
  const ghost = requests.find((r) => r.data.requestedUsername === 'nobody-here');
  assert.equal(ghost.data.personId, null);
  assert.equal((await post('/v1/public/password-reset-request', { username: 'again-1' })).status, 202);
  assert.equal((await post('/v1/public/password-reset-request', { username: 'again-2' })).status, 429, 'rate limited after a few');
});
