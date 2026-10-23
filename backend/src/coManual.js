import express from 'express';
import { createHash } from 'node:crypto';
import { MAX_EXCEL_BYTES } from './excel.js';
import { readSnapshots, searchTextOf, applyEdits, summarySnapshot, LIMITS } from './sheetSnapshot.js';
import { comparisonFor, reportFor, reportForFiles } from './sheetCompare.js';
import { coAccess, allowedCongregations, newId, ID_RE, audit, jsonErrors } from './coAccess.js';

/**
 * The Circuit Overseer's Previous Manual Record: an old Excel record is imported and SAVED as it looks (every worksheet keeps its own layout: merged cells,
 * colours, widths, title rows), so it can be opened any time, searched, edited and summarised.
 *
 *   GET    /v1/co/manual/congregations                    the congregations this Circuit Overseer may import for
 *   POST   /v1/co/manual/preview?name=&sheet=[&fileId=]   the worksheets and one of them as it will look, nothing is saved
 *   POST   /v1/co/manual/import?name=&congregationId=[&sheets=0,2][&remarks=][&fileId=][&allowDuplicate=1]   saves the whole file (or the chosen worksheets)
 *   GET    /v1/co/manual/imports?q=&congregationId=       saved files by congregation A-Z, newest first; with q, each worksheet says whether it contains the text
 *   GET    /v1/co/manual/imports/:id/sheets/:index        one saved worksheet as { snapshot, summary }
 *   PATCH  /v1/co/manual/imports/:id/sheets/:index { edits: [{ r, c, v }] }   change cells and save
 *   PATCH  /v1/co/manual/imports/:id { remarks }          DELETE /v1/co/manual/imports/:id
 *
 * The workbook is sent as the raw request body, or - to reuse a file kept in the File Manager - named by `fileId` (it must be the caller's own).
 * An import is all-or-nothing (one database transaction). Importing the same file again for the same congregation is refused unless `allowDuplicate=1`.
 * Every route only sees the caller's own imports, and only for congregations that are still on their grant. Formulas are never calculated.
 */
const MAX_EDITS = 500;

/** The free-text remarks the Circuit Overseer types for an import (optional, at most 500 characters). */
export const cleanRemarks = (v) => String(v ?? '').replace(/[\u0000-\u001f]+/g, ' ').trim().slice(0, 500);

/** The words of a search box, lower-cased (at most 6). */
export const searchWords = (q) => String(q ?? '').toLowerCase().split(/\s+/).filter(Boolean).slice(0, 6).map((w) => w.slice(0, 60));

