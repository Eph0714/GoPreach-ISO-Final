import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { loadActor, authorizeWrite, canReadRow, loadGrant } from '../src/policy/index.js';

const person = () => ({ isSuperAdmin: false, activeAdminRole: null, activeCongregationId: 'congA' });

async function setup() {
  const store = new MemoryStore();
  const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
  await put('people', 'pubA', person());
  await put('people', 'pubB', person());
  const fav = { publisherPersonId: 'pubA', recordId: 'r1', stage: 'RETURN_VISIT', createdAt: 1 };
  await put('favorites', 'pubA_r1', fav);
  const can = (who, op, id, data) => store.transaction(async (tx) => {
    const actor = await loadActor(who, tx.get);
    const existing = await tx.get('favorites', id);
    return (await authorizeWrite(actor, op, 'favorites', id, data, existing, tx.get)).ok;
  });
  const readable = async (who) => {
    const get = (c, i) => store.get(c, i);
    const row = { collection: 'favorites', id: 'pubA_r1', data: fav, deleted: false };
    return canReadRow(await loadActor(who, get), await loadGrant(who, get), row, get);
  };
  return { fav, can, readable };
}

test('a Favorite is private to its owner and is one per person and record', async () => {
  const { fav, can, readable } = await setup();
  assert.equal(await can('pubA', 'set', 'pubA_r2', { ...fav, recordId: 'r2' }), true, 'own bookmark');
  assert.equal(await can('pubA', 'set', 'pubA_r2', { ...fav, recordId: 'r3' }), false, 'the id must name the record: no duplicates under other ids');
  assert.equal(await can('pubA', 'set', 'random-id', fav), false, 'the id must be person_record');
  assert.equal(await can('pubB', 'set', 'pubA_r1', fav), false, 'cannot write for another person');
  assert.equal(await can('pubB', 'set', 'pubB_r1', { ...fav, publisherPersonId: 'pubA' }), false, 'cannot pose as another person');
  assert.equal(await can('pubB', 'delete', 'pubA_r1'), false);
  assert.equal(await can('pubA', 'delete', 'pubA_r1'), true);
  assert.equal(await readable('pubA'), true);
  assert.equal(await readable('pubB'), false, 'nobody else reads it');
});
