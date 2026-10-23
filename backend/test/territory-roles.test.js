import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

// Territory Map permissions come from the ASSIGNED system role, not from the publisher category the person is shown with:
// a Ministerial Servant / Regular Elder / Publisher who is also a Coordinator Elder, Secretary or Service Overseer keeps that role's
// congregation-wide reach; group-level people stay in their FS Group; nobody reaches another congregation.

let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, activeCongregationId = 'congA', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, ...extra });
const assign = (id, personId, roleType, congregationId = 'congA', status = 'ACTIVE') => put('roleAssignments', id, { personId, roleType, congregationId, status });

before(async () => {
  store = new MemoryStore();
  // publisher-category people who also hold a congregation-wide role
  await put('people', 'msCoord', person('MINISTERIAL_SERVANT'));
  await assign('ra1', 'msCoord', 'ADMIN:COORDINATOR_ELDER');
  await put('people', 'elderSec', person('REGULAR_ELDER'));
  await assign('ra2', 'elderSec', 'ADMIN:SECRETARY');
  await put('people', 'pubSO', person(null));
  await assign('ra3', 'pubSO', 'ADMIN:SERVICE_OVERSEER');
  // no wide role: a role that was switched off, a wide role of ANOTHER congregation, group-level slot holders
  await put('people', 'inactiveSO', person(null));
  await assign('ra4', 'inactiveSO', 'ADMIN:SERVICE_OVERSEER', 'congA', 'INACTIVE');
  await put('people', 'coordB', person(null, 'congB'));
  await assign('ra5', 'coordB', 'ADMIN:COORDINATOR_ELDER', 'congB');
  await put('people', 'wrongCong', person(null));
  await assign('ra6', 'wrongCong', 'ADMIN:COORDINATOR_ELDER', 'congB');
  await put('people', 'groupOv', person('REGULAR_ELDER'));
  await assign('ra7', 'groupOv', 'ADMIN:REGULAR_ELDER');
  await put('people', 'groupSv', person('MINISTERIAL_SERVANT'));
  await assign('ra8', 'groupSv', 'ADMIN:MINISTERIAL_SERVANT');
  await put('people', 'groupAs', person(null));
  await assign('ra9', 'groupAs', 'PUBLISHER:REGULAR_PUBLISHER');
  // the established roles
  await put('people', 'adminA', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'co', person('CIRCUIT_OVERSEER', null));
  await put('userAccessGrants', 'co', { personId: 'co', scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congA'], circuitCode: 'NT01', permissions: ['VIEW_CONGREGATIONS'] });
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('groups', 'g1', { congregationId: 'congA', overseerPersonId: 'groupOv', servantPersonId: 'groupSv', assistantPersonId: 'groupAs' });
  await put('groups', 'g2', { congregationId: 'congA', overseerPersonId: 'someone' });
  await put('territoryAssignmentBarangays', 'congA_t1', { congregationId: 'congA', groupId: 'g1' });
  await put('territoryAssignmentBarangays', 'congA_t2', { congregationId: 'congA', groupId: 'g2' });
  await put('territoryBounds', 'congA_t1', { congregationId: 'congA', groupId: 'g1', minLat: 10, maxLat: 11, minLng: 120, maxLng: 121 });
  const app = createApp(store, { devAuth: true });
  await new Promise((res) => { server = app.listen(0, res); });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const polygon = JSON.stringify({ type: 'Polygon', coordinates: [[[120.2, 10.2], [120.4, 10.2], [120.4, 10.4], [120.2, 10.2]]] });
const drawing = (who, o = {}) => ({
  geometryJson: polygon, fillColor: '#43A047', fillOpacity: 0.1, borderColor: '#1B5E20', status: 'FINISHED', remarks: '', name: '',
  userId: who, updatedByUserId: who, congregationId: 'congA', groupId: 'g1', territoryId: 'congA_t1',
  minLat: 10.2, maxLat: 10.4, minLng: 120.2, maxLng: 120.4, createdAt: 1, updatedAt: 1, ...o,
});
const post = async (who, path, body) => {
  const r = await fetch(base + path, { method: 'POST', headers: { 'content-type': 'application/json', 'x-dev-person': who }, body: JSON.stringify(body) });
  return { status: r.status, body: await r.json() };
};
const push = async (who, op) => (await post(who, '/v1/sync/push', { ops: [op] })).body.results[0].status;
const set = (collection, id, data) => ({ collection, id, op: 'set', data });
const otherGroup = { groupId: 'g2', territoryId: 'congA_t2' };
const saveTerritory = (who, o = {}) => post(who, '/v1/territory/save', {
  congregationId: 'congA', groupId: 'g2', groupName: 'Group Two', provinceId: 10, provinceName: 'Cebu',
  municipalities: [{ muncityId: 100, muncityName: 'Cebu City', barangays: [{ id: 7, name: 'Seven' }] }], ...o,
});

test('a Ministerial Servant, Regular Elder and Publisher with a congregation-wide role draw in every FS Group of their congregation', async () => {
  let n = 0;
  for (const who of ['msCoord', 'elderSec', 'pubSO']) {
    assert.equal(await push(who, set('territoryDrawings', `w${n++}`, drawing(who, otherGroup))), 'ok', `${who} in another FS Group`);
    assert.equal(await push(who, set('territoryDrawings', `w${n++}`, drawing(who, { maxLat: 12 }))), 'ok', `${who} outside the published bounds`);
    assert.equal(await push(who, set('territoryDrawings', `w${n++}`, drawing(who, { groupId: '', territoryId: '' }))), 'ok', `${who} in an unassigned area`);
  }
});

test('they also edit and delete other groups\' drawings, publish bounds and assign territory', async () => {
  await push('msCoord', set('territoryDrawings', 'x1', drawing('msCoord', otherGroup)));
  assert.equal(await push('elderSec', set('territoryDrawings', 'x1', drawing('msCoord', { ...otherGroup, updatedByUserId: 'elderSec', status: 'TO_DO', fillColor: '#E53935' }))), 'ok');
  assert.equal(await push('pubSO', { collection: 'territoryDrawings', id: 'x1', op: 'delete' }), 'ok');
  assert.equal(await push('msCoord', set('territoryBounds', 'congA_t2', { congregationId: 'congA', groupId: 'g2', minLat: 10, maxLat: 11, minLng: 120, maxLng: 121 })), 'ok');
  assert.equal((await saveTerritory('elderSec')).body.status, 'success');
  assert.equal((await post('pubSO', '/v1/territory/remove-group', { congregationId: 'congA', groupId: 'g2', provinceId: 10 })).body.status, 'success');
});

test('group-level people stay in their own FS Group, whatever their category', async () => {
  for (const who of ['groupOv', 'groupSv', 'groupAs']) {
    assert.equal(await push(who, set('territoryDrawings', `g-${who}`, drawing(who))), 'ok', `${who} in own group`);
    assert.equal(await push(who, set('territoryDrawings', `h-${who}`, drawing(who, otherGroup))), 'denied', `${who} in another group`);
    assert.equal(await push(who, set('territoryDrawings', `u-${who}`, drawing(who, { groupId: '', territoryId: '' }))), 'denied', `${who} unassigned area`);
    assert.equal((await saveTerritory(who)).status, 403, `${who} cannot assign territory`);
  }
});

test('a switched-off role, or a role of another congregation, gives nothing', async () => {
  for (const who of ['inactiveSO', 'coordB', 'wrongCong']) {
    assert.equal(await push(who, set('territoryDrawings', `n-${who}`, drawing(who))), 'denied', `${who} drawing`);
    assert.equal((await saveTerritory(who)).status, 403, `${who} assignment`);
  }
});

test('a wide role never reaches another congregation, by drawing or by direct request', async () => {
  for (const who of ['msCoord', 'elderSec', 'pubSO']) {
    assert.equal(await push(who, set('territoryDrawings', `o-${who}`, drawing(who, { congregationId: 'congB', groupId: '', territoryId: '' }))), 'denied');
    assert.equal((await saveTerritory(who, { congregationId: 'congB' })).status, 403);
    assert.equal((await post(who, '/v1/territory/remove-group', { congregationId: 'congB', groupId: 'gx', provinceId: 10 })).status, 403);
    assert.equal(await push(who, set('territoryBounds', 'congB_t9', { congregationId: 'congB', groupId: 'gx', minLat: 1, maxLat: 2, minLng: 1, maxLng: 2 })), 'denied');
  }
  // the author stamp cannot be forged to borrow someone else's reach
  assert.equal(await push('groupOv', set('territoryDrawings', 'f1', drawing('msCoord', otherGroup))), 'denied');
});

test('Admin, Super Admin and Circuit Overseer keep their established scopes', async () => {
  assert.equal(await push('adminA', set('territoryDrawings', 'a1', drawing('adminA', otherGroup))), 'ok');
  assert.equal(await push('sa', set('territoryDrawings', 'a2', drawing('sa', { congregationId: 'congB', groupId: '', territoryId: '' }))), 'ok');
  assert.equal(await push('co', set('territoryDrawings', 'a3', drawing('co', otherGroup))), 'denied');
  assert.equal((await saveTerritory('co')).status, 403);
});
