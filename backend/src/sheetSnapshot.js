import * as XLSX from 'xlsx';
import { inflateRawSync } from 'node:zlib';
import { excelKind, MAX_EXCEL_BYTES } from './excel.js';

/**
 * Reads a workbook (.xlsx / .xls / .csv) into SHEET SNAPSHOTS: each worksheet kept as it looks in Excel, so an imported record can look like the file it came from.
 *
 *   snapshot = {
 *     rows, cols,                       the size of the sheet (rows from 1, columns from A - empty spacer columns and title rows are kept)
 *     colWidths: [px ...], rowHeights: [px ...]   0 = hidden
 *     merges: [[r1, c1, r2, c2] ...]    zero-based, inclusive
 *     styles: [{ bg, fg, b, i, u, sz, ha, va, wrap, bt, bb, bl, br } ...]   style 0 is "plain"; a style only lists what is set
 *     cells: [[r, c, text, styleIndex] ...]   only cells with text, a fill or a border, in reading order
 *   }
 *
 * What a cell shows is the text Excel shows (dates, percentages and so on as formatted in the file). Formulas are never calculated and macros never loaded:
 * the last saved result of a formula is the text. Sizes are capped so a crafted workbook cannot exhaust memory.
 */
export const LIMITS = { rows: 3000, cols: 120, cells: 120000, cellChars: 1000, sheets: 30, formulas: 3000 };

const ZIP = (b) => b.length > 3 && b[0] === 0x50 && b[1] === 0x4b && (b[2] === 0x03 || b[2] === 0x05);
const unxml = (s) => String(s ?? '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n)).replace(/&amp;/g, '&');
const attr = (tag, name) => { const m = new RegExp(`(?:^|\\s)${name}="([^"]*)"`).exec(tag); return m ? unxml(m[1]) : null; };
const tidy = (v) => String(v ?? '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').replace(/\s+$/g, '').slice(0, LIMITS.cellChars);

/** The worksheet files of an .xlsx and the style number of every cell in them: { sheetName -> Map("B2" -> xfIndex) }. Nothing but plain text matching is done. */
function cellStyleIndexes(bytes) {
  const out = new Map();
  let files;
  try { files = unzipNeeded(bytes); } catch { return out; }
  const wbXml = files.get('xl/workbook.xml')?.toString('utf8'), relXml = files.get('xl/_rels/workbook.xml.rels')?.toString('utf8');
  if (!wbXml || !relXml) return out;
  const rels = new Map([...relXml.matchAll(/<Relationship\b[^>]*>/g)].map((m) => [attr(m[0], 'Id'), attr(m[0], 'Target')]));
  for (const m of wbXml.matchAll(/<sheet\b[^>]*>/g)) {
    const name = attr(m[0], 'name'), rid = attr(m[0], 'r:id');
    let target = rels.get(rid);
    if (!name || !target) continue;
    target = target.replace(/^\/?(xl\/)?/, 'xl/');
    const xml = files.get(target)?.toString('utf8');
    if (!xml) continue;
    const map = new Map();
    for (const c of xml.matchAll(/<c\b[^>]*>/g)) { const r = attr(c[0], 'r'), s = attr(c[0], 's'); if (r && s != null) map.set(r, Number(s)); }
    out.set(name, map);
  }
  return out;
}

/** Minimal zip reader (central directory + raw deflate) for the few XML files that carry the cell styles. */
function unzipNeeded(bytes) {
  let e = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) if (bytes.readUInt32LE(i) === 0x06054b50) { e = i; break; }
  if (e < 0) throw new Error('zip');
  const count = bytes.readUInt16LE(e + 10);
  let p = bytes.readUInt32LE(e + 16);
  const out = new Map();
  for (let n = 0; n < count && n < 4000; n++) {
    if (bytes.readUInt32LE(p) !== 0x02014b50) throw new Error('zip');
    const method = bytes.readUInt16LE(p + 10), csize = bytes.readUInt32LE(p + 20), usize = bytes.readUInt32LE(p + 24), nl = bytes.readUInt16LE(p + 28), xl = bytes.readUInt16LE(p + 30), cl = bytes.readUInt16LE(p + 32), lh = bytes.readUInt32LE(p + 42);
    const name = bytes.toString('utf8', p + 46, p + 46 + nl);
    p += 46 + nl + xl + cl;
    if (!/^xl\/(workbook\.xml|_rels\/workbook\.xml\.rels|worksheets\/[^/]+\.xml)$/.test(name) || usize > 80 * 1024 * 1024) continue;
    const dataStart = lh + 30 + bytes.readUInt16LE(lh + 26) + bytes.readUInt16LE(lh + 28), raw = bytes.subarray(dataStart, dataStart + csize);
    out.set(name, method === 0 ? raw : method === 8 ? inflateRawSync(raw, { maxOutputLength: 80 * 1024 * 1024 }) : null);
  }
  return out;
}

