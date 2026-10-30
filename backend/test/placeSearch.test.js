import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { PlaceSearchService, interpret, buildQuery, toResults, rank } from '../src/placeSearch.js';

const ELEMENTS = [
  { type: 'node', id: 1, lat: 16.5200, lon: 121.1800, tags: { name: 'Roxas Parish Church', amenity: 'place_of_worship', 'addr:city': 'Solano' } },
  { type: 'way', id: 2, center: { lat: 16.5210, lon: 121.1810 }, tags: { name: 'Roxas Parish Church', amenity: 'place_of_worship' } },
  { type: 'node', id: 3, lat: 16.6, lon: 121.3, tags: { name: 'Roxas Elementary School', amenity: 'school', phone: '0917' } },
  { type: 'way', id: 4, center: { lat: 16.52, lon: 121.18 }, tags: { name: 'Rizal Street', highway: 'residential' } },
  { type: 'way', id: 5, center: { lat: 16.5201, lon: 121.1801 }, tags: { name: 'Rizal Street', highway: 'residential' } },
  { type: 'node', id: 6, lat: 1, lon: 1, tags: { amenity: 'school' } },
];

test('interpret: category words, "in <place>", a trailing name and a plain name', () => {
  assert.deepEqual(interpret('church in Roxas'), { categories: [1], text: '', place: 'roxas' });
  assert.deepEqual(interpret('School Solano'), { categories: [2], text: '', place: 'solano' });
  assert.deepEqual(interpret('hospital'), { categories: [3], text: '', place: '' });
  assert.deepEqual(interpret('Kingdom Hall near Bagabag').categories, [0]);
  assert.deepEqual(interpret('Bayombong Central'), { categories: [], text: 'bayombong central', place: '' });
  assert.deepEqual(interpret('Rizal Street'), { categories: [], text: 'rizal street', place: '' });
  assert.deepEqual(interpret('paaralan sa Solano').categories, [2]);
});

test('buildQuery: around the position, inside a named area, escaped names, nothing for nothing', () => {
  assert.match(buildQuery(interpret('church'), 16.5, 121.2), /place_of_worship.*\(around:15000,16.5,121.2\)/);
  const q = buildQuery(interpret('school in Solano'), 16.5, 121.2);
  assert.match(q, /area\["name"~"solano",i\]/);
  assert.match(q, /\(area\.a\)\(around:60000,16.5,121.2\)/);
  assert.match(buildQuery(interpret('St. (Joseph)'), 16.5, 121.2), /st joseph/);
  assert.equal(buildQuery(interpret('in'), 16.5, 121.2), null);
});

test('toResults drops unnamed places, merges the same place mapped twice and one road in pieces, and adds the distance', () => {
  const r = toResults(ELEMENTS, 16.52, 121.18);
  assert.deepEqual(r.map((x) => x.name), ['Roxas Parish Church', 'Roxas Elementary School', 'Rizal Street']);
  assert.equal(r[0].distance, 0);
  assert.equal(r[1].phone, '0917');
  assert.equal(r[2].cat, 'road');
});

test('rank puts an exact name first, then names that start with the text, then nearer places', () => {
  const list = [{ name: 'Old Roxas Hall', distance: 10 }, { name: 'Roxas', distance: 9000 }, { name: 'Roxas Church', distance: 50 }];
  assert.deepEqual(rank(list, 'roxas').map((x) => x.name), ['Roxas', 'Roxas Church', 'Old Roxas Hall']);
});

let server, base, calls;
before(async () => {
  calls = [];
  const store = new MemoryStore();
  await store.transaction((tx) => tx.put('people', 'p1', { activeCongregationId: 'c' }, 'seed'));
  const fetchImpl = async (url, init) => { calls.push(decodeURIComponent(String(init.body))); return { ok: true, status: 200, json: async () => ({ elements: ELEMENTS }) }; };
  server = createApp(store, { devAuth: true, placeSearchService: new PlaceSearchService({ fetchImpl, urls: ['http://x'] }) }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test('GET /v1/map/search needs a login and a text, answers ranked results and keeps the answer for the next person', async () => {
  const url = `${base}/v1/map/search?q=${encodeURIComponent('church in Roxas')}&lat=16.52&lng=121.18`;
  assert.equal((await fetch(url)).status, 401);
  const ok = await (await fetch(url, { headers: { 'x-dev-person': 'p1' } })).json();
  assert.equal(ok.results[0].name, 'Roxas Parish Church');
  assert.deepEqual(ok.interpreted.categories, [1]);
  const n = calls.length;
  await fetch(url, { headers: { 'x-dev-person': 'p1' } });
  assert.equal(calls.length, n, 'the second search came from the cache');
  assert.equal((await fetch(`${base}/v1/map/search?q=a`, { headers: { 'x-dev-person': 'p1' } })).status, 400);
  assert.equal((await fetch(`${base}/v1/map/search?q=church&lat=999&lng=1`, { headers: { 'x-dev-person': 'p1' } })).status, 400);
});
