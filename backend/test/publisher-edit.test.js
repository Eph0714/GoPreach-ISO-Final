import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { hashPassword } from '../src/passwords.js';

// The unified publisher edit (POST /v1/publishers/:id/edit): who may change what, that it is all-or-nothing, and that the sign-in stays safe.

let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, activeCongregationId = 'congA', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, firstName: 'X', lastName: 'Y', ...extra });
const assign = (id, personId, roleType, congregationId = 'congA', groupId = null) => put('roleAssignments', id, { personId, roleType, congregationId, groupId, status: 'ACTIVE' });
const details = (o = {}) => ({ firstName: 'MARIA', lastName: 'SANTOS', address: 'PUROK 1', contact: '0917', province: 'NUEVA VIZCAYA', cityMunicipality: 'SOLANO', barangay: 'ROXAS', ...o });

before(async () => {
  store = new MemoryStore();
  await put('congregations', 'congA', { name: 'Alpha' });
  await put('congregations', 'congB', { name: 'Bravo' });
  for (const [id, who] of [['adm', 'ADMIN_PER_CONGREGATION'], ['coord', 'COORDINATOR_ELDER'], ['sec', 'SECRETARY'], ['so', 'SERVICE_OVERSEER']]) await put('people', id, person(who));
  // listed as a Ministerial Servant, but also assigned Secretary: the assigned role counts, not the category
  await put('people', 'msSec', person('MINISTERIAL_SERVANT')); await assign('ra_ms', 'msSec', 'ADMIN:SECRETARY');
  await put('people', 'ovr', person('REGULAR_ELDER')); await put('people', 'srv', person('MINISTERIAL_SERVANT')); await put('people', 'ast', person(null));
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('people', 'co', person('CIRCUIT_OVERSEER', null)); await put('userAccessGrants', 'co', { personId: 'co', scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congA'], circuitCode: 'NT01', permissions: ['VIEW_CONGREGATIONS', 'MANAGE_PUBLISHERS'] });
  await put('groups', 'g1', { congregationId: 'congA', name: 'Group 1', status: 'ACTIVE', overseerPersonId: 'ovr', servantPersonId: 'srv', assistantPersonId: 'ast' });
  await put('groups', 'g2', { congregationId: 'congA', name: 'Group 2', status: 'ACTIVE', overseerPersonId: 'someone' });
  await put('groups', 'gB', { congregationId: 'congB', name: 'Group B', status: 'ACTIVE', overseerPersonId: 'someoneB' });
  await put('people', 'pub1', person(null, 'congA', { username: 'maria.one', accountStatus: 'ACTIVE' })); await assign('ra_p1', 'pub1', 'PUBLISHER:REGULAR_PUBLISHER', 'congA', 'g1');
  await put('people', 'pub2', person(null, 'congA', { username: 'juan.two' })); await assign('ra_p2', 'pub2', 'PUBLISHER:REGULAR_PUBLISHER', 'congA', 'g2');
  await put('people', 'pubB', person(null, 'congB', { username: 'pedro.b' })); await assign('ra_pb', 'pubB', 'PUBLISHER:REGULAR_PUBLISHER', 'congB', 'gB');
  await put('people', 'eld', person('REGULAR_ELDER', 'congA', { username: 'eld.one' })); await assign('ra_e', 'eld', 'PUBLISHER:REGULAR_PUBLISHER', 'congA', 'g2');
  await put('monthlyReports', 'rep1', { publisherPersonId: 'pub1', congregationId: 'congA', periodMonth: 1, hours: 12 });
  for (const id of ['pub1', 'pub2', 'pubB', 'eld']) await store.createAccount(id, await hashPassword('Original1'));
  const app = createApp(store, { devAuth: true });
  await new Promise((res) => { server = app.listen(0, res); });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const edit = async (who, id, body) => {
  const r = await fetch(`${base}/v1/publishers/${id}/edit`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-dev-person': who }, body: JSON.stringify(body) });
  return { status: r.status, body: await r.json() };
};
const get = async (c, id) => (await store.get(c, id)).data;
const audits = async () => (await store.list('auditLog')).map((r) => r.data);
const pwHash = async (id) => (await store.getAccount(id)).pwHash;

test('the Admin edits everything of a publisher of any FS Group of their congregation in one save', async () => {
  const before = await pwHash('pub2');
  const r = await edit('adm', 'pub2', { person: details({ firstName: 'JUAN', email: 'juan@example.com', birthdate: Date.UTC(1990, 0, 5), gender: 'MALE', gpsLat: 16.5, gpsLng: 121.1 }), groupId: 'g1', category: 'REGULAR_PIONEER', username: 'juan.new', accountStatus: 'SUSPENDED', password: 'TempPass9' });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.deepEqual(r.body.changed.sort(), ['classification', 'details', 'group', 'password', 'username', 'accountStatus'].sort());
  const p = await get('people', 'pub2');
  assert.deepEqual([p.firstName, p.email, p.gender, p.gpsLat, p.username, p.accountStatus, p.isTemporaryCredential], ['JUAN', 'juan@example.com', 'MALE', 16.5, 'juan.new', 'SUSPENDED', true]);
  const a = await get('roleAssignments', 'ra_p2');
  assert.deepEqual([a.roleType, a.groupId, a.congregationId], ['PUBLISHER:REGULAR_PIONEER', 'g1', 'congA']);
  assert.notEqual(await pwHash('pub2'), before, 'the password was set (hashed)');
  assert.equal(JSON.stringify(r.body).includes('TempPass9'), false, 'the answer never carries a password');
  const log = await audits();
  assert.ok(['ACCOUNT_MGMT_USERNAME_CHANGE', 'ACCOUNT_MGMT_STATUS_CHANGE', 'ACCOUNT_MGMT_PASSWORD_RESET', 'PUBLISHER_CLASSIFICATION_CHANGE', 'CHANGE_PUBLISHER_GROUP', 'PUBLISHER_RECORD_EDIT'].every((x) => log.some((l) => l.action === x && l.actorPersonId === 'adm' && l.targetId === 'pub2')));
  assert.equal(JSON.stringify(log).includes('TempPass9'), false, 'a password is never written to the audit log');
});

test('the Coordinator Elder, Secretary and Service Overseer edit across FS Groups, also when they are listed as a Ministerial Servant', async () => {
  for (const [who, id, n] of [['coord', 'pub1', 'A'], ['sec', 'pub1', 'B'], ['so', 'pub1', 'C'], ['msSec', 'pub1', 'D']]) {
    const r = await edit(who, id, { person: details({ remarks: `by ${who}` }), groupId: n === 'A' ? 'g2' : 'g1', category: n === 'C' ? 'AUXILIARY_PIONEER' : 'REGULAR_PUBLISHER' });
    assert.equal(r.status, 200, `${who}: ${JSON.stringify(r.body)}`);
    assert.equal((await get('people', id)).remarks, `by ${who}`);
  }
});

test('the group roles edit personal details of their own group only, and nothing else', async () => {
  for (const who of ['ast', 'srv', 'ovr']) {
    const ok = await edit(who, 'pub1', { person: details({ contactPerson: `by ${who}` }) });
    assert.equal(ok.status, 200, `${who}: ${JSON.stringify(ok.body)}`);
    assert.equal((await get('people', 'pub1')).contactPerson, `by ${who}`);
    // another group's publisher
    assert.equal((await edit(who, 'eld', { person: details() })).status, 403);
    // the rest of the record is not theirs
    for (const [field, body] of [['username', { username: 'hijack.1' }], ['accountStatus', { accountStatus: 'INACTIVE' }], ['category', { category: 'REGULAR_PIONEER' }], ['group', { groupId: 'g2' }], ['congregation', { congregationId: 'congB' }], ['password', { password: 'Sneaky123' }]]) {
      const r = await edit(who, 'pub1', body);
      assert.equal(r.status, 403, `${who} must not change ${field}`);
    }
  }
  assert.equal((await get('people', 'pub1')).username, 'maria.one');
  assert.equal((await get('roleAssignments', 'ra_p1')).groupId, 'g1');
});

test('the same limits hold on the ordinary sync path', async () => {
  const push = async (who, id, data) => (await (await fetch(`${base}/v1/sync/push`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-dev-person': who }, body: JSON.stringify({ ops: [{ collection: 'people', id, op: 'set', data }] }) })).json()).results[0].status;
  const p1 = await get('people', 'pub1'), p2 = await get('people', 'eld');
  assert.equal(await push('srv', 'pub1', { ...p1, remarks: 'via sync' }), 'ok');
  assert.equal(await push('srv', 'eld', { ...p2, remarks: 'via sync' }), 'denied', 'another group');
  assert.equal(await push('srv', 'pub1', { ...p1, username: 'hijack.2' }), 'denied', 'a sign-in field');
  assert.equal(await push('srv', 'pub1', { ...p1, activeCongregationId: 'congB' }), 'denied', 'the congregation');
  assert.equal(await push('srv', 'pub1', { ...p1, isSuperAdmin: true }), 'denied');
});

test('nobody reaches another congregation, and only the Super Admin moves a publisher', async () => {
  for (const who of ['adm', 'coord', 'msSec', 'ast']) assert.equal((await edit(who, 'pubB', { person: details() })).status, 403, who);
  assert.equal((await edit('adm', 'pub1', { congregationId: 'congB' })).status, 403);
  assert.equal((await edit('adm', 'pub1', { groupId: 'gB' })).status, 422, 'a group of another congregation is not offered');
  const moved = await edit('sa', 'pub1', { congregationId: 'congB' });
  assert.equal(moved.status, 200, JSON.stringify(moved.body));
  const a = await get('roleAssignments', 'ra_p1');
  assert.deepEqual([a.congregationId, a.groupId], ['congB', null], 'the old group does not follow');
  assert.equal((await get('people', 'pub1')).activeCongregationId, 'congB');
  assert.deepEqual(await get('monthlyReports', 'rep1'), { publisherPersonId: 'pub1', congregationId: 'congA', periodMonth: 1, hours: 12 }, 'history stays where it was');
  assert.equal((await edit('sa', 'pub1', { congregationId: 'congA', groupId: 'g1' })).status, 200, 'and back, with a group of the new congregation');
  assert.equal((await edit('sa', 'pub1', { congregationId: 'nowhere' })).status, 422);
});

test('username: periods are kept, it must be unique, and only valid characters pass', async () => {
  assert.equal((await edit('adm', 'pub1', { username: 'jose.m.cruz' })).status, 200);
  assert.equal((await get('people', 'pub1')).username, 'jose.m.cruz');
  const taken = await edit('adm', 'pub1', { username: 'JUAN.NEW' });
  assert.equal(taken.status, 409); assert.equal(taken.body.field, 'username');
  for (const bad of ['a b', 'ab', 'x'.repeat(41), 'no@sign', 'semi;colon']) assert.equal((await edit('adm', 'pub1', { username: bad })).status, 422, bad);
  assert.equal((await edit('adm', 'pub1', { username: 'jose.m.cruz' })).status, 200, 'saving the same username is not a conflict');
});

test('all or nothing: a refusal anywhere in the request leaves the record exactly as it was', async () => {
  const people = JSON.stringify(await get('people', 'pub2')), assignment = JSON.stringify(await get('roleAssignments', 'ra_p2')), logs = (await audits()).length;
  for (const bad of [
    { person: details({ remarks: 'must not stay' }), groupId: 'gB' },
    { person: details({ remarks: 'must not stay' }), username: 'jose.m.cruz' },
    { person: details({ remarks: 'must not stay' }), category: 'ACTIVE' },
    { person: details({ remarks: 'must not stay' }), accountStatus: 'REGULAR_PUBLISHER' },
    { person: details({ remarks: 'must not stay' }), password: '123' },
    { person: details({ remarks: 'must not stay', email: 'not-an-email' }) },
    { person: details({ remarks: 'must not stay' }), baseVersion: 1 },
  ]) {
    const r = await edit('adm', 'pub2', bad);
    assert.ok([409, 422].includes(r.status), JSON.stringify(bad) + ' -> ' + r.status);
  }
  assert.equal(JSON.stringify(await get('people', 'pub2')), people);
  assert.equal(JSON.stringify(await get('roleAssignments', 'ra_p2')), assignment);
  assert.equal((await audits()).length, logs, 'a refused edit writes no audit entry either');
});

test('classification and account status stay apart; unknown fields and bad values are refused', async () => {
  assert.equal((await edit('adm', 'pub2', { category: 'ACTIVE' })).status, 422);
  assert.equal((await edit('adm', 'pub2', { accountStatus: 'REGULAR_PUBLISHER' })).status, 422);
  assert.equal((await edit('adm', 'pub2', { person: { isSuperAdmin: true } })).status, 422);
  assert.equal((await edit('adm', 'pub2', { person: { gender: 'X' } })).status, 422);
  assert.equal((await edit('adm', 'pub2', { person: { birthdate: Date.UTC(1990, 0, 1), baptismalDate: Date.UTC(1980, 0, 1) } })).status, 422);
  assert.equal((await edit('adm', 'eld', { person: { gpsLat: 10 } })).status, 422, 'both coordinates or neither');
  assert.equal((await edit('adm', 'pub2', { person: { firstName: '' } })).status, 422);
  for (const c of ['REGULAR_PUBLISHER', 'INACTIVE_PUBLISHER', 'REMOVED_PUBLISHER']) assert.equal((await edit('adm', 'pub2', { category: c })).status, 200, c);
  assert.equal((await get('roleAssignments', 'ra_p2')).roleType, 'PUBLISHER:REMOVED_PUBLISHER');
});

test('the password: blank means unchanged, a Super Admin reset is never readable, sessions of the old one end', async () => {
  const h = await pwHash('pub2');
  assert.equal((await edit('adm', 'pub2', { person: details(), password: '' })).status, 200);
  assert.equal(await pwHash('pub2'), h, 'a blank password changes nothing');
  assert.equal((await edit('sa', 'pub2', { password: 'SuperTemp1' })).status, 200);
  assert.notEqual(await pwHash('pub2'), h);
  const p = await get('people', 'pub2');
  assert.equal(p.isTemporaryCredential, true); assert.equal(p.temporaryPassword, null);
  assert.equal(JSON.stringify(await audits()).includes('SuperTemp1'), false);
  assert.equal((await edit('adm', 'pub2', { password: 'abc' })).status, 422, 'the password policy');
});

test('credentials follow the credential rules: a Service Overseer cannot change an elder, the Admin can', async () => {
  assert.equal((await edit('so', 'eld', { username: 'eld.changed' })).status, 403);
  assert.equal((await edit('so', 'eld', { person: details() })).status, 200, 'but the personal details are theirs to edit');
  assert.equal((await edit('adm', 'eld', { username: 'eld.changed' })).status, 200);
});

test('a Circuit Overseer, a plain publisher, an unknown caller and a bad id get nothing', async () => {
  for (const who of ['co', 'pub1', 'pub2']) assert.equal((await edit(who, 'pub1', { person: details() })).status, 403, who);
  assert.equal((await edit('ghost', 'pub1', { person: details() })).status, 403);
  assert.equal((await edit('adm', 'nobody', { person: details() })).status, 404);
  assert.equal((await edit('adm', 'not valid!', { person: details() })).status, 400);
});

test('an edit built on an older version of the record is refused instead of overwriting', async () => {
  const v = (await store.get('people', 'pub1')).version;
  assert.equal((await edit('adm', 'pub1', { person: details({ remarks: 'fresh' }), baseVersion: v })).status, 200);
  const stale = await edit('adm', 'pub1', { person: details({ remarks: 'stale' }), baseVersion: v });
  assert.equal(stale.status, 409); assert.equal(stale.body.status, 'conflict');
  assert.equal((await get('people', 'pub1')).remarks, 'fresh');
});

test('Province, Municipality, Barangay and Contact may be blank; every other required field and every given value is still checked', async () => {
  const blanks = { contact: '', province: '', cityMunicipality: '', barangay: '' };
  const ok = await edit('adm', 'pub1', { person: details(blanks) });
  assert.equal(ok.status, 200, JSON.stringify(ok.body));
  const p = await get('people', 'pub1');
  for (const k of Object.keys(blanks)) assert.equal(p[k], null, `${k} is saved blank, never invented`);
  assert.equal(p.firstName, 'MARIA', 'the other details are untouched');
  // a record whose fields are already blank can be edited again, with them still blank or filled in
  assert.equal((await edit('adm', 'pub1', { person: details({ ...blanks, remarks: 'second edit' }) })).status, 200);
  assert.equal((await edit('adm', 'pub1', { person: details({ province: 'PANGASINAN', contact: '0918' }) })).status, 200);
  assert.deepEqual([(await get('people', 'pub1')).province, (await get('people', 'pub1')).contact], ['PANGASINAN', '0918']);
  // still required
  for (const f of ['firstName', 'lastName', 'address']) assert.equal((await edit('adm', 'pub1', { person: details({ [f]: '' }) })).status, 422, f);
  // a value that is given keeps its limits
  assert.equal((await edit('adm', 'pub1', { person: details({ contact: '9'.repeat(61) }) })).status, 422);
  assert.equal((await edit('adm', 'pub1', { person: details({ province: 'x'.repeat(101) }) })).status, 422);
});

test('saving with the username left as it is is not a duplicate and changes nothing about the sign-in', async () => {
  const before = (await get('people', 'pub1')).username;
  const same = await edit('adm', 'pub1', { person: details({ remarks: 'unchanged username' }), username: before });
  assert.equal(same.status, 200, JSON.stringify(same.body));
  assert.equal((await get('people', 'pub1')).username, before);
  assert.ok(!same.body.changed.includes('username'), 'an unchanged username is not a username change');
  const absent = await edit('adm', 'pub1', { person: details({ remarks: 'no username sent' }) });
  assert.equal(absent.status, 200);
  assert.equal((await get('people', 'pub1')).username, before);
  // only a real change is checked for a clash
  assert.equal((await edit('adm', 'pub1', { username: (await get('people', 'pub2')).username })).status, 409, 'another publisher already has it');
});