const hex6 = (color) => { const v = color && typeof color.rgb === 'string' ? color.rgb.slice(-6).toUpperCase() : null; return v && /^[0-9A-F]{6}$/.test(v) ? '#' + v : null; };

/** One style number of a workbook, resolved to plain colours and flags (fill, font, alignment, borders), de-duplicated per sheet. */
function styleResolver(book) {
  const S = book.Styles || {}, xfs = S.CellXf || [], fills = S.Fills || [], fonts = S.Fonts || [], borders = S.Borders || [];
  return (xfIndex, cell) => {
    const xf = xfs[xfIndex];
    const fill = xf ? fills[xf.fillId] : cell?.s;
    const font = xf ? fonts[xf.fontId] : null, border = xf ? borders[xf.borderId] : null, al = xf?.alignment || {};
    const st = {};
    if (fill && fill.patternType === 'solid') { const bg = hex6(fill.fgColor); if (bg && bg !== '#FFFFFF') st.bg = bg; }
    if (font) {
      const fg = hex6(font.color); if (fg && fg !== '#000000') st.fg = fg;
      if (font.bold) st.b = 1; if (font.italic) st.i = 1; if (font.underline || font.u) st.u = 1;
      if (font.sz && Math.round(font.sz) !== 11) st.sz = Math.round(font.sz);
    }
    if (al.horizontal && al.horizontal !== 'general') st.ha = al.horizontal === 'centerContinuous' ? 'center' : al.horizontal;
    if (al.vertical && al.vertical !== 'bottom') st.va = al.vertical;
    if (al.wrapText) st.wrap = 1;
    if (al.textRotation) st.rot = al.textRotation;
    if (border) { if (border.top?.style) st.bt = 1; if (border.bottom?.style) st.bb = 1; if (border.left?.style) st.bl = 1; if (border.right?.style) st.br = 1; }
    return st;
  };
}

/**
 * @returns {{ ok: true, sheets: Array<{ name: string, snapshot: object }>, truncated: boolean } | { ok: false, reason: string }}
 */
