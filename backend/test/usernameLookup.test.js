import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';

// A typed username finds its account even when the phone capitalized it or a space slipped in; two usernames that differ only by case are never mixed up.

const seed = async (names) => {
  const store = new MemoryStore();
  for (const [id, username] of Object.entries(names)) await store.transaction((tx) => tx.put('people', id, { username }, 'seed'));
  return store;
};

test('exact match, different case and surrounding spaces all find the account', async () => {
  const store = await seed({ p1: 'ephraim', p2: 'maria.santos' });
  for (const typed of ['ephraim', 'Ephraim', 'EPHRAIM', '  ephraim ']) assert.equal((await store.findPersonByUsername(typed))?.id, 'p1', typed);
  assert.equal((await store.findPersonByUsername('Maria.Santos'))?.id, 'p2');
  assert.equal(await store.findPersonByUsername('nobody'), null);
  assert.equal(await store.findPersonByUsername('   '), null);
});

test('usernames that differ only by case: exact wins, an in-between spelling matches nobody', async () => {
  const store = await seed({ a: 'Jose', b: 'jose' });
  assert.equal((await store.findPersonByUsername('Jose'))?.id, 'a');
  assert.equal((await store.findPersonByUsername('jose'))?.id, 'b');
  assert.equal(await store.findPersonByUsername('JOSE'), null);
});

test('a stored username with a trailing space still matches what was typed', async () => {
  const store = await seed({ p: 'temp.user ' });
  assert.equal((await store.findPersonByUsername('temp.user'))?.id, 'p');
});
