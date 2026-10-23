import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import * as XLSX from 'xlsx';
import { existsSync, readFileSync } from 'node:fs';

// The upload limit is read when the module loads, so it is set first (and the modules are imported afterwards).
process.env.CO_FILE_MAX_MB = '1';
const { MemoryStore } = await import('../src/memoryStore.js');
const { createApp } = await import('../src/app.js');

/** The Circuit Overseer's File Manager and Previous Manual Record: access, folders, files, search, the recycle bin, Excel import, sorting and privacy. */
let store, server, base;
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const person = (activeAdminRole, activeCongregationId = null) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId });

before(async () => {
  store = new MemoryStore();
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('people', 'co1', person('CIRCUIT_OVERSEER'));
  await put('userAccessGrants', 'co1', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['cB', 'cA'], circuitCode: 'NT01' });
  await put('people', 'co2', person('CIRCUIT_OVERSEER'));
  await put('userAccessGrants', 'co2', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['cC'], circuitCode: 'NT02' });
  await put('people', 'elder', person('REGULAR_ELDER', 'cA'));
  await put('congregations', 'cA', { id: 'cA', name: 'Zamora Congregation' });
  await put('congregations', 'cB', { id: 'cB', name: 'Alpha Congregation' });
  await put('congregations', 'cC', { id: 'cC', name: 'Other Congregation' });
  server = createApp(store, { devAuth: true }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const call = (who, method, path, { body, type, json } = {}) => fetch(`${base}/v1/co${path}`, {
  method,
  headers: { 'x-dev-person': who, ...(json ? { 'content-type': 'application/json' } : type ? { 'content-type': type } : {}) },
  body: json ? JSON.stringify(json) : body,
});
const j = async (res) => res.json();

const PDF = Buffer.from('%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n');
const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(40)]);
const upload = (who, name, data, folderId = '', type = 'application/octet-stream') =>
  call(who, 'PUT', `/files/upload?name=${encodeURIComponent(name)}${folderId ? `&folderId=${folderId}` : ''}`, { body: data, type });
const mkfolder = async (who, name, parentId = null) => j(await call(who, 'POST', '/files/folders', { json: { name, parentId } }));

function workbook(sheets, bookType = 'xlsx') {
  const wb = XLSX.utils.book_new();
  for (const [name, rows] of Object.entries(sheets)) XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(rows), name);
  return XLSX.write(wb, { type: 'buffer', bookType });
}
const visits = [['Publisher', 'Hours', 'Notes'], ['Maria Santos', 12, 'Regular pioneer'], [], ['Juan dela Cruz', 8, 'Bible study with the Reyes family'], ['', '', ''], ['Pedro Reyes', 5, 'Returned in March']];

test('only a Circuit Overseer (or the Super-Admin) may use either module', async () => {
  assert.equal((await call('elder', 'GET', '/files/list')).status, 403);
  assert.equal((await call('elder', 'GET', '/manual/congregations')).status, 403);
  assert.equal((await call('nobody', 'GET', '/files/list')).status, 403);
  assert.equal((await fetch(`${base}/v1/co/files/list`)).status, 401, 'no login, no access');
  assert.equal((await call('co1', 'GET', '/files/list')).status, 200);
  assert.equal((await call('sa', 'GET', '/files/list')).status, 200);
});

test('folders: nested, no duplicates, no bad names, no moving into themselves, non-empty deletes need confirmation', async () => {
  const reports = await mkfolder('co1', 'Annual Reports');
  assert.ok(reports.id);
  const y26 = await mkfolder('co1', '2026', reports.id);
  assert.equal(y26.parentId, reports.id);
  assert.equal((await call('co1', 'POST', '/files/folders', { json: { name: 'annual reports', parentId: null } })).status, 409, 'same name, any case');
  assert.equal((await call('co1', 'POST', '/files/folders', { json: { name: '../etc', parentId: null } })).status, 400);
  assert.equal((await call('co1', 'POST', '/files/folders', { json: { name: '', parentId: null } })).status, 400);
  assert.equal((await call('co1', 'POST', '/files/folders', { json: { name: 'x', parentId: 'doesnotexist1' } })).status, 404);
  assert.equal((await call('co1', 'PATCH', `/files/folders/${reports.id}`, { json: { parentId: y26.id } })).status, 400, 'not into its own subfolder');
  const renamed = await j(await call('co1', 'PATCH', `/files/folders/${y26.id}`, { json: { name: '2026 Visits' } }));
  assert.equal(renamed.name, '2026 Visits');
  const tree = await j(await call('co1', 'GET', '/files/tree'));
  assert.deepEqual(tree.folders.map((f) => f.name).sort(), ['2026 Visits', 'Annual Reports']);
  await upload('co1', 'a.pdf', PDF, y26.id, 'application/pdf');
  const warn = await call('co1', 'DELETE', `/files/folders/${reports.id}`);
  assert.equal(warn.status, 409);
  assert.deepEqual(await j(warn), { error: 'This folder is not empty', needsConfirm: true, folders: 1, files: 1 });
  assert.equal((await call('co1', 'DELETE', `/files/folders/${reports.id}?confirm=1`)).status, 200);
  assert.equal((await j(await call('co1', 'GET', '/files/list'))).folders.length, 0);
});

