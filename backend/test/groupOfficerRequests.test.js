import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { hashPassword } from '../src/passwords.js';

/**
 * The Overseer, Servant and Assistant of an FS Group (1) decide the edit requests of the publishers of THEIR OWN group, writing the decision only, and (2) change the username and reset the
 * password of the plain publishers of their own group - never another group's, never anyone above a plain publisher, never themselves.
 */
let store, server, base;
const SECRET = 'test-secret-test-secret-test-secret';
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId: 'c1', username: 'u', ...extra });
const SEP = Date.UTC(2023, 8, 1) - 8 * 3600_000;
const tokens = {};

const call = async (path, body, who) => {
  const r = await fetch(base + path, { method: body ? 'POST' : 'GET', headers: { 'content-type': 'application/json', authorization: `Bearer ${tokens[who]}` }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, body: await r.json().catch(() => ({})) };
};
const push = async (who, collection, id, data) => (await call('/v1/sync/push', { ops: [{ collection, id, op: 'set', data }] }, who)).body.results[0];
const report = (publisher, extra = {}) => ({
  congregationId: 'c1', publisherPersonId: publisher, periodMonth: SEP, status: 'ACCESS_REQUESTED', category: 'REGULAR_PUBLISHER', bibleStudiesCount: 3, hoursRendered: null,
  accessRequestReason: 'A study was missed', accessRequestedAt: 5, source: 'APP', ...extra,
});
const decision = (old, who, granted, extra = {}) => ({
  ...old, ...(granted ? { status: 'ACCESS_GRANTED', accessGrantedByPersonId: who, accessGrantedAt: 9 } : { status: 'SUBMITTED', accessDecisionNote: 'Not needed', accessDecidedByPersonId: who }),
  accessDecidedByPersonId: who, accessDecidedAt: 9, lastEditedByPersonId: who, lastEditedAt: 9, ...extra,
});

before(async () => {
  store = new MemoryStore();
  await put('congregations', 'c1', { name: 'Alpha' });
  const people = {
    ovr001: person('REGULAR_ELDER', { activeGroupId: 'g1' }), srv001: person('MINISTERIAL_SERVANT', { activeGroupId: 'g1' }), ast001: person(null), ovr002: person('REGULAR_ELDER', { activeGroupId: 'g2' }),
    pub001: person(null), pub002: person(null), elder1: person('REGULAR_ELDER'), coord1: person('COORDINATOR_ELDER'), sec001: person('SECRETARY'), svc001: person('SERVICE_OVERSEER'), adm001: person('ADMIN_PER_CONGREGATION'),
    ms0001: person('MINISTERIAL_SERVANT'),
  };
  for (const [id, p] of Object.entries(people)) {
    await put('people', id, p);
    await store.createAccount(id, await hashPassword('Pass1234'));
  }
  await put('people', 'sa0001', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null, username: 'sa0001' });
  await store.createAccount('sa0001', await hashPassword('Pass1234'));
  await put('groups', 'g1', { id: 'g1', congregationId: 'c1', overseerPersonId: 'ovr001', servantPersonId: 'srv001', assistantPersonId: 'ast001', status: 'ACTIVE', name: 'Group One' });
  await put('groups', 'g2', { id: 'g2', congregationId: 'c1', overseerPersonId: 'ovr002', status: 'ACTIVE', name: 'Group Two' });
  const ra = (id, person, type, group) => put('roleAssignments', id, { personId: person, congregationId: 'c1', groupId: group ?? null, status: 'ACTIVE', roleType: type });
  await ra('r_p1', 'pub001', 'PUBLISHER:REGULAR_PUBLISHER', 'g1');
  await ra('r_p2', 'pub002', 'PUBLISHER:REGULAR_PUBLISHER', 'g2');
  await ra('r_ast', 'ast001', 'PUBLISHER:REGULAR_PUBLISHER', 'g1');
  await ra('r_ms1', 'ms0001', 'PUBLISHER:REGULAR_PUBLISHER', 'g1');
  await ra('r_ms1a', 'ms0001', 'ADMIN:MINISTERIAL_SERVANT', 'g1');
  await ra('r_ovr2', 'ovr002', 'PUBLISHER:REGULAR_PUBLISHER', 'g2');
  server = createApp(store, { authOptions: { secret: SECRET, limits: { login: { max: 1000 } } } }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
  for (const id of [...Object.keys(people), 'sa0001']) {
    const r = await fetch(base + '/v1/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email: `${id}@gopreach.app`, password: 'Pass1234' }) });
    const j = await r.json();
    tokens[id] = j.accessToken;
    assert.ok(tokens[id], `sign in ${id}: ${JSON.stringify(j)}`);
  }
});
after(() => server.close());

for (const [who, label] of [['ovr001', 'Group Overseer'], ['srv001', 'Group Servant'], ['ast001', 'Group Assistant']]) {
  test(`${label}: approves and rejects the edit requests of its own group, writing only the decision`, async () => {
    const id = `req_${who}`;
    await put('monthlyReports', id, report('pub001'));
    const old = (await store.get('monthlyReports', id)).data;
    assert.equal((await push(who, 'monthlyReports', id, decision(old, who, true))).status, 'ok', 'approve');
    assert.equal((await store.get('monthlyReports', id)).data.status, 'ACCESS_GRANTED');
    await put('monthlyReports', id, report('pub001'));
    assert.equal((await push(who, 'monthlyReports', id, decision(old, who, false))).status, 'ok', 'reject with remarks');
    const stored = (await store.get('monthlyReports', id)).data;
    assert.equal(stored.status, 'SUBMITTED');
    assert.equal(stored.accessDecisionNote, 'Not needed');
    assert.equal(stored.bibleStudiesCount, 3, 'the report itself is untouched');
  });

  test(`${label}: cannot decide for another group, change the report's figures, decide in another name or for itself`, async () => {
    await put('monthlyReports', `o_${who}`, report('pub002'));
    const other = (await store.get('monthlyReports', `o_${who}`)).data;
    assert.equal((await push(who, 'monthlyReports', `o_${who}`, decision(other, who, true))).status, 'denied', 'a publisher of another group');
    await put('monthlyReports', `m_${who}`, report('pub001'));
    const mine = (await store.get('monthlyReports', `m_${who}`)).data;
    assert.equal((await push(who, 'monthlyReports', `m_${who}`, decision(mine, who, true, { bibleStudiesCount: 99 }))).status, 'denied', 'a changed figure');
    assert.equal((await push(who, 'monthlyReports', `m_${who}`, decision(mine, who, true, { accessGrantedByPersonId: 'someone' }))).status, 'denied', 'in another name');
    assert.equal((await push(who, 'monthlyReports', `m_${who}`, { ...decision(mine, who, true), status: 'POSTED' })).status, 'denied', 'another status');
    assert.equal((await store.get('monthlyReports', `m_${who}`)).data.status, 'ACCESS_REQUESTED');
  });
}

test('an officer cannot approve their own request, and a plain member or an elder1 without a slot cannot decide', async () => {
  await put('monthlyReports', 'own_ast', report('ast001'));
  const own = (await store.get('monthlyReports', 'own_ast')).data;
  assert.equal((await push('ast001', 'monthlyReports', 'own_ast', decision(own, 'ast001', true))).status, 'denied', 'self-approval');
  await put('monthlyReports', 'x1', report('pub001'));
  const r = (await store.get('monthlyReports', 'x1')).data;
  for (const who of ['pub002', 'elder1', 'ovr002']) assert.equal((await push(who, 'monthlyReports', 'x1', decision(r, who, true))).status, 'denied', who);
  assert.equal((await push('sec001', 'monthlyReports', 'x1', decision(r, 'sec001', true))).status, 'ok', 'the congregation roles keep deciding');
});

for (const [who, label] of [['ovr001', 'Group Overseer'], ['srv001', 'Group Servant'], ['ast001', 'Group Assistant']]) {
  test(`${label}: changes the username and resets the password of a plain publisher of its own group`, async () => {
    const pub001 = (await store.get('people', 'pub001')).data;
    const name = `p1_${who}`;
    assert.equal((await push(who, 'people', 'pub001', { ...pub001, username: name })).status, 'ok', 'username');
    assert.equal((await store.get('people', 'pub001')).data.username, name);
    assert.equal((await push(who, 'people', 'pub001', { ...pub001, username: name, isTemporaryCredential: true, temporaryPassword: null })).status, 'ok', 'the temporary-credential flag');
    assert.equal((await push(who, 'people', 'pub001', { ...pub001, username: name, isTemporaryCredential: true, temporaryPassword: 'Visible123' })).status, 'denied', 'a readable password on the record');
    assert.equal((await push(who, 'people', 'pub001', { ...pub001, username: name, accountStatus: 'INACTIVE' })).status, 'denied', 'the account status is not theirs');
    assert.equal((await call('/v1/auth/accounts/pub001/password', { password: 'NewTemp123' }, who)).status, 200, 'password reset');
    const login = await fetch(base + '/v1/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email: 'pub001@gopreach.app', password: 'NewTemp123' }) });
    assert.equal(login.status, 200, 'the member signs in with the new password');
    await store.createAccount('pub001', await hashPassword('Pass1234')).catch(() => {});
    await store.setPassword('pub001', await hashPassword('Pass1234'));
  });

  test(`${label}: cannot manage another group's publisher or anyone above a plain publisher`, async () => {
    for (const target of ['pub002', 'ovr002', 'elder1', 'coord1', 'sec001', 'svc001', 'adm001', 'sa0001', 'ms0001']) {
      const t = (await store.get('people', target)).data;
      assert.equal((await push(who, 'people', target, { ...t, username: `hack_${target}` })).status, 'denied', `username of ${target}`);
      assert.equal((await call(`/v1/auth/accounts/${target}/password`, { password: 'Hacked123' }, who)).status, 403, `password of ${target}`);
    }
    // the other officers of the own group are not plain publishers either, and nobody resets their own through this path
    for (const target of ['ovr001', 'srv001', 'ast001']) {
      if (target === who) continue;
      assert.equal((await call(`/v1/auth/accounts/${target}/password`, { password: 'Hacked123' }, who)).status, 403, `password of officer ${target}`);
    }
    assert.equal((await call(`/v1/auth/accounts/${who}/password`, { password: 'Hacked123' }, who)).status, 403, 'itself');
  });
}

test('a member and an ordinary elder1 reset nobody; the Secretary and the Super Admin keep their reach', async () => {
  assert.equal((await call('/v1/auth/accounts/pub002/password', { password: 'Hacked123' }, 'ms0001')).status, 403);
  assert.equal((await call('/v1/auth/accounts/pub002/password', { password: 'Hacked123' }, 'elder1')).status, 403);
  assert.equal((await call('/v1/auth/accounts/pub002/password', { password: 'Temp123456' }, 'sec001')).status, 200);
  assert.equal((await call('/v1/auth/accounts/pub002/password', { password: 'Temp123457' }, 'sa0001')).status, 200);
});
