import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { loadActor, authorizeWrite, canReadRow, loadGrant } from '../src/policy/index.js';

/** Publisher category history: append-only, written by whoever manages the congregation, scoped to that congregation. */
let store;
const person = (activeAdminRole, activeCongregationId = 'congB') => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId });
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const entry = { publisherPersonId: 'pubB', congregationId: 'congB', category: 'AUXILIARY_PIONEER', effectiveFrom: 1790784000000, source: 'CHANGED', recordedByPersonId: 'adminB', recordedAt: 5 };

before(async () => {
  store = new MemoryStore();
  await put('people', 'adminB', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'elderB', person('REGULAR_ELDER'));
  await put('people', 'pubB', person(null));
  await put('people', 'adminA', person('ADMIN_PER_CONGREGATION', 'congA'));
});

async function write(who, op, collection, id, data) {
  return store.transaction(async (tx) => {
    const actor = await loadActor(who, tx.get);
    const existing = await tx.get(collection, id);
    return (await authorizeWrite(actor, op, collection, id, data, existing, tx.get)).ok;
  });
}
const set = (who, id, d) => write(who, 'set', 'publisherCategoryHistory', id, d);

test('the congregation managers add history entries, as themselves', async () => {
  assert.equal(await set('adminB', 'h1', entry), true, 'an admin of that congregation');
  assert.equal(await set('elderB', 'h2', { ...entry, recordedByPersonId: 'elderB' }), true, 'an elder who manages publishers');
  assert.equal(await set('pubB', 'h3', { ...entry, recordedByPersonId: 'pubB' }), false, 'a plain publisher cannot');
  assert.equal(await set('adminA', 'h4', entry), false, 'another congregation cannot');
  assert.equal(await set('adminB', 'h5', { ...entry, recordedByPersonId: 'pubB' }), false, 'recorded as yourself');
});

test('entries must be well formed', async () => {
  assert.equal(await set('adminB', 'h6', { ...entry, category: 'KING' }), false, 'unknown category');
  assert.equal(await set('adminB', 'h7', { ...entry, publisherPersonId: '' }), false, 'a publisher is required');
  assert.equal(await set('adminB', 'h8', { ...entry, effectiveFrom: 'July' }), false, 'a month is required');
  assert.equal(await set('adminB', 'h9', { ...entry, effectiveThrough: entry.effectiveFrom + 1 }), false, 'a confirmation covers exactly one month');
  assert.equal(await set('adminB', 'h10', { ...entry, source: 'ADMIN_CONFIRMED', effectiveThrough: entry.effectiveFrom }), true, 'an admin confirmation for one month');
});

test('history is append-only', async () => {
  await put('publisherCategoryHistory', 'h1', entry);
  assert.equal(await set('adminB', 'h1', { ...entry, category: 'REGULAR_PIONEER' }), false, 'never edited');
  assert.equal(await write('adminB', 'delete', 'publisherCategoryHistory', 'h1', null), false, 'never deleted');
});

test('history stays inside its congregation', async () => {
  const get = (c, i) => store.get(c, i);
  const row = await store.get('publisherCategoryHistory', 'h1');
  const read = async (who) => canReadRow(await loadActor(who, get), await loadGrant(who, get), { collection: 'publisherCategoryHistory', id: 'h1', data: row.data, deleted: row.deleted }, get);
  assert.equal(await read('adminB'), true);
  assert.equal(await read('adminA'), false);
});
