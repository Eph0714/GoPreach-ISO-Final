import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { loadActor, authorizeWrite, canReadRow, loadGrant } from '../src/policy/index.js';

/** Received Reports: the frozen copy of a sent month, and the Circuit Overseer's read marks. */
let store;
const person = (activeAdminRole, activeCongregationId = 'congB', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, ...extra });
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const MONTH = 1790784000000;
const status = { congregationId: 'congB', periodMonth: MONTH, status: 'SUBMITTED', version: 1, submittedAt: 1 };
const copy = { congregationId: 'congB', congregationName: 'Beta', periodMonth: MONTH, version: 1, submittedAt: 1, submittedByPersonId: 'adminB', submittedByName: 'Admin B', rows: [{ number: 1, status: 'RP', name: 'A, B', reportsCount: 1, hours: 50, bibleStudies: 2, remarks: '' }] };

before(async () => {
  store = new MemoryStore();
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('people', 'adminB', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'pubB', person(null));
  await put('people', 'adminA', person('ADMIN_PER_CONGREGATION', 'congA'));
  await put('people', 'coB', person('CIRCUIT_OVERSEER', null));
  await put('userAccessGrants', 'coB', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congB'], circuitCode: 'NT01' });
  await put('people', 'coOther', person('CIRCUIT_OVERSEER', null));
  await put('userAccessGrants', 'coOther', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congA'], circuitCode: 'NT02' });
  await put('coFieldServiceMonthStatus', `congB_${MONTH}`, status);
});

async function write(who, op, collection, id, data) {
  return store.transaction(async (tx) => {
    const actor = await loadActor(who, tx.get);
    const existing = await tx.get(collection, id);
    return (await authorizeWrite(actor, op, collection, id, data, existing, tx.get)).ok;
  });
}
const set = (who, c, id, d) => write(who, 'set', c, id, d);
async function reads(who, collection, id) {
  const get = (c, i) => store.get(c, i);
  const row = await store.get(collection, id);
  return canReadRow(await loadActor(who, get), await loadGrant(who, get), { collection, id, data: row.data, deleted: row.deleted }, get);
}

test('only the congregation sending a month saves its frozen copy, once, following the send', async () => {
  const id = `congB_${MONTH}_1`;
  assert.equal(await set('pubB', 'coReceivedReports', id, copy), false, 'a plain publisher cannot');
  assert.equal(await set('adminA', 'coReceivedReports', id, copy), false, 'another congregation cannot');
  assert.equal(await set('coB', 'coReceivedReports', id, copy), false, 'the overseer cannot write it');
  assert.equal(await set('adminB', 'coReceivedReports', 'wrong', copy), false, 'the id must be congregation + month + send number');
  assert.equal(await set('adminB', 'coReceivedReports', `congB_${MONTH}_2`, { ...copy, version: 2 }), false, 'it must follow the status version');
  assert.equal(await set('adminB', 'coReceivedReports', id, { ...copy, submittedByPersonId: 'pubB' }), false, 'saved as yourself');
  assert.equal(await set('adminB', 'coReceivedReports', id, { ...copy, rows: 'nope' }), false, 'rows must be a list');
  assert.equal(await set('adminB', 'coReceivedReports', id, copy), true, 'the sender saves it');
  await put('coReceivedReports', id, copy);
  assert.equal(await set('adminB', 'coReceivedReports', id, { ...copy, rows: [] }), false, 'never edited');
  assert.equal(await write('adminB', 'delete', 'coReceivedReports', id, null), false, 'never deleted');
});

test('the frozen copy is read by the congregation and its current overseer only', async () => {
  const id = `congB_${MONTH}_1`;
  assert.equal(await reads('coB', 'coReceivedReports', id), true, 'the assigned overseer');
  assert.equal(await reads('coOther', 'coReceivedReports', id), false, 'another overseer');
  assert.equal(await reads('adminB', 'coReceivedReports', id), true, 'the sending congregation');
  assert.equal(await reads('adminA', 'coReceivedReports', id), false, 'another congregation');
  assert.equal(await reads('pubB', 'coReceivedReports', id), false, 'a plain publisher');
  // A transfer: the assignment moves, so the report follows it without being copied or deleted.
  await put('userAccessGrants', 'coOther', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congA', 'congB'], circuitCode: 'NT02' });
  await put('userAccessGrants', 'coB', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: [], circuitCode: 'NT01' });
  assert.equal(await reads('coOther', 'coReceivedReports', id), true, 'the new overseer now reads it');
  assert.equal(await reads('coB', 'coReceivedReports', id), false, 'the previous overseer no longer does');
});

test('read marks belong to one overseer and only for a congregation they hold', async () => {
  const rid = `congB_${MONTH}_1`;
  const mark = { coPersonId: 'coOther', receivedReportId: rid, congregationId: 'congB', readAt: 5 };
  assert.equal(await set('coOther', 'coReportReads', `coOther_${rid}`, mark), true, 'the holder marks it read');
  assert.equal(await set('coOther', 'coReportReads', `coOther_${rid}`, { ...mark, congregationId: 'congZ' }), false, 'not for a congregation they do not hold');
  assert.equal(await set('coB', 'coReportReads', `coOther_${rid}`, mark), false, 'nobody marks for someone else');
  assert.equal(await set('adminB', 'coReportReads', `adminB_${rid}`, { ...mark, coPersonId: 'adminB' }), false, 'only overseers');
  await put('coReportReads', `coOther_${rid}`, mark);
  assert.equal(await reads('coOther', 'coReportReads', `coOther_${rid}`), true, 'you read your own marks');
  assert.equal(await reads('coB', 'coReportReads', `coOther_${rid}`), false, 'and nobody else does');
});