test('uploads: type, content and size are checked; names never collide; the bytes come back unchanged', async () => {
  const ok = await upload('co1', 'letter.pdf', PDF, '', 'application/pdf');
  assert.equal(ok.status, 201);
  const meta = await j(ok);
  assert.equal(meta.size, PDF.length);
  const again = await j(await upload('co1', 'letter.pdf', PDF, '', 'application/pdf'));
  assert.equal(again.name, 'letter (2).pdf', 'a second upload never overwrites the first');
  assert.equal((await upload('co1', 'setup.exe', Buffer.from('MZ....'))).status, 400, 'executables are refused');
  assert.equal((await upload('co1', 'run.sh', Buffer.from('echo hi'))).status, 400);
  assert.equal((await upload('co1', 'fake.pdf', Buffer.from('this is not a pdf'))).status, 400, 'content must match the type');
  assert.equal((await upload('co1', 'photo.png', PDF, '', 'image/png')).status, 400, 'a PDF renamed .png is refused');
  assert.equal((await upload('co1', 'photo.jpg', PNG, '', 'image/png')).status, 400, 'a PNG named .jpg is refused');
  assert.equal((await upload('co1', 'photo.png', PNG, '', 'image/png')).status, 201);
  assert.equal((await upload('co1', '..\\evil.pdf', PDF)).status, 400, 'no paths in names');
  assert.equal((await upload('co1', 'empty.pdf', Buffer.alloc(0))).status, 400);
  assert.ok([400, 413].includes((await upload('co1', 'big.pdf', Buffer.concat([PDF, Buffer.alloc(2 * 1024 * 1024)]))).status), 'over the configured limit');
  assert.equal((await upload('co1', 'x.pdf', PDF, 'nofolder12345')).status, 404);
  const dl = await call('co1', 'GET', `/files/${meta.id}/download`);
  assert.equal(dl.status, 200);
  assert.deepEqual(Buffer.from(await dl.arrayBuffer()), PDF, 'not corrupted');
  assert.match(dl.headers.get('content-disposition'), /attachment; filename="letter.pdf"/);
  assert.equal(dl.headers.get('x-content-type-options'), 'nosniff');
  const view = await call('co1', 'GET', `/files/${meta.id}/download?inline=1`);
  assert.match(view.headers.get('content-disposition'), /^inline/);
  assert.match(view.headers.get('content-security-policy'), /sandbox/);
  const word = await j(await upload('co1', 'minutes.docx', Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), Buffer.alloc(30)])));
  const noPreview = await call('co1', 'GET', `/files/${word.id}/download?inline=1`);
  assert.match(noPreview.headers.get('content-disposition'), /^attachment/, 'Word is never rendered in the page');
});

test('rename keeps the type, move works, details show the folder, and a file cannot be taken by someone else', async () => {
  const folder = await mkfolder('co1', 'Letters');
  const f = await j(await upload('co1', 'greeting.txt', Buffer.from('hello'), '', 'text/plain'));
  const renamed = await j(await call('co1', 'PATCH', `/files/${f.id}`, { json: { name: 'welcome' } }));
  assert.equal(renamed.name, 'welcome.txt');
  assert.equal((await call('co1', 'PATCH', `/files/${f.id}`, { json: { name: 'welcome.exe' } })).status, 400);
  assert.equal((await call('co1', 'PATCH', `/files/${f.id}`, { json: { name: 'welcome.pdf' } })).status, 400, 'a file keeps its type');
  const moved = await j(await call('co1', 'PATCH', `/files/${f.id}`, { json: { folderId: folder.id } }));
  assert.equal(moved.folderPath, 'File Manager / Letters');
  const details = await j(await call('co1', 'GET', `/files/${f.id}`));
  assert.equal(details.owner, 'co1');
  assert.equal(details.folderId, folder.id);
  for (const [method, path, json] of [['GET', `/files/${f.id}`], ['GET', `/files/${f.id}/download`], ['PATCH', `/files/${f.id}`, { name: 'mine' }], ['DELETE', `/files/${f.id}`]]) {
    assert.equal((await call('co2', method, path, { json })).status, 404, `${method} ${path} of another Circuit Overseer's file`);
  }
  assert.equal((await call('co2', 'PATCH', `/files/folders/${folder.id}`, { json: { name: 'stolen' } })).status, 404);
  assert.equal((await call('co2', 'POST', '/files/folders', { json: { name: 'in yours', parentId: folder.id } })).status, 404);
  assert.equal((await j(await call('co2', 'GET', '/files/list'))).files.length, 0, 'the other CO sees nothing of it');
  const sneaky = await fetch(`${base}/v1/files?path=${encodeURIComponent(`co-files/co1/${f.id}`)}`, { method: 'PUT', headers: { 'x-dev-person': 'co2', 'content-type': 'image/png' }, body: PNG });
  assert.equal(sneaky.status, 403, 'the general file store never writes into co-files/');
});

