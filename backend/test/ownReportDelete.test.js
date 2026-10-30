import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { hashPassword } from '../src/passwords.js';

/**
 * The Overseer, Servant and Assistant of an FS Group who are also publishers of that group delete their OWN submitted report of a month that is still open. A plain publisher still cannot
 * (a submitted report is locked for them), and nobody deletes another group's report.
 */
let store, server, base;
const SECRET = 'test-secret-test-secret-test-secret';
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId: 'c1', username: 'u', ...extra });
const SEP = Date.UTC(2023, 8, 1) - 8 * 3600_000;
const tokens = {};

const call = async (path, body, who) => {
  const r = await fetch(base + path, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${tokens[who]}` }, body: JSON.stringify(body) });
  return { status: r.status, body: await r.json().catch(() => ({})) };
};
const del = async (who, id) => (await call('/v1/sync/push', { ops: [{ collection: 'monthlyReports', id, op: 'delete' }] }, who)).body.results[0];
const report = (publisher, extra = {}) => ({
  congregationId: 'c1', publisherPersonId: publisher, periodMonth: SEP, status: 'SUBMITTED', category: 'REGULAR_PUBLISHER', bibleStudiesCount: 3, hoursRendered: null, source: 'APP', ...extra,
});

before(async () => {
  store = new MemoryStore();
  await put('congregations', 'c1', { name: 'Alpha' });
  const people = {
    ovr001: person('REGULAR_ELDER', { activeGroupId: 'g1' }), srv001: person('MINISTERIAL_SERVANT', { activeGroupId: 'g1' }), ast001: person(null), ovr002: person('REGULAR_ELDER', { activeGroupId: 'g2' }), pub001: person(null),
  };
  for (const [id, p] of Object.entries(people)) {
    await put('people', id, p);
    await store.createAccount(id, await hashPassword('Pass1234'));
  }
  await put('groups', 'g1', { id: 'g1', congregationId: 'c1', overseerPersonId: 'ovr001', servantPersonId: 'srv001', assistantPersonId: 'ast001', status: 'ACTIVE', name: 'Group One' });
  await put('groups', 'g2', { id: 'g2', congregationId: 'c1', overseerPersonId: 'ovr002', status: 'ACTIVE', name: 'Group Two' });
  const ra = (id, who, group) => put('roleAssignments', id, { personId: who, congregationId: 'c1', groupId: group, status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  await ra('r_ovr', 'ovr001', 'g1');
  await ra('r_srv', 'srv001', 'g1');
  await ra('r_ast', 'ast001', 'g1');
  await ra('r_pub', 'pub001', 'g1');
  await ra('r_ovr2', 'ovr002', 'g2');
  server = createApp(store, { authOptions: { secret: SECRET, limits: { login: { max: 1000 } } } }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
  for (const id of Object.keys(people)) {
    const r = await fetch(base + '/v1/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email: `${id}@gopreach.app`, password: 'Pass1234' }) });
    tokens[id] = (await r.json()).accessToken;
    assert.ok(tokens[id], `sign in ${id}`);
  }
});
after(() => server.close());

for (const [who, label] of [['ovr001', 'Group Overseer'], ['srv001', 'Group Servant'], ['ast001', 'Group Assistant']]) {
  test(`${label} deletes their own submitted report`, async () => {
    const id = `own_${who}`;
    await put('monthlyReports', id, report(who));
    assert.equal((await del(who, id)).status, 'ok');
    assert.equal(!!(await store.get('monthlyReports', id))?.deleted || !(await store.get('monthlyReports', id)), true, 'it is gone');
  });
}

test('a plain publisher cannot delete their own submitted report, and nobody deletes another group\'s report', async () => {
  await put('monthlyReports', 'own_pub', report('pub001'));
  assert.equal((await del('pub001', 'own_pub')).status, 'denied', 'a plain publisher');
  await put('monthlyReports', 'other_grp', report('ovr002'));
  assert.equal((await del('ovr001', 'other_grp')).status, 'denied', 'the report of an officer of another group');
  assert.ok(await store.get('monthlyReports', 'other_grp'));
});

test('an officer can still delete a publisher of their own group (stamping the report first) even when it is submitted', async () => {
  await put('monthlyReports', 'grp_pub', report('pub001', { groupRoleAssignmentId: 'r_pub' }));
  assert.equal((await del('ovr001', 'grp_pub')).status, 'ok');
});
