import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';

let store, server, base;
const person = (activeAdminRole, activeCongregationId = 'congA') => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId });
const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4]);

before(async () => {
  store = new MemoryStore();
  const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('people', 'member', person(null));
  await put('people', 'outsider', person(null, 'congB'));
  await put('people', 'coord', person('COORDINATOR_ELDER'));
  await put('groupChats', 'chat1', { congregationId: 'congA', participantIds: ['member'] });
  server = createApp(store, { devAuth: true }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const upload = (who, path, body = png, type = 'image/png') =>
  fetch(`${base}/v1/files?path=${encodeURIComponent(path)}`, { method: 'PUT', headers: { 'x-dev-person': who, 'content-type': type }, body });

test('a signed-in person uploads a file and anyone with the link downloads it, with the right type', async () => {
  const r = await upload('member', 'profiles/member/photo.png');
  assert.equal(r.status, 200);
  const { url } = await r.json();
  assert.match(url, /\/v1\/files\/[A-Za-z0-9_-]{20,}$/);
  const d = await fetch(url);
  assert.equal(d.status, 200);
  assert.equal(d.headers.get('content-type'), 'image/png');
  assert.deepEqual(Buffer.from(await d.arrayBuffer()), png);
});

test('uploading needs a login and a valid path', async () => {
  const anon = await fetch(`${base}/v1/files?path=x/y.png`, { method: 'PUT', body: png });
  assert.equal(anon.status, 401);
  assert.equal((await upload('member', '../secret.png')).status, 400);
  assert.equal((await upload('member', 'profiles/a/b.png', Buffer.alloc(0))).status, 400);
  assert.equal((await upload('ghost', 'profiles/a/b.png')).status, 403, 'an unknown person');
});

test('the app logo is Super-Admin only', async () => {
  assert.equal((await upload('member', 'app-settings/logo.png')).status, 403);
  assert.equal((await upload('coord', 'app-settings/logo.png')).status, 403);
  assert.equal((await upload('sa', 'app-settings/logo.png')).status, 200);
});

test('group chat attachments: participants, that congregation\'s coordinator and the Super-Admin only', async () => {
  const path = 'groupChats/chat1/attachments/m1/photo.png';
  assert.equal((await upload('member', path)).status, 200);
  assert.equal((await upload('coord', path)).status, 200);
  assert.equal((await upload('sa', path)).status, 200);
  assert.equal((await upload('outsider', path)).status, 403);
});

test('a replaced file gets a new link and the old one stops working; delete removes it', async () => {
  const first = await (await upload('member', 'profiles/member/p.png')).json();
  const second = await (await upload('member', 'profiles/member/p.png', Buffer.from([9, 9, 9]))).json();
  assert.notEqual(first.url, second.url);
  assert.equal((await fetch(first.url)).status, 404);
  assert.equal((await fetch(second.url)).status, 200);
  const del = await fetch(`${base}/v1/files?path=${encodeURIComponent('profiles/member/p.png')}`, { method: 'DELETE', headers: { 'x-dev-person': 'member' } });
  assert.equal(del.status, 200);
  assert.equal((await fetch(second.url)).status, 404);
});

test('non-images are served as downloads, and a wrong token is a 404', async () => {
  const { url } = await (await upload('member', 'announcements/a/file.pdf', Buffer.from('%PDF-1.4'), 'application/pdf')).json();
  const d = await fetch(url);
  assert.equal(d.headers.get('content-disposition'), 'attachment');
  assert.equal(d.headers.get('x-content-type-options'), 'nosniff');
  assert.equal((await fetch(`${base}/v1/files/aaaaaaaaaaaaaaaaaaaaaaaaaaaa`)).status, 404);
});
