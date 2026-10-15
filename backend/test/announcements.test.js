import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { loadActor, authorizeWrite, canReadRow, loadGrant } from '../src/policy/index.js';
import { checkAnnouncementFile } from '../src/announcementFiles.js';

/** Circuit Overseer announcements: who may write them, who may read them (targeted elders), their read marks and their secure attachments. */
let store, server, base;
const person = (activeAdminRole, activeCongregationId = 'congB', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, ...extra });
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));

before(async () => {
  store = new MemoryStore();
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('people', 'coB', person('CIRCUIT_OVERSEER', null));
  await put('userAccessGrants', 'coB', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congB'], circuitCode: 'NT01' });
  await put('people', 'coOther', person('CIRCUIT_OVERSEER', null));
  await put('userAccessGrants', 'coOther', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congA'], circuitCode: 'NT02' });
  await put('people', 'elder1', person('REGULAR_ELDER'));
  await put('people', 'elder2', person('SECRETARY'));
  await put('people', 'elderA', person('REGULAR_ELDER', 'congA'));
  await put('people', 'adminB', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'pubB', person(null));
  server = createApp(store, { devAuth: true }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

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

const base1 = { congregationId: 'congB', title: 'Circuit assembly', details: 'Please read the attached programme.', announcementDate: 1, status: 'PUBLISHED', recipientScope: 'CONGREGATION', recipientIds: [], attachments: [], createdByPersonId: 'coB', createdAt: 1 };

test('only the Circuit Overseer of the congregation writes an announcement, as themselves', async () => {
  assert.equal(await set('coB', 'announcements', 'n1', base1), true);
  assert.equal(await set('adminB', 'announcements', 'n1x', { ...base1, createdByPersonId: 'adminB' }), false, 'a congregation admin cannot publish one');
  assert.equal(await set('pubB', 'announcements', 'n1y', { ...base1, createdByPersonId: 'pubB' }), false);
  assert.equal(await set('coOther', 'announcements', 'n1z', { ...base1, createdByPersonId: 'coOther' }), false, 'a Circuit Overseer of another congregation cannot');
  assert.equal(await set('coB', 'announcements', 'n1w', { ...base1, createdByPersonId: 'someoneElse' }), false, 'created as yourself');
  assert.equal(await set('sa', 'announcements', 'n1s', base1), true, 'the Super-Admin always can');
});

test('the form is validated on the server: title, details, status and scope', async () => {
  assert.equal(await set('coB', 'announcements', 'v1', { ...base1, title: '  ' }), false);
  assert.equal(await set('coB', 'announcements', 'v2', { ...base1, details: '' }), false, 'a published announcement needs details');
  assert.equal(await set('coB', 'announcements', 'v2d', { ...base1, details: '', status: 'DRAFT' }), true, 'a draft may still be incomplete');
  assert.equal(await set('coB', 'announcements', 'v2e', { ...base1, status: 'DRAFT', recipientScope: 'SELECTED_ELDERS', recipientIds: [] }), true, 'a draft may have no elder chosen yet');
  assert.equal(await set('coB', 'announcements', 'v2f', { ...base1, status: 'DRAFT', recipientScope: 'SELECTED_ELDERS', recipientIds: ['elderA'] }), false, 'but a named elder must still belong to the congregation');
  assert.equal(await set('coB', 'announcements', 'v3', { ...base1, status: 'EXPIRED' }), false);
  assert.equal(await set('coB', 'announcements', 'v4', { ...base1, recipientScope: 'EVERYONE' }), false);
  assert.equal(await set('coB', 'announcements', 'v5', { ...base1, recipientIds: ['elder1'] }), false, 'a whole-congregation announcement names nobody');
  assert.equal(await set('coB', 'announcements', 'v6', { ...base1, attachments: [{ id: 5, name: 'x' }] }), false);
});

test('named recipients must be elders of the selected congregation - checked on the server', async () => {
  const targeted = { ...base1, recipientScope: 'SELECTED_ELDERS' };
  assert.equal(await set('coB', 'announcements', 't1', { ...targeted, recipientIds: ['elder1', 'elder2'] }), true);
  assert.equal(await set('coB', 'announcements', 't2', { ...targeted, recipientIds: [] }), false, 'at least one elder');
  assert.equal(await set('coB', 'announcements', 't3', { ...targeted, recipientIds: ['elderA'] }), false, 'an elder of another congregation');
  assert.equal(await set('coB', 'announcements', 't4', { ...targeted, recipientIds: ['pubB'] }), false, 'a publisher is not an elder');
  assert.equal(await set('coB', 'announcements', 't5', { ...targeted, recipientIds: ['nobody'] }), false, 'an unknown person');
  assert.equal(await set('coB', 'announcements', 't6', { ...targeted, recipientIds: ['elder1', 'elder1'] }), false, 'no duplicates');
});

test('a targeted announcement is visible only to the named elders, its sender and the congregation\'s Circuit Overseer', async () => {
  await put('announcements', 'tgt', { ...base1, recipientScope: 'SELECTED_ELDERS', recipientIds: ['elder1'] });
  assert.equal(await reads('elder1', 'announcements', 'tgt'), true, 'the named elder');
  assert.equal(await reads('elder2', 'announcements', 'tgt'), false, 'another elder of the same congregation');
  assert.equal(await reads('pubB', 'announcements', 'tgt'), false, 'a publisher of the same congregation');
  assert.equal(await reads('adminB', 'announcements', 'tgt'), false, 'even the congregation admin');
  assert.equal(await reads('elderA', 'announcements', 'tgt'), false, 'another congregation');
  assert.equal(await reads('coOther', 'announcements', 'tgt'), false, 'a Circuit Overseer of another congregation');
  assert.equal(await reads('coB', 'announcements', 'tgt'), true);
  assert.equal(await reads('sa', 'announcements', 'tgt'), true);
});

test('a whole-congregation announcement reaches that congregation only, and drafts and archived ones reach no recipient', async () => {
  await put('announcements', 'all', base1);
  assert.equal(await reads('pubB', 'announcements', 'all'), true);
  assert.equal(await reads('elder2', 'announcements', 'all'), true);
  assert.equal(await reads('elderA', 'announcements', 'all'), false, 'another congregation');
  await put('announcements', 'draft', { ...base1, status: 'DRAFT' });
  await put('announcements', 'old', { ...base1, status: 'ARCHIVED' });
  assert.equal(await reads('pubB', 'announcements', 'draft'), false);
  assert.equal(await reads('pubB', 'announcements', 'old'), false);
  assert.equal(await reads('coB', 'announcements', 'draft'), true, 'the sender still sees the draft');
});

test('an announcement keeps its congregation and sender, is never un-published, and cannot become a general one', async () => {
  await put('announcements', 'ed', base1);
  assert.equal(await set('coB', 'announcements', 'ed', { ...base1, title: 'Edited' }), true);
  assert.equal(await set('coB', 'announcements', 'ed', { ...base1, congregationId: 'congA' }), false);
  assert.equal(await set('coB', 'announcements', 'ed', { ...base1, createdByPersonId: 'coOther' }), false);
  assert.equal(await set('coB', 'announcements', 'ed', { ...base1, status: 'DRAFT' }), false, 'published -> archived, not back to draft');
  assert.equal(await set('coB', 'announcements', 'ed', { ...base1, status: 'ARCHIVED' }), true);
  const { recipientScope, recipientIds, ...general } = base1;
  assert.equal(await set('coB', 'announcements', 'ed', general), false, 'cannot be rewritten as an ordinary announcement');
  assert.equal(await write('pubB', 'delete', 'announcements', 'ed', null), false);
  assert.equal(await write('coB', 'delete', 'announcements', 'ed', null), true);
});

test('the original admin announcements behave as before', async () => {
  const legacy = { congregationId: 'congB', title: 'Cleaning day', details: 'Saturday', createdByPersonId: 'adminB', createdAt: 1 };
  assert.equal(await set('adminB', 'announcements', 'legacy1', legacy), true);
  await put('announcements', 'legacy2', legacy);
  assert.equal(await reads('pubB', 'announcements', 'legacy2'), true);
});

test('read marks are your own, for an announcement addressed to you', async () => {
  await put('announcements', 'rd', { ...base1, recipientScope: 'SELECTED_ELDERS', recipientIds: ['elder1'] });
  const mark = (who, aid) => ({ announcementId: aid, personId: who, readAt: 5 });
  assert.equal(await set('elder1', 'announcementReads', 'elder1_rd', mark('elder1', 'rd')), true);
  assert.equal(await set('elder2', 'announcementReads', 'elder2_rd', mark('elder2', 'rd')), false, 'not addressed to elder2');
  assert.equal(await set('elder1', 'announcementReads', 'elder2_rd', mark('elder2', 'rd')), false, 'only your own mark');
  assert.equal(await set('elder1', 'announcementReads', 'wrongid', mark('elder1', 'rd')), false, 'the id is person_announcement');
  await put('announcementReads', 'elder1_rd', mark('elder1', 'rd'));
  assert.equal(await reads('elder1', 'announcementReads', 'elder1_rd'), true);
  assert.equal(await reads('coB', 'announcementReads', 'elder1_rd'), true, 'the sender sees who read it');
  assert.equal(await reads('elder2', 'announcementReads', 'elder1_rd'), false);
});

// ---------------------------------------------------------------- attachments
const PDF = Buffer.from('%PDF-1.4\n1 0 obj\n<<>>\nendobj\n');
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2]);
const upload = (who, id, name, body = PDF, type = 'application/pdf') =>
  fetch(`${base}/v1/announcements/${id}/files?name=${encodeURIComponent(name)}`, { method: 'PUT', headers: { 'x-dev-person': who, 'content-type': type }, body });
const download = (who, id, fileId, query = '') => fetch(`${base}/v1/announcements/${id}/files/${fileId}${query}`, { headers: { 'x-dev-person': who } });

test('the file checker accepts only allowed types whose name, declared type and content agree', () => {
  assert.equal(checkAnnouncementFile('a.pdf', 'application/pdf', PDF).ok, true);
  assert.equal(checkAnnouncementFile('a.pdf', 'application/octet-stream', PDF).ok, true, 'a generic declared type is judged by its content');
  assert.equal(checkAnnouncementFile('photo.PNG', 'image/png', PNG).ok, true);
  assert.equal(checkAnnouncementFile('a.exe', 'application/x-msdownload', PDF).ok, false, 'not an allowed type');
  assert.equal(checkAnnouncementFile('a.pdf', 'application/pdf', PNG).ok, false, 'a PNG renamed .pdf');
  assert.equal(checkAnnouncementFile('a.png', 'application/pdf', PNG).ok, false, 'declared type disagrees');
  assert.equal(checkAnnouncementFile('a.txt', 'text/plain', Buffer.from([1, 0, 2, 0])).ok, false, 'binary renamed .txt');
  assert.equal(checkAnnouncementFile('a.pdf', 'application/pdf', Buffer.alloc(0)).ok, false);
  assert.equal(checkAnnouncementFile('a.pdf', 'application/pdf', Buffer.concat([PDF, Buffer.alloc(10 * 1024 * 1024)])).ok, false, 'over 10 MB');
  assert.equal(checkAnnouncementFile('noextension', 'application/pdf', PDF).ok, false);
});

test('the sender uploads attachments; only the people who may read the announcement download them', async () => {
  await put('announcements', 'fa', { ...base1, recipientScope: 'SELECTED_ELDERS', recipientIds: ['elder1'], attachments: [] });
  const r = await upload('coB', 'fa', 'Programme.pdf');
  assert.equal(r.status, 200);
  const meta = await r.json();
  assert.equal(meta.name, 'Programme.pdf'); assert.equal(meta.size, PDF.length);

  const ok = await download('elder1', 'fa', meta.id);
  assert.equal(ok.status, 200);
  assert.deepEqual(Buffer.from(await ok.arrayBuffer()), PDF);
  assert.match(ok.headers.get('content-disposition'), /^attachment/);
  assert.equal(ok.headers.get('x-content-type-options'), 'nosniff');
  assert.equal((await download('elder1', 'fa', meta.id, '?inline=1')).headers.get('content-disposition').startsWith('inline'), true, 'a PDF may be previewed');

  assert.equal((await download('coB', 'fa', meta.id)).status, 200, 'the sender');
  assert.equal((await download('elder2', 'fa', meta.id)).status, 404, 'another elder of the same congregation');
  assert.equal((await download('pubB', 'fa', meta.id)).status, 404, 'a publisher');
  assert.equal((await download('coOther', 'fa', meta.id)).status, 404, 'another Circuit Overseer');
  assert.equal((await fetch(`${base}/v1/announcements/fa/files/${meta.id}`)).status, 401, 'no login, no file');
  assert.equal((await download('elder1', 'fa', 'guessed-file-id')).status, 404);
});

test('uploads are limited to the sender, to allowed files, and a draft not yet synced still accepts the overseer\'s files', async () => {
  assert.equal((await upload('elder1', 'fa', 'x.pdf')).status, 403, 'a recipient cannot add files');
  assert.equal((await upload('adminB', 'fa', 'x.pdf')).status, 403);
  assert.equal((await upload('coOther', 'fa', 'x.pdf')).status, 403, 'a Circuit Overseer of another congregation');
  assert.equal((await upload('coB', 'fa', 'x.exe', Buffer.from('MZ'), 'application/octet-stream')).status, 400);
  assert.equal((await upload('coB', 'fa', 'fake.pdf', PNG, 'application/pdf')).status, 400);
  assert.equal((await upload('coB', 'brand-new-draft', 'a.pdf')).status, 200, 'a draft being composed has no record yet');
  assert.equal((await upload('pubB', 'brand-new-draft', 'a.pdf')).status, 403);
  // the generic file store cannot be used to reach the protected area
  const sneaky = await fetch(`${base}/v1/files?path=${encodeURIComponent('announcement-files/fa/abc')}`, { method: 'PUT', headers: { 'x-dev-person': 'pubB', 'content-type': 'image/png' }, body: PNG });
  assert.equal(sneaky.status, 403);
});

test('the sender removes a file; nobody else can', async () => {
  const meta = await (await upload('coB', 'fa', 'Second.pdf')).json();
  assert.equal((await fetch(`${base}/v1/announcements/fa/files/${meta.id}`, { method: 'DELETE', headers: { 'x-dev-person': 'elder1' } })).status, 403);
  assert.equal((await fetch(`${base}/v1/announcements/fa/files/${meta.id}`, { method: 'DELETE', headers: { 'x-dev-person': 'coB' } })).status, 200);
  assert.equal((await download('coB', 'fa', meta.id)).status, 404);
});
