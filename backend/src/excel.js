import * as XLSX from 'xlsx';

/**
 * Reads an Excel workbook (.xlsx / .xls) or a .csv into plain text rows for the Previous Manual Record import.
 *
 * Nothing in the file is ever executed or evaluated: formulas are not parsed, macros (VBA) are not loaded, hyperlinks and styles are
 * ignored, and only the displayed text of each cell is kept. The size of the result is capped so a crafted workbook cannot exhaust memory.
 */
export const MAX_EXCEL_BYTES = 10 * 1024 * 1024;
export const MAX_ROWS = 20000;
export const MAX_COLUMNS = 60;
export const MAX_CELL_CHARS = 1000;

const ZIP = (b) => b.length > 3 && b[0] === 0x50 && b[1] === 0x4b && (b[2] === 0x03 || b[2] === 0x05);
const OLE = (b) => b.length > 7 && b[0] === 0xd0 && b[1] === 0xcf && b[2] === 0x11 && b[3] === 0xe0;

/** Which kind of workbook this is, from the file name and its first bytes (both must agree). */
export function excelKind(name, data) {
  const ext = String(name ?? '').includes('.') ? String(name).split('.').pop().toLowerCase() : '';
  if (ext === 'xlsx' && ZIP(data)) return 'xlsx';
  if (ext === 'xls' && OLE(data)) return 'xls';
  if (ext === 'csv' && !data.subarray(0, 8192).includes(0)) return 'csv';
  return null;
}

export const clean = (v) => String(v ?? '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').replace(/\s+$/g, '').slice(0, MAX_CELL_CHARS);

/**
 * @returns {{ ok: true, sheets: string[], sheet: string, headers: string[], rows: string[][], skippedEmpty: number, truncated: boolean }
 *          | { ok: false, reason: string }}
 * The first non-empty row is the header row. Empty rows are skipped; rows are padded or cut to the number of headers.
 */
export function readWorkbook(name, data, sheetName) {
  if (!Buffer.isBuffer(data) || data.length === 0) return { ok: false, reason: 'The file is empty' };
  if (data.length > MAX_EXCEL_BYTES) return { ok: false, reason: 'The file is larger than 10 MB' };
  const kind = excelKind(name, data);
  if (!kind) return { ok: false, reason: 'Choose an .xlsx, .xls or .csv file (the content must match the file type)' };
  let book;
  try {
    book = XLSX.read(data, { type: 'buffer', cellDates: true, cellFormula: false, cellHTML: false, cellStyles: false, bookVBA: false, sheetStubs: false, dense: true, raw: kind === 'csv' });
  } catch {
    return { ok: false, reason: 'The file could not be read. It may be damaged or password-protected' };
  }
  const sheets = (book.SheetNames ?? []).filter((n) => book.Sheets[n]);
  if (!sheets.length) return { ok: false, reason: 'The workbook has no worksheets' };
  const sheet = sheetName && sheets.includes(sheetName) ? sheetName : sheets[0];
  if (sheetName && !sheets.includes(sheetName)) return { ok: false, reason: `The worksheet "${String(sheetName).slice(0, 60)}" was not found` };
  let grid;
  try {
    grid = XLSX.utils.sheet_to_json(book.Sheets[sheet], { header: 1, raw: false, dateNF: 'yyyy-mm-dd', blankrows: true, defval: '' });
  } catch {
    return { ok: false, reason: 'The worksheet could not be read' };
  }
  let start = grid.findIndex((r) => Array.isArray(r) && r.some((c) => clean(c) !== ''));
  if (start < 0) return { ok: false, reason: 'The worksheet has no data' };
  const width = Math.min(MAX_COLUMNS, Math.max(...grid.slice(start, start + 200).map((r) => (Array.isArray(r) ? r.length : 0))));
  const seen = new Map();
  const headers = Array.from({ length: width }, (_, i) => {
    let h = clean(grid[start][i]).trim() || `Column ${i + 1}`;
    const n = (seen.get(h.toLowerCase()) ?? 0) + 1;
    seen.set(h.toLowerCase(), n);
    return n > 1 ? `${h} (${n})` : h;
  });
  // A column with no header and no data at all is dropped (a sheet's used range often ends with empty columns).
  const rows = [];
  let skippedEmpty = 0;
  let truncated = false;
  for (const r of grid.slice(start + 1)) {
    const cells = Array.from({ length: width }, (_, i) => clean(Array.isArray(r) ? r[i] : ''));
    if (cells.every((c) => c.trim() === '')) { skippedEmpty++; continue; }
    if (rows.length >= MAX_ROWS) { truncated = true; break; }
    rows.push(cells);
  }
  const used = headers.map((h, i) => !h.startsWith('Column ') || rows.some((r) => r[i].trim() !== ''));
  const keep = used.map((u, i) => (u ? i : -1)).filter((i) => i >= 0);
  if (!rows.length) return { ok: false, reason: 'The worksheet has a header row but no records' };
  return { ok: true, sheets, sheet, headers: keep.map((i) => headers[i]), rows: rows.map((r) => keep.map((i) => r[i])), skippedEmpty, truncated };
}