test('search looks in every folder, partial and case-insensitive, and says where each result is', async () => {
  const a = await mkfolder('co1', 'Congregation Records');
  const b = await mkfolder('co1', 'Solano', a.id);
  await upload('co1', 'Solano Visit Report 2026.xlsx', Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), Buffer.alloc(30)]), b.id);
  const res = await j(await call('co1', 'GET', '/files/search?q=SOLA'));
  const names = res.items.map((i) => `${i.kind}:${i.name}`);
  assert.ok(names.includes('folder:Solano') && names.includes('file:Solano Visit Report 2026.xlsx'));
  const file = res.items.find((i) => i.kind === 'file');
  assert.equal(file.folderPath, 'File Manager / Congregation Records / Solano');
  assert.equal((await j(await call('co1', 'GET', '/files/search?q=visit%202026'))).total, 1, 'several words must all match');
  assert.equal((await j(await call('co1', 'GET', '/files/search?q=zzzz'))).total, 0);
  assert.equal((await j(await call('co2', 'GET', '/files/search?q=solano'))).total, 0, 'never another CO\'s files');
});

test('sorting and the list view', async () => {
  const z = await mkfolder('co1', 'Sorting');
  await upload('co1', 'b.pdf', PDF, z.id, 'application/pdf');
  await upload('co1', 'a.png', PNG, z.id, 'image/png');
  const asc = await j(await call('co1', 'GET', `/files/list?folderId=${z.id}&sort=name`));
  assert.deepEqual(asc.files.map((f) => f.name), ['a.png', 'b.pdf']);
  const desc = await j(await call('co1', 'GET', `/files/list?folderId=${z.id}&sort=name&dir=desc`));
  assert.deepEqual(desc.files.map((f) => f.name), ['b.pdf', 'a.png']);
  const bySize = await j(await call('co1', 'GET', `/files/list?folderId=${z.id}&sort=size`));
  assert.equal(bySize.files[0].name, 'b.pdf');
  assert.deepEqual(asc.path.map((p) => p.name), ['Sorting']);
});

test('recycle bin: delete, restore, delete forever (the bytes go too), and a bulk move', async () => {
  const f = await j(await upload('co1', 'old.pdf', PDF, '', 'application/pdf'));
  assert.equal((await call('co1', 'DELETE', `/files/${f.id}`)).status, 200);
  assert.equal((await call('co1', 'GET', `/files/${f.id}/download`)).status, 404, 'deleted files are not served');
  assert.ok((await store.getFileByPath(`co-files/co1/${f.id}`)), 'but the bytes are kept until it is deleted forever');
  const trash = await j(await call('co1', 'GET', '/files/trash'));
  assert.ok(trash.items.some((i) => i.id === f.id));
  assert.equal((await call('co1', 'POST', '/files/trash/restore', { json: { kind: 'file', id: f.id } })).status, 200);
  assert.equal((await call('co1', 'GET', `/files/${f.id}/download`)).status, 200);
  await call('co1', 'DELETE', `/files/${f.id}`);
  assert.equal((await call('co2', 'DELETE', `/files/trash/file/${f.id}`)).status, 404, 'another CO cannot purge it');
  assert.equal((await call('co1', 'DELETE', `/files/trash/file/${f.id}`)).status, 200);
  assert.equal(await store.getFileByPath(`co-files/co1/${f.id}`), null, 'row and bytes are gone together');
  const target = await mkfolder('co1', 'Bulk target');
  const x = await j(await upload('co1', 'x1.pdf', PDF, '', 'application/pdf'));
  const y = await j(await upload('co1', 'x2.pdf', PDF, '', 'application/pdf'));
  const moved = await j(await call('co1', 'POST', '/files/bulk', { json: { action: 'move', fileIds: [x.id, y.id], targetFolderId: target.id } }));
  assert.equal(moved.files, 2);
  assert.equal((await j(await call('co1', 'GET', `/files/list?folderId=${target.id}`))).files.length, 2);
  await call('co1', 'POST', '/files/bulk', { json: { action: 'delete', fileIds: [x.id, y.id] } });
  const folderTrash = await mkfolder('co1', 'Trash me');
  await upload('co1', 'inside.pdf', PDF, folderTrash.id, 'application/pdf');
  await call('co1', 'DELETE', `/files/folders/${folderTrash.id}?confirm=1`);
  assert.equal((await call('co1', 'POST', '/files/trash/restore', { json: { kind: 'folder', id: folderTrash.id } })).status, 200);
  const back = await j(await call('co1', 'GET', `/files/list?folderId=${folderTrash.id}`));
  assert.deepEqual(back.files.map((f) => f.name), ['inside.pdf'], 'a restored folder brings its files back');
});

test('Previous Manual Record: only the CO\'s own congregations can be chosen', async () => {
  const res = await j(await call('co1', 'GET', '/manual/congregations'));
  assert.deepEqual(res.congregations.map((c) => c.name), ['Alpha Congregation', 'Zamora Congregation']);
  assert.deepEqual((await j(await call('co2', 'GET', '/manual/congregations'))).congregations.map((c) => c.id), ['cC']);
  assert.equal((await j(await call('sa', 'GET', '/manual/congregations'))).congregations.length, 3);
});

const cellText = (snap, r, c) => snap.cells.find((x) => x[0] === r && x[1] === c)?.[2];

