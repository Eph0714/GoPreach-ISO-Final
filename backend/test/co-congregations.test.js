import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

/** Congregation Management for the Circuit Overseer: enrolling under their own account, seeing only their own, and everything a request must not be able to do. */
let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const grant = (code, ids) => ({ permissions: ['VIEW_CONGREGATIONS', 'VIEW_GROUPS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ids, circuitCode: code });

before(async () => {
  store = new MemoryStore();
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  for (const [id, code, ids] of [['co1', 'NT01', ['cA']], ['co2', 'NT02', ['cB']]]) {
    await put('people', id, { isSuperAdmin: false, activeAdminRole: 'CIRCUIT_OVERSEER', activeCongregationId: null, accountStatus: 'ACTIVE' });
    await put('userAccessGrants', id, grant(code, ids));
    await put('circuitCodes', code, { id: code, code, status: 'ACTIVE', overseerPersonId: id });
  }
  await put('people', 'co3', { isSuperAdmin: false, activeAdminRole: 'CIRCUIT_OVERSEER', activeCongregationId: null, accountStatus: 'SUSPENDED' });
  await put('userAccessGrants', 'co3', grant('NT03', []));
  await put('circuitCodes', 'NT03', { id: 'NT03', code: 'NT03', status: 'ACTIVE', overseerPersonId: 'co3' });
  await put('people', 'co4', { isSuperAdmin: false, activeAdminRole: 'CIRCUIT_OVERSEER', activeCongregationId: null, accountStatus: 'ACTIVE' });   // a grant whose code is inactive
  await put('userAccessGrants', 'co4', grant('NT04', []));
  await put('circuitCodes', 'NT04', { id: 'NT04', code: 'NT04', status: 'INACTIVE', overseerPersonId: 'co4' });
  await put('people', 'elder', { isSuperAdmin: false, activeAdminRole: 'REGULAR_ELDER', activeCongregationId: 'cA' });
  await put('congregations', 'cA', { id: 'cA', name: 'ALPHA', code: 'AL1', province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Roxas', status: 'ACTIVE', createdAt: 1 });
  await put('congregations', 'cB', { id: 'cB', name: 'BRAVO', code: 'BR1', province: 'Nueva Vizcaya', cityMunicipality: 'Bagabag', barangay: 'Ibung', status: 'ACTIVE', createdAt: 2 });
  await put('congregationCircuits', 'cA', { id: 'cA', congregationId: 'cA', circuitOverseerPersonId: 'co1', circuitCode: 'NT01', assignedAt: 1, assignedByPersonId: 'sa' });
  await put('congregationCircuits', 'cB', { id: 'cB', congregationId: 'cB', circuitOverseerPersonId: 'co2', circuitCode: 'NT02', assignedAt: 1, assignedByPersonId: 'sa' });
  server = createApp(store, { devAuth: true }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const call = async (who, method, path, json) => {
  const r = await fetch(`${base}/v1/co/congregations${path}`, { method, headers: { 'x-dev-person': who, ...(json ? { 'content-type': 'application/json' } : {}) }, body: json ? JSON.stringify(json) : undefined });
  return { status: r.status, body: await r.json() };
};
const form = (o = {}) => ({ name: 'Gamma', code: 'GM1', province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Quirino', languages: ['Iloko'], ...o });
const doc = async (c, id) => (await store.get(c, id))?.data;
const sync = (who, ...ops) => fetch(`${base}/v1/sync/push`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-dev-person': who }, body: JSON.stringify({ ops }) }).then((r) => r.json());

test('only an active Circuit Overseer with an active Circuit Code of their own may use it', async () => {
  for (const who of ['elder', 'sa']) assert.equal((await call(who, 'GET', '')).status, 403, `${who} is refused`);
  assert.equal((await call('elder', 'POST', '', form())).status, 403);
  assert.equal((await call('sa', 'POST', '', form())).status, 403, 'the Super Admin uses the full module, not this route');
  const suspended = await call('co3', 'POST', '', form({ code: 'S1', name: 'S1' }));
  assert.equal(suspended.status, 403); assert.match(suspended.body.error, /not active/);
  const noCode = await call('co4', 'POST', '', form({ code: 'S2', name: 'S2' }));
  assert.equal(noCode.status, 403); assert.match(noCode.body.error, /Circuit Code/);
  assert.equal((await fetch(`${base}/v1/co/congregations`)).status, 401, 'no sign-in, no access');
});

test('a congregation is enrolled under the signed-in overseer, with its link, scope and audit entry, in one step', async () => {
  const r = await call('co1', 'POST', '', form());
  assert.equal(r.status, 201);
  const id = r.body.id;
  assert.deepEqual([r.body.name, r.body.code, r.body.barangay, r.body.status, r.body.circuitCode, r.body.createdByPersonId, r.body.enrolledByPersonId], ['GAMMA', 'GM1', 'Quirino', 'ACTIVE', 'NT01', 'co1', 'co1']);
  assert.equal(r.body.address, 'Quirino, Solano, Nueva Vizcaya');
  const c = await doc('congregations', id);
  assert.equal(c.createdByPersonId, 'co1'); assert.equal(c.enrolledByCircuitCode, 'NT01'); assert.ok(c.createdAt > 0);
  const link = await doc('congregationCircuits', id);
  assert.deepEqual([link.circuitOverseerPersonId, link.circuitCode, link.assignedByPersonId], ['co1', 'NT01', 'co1']);
  assert.deepEqual((await doc('userAccessGrants', 'co1')).scopeCongregationIds.sort(), ['cA', id].sort());
  const audit = [...store.docs.values()].find((d) => d.collection === 'auditLog' && d.data.action === 'CO_ENROLL_CONGREGATION');
  assert.equal(audit.data.actorPersonId, 'co1');
  // it is in the overseer's list, found by search, and the Super Admin sees who enrolled it
  const list = (await call('co1', 'GET', '')).body.congregations;
  assert.ok(list.some((x) => x.id === id) && list.some((x) => x.id === 'cA'));
  assert.deepEqual((await call('co1', 'GET', '?q=quirino')).body.congregations.map((x) => x.id), [id]);
  assert.equal((await store.get('congregations', id)).data.enrolledByPersonId, 'co1');
});

test('the overseer comes from the sign-in: naming another overseer is refused and nothing is written', async () => {
  const before = (await store.list('congregations')).length;
  for (const k of ['circuitOverseerPersonId', 'overseerPersonId', 'assignedToPersonId', 'coPersonId']) {
    const r = await call('co1', 'POST', '', form({ name: 'Delta', code: 'DL1', [k]: 'co2' }));
    assert.equal(r.status, 403, k); assert.match(r.body.error, /your own/);
  }
  assert.equal((await call('co1', 'POST', '', form({ name: 'Delta', code: 'DL1', circuitCode: 'NT02' }))).status, 403);
  assert.equal((await store.list('congregations')).length, before, 'nothing was created');
  assert.ok(!(await doc('userAccessGrants', 'co2')).scopeCongregationIds.includes('cA'));
  // naming yourself is harmless
  assert.equal((await call('co1', 'POST', '', form({ name: 'Delta', code: 'DL1', circuitOverseerPersonId: 'co1' }))).status, 201);
});

test('the required fields and the code are validated like the Super Admin form', async () => {
  for (const [patch, field] of [[{ name: '' }, 'name'], [{ province: ' ' }, 'province'], [{ cityMunicipality: '' }, 'cityMunicipality'], [{ barangay: '' }, 'barangay'], [{ code: '' }, 'code'], [{ code: 'bad/code!' }, 'code']]) {
    const r = await call('co1', 'POST', '', form({ code: 'V1', name: 'V1', ...patch }));
    assert.equal(r.status, 422, JSON.stringify(patch)); assert.equal(r.body.field, field);
  }
  assert.equal((await call('co1', 'POST', '', form({ code: 'V2', name: 'V2', languages: Array.from({ length: 11 }, (_, i) => `L${i}`) }))).status, 422);
});

test('the same congregation is never enrolled twice: by code, by name and place, or by a repeated request', async () => {
  const r1 = await call('co2', 'POST', '', form({ name: 'Echo', code: 'EC1', barangay: 'Ibung', requestId: 'reqEcho0001' }));
  assert.equal(r1.status, 201);
  const total = (await store.list('congregations')).length;
  const again = await call('co2', 'POST', '', form({ name: 'Echo', code: 'EC1', barangay: 'Ibung', requestId: 'reqEcho0001' }));
  assert.equal(again.status, 200); assert.equal(again.body.duplicate, true); assert.equal(again.body.id, r1.body.id);
  assert.equal((await call('co2', 'POST', '', form({ name: 'Other', code: 'ec1' }))).status, 409, 'the code, whatever its case');
  assert.equal((await call('co2', 'POST', '', form({ name: ' echo ', code: 'EC9', barangay: 'ibung' }))).status, 409, 'the same name in the same barangay');
  assert.equal((await call('co1', 'POST', '', form({ name: 'Alpha', code: 'ZZ9', barangay: 'Roxas', cityMunicipality: 'Solano' }))).status, 409, 'another overseer\'s congregation is found too, without saying whose');
  assert.equal((await call('co1', 'POST', '', form({ name: 'Taken', code: 'T1', requestId: 'reqEcho0001' }))).status, 409, 'a request id used by someone else');
  assert.equal((await store.list('congregations')).length, total, 'no duplicate was created');
});

test('one overseer never sees, reads or edits the congregations of another', async () => {
  const mine = (await call('co2', 'GET', '')).body.congregations.map((c) => c.id);
  assert.ok(mine.includes('cB') && !mine.includes('cA'));
  assert.equal((await call('co2', 'GET', '/cA')).status, 404);
  assert.equal((await call('co2', 'POST', '/cA', { name: 'HACKED', province: 'P', cityMunicipality: 'M', barangay: 'B' })).status, 404);
  assert.equal((await doc('congregations', 'cA')).name, 'ALPHA');
  assert.equal((await call('co2', 'POST', '/does-not-exist', { name: 'X', province: 'P', cityMunicipality: 'M', barangay: 'B' })).status, 404);
});

test('an overseer edits the name, place and languages of their own congregation, and nothing else', async () => {
  const r = await call('co1', 'POST', '/cA', { name: 'alpha two', province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Roxas', languages: ['Iloko', 'Ibanag', 'iloko'] });
  assert.equal(r.status, 200);
  assert.deepEqual([r.body.name, r.body.code, r.body.languages, r.body.status], ['ALPHA TWO', 'AL1', ['Iloko', 'Ibanag'], 'ACTIVE']);
  assert.equal((await doc('congregations', 'cA')).code, 'AL1', 'the code is untouched');
  for (const k of ['status', 'code', 'circuitOverseerPersonId', 'circuitCode']) {
    const x = await call('co1', 'POST', '/cA', { name: 'ALPHA TWO', province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Roxas', [k]: k === 'status' ? 'INACTIVE' : 'co2' });
    assert.equal(x.status, 403, k);
  }
  assert.equal((await doc('congregationCircuits', 'cA')).circuitOverseerPersonId, 'co1', 'the assignment did not move');
  // renaming onto an existing congregation of the same barangay is refused
  await put('congregations', 'cX', { id: 'cX', name: 'TWIN', province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Roxas', status: 'ACTIVE', createdAt: 5 });
  assert.equal((await call('co1', 'POST', '/cA', { name: 'Twin', province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Roxas' })).status, 409);
  // an inactive congregation waits for the Super Admin
  await put('congregations', 'cA', { ...(await doc('congregations', 'cA')), status: 'INACTIVE' });
  assert.equal((await call('co1', 'POST', '/cA', { name: 'ALPHA TWO', province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Roxas' })).status, 409);
});

test('the general sync still refuses a Circuit Overseer any congregation, link or grant write', async () => {
  const ops = [
    { collection: 'congregations', id: 'sneaky', op: 'set', data: { id: 'sneaky', name: 'SNEAKY', code: 'SN1', status: 'ACTIVE' } },
    { collection: 'congregationCircuits', id: 'sneaky', op: 'set', data: { id: 'sneaky', congregationId: 'sneaky', circuitOverseerPersonId: 'co1', circuitCode: 'NT01' } },
    { collection: 'userAccessGrants', id: 'co1', op: 'set', data: grant('NT01', ['cA', 'cB', 'sneaky']) },
    { collection: 'congregations', id: 'cB', op: 'set', data: { id: 'cB', name: 'STOLEN', code: 'BR1', status: 'ACTIVE' } },
    { collection: 'congregations', id: 'cA', op: 'set', data: { id: 'cA', name: 'EDITED', code: 'AL1', status: 'ACTIVE' } },
  ];
  const res = await sync('co1', ...ops);
  assert.deepEqual(res.results.map((r) => r.status), ops.map(() => 'denied'), JSON.stringify(res.results));
  assert.equal(await doc('congregations', 'sneaky'), undefined);
  assert.ok(!(await doc('userAccessGrants', 'co1')).scopeCongregationIds.includes('cB'));
});

test('a congregation enrolled by an overseer carries who enrolled it, so the Super Admin can see it', async () => {
  const id = (await call('co1', 'POST', '', form({ name: 'Zulu', code: 'ZU1', barangay: 'Zulu Brgy' }))).body.id;
  const c = await doc('congregations', id);
  assert.deepEqual([c.enrolledByPersonId, c.enrolledByCircuitCode, c.createdByPersonId], ['co1', 'NT01', 'co1']);
  assert.equal((await doc('congregationCircuits', id)).circuitOverseerPersonId, 'co1');
  // the Super Admin reads every congregation through the general sync (no grant limits them)
  const pulled = await (await fetch(`${base}/v1/sync/pull?since=0&collections=congregations&limit=500`, { headers: { 'x-dev-person': 'sa' } })).text();
  assert.ok(pulled.includes(id), 'the Super Admin sees it');
});
