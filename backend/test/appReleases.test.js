import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import { mountAppReleases, isNewer } from '../src/appReleases.js';

// The release notes the update window lists: every release newer than the installed one, newest first, from a source the server keeps for a few minutes.

const HISTORY = [
  { version: '1.169.36', date: '2026-11-06', notes: 'Territory Map filters are tidier.' },
  { version: '1.169.37', date: '2026-11-06', notes: ' - Update window shows the specific changes.\n - Tabs are aligned. ' },
  { version: '1.169.38', date: '2026-11-06', notes: '- Duplicate person warning.' },
  { version: '1.169.9', date: '2026-10-01', notes: 'An older one.' },
  { version: '1.169.39', date: '2026-11-06' },
];
let server, base, calls = 0, fail = false;

before(async () => {
  const app = express();
  mountAppReleases(app, {
    fetchImpl: async () => { calls++; if (fail) return { ok: false, status: 503 }; return { ok: true, status: 200, json: async () => HISTORY }; },
    ttlMs: 60_000,
  });
  await new Promise((res) => { server = app.listen(0, res); });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());
const get = async (q = '') => { const r = await fetch(`${base}/v1/public/app-releases${q}`); return { status: r.status, body: await r.json() }; };

test('versions compare as numbers, not as text', () => {
  assert.equal(isNewer('1.169.9', '1.169.10'), true);
  assert.equal(isNewer('1.169.38', '1.169.38'), false);
  assert.equal(isNewer('1.170.0', '1.169.99'), false);
});

test('lists every release since the installed version, newest first, with its notes', async () => {
  const r = await get('?since=1.169.36');
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.releases.map((x) => x.version), ['1.169.39', '1.169.38', '1.169.37']);
  assert.equal(r.body.releases[2].notes, '- Update window shows the specific changes.\n - Tabs are aligned.');
  assert.equal(r.body.releases[0].notes, '', 'a release with no notes still appears');
  assert.equal((await get('?since=1.169.39')).body.releases.length, 0);
  assert.equal((await get()).body.releases[0].version, '1.169.39');
});

test('the source is read once per few minutes and a stale list is kept when it fails', async () => {
  const before = calls;
  await get('?since=1.169.1'); await get('?since=1.169.1');
  assert.equal(calls, before, 'cached');
  fail = true;
  assert.equal((await get('?since=1.169.38')).body.releases[0].version, '1.169.39');
});