test('Excel: the preview lists the worksheets and shows one as it looks; bad files are refused', async () => {
  const book = workbook({ January: visits, February: [['Name', 'Hours'], ['Ana', 3]] });
  const pv = await j(await call('co1', 'POST', '/manual/preview?name=visits.xlsx', { body: book, type: 'application/octet-stream' }));
  assert.deepEqual(pv.sheets.map((s) => s.name), ['January', 'February']);
  assert.equal(pv.sheet.name, 'January');
  assert.equal(cellText(pv.sheet.snapshot, 1, 0), 'Maria Santos');
  assert.equal(cellText(pv.sheet.snapshot, 1, 1), '12');
  assert.equal(pv.sheet.snapshot.rows, 6, 'empty rows are kept, the sheet looks like the original');
  const feb = await j(await call('co1', 'POST', '/manual/preview?name=visits.xlsx&sheet=1', { body: book }));
  assert.equal(feb.sheet.name, 'February');
  assert.equal((await call('co1', 'POST', '/manual/preview?name=notes.xlsx', { body: Buffer.from('plain text, not a workbook') })).status, 400, 'content must match the type');
  assert.equal((await call('co1', 'POST', '/manual/preview?name=run.exe', { body: book })).status, 400);
  assert.equal((await call('co1', 'POST', '/manual/preview?name=visits.xlsx', {})).status, 400, 'no file');
  const evil = workbook({ S: [['Name', 'Note'], ['=HYPERLINK("http://evil","x")', '=1+1']] });
  const ev = await j(await call('co1', 'POST', '/manual/preview?name=evil.xlsx', { body: evil }));
  assert.equal(cellText(ev.sheet.snapshot, 1, 0).startsWith('='), true, 'a formula-looking text is kept as plain text, never calculated');
});

