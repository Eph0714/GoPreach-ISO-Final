import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { LandmarkService, classify, toLandmarks, tilesFor } from '../src/landmarks.js';

const ELEMENTS = [
  { type: 'node', id: 1, lat: 16.5, lon: 121.2, tags: { name: 'Bagabag Kingdom Hall', amenity: 'place_of_worship', religion: 'jehovahs_witness' } },
  { type: 'node', id: 2, lat: 16.51, lon: 121.21, tags: { name: 'St. Joseph Church', amenity: 'place_of_worship', 'addr:street': 'Rizal St', 'addr:city': 'Bagabag', phone: '0917' } },
  { type: 'way', id: 3, center: { lat: 16.52, lon: 121.22 }, tags: { name: 'Bagabag Central School', amenity: 'school' } },
  { type: 'way', id: 4, center: { lat: 16.5, lon: 121.2 }, tags: { name: 'Magat River', waterway: 'river' } },
  { type: 'way', id: 5, center: { lat: 16.5, lon: 121.21 }, tags: { name: 'Magat River', waterway: 'river' } },
  { type: 'node', id: 6, lat: 16.5, lon: 121.2, tags: { amenity: 'school' } },
  { type: 'node', id: 7, lat: 16.5, lon: 121.2, tags: { name: 'Nothing', building: 'yes' } },
];

test('classify puts Kingdom Halls apart from other churches and ranks places by importance', () => {
  assert.deepEqual(classify({ name: 'x', amenity: 'place_of_worship', religion: 'jehovahs_witness' }), { cat: 'kingdom_hall', tier: 1 });
  assert.deepEqual(classify({ name: 'Kingdom Hall of JW', amenity: 'place_of_worship' }), { cat: 'kingdom_hall', tier: 1 });
  assert.deepEqual(classify({ name: 'x', amenity: 'place_of_worship' }), { cat: 'worship', tier: 2 });
  assert.equal(classify({ name: 'x', amenity: 'hospital' }).tier, 1);
  assert.equal(classify({ name: 'Brgy. Hall', office: 'government' }).tier, 2);
  assert.equal(classify({ name: 'x', building: 'yes' }), null);
});

test('toLandmarks drops unnamed or unclassified elements, uses way centres and keeps one pin per river', () => {
  const out = toLandmarks(ELEMENTS);
  assert.deepEqual(out.map((l) => l.id), ['n1', 'n2', 'w3', 'w4']);
  assert.equal(out[1].address, 'Rizal St, Bagabag');
  assert.equal(out[1].phone, '0917');
  assert.equal(out[2].lat, 16.52);
});

test('tilesFor covers a box with whole tiles', () => {
  assert.equal(tilesFor(16.49, 121.19, 16.51, 121.21).length >= 1, true);
  assert.equal(tilesFor(16.0, 121.0, 17.5, 122.5).length > 16, true);
});

const fakeFetch = (calls, { fail = false } = {}) => async (url, init) => {
  calls.push({ url, body: decodeURIComponent(String(init.body)) });
  if (fail) return { ok: false, status: 429, json: async () => ({}) };
  return { ok: true, status: 200, json: async () => ({ elements: ELEMENTS }) };
};

test('a tile is fetched once and then answered from the cache; a failed tile is not kept', async () => {
  const calls = [];
  let clock = 1_000_000;
  const svc = new LandmarkService({ fetchImpl: fakeFetch(calls), now: () => clock, urls: ['http://x'] });
  const a = await svc.inBox(16.49, 121.19, 16.53, 121.23);
  assert.equal(a.partial, false);
  assert.ok(a.landmarks.length >= 4);
  const n = calls.length;
  await svc.inBox(16.49, 121.19, 16.53, 121.23);
  assert.equal(calls.length, n, 'second request came from the cache');
  assert.match(calls[0].body, /\[bbox:/);

  const bad = [];
  const failing = new LandmarkService({ fetchImpl: fakeFetch(bad, { fail: true }), now: () => clock, urls: ['http://x'] });
  const r = await failing.inBox(16.49, 121.19, 16.5, 121.2);
  assert.equal(r.partial, true);
  assert.deepEqual(r.landmarks, []);
  const asked = bad.length;
  await failing.inBox(16.49, 121.19, 16.5, 121.2);
  assert.equal(bad.length, asked, 'it waits a little before asking Overpass again');
  clock += 120_000;
  await failing.inBox(16.49, 121.19, 16.5, 121.2);
  assert.ok(bad.length > asked);
});

let server, base;
before(async () => {
  const store = new MemoryStore();
  await store.transaction((tx) => tx.put('people', 'p1', { activeCongregationId: 'c' }, 'seed'));
  server = createApp(store, { devAuth: true, landmarkService: new LandmarkService({ fetchImpl: fakeFetch([]), urls: ['http://x'] }) }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test('GET /v1/map/landmarks needs a login, a valid box and a box that is not too large', async () => {
  const q = 's=16.49&w=121.19&n=16.53&e=121.23';
  assert.equal((await fetch(`${base}/v1/map/landmarks?${q}`)).status, 401);
  const ok = await fetch(`${base}/v1/map/landmarks?${q}`, { headers: { 'x-dev-person': 'p1' } });
  assert.equal(ok.status, 200);
  assert.ok((await ok.json()).landmarks.length >= 4);
  assert.equal((await fetch(`${base}/v1/map/landmarks?s=1&w=2`, { headers: { 'x-dev-person': 'p1' } })).status, 400);
  assert.equal((await fetch(`${base}/v1/map/landmarks?s=10&w=120&n=14&e=124`, { headers: { 'x-dev-person': 'p1' } })).status, 400);
});