export function readSnapshots(name, data) {
  if (!Buffer.isBuffer(data) || data.length === 0) return { ok: false, reason: 'The file is empty' };
  if (data.length > MAX_EXCEL_BYTES) return { ok: false, reason: 'The file is larger than 10 MB' };
  const kind = excelKind(name, data);
  if (!kind) return { ok: false, reason: 'Choose an .xlsx, .xls or .csv file (the content must match the file type)' };
  let book;
  try {
    book = XLSX.read(data, { type: 'buffer', cellStyles: true, sheetStubs: true, cellFormula: true, cellHTML: false, bookVBA: false, cellDates: false, raw: kind === 'csv' });
  } catch {
    return { ok: false, reason: 'The file could not be read. It may be damaged or password-protected' };
  }
  const names = (book.SheetNames ?? []).filter((n) => book.Sheets[n]).slice(0, LIMITS.sheets);
  if (!names.length) return { ok: false, reason: 'The workbook has no worksheets' };
  const xfIndexes = kind === 'xlsx' && ZIP(data) ? cellStyleIndexes(data) : new Map();
  const resolve = styleResolver(book);
  let truncated = false;
  const sheets = [];
  for (const sheetName of names) {
    const ws = book.Sheets[sheetName];
    const ref = ws['!ref'] ? XLSX.utils.decode_range(ws['!ref']) : { s: { r: 0, c: 0 }, e: { r: 0, c: 0 } };
    const styles = [{}], styleKey = new Map([['{}', 0]]);
    const styleIndex = (st) => { const k = JSON.stringify(st); if (!styleKey.has(k)) { styleKey.set(k, styles.length); styles.push(st); } return styleKey.get(k); };
    const xf = xfIndexes.get(sheetName) ?? new Map();
    const cells = [], formulas = [];
    let lastRow = -1, lastCol = -1;
    for (const key of Object.keys(ws)) {
      if (key[0] === '!') continue;
      const { r, c } = XLSX.utils.decode_cell(key);
      if (r >= LIMITS.rows || c >= LIMITS.cols) { truncated = true; continue; }
      const cell = ws[key];
      const text = cell.t === 'z' ? '' : tidy(cell.w ?? cell.v ?? '');
      const st = resolve(xf.get(key), cell);
      const decorated = !!(st.bg || st.bt || st.bb || st.bl || st.br);
      if (text === '' && !decorated) continue;
      if (cells.length >= LIMITS.cells) { truncated = true; break; }
      cells.push([r, c, text, styleIndex(st)]);
      if (typeof cell.f === 'string' && cell.f && formulas.length < LIMITS.formulas) formulas.push([r, c, cell.f.slice(0, 200)]);
      if (r > lastRow) lastRow = r;
      if (c > lastCol) lastCol = c;
    }
    cells.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    const merges = (ws['!merges'] ?? []).map((m) => [m.s.r, m.s.c, Math.min(m.e.r, LIMITS.rows - 1), Math.min(m.e.c, LIMITS.cols - 1)]).filter((m) => m[0] < LIMITS.rows && m[1] < LIMITS.cols && (m[2] > m[0] || m[3] > m[1]));
    for (const m of merges) { if (m[2] > lastRow) lastRow = m[2]; if (m[3] > lastCol) lastCol = m[3]; }
    const rows = Math.max(1, lastRow + 1), cols = Math.max(1, lastCol + 1);
    const colWidths = Array.from({ length: cols }, (_, c) => { const d = ws['!cols']?.[c]; return d?.hidden ? 0 : Math.round(d?.wpx ?? 64); });
    const rowHeights = Array.from({ length: rows }, (_, r) => { const d = ws['!rows']?.[r]; return d?.hidden ? 0 : Math.round(d?.hpx ?? 20); });
    void ref;
    sheets.push({ name: sheetName, snapshot: { rows, cols, colWidths, rowHeights, merges, styles, cells, formulas } });
  }
  return { ok: true, sheets, truncated };
}

/** The lower-cased text of a whole sheet (every cell), for searching; capped. */
export const searchTextOf = (snapshot) => snapshot.cells.map((c) => c[2]).filter(Boolean).join(' ').toLowerCase().slice(0, 600_000);

/** Applies edits [{ r, c, v }] to a snapshot in place: a cell that exists changes its text, a new cell is plain. Returns how many cells changed. */
export function applyEdits(snapshot, edits) {
  const at = new Map(snapshot.cells.map((cell, i) => [`${cell[0]}:${cell[1]}`, i]));
  let changed = 0;
  for (const e of edits) {
    const text = tidy(e.v);
    const key = `${e.r}:${e.c}`;
    if (at.has(key)) {
      if (snapshot.cells[at.get(key)][2] !== text) {
        snapshot.cells[at.get(key)][2] = text;
        // a typed value replaces the formula of that cell
        if (snapshot.formulas) snapshot.formulas = snapshot.formulas.filter((f) => f[0] !== e.r || f[1] !== e.c);
        changed++;
      }
    } else if (text !== '') {
      snapshot.cells.push([e.r, e.c, text, 0]);
      at.set(key, snapshot.cells.length - 1);
      changed++;
    }
  }
  if (changed) snapshot.cells.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  return changed;
}

const NUM_RE = /^-?\d[\d,]*(\.\d+)?$|^-?\.\d+$/;
const numberOf = (t) => (NUM_RE.test(t.trim()) ? Number(t.replaceAll(',', '')) : null);
const colName = (c) => XLSX.utils.encode_col(c);
const round = (n) => Math.round(n * 100) / 100;

/**
 * A short summary of a sheet from its layout and data, for the "Summary" view: the title, how much is filled, the numeric columns (count / total / average),
 * the formulas the file uses and a check of them. Nothing is calculated into the sheet: a total is only COMPARED with the cells it says it adds up.
 * Computed on demand from the snapshot (not stored), so it is always current after an edit.
 */