test('Excel: merged cells, column widths and row heights are copied from the file, whatever its layout', async () => {
  const ws = XLSX.utils.aoa_to_sheet([['TITLE OF THE RECORD', '', '', ''], ['Name', 'A', 'B', 'C'], ['Row', 1, 2, 3]]);
  ws['!merges'] = [{ s: { r: 0, c: 0 }, e: { r: 0, c: 3 } }];
  ws['!cols'] = [{ wpx: 150 }, { wpx: 40 }, { wpx: 40 }, { hidden: true }];
  ws['!rows'] = [{ hpx: 44 }];
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'Layout');
  const pv = await j(await call('co1', 'POST', '/manual/preview?name=layout.xlsx', { body: XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' }) }));
  const s = pv.sheet.snapshot;
  assert.deepEqual(s.merges, [[0, 0, 0, 3]]);
  assert.equal(s.colWidths[0], 150);
  assert.equal(s.colWidths[3], 0, 'a hidden column stays hidden');
  assert.equal(s.rowHeights[0], 44);
  assert.equal(cellText(s, 0, 0), 'TITLE OF THE RECORD');
});

test('the summary reads the formulas of the file and checks them without calculating anything', async () => {
  const ws = XLSX.utils.aoa_to_sheet([['Report'], ['Name', 'Hours'], ['A', 10], ['B', 20], ['C', 30], ['Total', 0]]);
  ws.B6 = { t: 'n', v: 55, f: 'SUM(B3:B5)' }; // the saved total is wrong on purpose: 10+20+30 = 60
  ws['!ref'] = 'A1:B6';
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'S');
  const pv = await j(await call('co1', 'POST', '/manual/preview?name=sum.xlsx', { body: XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' }) }));
  const sum = pv.summary;
  assert.equal(sum.title, 'Report');
  assert.deepEqual(sum.functions, { SUM: 1 });
  assert.equal(sum.formulaCount, 1);
  assert.deepEqual(sum.formulas[0], { cell: 'B6', formula: 'SUM(B3:B5)', shows: '55' });
  const hours = sum.columns.find((c) => c.column === 'B');
  assert.deepEqual([hours.count, hours.sum, hours.average, hours.label], [3, 60, 20, 'Hours'], 'the total row itself is not counted twice');
  assert.ok(sum.notes.some((n) => n.includes('B6') && n.includes('60')), 'a total that does not match its own cells is reported');
});

test('Excel import: the congregation is required and checked on the server; the remarks are optional', async () => {
  const book = workbook({ Visits: visits });
  const q = (extra) => `/manual/import?name=visits.xlsx${extra}`;
  assert.equal((await call('co1', 'POST', q(''), { body: book })).status, 400, 'no congregation');
  assert.equal((await call('co1', 'POST', q('&congregationId=cC'), { body: book })).status, 400, 'a congregation that is not theirs');
  assert.equal((await call('co2', 'POST', q('&congregationId=cB'), { body: book })).status, 400, 'the other CO cannot import for it either');
  assert.equal((await call('elder', 'POST', q('&congregationId=cA'), { body: book })).status, 403);
  assert.equal((await j(await call('co1', 'GET', '/manual/imports'))).imports.length, 0, 'nothing was saved by the refused attempts');
});

test('Save: the whole file is kept with all its worksheets, listed by congregation, searchable inside, and duplicates are refused', async () => {
  const book = workbook({ Visits: visits, Other: [['Name', 'Hours'], ['Ana Lopez', 3]] });
  const pause = () => new Promise((r) => setTimeout(r, 8));
  const ok = await call('co1', 'POST', '/manual/import?name=visits.xlsx&congregationId=cA&remarks=First%20visit%20of%20the%20year', { body: book });
  assert.equal(ok.status, 201);
  const first = await j(ok);
  assert.deepEqual(first.sheets.map((s) => s.name), ['Visits', 'Other'], 'every worksheet is saved');
  assert.equal(first.remarks, 'First visit of the year');
  const dup = await call('co1', 'POST', '/manual/import?name=visits.xlsx&congregationId=cA&remarks=another', { body: book });
  assert.equal(dup.status, 409, 'the same file for the same congregation is a duplicate, whatever the remarks');
  assert.equal((await j(dup)).duplicate, true);
  await pause();
  assert.equal((await call('co1', 'POST', '/manual/import?name=visits.xlsx&congregationId=cA&remarks=Again&allowDuplicate=1', { body: book })).status, 201, 'on purpose it may be repeated');
  const onlyOther = await j(await call('co1', 'POST', '/manual/import?name=a.xlsx&congregationId=cB&sheets=1', { body: book }));
  assert.deepEqual(onlyOther.sheets.map((s) => s.name), ['Other'], 'a chosen worksheet only');
  assert.equal((await call('co1', 'POST', '/manual/import?name=a.xlsx&congregationId=cB&sheets=9', { body: book })).status, 400, 'no such worksheet');
  await pause();
  const xls = workbook({ Visits: [['Publisher', 'Hours'], ['Old Format', 1]] }, 'biff8');
  assert.equal((await call('co1', 'POST', '/manual/import?name=old.xls&congregationId=cB&remarks=Old%20format', { body: xls })).status, 201);
  await pause();
  assert.equal((await call('co1', 'POST', '/manual/import?name=list.csv&congregationId=cB&remarks=Csv%20list', { body: Buffer.from('Publisher,Hours\nCsv Person,2\n') })).status, 201);
  const long = await j(await call('co1', 'POST', `/manual/import?name=l.csv&congregationId=cB&remarks=${'x'.repeat(900)}`, { body: Buffer.from('A\nlong remark\n') }));
  assert.equal(long.remarks.length, 500, 'remarks are cut at 500 characters');

  const all = (await j(await call('co1', 'GET', '/manual/imports'))).imports;
  assert.equal(all.length, 6);
  assert.ok(all.every((i) => i.fileName && Array.isArray(i.sheets) && !('contentHash' in i)));
  const names = all.map((i) => i.congregationName);
  assert.deepEqual(names, [...names].sort((a, b) => a.localeCompare(b)), 'congregation A-Z');
  const alpha = all.filter((i) => i.congregationName === 'Alpha Congregation');
  assert.equal(alpha[0].remarks, 'x'.repeat(500), 'within a congregation the newest import comes first');
  assert.equal(alpha[1].remarks, 'Csv list');
  assert.equal(all.find((i) => i.congregationName === 'Zamora Congregation').remarks, 'Again');

  // search: which saved files and worksheets contain the text
  const hit = async (q) => (await j(await call('co1', 'GET', `/manual/imports?q=${encodeURIComponent(q)}`))).imports;
  const reyes = await hit('REYES');
  assert.equal(reyes.length, 2, 'any case, partial words, found in the cells of both saves for Zamora');
  assert.deepEqual(reyes[0].sheets.map((s) => [s.name, s.hit]), [['Visits', true], ['Other', false]], 'and it says WHICH worksheet holds it');
  assert.equal((await hit('reyes bible')).length, 2, 'every word must match');
  assert.equal((await hit('ana lopez')).length, 3, 'the other worksheet, in all three saves that kept it');
  assert.equal((await hit('alpha csv')).length, 2, 'the congregation name, the file name and the remarks are searched too');
  assert.equal((await hit('first visit')).length, 1);
  assert.equal((await hit('nothing-like-this')).length, 0);
  assert.equal((await hit('100%')).length, 0, 'LIKE wildcards are plain text');
  assert.equal((await j(await call('co1', 'GET', '/manual/imports?congregationId=cA'))).imports.length, 2, 'filter by congregation');
  assert.equal((await j(await call('co1', 'GET', '/manual/imports?congregationId=cC'))).imports.length, 0, 'a congregation that is not theirs returns nothing');

  // a saved sheet opens again as it was
  const one = all.find((i) => i.fileName === 'old.xls');
  const opened = await j(await call('co1', 'GET', `/manual/imports/${one.id}/sheets/0`));
  assert.equal(cellText(opened.sheet.snapshot, 1, 0), 'Old Format');
  assert.ok(opened.summary && opened.import.remarks === 'Old format');
  assert.equal((await call('co1', 'GET', `/manual/imports/${one.id}/sheets/5`)).status, 404);
  // privacy
  assert.equal((await j(await call('co2', 'GET', '/manual/imports'))).imports.length, 0);
  assert.equal((await call('co2', 'GET', `/manual/imports/${one.id}/sheets/0`)).status, 404);
  assert.equal((await call('co2', 'DELETE', `/manual/imports/${all[0].id}`)).status, 404);
  assert.equal((await call('co1', 'DELETE', `/manual/imports/${all[0].id}`)).status, 200);
  assert.equal((await j(await call('co1', 'GET', '/manual/imports'))).imports.length, 5);
});

test('a saved worksheet is edited in place and saved; so are the remarks of its file; nobody else can', async () => {
  const book = workbook({ Sheet1: [['Name', 'Hours'], ['Zork Zed', 3], ['Leave Me', 4]] });
  const ok = await j(await call('co1', 'POST', '/manual/import?name=edit.xlsx&congregationId=cB&remarks=Before', { body: book }));
  const path = `/manual/imports/${ok.importId}/sheets/0`;
  const edit = (who, edits) => call(who, 'PATCH', path, { json: { edits } });
  const res = await edit('co1', [{ r: 1, c: 0, v: 'Edited Name' }, { r: 1, c: 1, v: '9' }, { r: 5, c: 3, v: 'new far cell' }]);
  assert.equal(res.status, 200);
  const done = await j(res);
  assert.equal(done.changed, 3);
  const after = await j(await call('co1', 'GET', path));
  assert.equal(cellText(after.sheet.snapshot, 1, 0), 'Edited Name');
  assert.equal(cellText(after.sheet.snapshot, 2, 0), 'Leave Me', 'the other cells are untouched');
  assert.equal(after.sheet.snapshot.rows, 6, 'the sheet grows to hold a new cell');
  assert.equal((await j(await call('co1', 'GET', '/manual/imports?q=edited%20name'))).imports.length, 1, 'the new value is searchable');
  assert.equal((await j(await call('co1', 'GET', '/manual/imports?q=zork'))).imports.length, 0, 'and the old value is gone');
  assert.equal((await edit('co1', [])).status, 400);
  assert.equal((await edit('co1', [{ r: -1, c: 0, v: 'x' }])).status, 400);
  assert.equal((await edit('co1', [{ r: 0, c: 0, v: 5 }])).status, 400, 'values are text');
  assert.equal((await edit('co1', [{ r: 99999, c: 0, v: 'x' }])).status, 400);
  assert.equal((await edit('co2', [{ r: 0, c: 0, v: 'Stolen' }])).status, 404, 'another Circuit Overseer');
  assert.equal((await edit('elder', [{ r: 0, c: 0, v: 'x' }])).status, 403);
  const long = await j(await edit('co1', [{ r: 1, c: 0, v: 'y'.repeat(2000) }]));
  assert.equal(cellText(long.sheet.snapshot, 1, 0).length, 1000, 'a value is cut at 1000 characters');
  const rem = await call('co1', 'PATCH', `/manual/imports/${ok.importId}`, { json: { remarks: '  After the visit  ' } });
  assert.equal((await j(rem)).remarks, 'After the visit');
  assert.equal((await j(await call('co1', 'GET', '/manual/imports?q=after%20the%20visit'))).imports.length, 1, 'remarks are searched too');
  assert.equal((await call('co2', 'PATCH', `/manual/imports/${ok.importId}`, { json: { remarks: 'x' } })).status, 404);
  await call('co1', 'DELETE', `/manual/imports/${ok.importId}`);
});

test('a congregation taken off the grant is no longer shown, and an import can reuse a File Manager file', async () => {
  const book = workbook({ Sheet1: [['Name', 'Hours'], ['From manager', 7]] });
  const stored = await j(await upload('co1', 'kept visits.xlsx', book));
  const before = (await j(await call('co1', 'GET', '/manual/imports'))).imports.length;
  const imp = await call('co1', 'POST', `/manual/import?fileId=${stored.id}&congregationId=cB`);
  assert.equal(imp.status, 201);
  assert.equal((await j(await call('co1', 'GET', '/manual/imports'))).imports.length, before + 1);
  assert.equal((await call('co1', 'GET', `/files/${stored.id}/download`)).status, 200, 'the original stays in the File Manager');
  assert.equal((await call('co2', 'POST', `/manual/import?fileId=${stored.id}&congregationId=cC`)).status, 404, 'only the owner can use a stored file');
  const pdf = await j(await upload('co1', 'not-excel.pdf', PDF, '', 'application/pdf'));
  assert.equal((await call('co1', 'POST', `/manual/import?fileId=${pdf.id}&congregationId=cB`)).status, 400);
  await put('userAccessGrants', 'co1', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['cB'], circuitCode: 'NT01' });
  assert.deepEqual((await j(await call('co1', 'GET', '/manual/congregations'))).congregations.map((c) => c.id), ['cB']);
  const rows = (await j(await call('co1', 'GET', '/manual/imports'))).imports;
  assert.ok(rows.every((r) => r.congregationId === 'cB'), 'files of a congregation no longer on the grant are hidden');
});

test('a failed import leaves nothing behind', async () => {
  const original = store.co_addImport.bind(store);
  const countBefore = (await store.co_listImports('co1')).length;
  store.co_addImport = async () => { throw new Error('disk full'); };
  const res = await call('co1', 'POST', '/manual/import?name=v.xlsx&congregationId=cB', { body: workbook({ S: visits }) });
  store.co_addImport = original;
  assert.equal(res.status, 500);
  assert.equal((await store.co_listImports('co1')).length, countBefore);
});

const REAL = 'C:/Users/Ephraim/Desktop/CO Visit Records NT01 for ephraim.xlsx';
test('a real CO workbook keeps its layout and is summarised (skipped when the sample file is not on this computer)', { skip: !existsSync(REAL) }, async () => {
  const pv = await j(await call('co1', 'POST', '/manual/preview?name=real.xlsx&sheet=1', { body: readFileSync(REAL) }));
  assert.equal(pv.sheets.length, 3);
  assert.ok(pv.sheet.snapshot.merges.length > 5 && pv.sheet.snapshot.styles.length > 3, 'merged cells and colours are copied');
  assert.ok(pv.summary.functions.SUM > 20 && pv.summary.notes.length > 0);
});


test('important actions are written to the audit log', async () => {
  const logs = await store.list('auditLog');
  const actions = new Set(logs.map((l) => l.data.action));
  for (const a of ['CO_FOLDER_CREATE', 'CO_FILE_UPLOAD', 'CO_FILE_DELETE', 'CO_FILE_PURGE', 'CO_MANUAL_IMPORT', 'CO_MANUAL_DELETE']) assert.ok(actions.has(a), a);
});

// ---- the Super-Admin's read-only view of any Circuit Overseer's files --------------------------------------------------

const adm = (who, method, path, opts = {}) => fetch(`${base}/v1/admin${path}`, { method, headers: { 'x-dev-person': who, ...(opts.json ? { 'content-type': 'application/json' } : {}) }, body: opts.json ? JSON.stringify(opts.json) : opts.body });

test('Super-Admin: lists every Circuit Overseer account and browses one CO\'s files read-only, never mixing COs', async () => {
  const mine = await (await upload('co1', 'co1-report.pdf', PDF, '', 'application/pdf')).json();
  await upload('co2', 'co2-secret.pdf', PDF, '', 'application/pdf');
  const folder = await mkfolder('co1', 'Reports 2026');

  // the CO list: Super-Admin only
  const list = await adm('sa', 'GET', '/co-accounts');
  assert.equal(list.status, 200);
  const accounts = (await list.json()).accounts;
  assert.deepEqual(accounts.map((a) => [a.id, a.circuitCode]), [['co1', 'NT01'], ['co2', 'NT02']], 'only Circuit Overseer accounts, in circuit order');
  assert.ok(accounts.find((a) => a.id === 'co1').fileCount >= 1);
  for (const who of ['co1', 'elder', 'nobody']) assert.equal((await adm(who, 'GET', '/co-accounts')).status, 403, `${who} may not list the accounts`);
  assert.equal((await fetch(`${base}/v1/admin/co-accounts`)).status, 401);

  // browsing: only that CO's files, each CO separately
  const one = await (await adm('sa', 'GET', '/co-files/co1/list')).json();
  assert.ok(one.files.some((f) => f.name === 'co1-report.pdf') && !one.files.some((f) => f.name === 'co2-secret.pdf'), 'co1 sees co1 files only');
  assert.ok(one.folders.some((f) => f.name === 'Reports 2026'));
  const two = await (await adm('sa', 'GET', '/co-files/co2/list')).json();
  assert.ok(two.files.some((f) => f.name === 'co2-secret.pdf') && !two.files.some((f) => f.name === 'co1-report.pdf'), 'co2 sees co2 files only');
  assert.equal((await (await adm('sa', 'GET', '/co-files/co1/search?q=secret')).json()).total, 0, 'search stays inside the chosen CO');
  assert.equal((await (await adm('sa', 'GET', '/co-files/co2/search?q=secret')).json()).total, 1);
  assert.equal((await (await adm('sa', 'GET', '/co-files/co1/tree')).json()).folders.length >= 1, true);
  assert.equal((await adm('sa', 'GET', `/co-files/co2/${mine.id}`)).status, 404, 'a file of co1 is not reachable through co2');
  const dl = await adm('sa', 'GET', `/co-files/co1/${mine.id}/download`);
  assert.equal(dl.status, 200);
  assert.deepEqual(Buffer.from(await dl.arrayBuffer()), PDF, 'the bytes come back unchanged');
  assert.equal((await adm('sa', 'GET', `/co-files/co1/${mine.id}/download?inline=1`)).headers.get('content-type'), 'application/pdf');

  // read-only, and only for the Super-Admin
  for (const [m, p, json] of [['POST', '/co-files/co1/folders', { name: 'x' }], ['PATCH', `/co-files/co1/${mine.id}`, { name: 'x.pdf' }], ['DELETE', `/co-files/co1/${mine.id}`], ['DELETE', `/co-files/co1/folders/${folder.id}`]]) {
    assert.equal((await adm('sa', m, p, { json })).status, 403, `${m} ${p}`);
  }
  assert.equal((await adm('co2', 'GET', '/co-files/co1/list')).status, 403, 'a CO never reaches another CO\'s files this way');
  assert.equal((await adm('co1', 'GET', '/co-files/co1/list')).status, 403, 'not even their own through the admin door');
  assert.equal((await adm('elder', 'GET', '/co-files/co1/list')).status, 403);
  assert.equal((await adm('sa', 'GET', '/co-files/elder/list')).status, 404, 'a person who is not a Circuit Overseer');
  assert.equal((await adm('sa', 'GET', '/co-files/nobodyxx/list')).status, 404);
  assert.equal((await call('co1', 'GET', '/files/list').then(j)).files.length >= 1, true, 'the CO still sees their own files');
  const after = await (await adm('sa', 'GET', '/co-files/co1/list')).json();
  assert.deepEqual(after.files.map((f) => f.name), one.files.map((f) => f.name), 'nothing was changed');

  // opening / downloading a file is audited with the Super-Admin as the actor
  const logs = (await store.list('auditLog')).map((l) => l.data).filter((l) => l.action === 'SUPER_CO_FILE_VIEW');
  assert.ok(logs.length >= 2 && logs.every((l) => l.actorPersonId === 'sa'));
  assert.ok(logs.some((l) => /download/.test(l.details)));
  assert.equal((await store.list('auditLog')).map((l) => l.data).filter((l) => l.action === 'SUPER_CO_FILE_VIEW' && /list|tree|search/.test(l.targetId ?? '')).length, 0, 'listing is not logged as a file view');
});

test('Comparative report: built from the saved worksheet, only for its owner, never changing the file', async () => {
  const rows = [['Indicator', 'Jan-Jun 2026', 'Jul-Oct 2026'], ['Publishers', 80, 84], ['Regular pioneers', 12, 15], ['Bible studies', 40, 31]];
  const book = workbook({ Visit: rows, Names: [['Name', 'Hours'], ['Ana', 3], ['Ben', 4], ['Cora', 5]] });
  const pv = await j(await call('co1', 'POST', '/manual/preview?name=compare.xlsx', { body: book }));
  assert.equal(pv.comparison.available, true, 'a draft preview already carries its recommended report');
  assert.equal(pv.comparison.report.scope.fileName, 'compare.xlsx');
  const cong = (await j(await call('co1', 'GET', '/manual/congregations'))).congregations[0];
  const saved = await call('co1', 'POST', `/manual/import?name=compare.xlsx&congregationId=${cong.id}&remarks=visit`, { body: book });
  assert.equal(saved.status, 201, JSON.stringify(await saved.clone().json().catch(() => ({}))));
  const imp = (await j(saved)).import ?? (await j(await call('co1', 'GET', '/manual/imports'))).imports.find((i) => i.fileName === 'compare.xlsx');
  const one = await j(await call('co1', 'GET', `/manual/imports/${imp.id}/sheets/0`));
  const g = one.comparison.groups[0];
  assert.equal(one.comparison.report.scope.congregation, cong.name);
  assert.equal(one.comparison.report.table.find((r) => r.label === 'Regular pioneers').percent, 25);
  const swapped = await j(await call('co1', 'GET', `/manual/imports/${imp.id}/sheets/0/compare?group=${g.id}&a=${g.columns[1].key}&b=${g.columns[0].key}`));
  assert.equal(swapped.report.table.find((r) => r.label === 'Publishers').difference, -4);
  assert.equal((await call('co1', 'GET', `/manual/imports/${imp.id}/sheets/0/compare?group=${g.id}&a=1&b=1`)).status, 400);
  assert.equal((await call('co1', 'GET', `/manual/imports/${imp.id}/sheets/0/compare?group=nope&a=1&b=2`)).status, 400);
  const names = await j(await call('co1', 'GET', `/manual/imports/${imp.id}/sheets/1`));
  assert.equal(names.comparison.available, false);
  assert.match(names.comparison.message, /No comparable records were found/);
  // another Circuit Overseer, an elder and a guessed id get nothing
  assert.equal((await call('co2', 'GET', `/manual/imports/${imp.id}/sheets/0/compare?group=${g.id}&a=1&b=2`)).status, 404);
  assert.equal((await call('elder', 'GET', `/manual/imports/${imp.id}/sheets/0/compare?group=${g.id}&a=1&b=2`)).status, 403);
  assert.equal((await call('co1', 'GET', '/manual/imports/zzzzzzzzzzzz/sheets/0/compare?group=g1&a=1&b=2')).status, 404);
  // the saved cells are exactly as imported
  assert.equal(cellText(one.sheet.snapshot, 1, 1), '80');
  assert.equal(cellText((await j(await call('co1', 'GET', `/manual/imports/${imp.id}/sheets/0`))).sheet.snapshot, 1, 1), '80');
});

test('Comparative report across two saved files: both must be the caller\'s own', async () => {
  const mk = (n) => workbook({ S: [['Indicator', 'Count'], ['Publishers', n], ['Regular pioneers', 10]] });
  const cong = (await j(await call('co1', 'GET', '/manual/congregations'))).congregations[0];
  await call('co1', 'POST', `/manual/import?name=march.xlsx&congregationId=${cong.id}&remarks=a`, { body: mk(50) });
  await call('co1', 'POST', `/manual/import?name=sept.xlsx&congregationId=${cong.id}&remarks=b`, { body: mk(56) });
  const list = (await j(await call('co1', 'GET', '/manual/imports'))).imports;
  const a = list.find((i) => i.fileName === 'march.xlsx'), b = list.find((i) => i.fileName === 'sept.xlsx');
  const r = await j(await call('co1', 'GET', `/manual/compare-imports?a=${a.id}&b=${b.id}`));
  assert.equal(r.report.table.find((x) => x.label === 'Publishers').percent, 12);
  assert.equal(r.report.scope.congregation, cong.name);
  assert.equal((await call('co2', 'GET', `/manual/compare-imports?a=${a.id}&b=${b.id}`)).status, 404);
  assert.equal((await call('co1', 'GET', `/manual/compare-imports?a=${a.id}&b=${a.id}`)).status, 404);
  assert.equal((await call('co1', 'GET', `/manual/compare-imports?a=${a.id}&b=nope`)).status, 404);
  assert.equal((await call('elder', 'GET', `/manual/compare-imports?a=${a.id}&b=${b.id}`)).status, 403);
});
