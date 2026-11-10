import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

// A temporary password is sent ONLY to the Super Admin and to the Admin of the person's own congregation.

let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, activeCongregationId = 'congA', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, firstName: 'X', lastName: 'Y', ...extra });
const hdr = (who) => ({ 'content-type': 'application/json', 'x-dev-person': who });
const push = async (who, ops) => (await (await fetch(`${base}/v1/sync/push`, { method: 'POST', headers: hdr(who), body: JSON.stringify({ ops }) })).json()).results;
const seen = async (who, id = 'newpub') => {
  const r = await (await fetch(`${base}/v1/sync/pull?since=0&collections=people`, { headers: hdr(who) })).json();
  const row = r.changes.find((c) => c.id === id && !c.deleted);
  return row ? { present: true, pw: row.data.temporaryPassword } : { present: false };
};

before(async () => {
  store = new MemoryStore();
  await put('congregations', 'congA', { name: 'Alpha' });
  await put('congregations', 'congB', { name: 'Bravo' });
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null, firstName: 'S', lastName: 'A' });
  await put('people', 'adm', person('ADMIN_PER_CONGREGATION'));
  await put('roleAssignments', 'ra_adm', { personId: 'adm', roleType: 'ADMIN:ADMIN_PER_CONGREGATION', congregationId: 'congA', status: 'ACTIVE' });
  await put('people', 'admB', person('ADMIN_PER_CONGREGATION', 'congB'));
  await put('roleAssignments', 'ra_admB', { personId: 'admB', roleType: 'ADMIN:ADMIN_PER_CONGREGATION', congregationId: 'congB', status: 'ACTIVE' });
  await put('people', 'so', person('SERVICE_OVERSEER'));
  await put('roleAssignments', 'ra_so', { personId: 'so', roleType: 'ADMIN:SERVICE_OVERSEER', congregationId: 'congA', status: 'ACTIVE' });
  await put('people', 'ovr', person('REGULAR_ELDER', 'congA', { activeGroupId: 'g1' }));
  await put('groups', 'g1', { congregationId: 'congA', name: 'Group 1', status: 'ACTIVE', overseerPersonId: 'ovr' });
  await put('people', 'pub2', person(null));
  await put('people', 'newpub', person(null, 'congA', { firstName: 'New', username: 'new.pub', address: 'Osmena', contact: '09170000000', province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Osmena', isTemporaryCredential: true, temporaryPassword: 'Temp-secret-1' }));
  await put('roleAssignments', 'ra_new', { personId: 'newpub', roleType: 'PUBLISHER:REGULAR_PUBLISHER', congregationId: 'congA', groupId: 'g1', status: 'ACTIVE' });
  const app = createApp(store, { devAuth: true });
  await new Promise((res) => { server = app.listen(0, res); });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test('only the Super Admin and the Admin of that congregation are sent the temporary password', async () => {
  assert.equal((await seen('sa')).pw, 'Temp-secret-1', 'Super Admin');
  assert.equal((await seen('adm')).pw, 'Temp-secret-1', 'Admin of the same congregation');
  for (const who of ['admB', 'so', 'ovr', 'pub2', 'newpub']) {
    const s = await seen(who);
    assert.equal(s.present, true, `${who} still sees the person (name, username, status)`);
    assert.equal(s.pw, undefined, `${who} is not sent the password`);
  }
});

test('saving the person without the password does not erase it for the Admin; an Admin can still set or clear it', async () => {
  const noPw = { ...person(null, 'congA', { firstName: 'New', username: 'new.pub', address: 'Osmena', contact: '09170000000', province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Osmena', isTemporaryCredential: true }) };
  assert.equal((await push('ovr', [{ collection: 'people', id: 'newpub', op: 'set', data: { ...noPw, remarks: 'edited by the group officer' } }]))[0].status, 'ok', 'the FS Group officer edits the member details');
  assert.equal((await seen('adm')).pw, 'Temp-secret-1', 'and that did not erase the password for the Admin');
  const own = await push('newpub', [{ collection: 'people', id: 'newpub', op: 'set', data: { ...noPw, preachingAvailableDays: ['MONDAY'] } }]); assert.equal(own[0].status, 'ok', JSON.stringify(own) + 'the person saves their own record (which they are never sent the password for)');
  assert.equal((await seen('adm')).pw, 'Temp-secret-1', 'and that did not erase it either');
  assert.equal((await seen('adm')).pw, 'Temp-secret-1', 'the Admin can still read it afterwards');
  assert.equal((await push('adm', [{ collection: 'people', id: 'newpub', op: 'set', data: { ...noPw, temporaryPassword: 'Temp-new-2' } }]))[0].status, 'ok');
  assert.equal((await seen('sa')).pw, 'Temp-new-2', 'an Admin sets a new one');
  assert.equal((await push('adm', [{ collection: 'people', id: 'newpub', op: 'set', data: { ...noPw, temporaryPassword: null } }]))[0].status, 'ok');
  assert.equal((await seen('sa')).pw, null, 'an Admin clears it');
});