export function summarySnapshot(snapshot) {
  const text = new Map(snapshot.cells.filter((c) => c[2] !== '').map((c) => [`${c[0]}:${c[1]}`, c[2]]));
  const formulaAt = new Map((snapshot.formulas ?? []).map(([r, c, f]) => [`${r}:${c}`, f]));
  const first = snapshot.cells.find((c) => c[2] !== '');
  const byCol = new Map();
  for (const [key, t] of text) {
    const [r, c] = key.split(':').map(Number);
    const n = numberOf(t);
    if (n === null) continue;
    if (!byCol.has(c)) byCol.set(c, []);
    byCol.get(c).push({ r, n, formula: formulaAt.has(key) });
  }
  const labelOf = (c, r0) => { for (let r = r0 - 1; r >= 0; r--) { const t = text.get(`${r}:${c}`); if (t && numberOf(t) === null) return t.slice(0, 60); } return colName(c); };
  const columns = [];
  for (const [c, list] of [...byCol].sort((a, b) => a[0] - b[0])) {
    const data = list.filter((x) => !x.formula);
    if (data.length < 3 || /#|\bno\.?$/i.test(labelOf(c, data[0].r))) continue;
    const sum = data.reduce((s, x) => s + x.n, 0);
    columns.push({ column: colName(c), label: labelOf(c, data[0].r), count: data.length, sum: round(sum), average: round(sum / data.length) });
    if (columns.length >= 16) break;
  }
  const functions = {};
  const notes = [];
  const sums = [];
  for (const [r, c, f] of snapshot.formulas ?? []) {
    const names = [...f.matchAll(/([A-Z][A-Z0-9.]*)\(/g)].map((m) => m[1]);
    for (const n of new Set(names.length ? names : ['(expression)'])) functions[n] = (functions[n] ?? 0) + 1;
    const m = /^\s*SUM\(\$?([A-Z]{1,3})\$?(\d+):\$?([A-Z]{1,3})\$?(\d+)\)\s*$/.exec(f);
    if (m && m[1] === m[3]) {
      const col = XLSX.utils.decode_col(m[1]), a = Number(m[2]) - 1, b = Number(m[4]) - 1;
      let total = 0;
      for (let i = a; i <= b; i++) total += numberOf(text.get(`${i}:${col}`) ?? '') ?? 0;
      const shown = numberOf(text.get(`${r}:${c}`) ?? '');
      sums.push({ r, c, f, a, b, col });
      if (shown !== null && Math.abs(shown - total) > 0.005 && notes.length < 12) notes.push(`${colName(c)}${r + 1} =${f} shows ${shown}, but the cells it adds up total ${round(total)}`);
    }
  }
  const rowsOfSums = new Map();
  for (const s of sums) { if (!rowsOfSums.has(s.r)) rowsOfSums.set(s.r, []); rowsOfSums.get(s.r).push(s); }
  for (const [r, list] of rowsOfSums) {
    if (list.length < 3) continue;
    const counts = new Map();
    for (const s of list) counts.set(`${s.a}-${s.b}`, (counts.get(`${s.a}-${s.b}`) ?? 0) + 1);
    const common = [...counts].sort((x, y) => y[1] - x[1])[0][0];
    for (const s of list) if (`${s.a}-${s.b}` !== common && notes.length < 12) notes.push(`${colName(s.c)}${r + 1} =${s.f} covers a different range than the other totals in row ${r + 1}`);
  }
  if ((functions.TODAY ?? 0) + (functions.NOW ?? 0) > 0) notes.push('Some formulas use today\'s date (for example ages); the file shows the value saved when it was last opened in Excel');
  return {
    title: first ? first[2].slice(0, 120) : '',
    rows: snapshot.rows, cols: snapshot.cols, filled: text.size, numeric: [...byCol.values()].reduce((s, l) => s + l.length, 0), merged: snapshot.merges.length,
    columns, formulaCount: formulaAt.size, functions, notes,
    formulas: (snapshot.formulas ?? []).slice(0, 60).map(([r, c, f]) => ({ cell: `${colName(c)}${r + 1}`, formula: f, shows: text.get(`${r}:${c}`) ?? '' })),
  };
}
