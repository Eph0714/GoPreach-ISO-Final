import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

// The Ministry Recycle Bin (Deleted Records of Searching, Return Visit and Bible Study): who may see, restore and remove one.

let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, activeCongregationId = 'congA', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, firstName: 'X', lastName: 'Y', ...extra });
const hdr = (who) => ({ 'content-type': 'application/json', 'x-dev-person': who });
const push = async (who, ops) => (await (await fetch(`${base}/v1/sync/push`, { method: 'POST', headers: hdr(who), body: JSON.stringify({ ops }) })).json()).results;
const pull = async (who) => (await (await fetch(`${base}/v1/sync/pull?since=0&collections=deletedRecords`, { headers: hdr(who) })).json()).changes.filter((c) => !c.deleted).map((c) => c.id);
const entry = (extra = {}) => ({ status: 'deleted', recordType: 'Return Visit', module: 'Return Visit / Bible Study', label: 'JUAN CRUZ', congregationId: 'congA', groupId: 'g1', deletedAt: 1790000000000, deletedByPersonId: 'pub1', deletedByName: 'Pat One', ownerPersonId: 'pub1', itemsJson: '[]', ...extra });

before(async () => {
  store = new MemoryStore();
  await put('congregations', 'congA', { name: 'Alpha' });
  await put('congregations', 'congB', { name: 'Bravo' });
  await put('people', 'pub1', person(null));
  await put('people', 'pub2', person(null));
  await put('people', 'adm', person('ADMIN_PER_CONGREGATION'));
  await put('roleAssignments', 'ra_adm', { personId: 'adm', roleType: 'ADMIN:ADMIN_PER_CONGREGATION', congregationId: 'congA', status: 'ACTIVE' });
  await put('people', 'ovr1', person('REGULAR_ELDER', 'congA', { activeGroupId: 'g1' }));
  await put('people', 'ovr2', person('REGULAR_ELDER', 'congA', { activeGroupId: 'g2' }));
  await put('people', 'pubB', person(null, 'congB'));
  await put('groups', 'g1', { congregationId: 'congA', name: 'Group 1', status: 'ACTIVE', overseerPersonId: 'ovr1' });
  await put('groups', 'g2', { congregationId: 'congA', name: 'Group 2', status: 'ACTIVE', overseerPersonId: 'ovr2' });
  await put('deletedRecords', 'd1', entry());
  await put('deletedRecords', 'd2', entry({ label: 'MARIA SANTOS', deletedByPersonId: 'adm', deletedByName: 'Ada Admin' }));
  const app = createApp(store, { devAuth: true });
  await new Promise((res) => { server = app.listen(0, res); });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test('who sees a deleted Ministry record: the deleter, its owner, the congregation roles and the owner group officers', async () => {
  assert.deepEqual((await pull('pub1')).sort(), ['d1', 'd2'], 'the owner sees both (both are theirs)');
  assert.deepEqual(await pull('pub2'), [], 'another publisher sees none');
  assert.deepEqual((await pull('adm')).sort(), ['d1', 'd2']);
  assert.deepEqual((await pull('ovr1')).sort(), ['d1', 'd2'], 'the officer of the owner FS Group');
  assert.deepEqual(await pull('ovr2'), [], 'the officer of another FS Group');
  assert.deepEqual(await pull('pubB'), [], 'another congregation');
});

test('who may remove one for good, and a deleted record is never edited', async () => {
  assert.equal((await push('pub2', [{ collection: 'deletedRecords', id: 'd1', op: 'delete' }]))[0].status, 'denied');
  assert.equal((await push('ovr2', [{ collection: 'deletedRecords', id: 'd1', op: 'delete' }]))[0].status, 'denied');
  assert.equal((await push('pubB', [{ collection: 'deletedRecords', id: 'd1', op: 'delete' }]))[0].status, 'denied');
  assert.equal((await push('pub1', [{ collection: 'deletedRecords', id: 'd1', op: 'set', data: entry({ label: 'CHANGED' }) }]))[0].status, 'denied');
  assert.equal((await push('ovr1', [{ collection: 'deletedRecords', id: 'd1', op: 'delete' }]))[0].status, 'ok', 'the officer of the owner group');
  assert.equal((await push('pub1', [{ collection: 'deletedRecords', id: 'd2', op: 'delete' }]))[0].status, 'ok', 'the owner');
  assert.deepEqual(await pull('adm'), []);
});