export function mountCoManual(app, store, auth) {
  const router = express.Router();
  const raw = express.raw({ type: () => true, limit: MAX_EXCEL_BYTES + 1024 });
  const json = express.json({ limit: '2mb' });

  const guard = (handler) => async (req, res) => {
    try {
      const access = await coAccess(store, req.personId);
      if (!access.allowed) return res.status(403).json({ error: 'The Previous Manual Record is for the Circuit Overseer' });
      const congregations = await allowedCongregations(store, access);
      return await handler(req, res, { owner: req.personId, access, congregations, ids: congregations.map((c) => c.id) });
    } catch (e) {
      console.error('co manual failed', req.method, req.path, e);
      return res.status(500).json({ error: 'Server error' });
    }
  };

  /** The workbook bytes and name of a request: a File Manager file of the caller, or the raw body. */
  async function source(req, ctx) {
    const fileId = String(req.query.fileId ?? '');
    if (fileId) {
      if (!ID_RE.test(fileId)) return { error: 'File not found', status: 404 };
      const file = (await store.co_listFiles(ctx.owner)).find((f) => f.id === fileId && f.deletedAt == null);
      if (!file) return { error: 'File not found', status: 404 };
      if (!['xlsx', 'xls', 'csv'].includes(file.ext)) return { error: 'Only Excel (.xlsx, .xls) and .csv files can be imported', status: 400 };
      const blob = await store.getFileByPath(`co-files/${ctx.owner}/${file.id}`);
      if (!blob) return { error: 'The stored file is missing', status: 404 };
      return { name: file.name, data: blob.data };
    }
    if (!Buffer.isBuffer(req.body) || req.body.length === 0) return { error: 'Choose an Excel file first', status: 400 };
    return { name: String(req.query.name ?? ''), data: req.body };
  }

  /** A saved import the caller may open: it exists, is theirs and its congregation is still on their grant. */
  async function ownImport(req, ctx) {
    const imp = ID_RE.test(req.params.id) ? await store.co_getImport(ctx.owner, req.params.id) : null;
    return imp && ctx.ids.includes(imp.congregationId) ? imp : null;
  }

  const publicImport = ({ contentHash, ...i }) => i;
  /** What the comparison needs to name its source: the file, its congregation and worksheet, and the congregations this caller may see. */
  const compareCtx = (ctx, imp, sheetName) => ({ fileName: imp?.fileName ?? '', congregationName: imp?.congregationName ?? '', sheetName: sheetName ?? '', congregations: ctx.congregations.map((c) => c.name) });

  router.get('/congregations', auth, guard(async (req, res, ctx) => res.json({ congregations: ctx.congregations })));

  router.post('/preview', auth, raw, guard(async (req, res, ctx) => {
    const src = await source(req, ctx);
    if (src.error) return res.status(src.status).json({ error: src.error });
    const book = readSnapshots(src.name, src.data);
    if (!book.ok) return res.status(400).json({ error: book.reason });
    const wanted = Number.parseInt(req.query.sheet, 10);
    const index = Number.isInteger(wanted) && wanted >= 0 && wanted < book.sheets.length ? wanted : 0;
    const s = book.sheets[index];
    res.json({
      fileName: src.name, truncated: book.truncated,
      sheets: book.sheets.map((b, i) => ({ index: i, name: b.name, rows: b.snapshot.rows, cols: b.snapshot.cols })),
      sheet: { index, name: s.name, snapshot: s.snapshot }, summary: summarySnapshot(s.snapshot),
      comparison: comparisonFor(s.snapshot, compareCtx(ctx, { fileName: src.name }, s.name)),
    });
  }));

  router.post('/import', auth, raw, guard(async (req, res, ctx) => {
    const congregation = ctx.congregations.find((c) => c.id === String(req.query.congregationId ?? ''));
    if (!congregation) return res.status(400).json({ error: 'Choose one of your congregations' });
    const remarks = cleanRemarks(req.query.remarks);
    const src = await source(req, ctx);
    if (src.error) return res.status(src.status).json({ error: src.error });
    const book = readSnapshots(src.name, src.data);
    if (!book.ok) return res.status(400).json({ error: book.reason });
    let chosen = book.sheets.map((s, i) => ({ ...s, index: i }));
    if (req.query.sheets) {
      const want = new Set(String(req.query.sheets).split(',').map((n) => Number.parseInt(n, 10)));
      chosen = chosen.filter((s) => want.has(s.index));
      if (!chosen.length) return res.status(400).json({ error: 'Choose at least one worksheet' });
    }
    const contentHash = createHash('sha256').update(src.data).update('\u0000').update(chosen.map((s) => s.index).join(',')).digest('hex');
    if (req.query.allowDuplicate !== '1') {
      const dup = await store.co_findImport(ctx.owner, { hash: contentHash, congregationId: congregation.id });
      if (dup) return res.status(409).json({ error: 'This file was already imported for this congregation', duplicate: true, importId: dup.id, importedAt: dup.createdAt });
    }
    const sheets = chosen.map((s, i) => ({ index: i, name: s.name.slice(0, 255), rows: s.snapshot.rows, cols: s.snapshot.cols, data: s.snapshot, search: searchTextOf(s.snapshot) }));
    const imp = { id: newId(), congregationId: congregation.id, congregationName: congregation.name, remarks, fileName: src.name.slice(0, 255), contentHash, createdAt: Date.now() };
    await store.co_addImport(ctx.owner, imp, sheets);
    await audit(store, ctx.owner, 'CO_MANUAL_IMPORT', 'CoManualImport', imp.id, `${imp.fileName}: ${sheets.length} worksheet(s) for ${imp.congregationName}`);
    res.status(201).json({ importId: imp.id, sheets: sheets.map(({ index, name, rows, cols }) => ({ index, name, rows, cols })), truncated: book.truncated, congregationName: imp.congregationName, remarks });
  }));

  router.get('/imports', auth, guard(async (req, res, ctx) => {
    const wanted = String(req.query.congregationId ?? '');
    const imports = (await store.co_listImports(ctx.owner)).filter((i) => ctx.ids.includes(i.congregationId) && (!wanted || i.congregationId === wanted))
      .sort((a, b) => a.congregationName.localeCompare(b.congregationName, undefined, { sensitivity: 'base' }) || b.createdAt - a.createdAt);
    const words = searchWords(req.query.q);
    if (!words.length) return res.json({ imports: imports.map(publicImport) });
    // a word may be in the congregation name, the remarks, the file name or a worksheet name (then every worksheet of the file counts) or in a worksheet's cells
    const found = [];
    for (const i of imports) {
      const meta = `${i.congregationName} ${i.remarks} ${i.fileName}`.toLowerCase();
      const rest = words.filter((w) => !meta.includes(w));
      const hits = rest.length ? await store.co_sheetsMatching(ctx.owner, [i.id], rest) : null;
      const sheets = i.sheets.map((s) => ({ ...s, hit: hits ? hits.has(`${i.id}:${s.index}`) || rest.every((w) => `${meta} ${s.name}`.toLowerCase().includes(w)) : true }));
      if (sheets.some((s) => s.hit)) found.push({ ...i, sheets });
    }
    res.json({ imports: found.map(publicImport) });
  }));

  router.get('/imports/:id/sheets/:index', auth, guard(async (req, res, ctx) => {
    const imp = await ownImport(req, ctx);
    const sheet = imp && /^\d{1,3}$/.test(req.params.index) ? await store.co_getSheet(ctx.owner, imp.id, req.params.index) : null;
    if (!sheet) return res.status(404).json({ error: 'Worksheet not found' });
    res.json({ import: publicImport(imp), sheet: { index: sheet.index, name: sheet.name, snapshot: sheet.data }, summary: summarySnapshot(sheet.data), comparison: comparisonFor(sheet.data, compareCtx(ctx, imp, sheet.name)) });
  }));

  /** The comparative report of a chosen group of columns and pair of them (the caller's own saved worksheet only). */
  router.get('/imports/:id/sheets/:index/compare', auth, guard(async (req, res, ctx) => {
    const imp = await ownImport(req, ctx);
    const sheet = imp && /^\d{1,3}$/.test(req.params.index) ? await store.co_getSheet(ctx.owner, imp.id, req.params.index) : null;
    if (!sheet) return res.status(404).json({ error: 'Worksheet not found' });
    const { group, a, b } = req.query;
    const report = reportFor(sheet.data, String(group ?? ''), String(a ?? ''), String(b ?? ''), compareCtx(ctx, imp, sheet.name));
    if (!report) return res.status(400).json({ error: 'Choose two different columns of the same group to compare' });
    res.json({ report });
  }));

  /** Two saved worksheets of the caller compared by indicator name (for example the records of two visits). */
  router.get('/compare-imports', auth, guard(async (req, res, ctx) => {
    const pick = async (id, index) => {
      const imp = ID_RE.test(String(id)) ? await store.co_getImport(ctx.owner, String(id)) : null;
      if (!imp || !ctx.ids.includes(imp.congregationId) || !/^\d{1,3}$/.test(String(index ?? '0'))) return null;
      const sheet = await store.co_getSheet(ctx.owner, imp.id, String(index ?? '0'));
      return sheet ? { imp, sheet } : null;
    };
    const [x, y] = await Promise.all([pick(req.query.a, req.query.sheetA), pick(req.query.b, req.query.sheetB)]);
    if (!x || !y || x.imp.id === y.imp.id && String(req.query.sheetA ?? '0') === String(req.query.sheetB ?? '0')) return res.status(404).json({ error: 'Choose two different saved worksheets' });
    const when = (i) => new Date(i.createdAt).toISOString().slice(0, 10);
    const side = ({ imp, sheet }) => ({ snapshot: sheet.data, label: `${imp.fileName} (saved ${when(imp)})`, fileName: imp.fileName, sheetName: sheet.name });
    const report = reportForFiles(side(x), side(y), { congregationName: x.imp.congregationId === y.imp.congregationId ? x.imp.congregationName : `${x.imp.congregationName} and ${y.imp.congregationName}`, sameCongregation: x.imp.congregationId === y.imp.congregationId });
    if (!report) return res.status(200).json({ report: null, message: 'No matching indicators were found in these two worksheets. Choose two files that report the same indicators.' });
    res.json({ report });
  }));

  /** Edit cells of a saved worksheet: every value is plain text (a typed value replaces a formula, nothing is ever calculated). */
  router.patch('/imports/:id/sheets/:index', auth, json, guard(async (req, res, ctx) => {
    const imp = await ownImport(req, ctx);
    const sheet = imp && /^\d{1,3}$/.test(req.params.index) ? await store.co_getSheet(ctx.owner, imp.id, req.params.index) : null;
    if (!sheet) return res.status(404).json({ error: 'Worksheet not found' });
    const edits = req.body?.edits;
    if (!Array.isArray(edits) || edits.length === 0 || edits.length > MAX_EDITS) return res.status(400).json({ error: `Send between 1 and ${MAX_EDITS} changed cells` });
    const snap = sheet.data;
    for (const e of edits) {
      if (!Number.isInteger(e?.r) || !Number.isInteger(e?.c) || e.r < 0 || e.c < 0 || e.r >= LIMITS.rows || e.c >= LIMITS.cols || typeof e.v !== 'string') return res.status(400).json({ error: 'A changed cell is not valid' });
    }
    const changed = applyEdits(snap, edits);
    snap.rows = Math.max(snap.rows, ...edits.map((e) => e.r + 1));
    snap.cols = Math.max(snap.cols, ...edits.map((e) => e.c + 1));
    while (snap.rowHeights.length < snap.rows) snap.rowHeights.push(20);
    while (snap.colWidths.length < snap.cols) snap.colWidths.push(64);
    await store.co_saveSheet(ctx.owner, imp.id, sheet.index, snap, searchTextOf(snap));
    await audit(store, ctx.owner, 'CO_MANUAL_EDIT', 'CoManualImport', imp.id, `${imp.fileName} / ${sheet.name}: ${changed} cell(s)`);
    res.json({ changed, sheet: { index: sheet.index, name: sheet.name, snapshot: snap }, summary: summarySnapshot(snap), comparison: comparisonFor(snap, compareCtx(ctx, imp, sheet.name)) });
  }));

  router.patch('/imports/:id', auth, json, guard(async (req, res, ctx) => {
    const imp = await ownImport(req, ctx);
    if (!imp) return res.status(404).json({ error: 'Import not found' });
    const remarks = cleanRemarks(req.body?.remarks);
    await store.co_updateImportRemarks(ctx.owner, imp.id, remarks);
    await audit(store, ctx.owner, 'CO_MANUAL_REMARKS', 'CoManualImport', imp.id, imp.fileName);
    res.json({ ok: true, remarks });
  }));

  router.delete('/imports/:id', auth, guard(async (req, res, ctx) => {
    const imp = await ownImport(req, ctx);
    if (!imp) return res.status(404).json({ error: 'Import not found' });
    await store.co_deleteImport(ctx.owner, imp.id);
    await audit(store, ctx.owner, 'CO_MANUAL_DELETE', 'CoManualImport', imp.id, `${imp.fileName} (${imp.sheets.length} worksheets)`);
    res.json({ ok: true });
  }));

  router.use(jsonErrors);
  app.use('/v1/co/manual', router);
}
