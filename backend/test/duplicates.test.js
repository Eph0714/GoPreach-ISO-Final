import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { nameSimilarity, compare, normalizePlace, sameAddress, haversineMeters, DEFAULTS } from '../src/duplicates.js';

// Possible duplicate persons: the matching rules (name + geographic evidence), the cross-record check, and who may see what.

let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, activeCongregationId = 'congA', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, firstName: 'X', lastName: 'Y', ...extra });
const ADDR = { province: 'Nueva Vizcaya', cityMunicipality: 'Solano', barangay: 'Quezon' };
const rec = (extra = {}) => ({ name: 'JUAN DELA CRUZ', pipelineStage: 'RETURN_VISIT', status: 'ACTIVE', congregationId: 'congA', publisherPersonId: 'pub1', ...ADDR, gpsLat: 16.52, gpsLng: 121.18, ...extra });
const ask = async (who, body) => {
  const r = await fetch(`${base}/v1/duplicates/check`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-dev-person': who }, body: JSON.stringify(body) });
  return { status: r.status, body: await r.json() };
};
const NEW = { name: 'Juan Dela Cruz', ...ADDR, lat: 16.52, lng: 121.18 };

before(async () => {
  store = new MemoryStore();
  await put('congregations', 'congA', { name: 'Alpha' });
  await put('congregations', 'congB', { name: 'Bravo' });
  await put('people', 'pub1', person(null, 'congA', { firstName: 'Pat', lastName: 'One' }));
  await put('people', 'pub2', person(null, 'congA'));
  await put('people', 'adm', person('ADMIN_PER_CONGREGATION'));
  await put('roleAssignments', 'ra_adm', { personId: 'adm', roleType: 'ADMIN:ADMIN_PER_CONGREGATION', congregationId: 'congA', status: 'ACTIVE' });
  await put('people', 'pubB', person(null, 'congB'));
  await put('people', 'ovr', person('REGULAR_ELDER', 'congA', { activeGroupId: 'g2' }));
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  for (const [id, p, g] of [['ra1', 'pub1', 'g1'], ['ra2', 'pub2', 'g2']]) await put('roleAssignments', id, { personId: p, roleType: 'PUBLISHER:REGULAR_PUBLISHER', congregationId: 'congA', groupId: g, status: 'ACTIVE' });
  await put('interestedPeople', 'rv1', rec());
  await put('interestedPeople', 'bs1', rec({ name: 'Maria Santos', pipelineStage: 'BIBLE_STUDY', gpsLat: 16.6, gpsLng: 121.2 }));
  await put('interestedPeople', 'far', rec({ name: 'Pedro Reyes', gpsLat: 14.0, gpsLng: 120.0, province: 'Cavite', cityMunicipality: 'Bacoor', barangay: 'Molino' }));
  await put('interestedPeople', 'otherCong', rec({ congregationId: 'congB', publisherPersonId: 'pubB' }));
  await put('interestedPeople', 'gone', rec({ name: 'Ana Lim', status: 'INACTIVE' }));
  const app = createApp(store, { devAuth: true });
  await new Promise((res) => { server = app.listen(0, res); });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test('names: spelling, case, spacing, order, middle initials and particles; a surname alone never matches', () => {
  const sim = (a, b) => nameSimilarity(a, b);
  assert.equal(sim('Juan Dela Cruz', 'JUAN  DELA CRUZ'), 1);
  assert.ok(sim('Juan Dela Cruz', 'Cruz, Juan') >= 0.85, 'order');
  assert.ok(sim('Juan Dela Cruz', 'Juan M. De la Cruz Jr.') >= 0.85, 'middle initial, particle, suffix');
  assert.ok(sim('Juan Cruz', 'Juan Cruzz') >= 0.85, 'typo');
  assert.ok(sim('Maria Ramos', 'Maria Ramoz') >= 0.85, 'sounds alike');
  assert.ok(sim('Juan Cruz', 'Maria Cruz') < 0.85, 'same surname only');
  assert.ok(sim('Juan Cruz', 'Juan Santos') < 0.85, 'same first name only');
  assert.equal(sim('Juan', 'Juan Cruz'), null, 'one word cannot be judged');
});

test('places: formatting variations match, and a Municipality or Barangay is only compared inside the same Province', () => {
  assert.equal(normalizePlace('City of Solano '), normalizePlace('SOLANO'));
  assert.equal(normalizePlace('Brgy. Quezon'), normalizePlace('quezon'));
  assert.ok(sameAddress(ADDR, { province: 'NUEVA  VIZCAYA', cityMunicipality: 'Municipality of Solano', barangay: 'Brgy Quezon' }));
  assert.ok(!sameAddress(ADDR, { ...ADDR, province: 'Isabela' }), 'same Municipality and Barangay name in another Province');
  assert.ok(!sameAddress(ADDR, { ...ADDR, barangay: '' }), 'missing part');
  assert.ok(Math.abs(haversineMeters(16.52, 121.18, 16.52, 121.18045) - 48) < 3);
});

test('the matching table: name + geographic evidence', () => {
  const r = rec();
  const c = (input, record = r) => compare({ ...NEW, ...input }, record)?.confidence ?? null;
  assert.equal(c({}), 'strong');                                          // all same, same coordinates
  assert.equal(c({ lat: 16.5203 }), 'strong');                           // nearby (about 33 m)
  assert.equal(c({ lat: null, lng: null }), 'possible');                 // same address, no coordinates
  assert.equal(c({ barangay: 'Roxas', lat: 16.5203 }), 'possible');      // different barangay, nearby
  assert.equal(c({ cityMunicipality: 'Bayombong', barangay: 'X', lat: 17, lng: 121.5 }), null); // different, far
  assert.equal(c({ province: 'Isabela', cityMunicipality: 'Echague', barangay: 'Y', lat: 17, lng: 121.5 }), null);
  assert.equal(c({ name: 'Maria Santos' }), null);                       // different name, same place
  assert.equal(c({ name: 'Maria Cruz' }), null);                         // same surname only
  assert.equal(c({ lat: 16.6, lng: 121.3 }), null);                      // same address, far away
  assert.equal(c({ lat: 16.5203, province: '', cityMunicipality: '', barangay: '' }), 'possible'); // nearby, no address
  assert.notEqual(c({ name: 'Jaun Dela Cruz' }), null);                  // swapped letters still reported
  assert.notEqual(c({ name: 'Juan Dela Kruz' }), null);                  // sounds alike still reported
  assert.equal(c({}, rec({ gpsLat: null, gpsLng: null })), 'possible');  // the existing record has no coordinates
  // a record with no location at all, and a new person with none: only the name is there to compare
  const bare = { province: null, cityMunicipality: null, barangay: null, gpsLat: null, gpsLng: null };
  const noPlace = { province: '', cityMunicipality: '', barangay: '', lat: null, lng: null };
  assert.equal(c(noPlace, rec(bare)), 'possible');                                              // both without a location, same name
  assert.equal(c({ ...noPlace, name: 'Jaun Dela Cruz' }, rec(bare)), 'possible');                // a typo in it
  assert.equal(c({ ...noPlace, name: 'Maria Cruz' }, rec(bare)), null);                         // same surname only
  assert.equal(c({ ...noPlace, name: 'Juan Dela Cruzz' }, rec()), 'possible');                  // the new one has no location, the old one has
  assert.equal(c({}, rec(bare)), 'possible');                                                   // the new one has a location, the old one has none
  assert.equal(c({ lat: 14.0, lng: 120.0, province: 'Cavite', cityMunicipality: 'Bacoor', barangay: 'Molino' }), null); // both have a location and they disagree
  assert.equal(compare({ ...NEW, ...noPlace, name: 'Juana Santos' }, rec(bare)), null);         // different name
});

test('the check finds the same person across Searching, Return Visit and Bible Study, strongest first, and edits nothing', async () => {
  await put('interestedPeople', 'sr1', rec({ pipelineStage: 'SEARCHING', gpsLat: 16.5203 }));
  await put('interestedPeople', 'bs2', rec({ name: 'Juan Dela Kruz', pipelineStage: 'BIBLE_STUDY', gpsLat: null, gpsLng: null }));
  const snapshot = JSON.stringify((await store.list('interestedPeople')).map((x) => x.data));
  const r = await ask('adm', NEW);
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.matches.map((m) => m.stage).sort(), ['BIBLE_STUDY', 'RETURN_VISIT', 'SEARCHING']);
  assert.equal(r.body.matches[0].confidence, 'strong');
  assert.equal(r.body.matches.at(-1).confidence, 'possible');
  const rv = r.body.matches.find((m) => m.id === 'rv1');
  assert.deepEqual([rv.similarity, rv.distanceMeters, rv.publisherName], [100, 0, 'Pat One']);
  assert.equal(JSON.stringify((await store.list('interestedPeople')).map((x) => x.data)), snapshot, 'nothing was changed');
  await store.transaction(async (tx) => { await tx.remove('interestedPeople', 'sr1', 'seed'); await tx.remove('interestedPeople', 'bs2', 'seed'); });
});

test('never another congregation, never an inactive record, and a record being edited is left out', async () => {
  const r = await ask('adm', NEW);
  assert.ok(!r.body.matches.some((m) => m.id === 'otherCong'));
  assert.equal((await ask('adm', { name: 'Ana Lim', ...ADDR, lat: 16.52, lng: 121.18 })).body.matches.length, 0);
  assert.ok(!(await ask('adm', { ...NEW, excludeId: 'rv1' })).body.matches.some((m) => m.id === 'rv1'));
  const b = await ask('pubB', NEW);
  assert.deepEqual(b.body.matches.map((m) => m.id), ['otherCong']);
  assert.equal((await ask('sa', { ...NEW, congregationId: 'congB' })).body.matches[0].id, 'otherCong');
});

test('a publisher sees only what the lists show them; a group role only its own group', async () => {
  assert.equal((await ask('pub1', NEW)).body.matches.length, 1);                  // their own record
  assert.equal((await ask('pub2', NEW)).body.matches.length, 0);                  // another publisher's, not shared
  await put('publisherVisibilitySettings', 'congA', { showOthersReturnVisit: true });
  assert.equal((await ask('pub2', NEW)).body.matches.length, 1);                  // the congregation shares Return Visits
  await put('publisherVisibilitySettings', 'congA', {});
  assert.equal((await ask('ovr', NEW)).body.matches.length, 0);                   // group 2's overseer: the record is group 1's
  assert.equal((await ask('pub2', {})).status, 400);
  assert.equal((await ask('pub2', { name: 'Juan Cruz', lat: 200, lng: 0 })).status, 400);
});

test('the thresholds are settings, adjustable without code', async () => {
  await put('appSettings', 'duplicateMatching', { nameThreshold: 0.99, radiusMeters: 5 });
  assert.equal((await ask('adm', { ...NEW, name: 'Jaun Dela Kruz' })).body.matches.length, 0);
  assert.equal((await ask('adm', NEW)).body.matches.length, 1);
  assert.deepEqual((await ask('adm', NEW)).body.settings, { nameThreshold: 0.99, radiusMeters: 5 });
  await put('appSettings', 'duplicateMatching', {});
  assert.equal(DEFAULTS.nameThreshold, 0.85);
});
