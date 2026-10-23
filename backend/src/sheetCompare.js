// Comparative reports from the Excel records a Circuit Overseer saved (Previous Manual Record).
//
// One engine, one set of rules, for the website and the Android app: both only show (and read aloud) what this module returns. It never
// assumes the columns of a file. It looks at the cells of a worksheet, finds tables whose columns (or rows) are reporting periods, visits or
// congregations, matches the indicators across them by name, and compares only what is really there:
//   - a blank or non-numeric cell is "not available", never zero;
//   - a percentage change is given only when the earlier value is a non-zero number of the same kind;
//   - a label that appears twice with different values is left out (and said so), twice with the same value it counts once;
//   - an increase is called an improvement only for indicators whose meaning says so (more publishers, more studies ...), otherwise it is
//     reported neutrally as an increase.
// The original cells are only read.

const NUMBER = /^-?\d[\d,]*(\.\d+)?$|^-?\.\d+$/;
const MONTH = '(jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sep(t(ember)?)?|oct(ober)?|nov(ember)?|dec(ember)?)';
const PERIOD_WORDS = [
  new RegExp(`\\b${MONTH}\\b`, 'i'), /\b(19|20)\d{2}\b/, /\bq[1-4]\b/i, /^\d{1,4}[/.-]\d{1,2}[/.-]\d{1,4}$/,
  /^(previous|prior|last|current|this|before|after|earlier|later)\b/i, /^(week|wk|visit|period|month|year|fy|sy|service year)\b/i,
  /\b(first|second|third|fourth|1st|2nd|3rd|4th)\s+(visit|half|quarter)\b/i, /\b(h1|h2)\b/i,
];
const YEAR_ONLY = /^(19|20)\d{2}$/;

export const MAX_BLOCKS = 8;
const MAX_ROWS_SCAN = 3000;

const round = (n, d = 2) => { const f = 10 ** d; return Math.round((n + Number.EPSILON) * f) / f; };
export const say = (n) => (Number.isInteger(n) ? String(n) : String(round(n, 2)));
export const norm = (t) => String(t ?? '').toLowerCase().replace(/[^a-z0-9À-ɏ ]+/g, ' ').replace(/\s+/g, ' ').trim();

/** The number a cell shows, with the kind of number it is: plain, percent or currency. null when it is not a number. */
export function parseNumber(text) {
  let t = String(text ?? '').trim();
  if (!t) return null;
  let unit = '';
  if (t.endsWith('%')) { unit = '%'; t = t.slice(0, -1).trim(); }
  else if (/^[₱$€£]/.test(t)) { unit = 'currency'; t = t.slice(1).trim(); }
  if (!NUMBER.test(t)) return null;
  return { value: Number(t.replaceAll(',', '')), unit };
}

export const isPeriodLabel = (t) => { const s = String(t ?? '').trim(); return s.length > 0 && s.length <= 60 && (YEAR_ONLY.test(s) || PERIOD_WORDS.some((re) => re.test(s))); };

// ------------------------------------------------------------------------------------------------------------------------------ reading the grid

function gridOf(snapshot) {
  const cells = new Map();
  let rows = 0, cols = 0;
  for (const [r, c, text] of snapshot.cells ?? []) {
    if (text === '' || text == null) continue;
    cells.set(r * 1000 + c, String(text).trim());
    if (r + 1 > rows) rows = r + 1;
    if (c + 1 > cols) cols = c + 1;
  }
  return { cells, rows: Math.min(rows, MAX_ROWS_SCAN), cols: Math.min(cols, 200) };
}
const view = (grid, transposed) => ({
  rows: transposed ? grid.cols : grid.rows,
  cols: transposed ? grid.rows : grid.cols,
  at: transposed ? (r, c) => grid.cells.get(c * 1000 + r) : (r, c) => grid.cells.get(r * 1000 + c),
  // where a cell of this view lives in the worksheet (for "A12" style references)
  ref: transposed ? (r, c) => [c, r] : (r, c) => [r, c],
});

const colLetters = (c) => { let n = c + 1, s = ''; while (n > 0) { s = String.fromCharCode(65 + ((n - 1) % 26)) + s; n = Math.floor((n - 1) / 26); } return s; };
const cellRef = (r, c) => `${colLetters(c)}${r + 1}`;

const isHeaderText = (t) => t != null && t !== '' && t.length <= 60 && (parseNumber(t) === null || YEAR_ONLY.test(t));

/** Finds tables in a view: a header row of >= 2 labelled columns, a label column on the left and numeric cells under the headers. */
function findBlocks(v, congregationNames) {
  const blocks = [];
  let h = 0;
  while (h < v.rows && blocks.length < MAX_BLOCKS * 2) {
    const heads = [];
    for (let c = 0; c < v.cols; c++) { const t = v.at(h, c); if (isHeaderText(t)) heads.push(c); }
    if (heads.length < 2) { h++; continue; }
    // the data rows: until two empty rows in a row or another all-text header row
    const data = [];
    let blank = 0, truncated = false;
    for (let r = h + 1; r < v.rows; r++) {
      let any = false, text = 0, nums = 0;
      for (let c = 0; c < v.cols; c++) { const t = v.at(r, c); if (t == null) continue; any = true; if (parseNumber(t) === null) text++; else nums++; }
      if (!any) { if (++blank >= 2) break; continue; }
      blank = 0;
      if (nums === 0 && text >= 3 && data.length > 0) break;     // a new header row
      if (data.length >= 600) { truncated = true; break; }
      data.push(r);
    }
    if (data.length === 0) { h++; continue; }
    const numericShare = (c) => data.filter((r) => { const t = v.at(r, c); return t != null && parseNumber(t) !== null; }).length / data.length;
    const textShare = (c) => data.filter((r) => { const t = v.at(r, c); return t != null && parseNumber(t) === null; }).length / data.length;
    // the label column: the left-most header column that is mostly text, or the column just before the first numeric one
    let labelCol = heads.find((c) => textShare(c) >= 0.6 && numericShare(c) < 0.3);
    if (labelCol === undefined) { for (let c = Math.min(heads[0], v.cols) - 1; c >= 0; c--) if (data.filter((r) => v.at(r, c) != null).length / data.length >= 0.6 && textShare(c) >= 0.6) { labelCol = c; break; } }
    const valueCols = heads.filter((c) => c !== labelCol && (numericShare(c) >= 0.4 || data.filter((r) => parseNumber(v.at(r, c) ?? '') !== null).length >= 2));
    if (valueCols.length < 2) { h++; continue; }
    const columns = valueCols.map((c) => ({ c, label: v.at(h, c) }));
    const labels = columns.map((x) => x.label);
    const kind = classifyHeaders(labels, congregationNames);
    if (kind) blocks.push({ h, labelCol, columns, data, kind, v, truncated });
    h = Math.max(h + 1, data[data.length - 1] + 1);
  }
  return blocks;
}

function classifyHeaders(labels, congregationNames) {
  const period = labels.filter(isPeriodLabel).length;
  if (period >= 2 && period >= labels.length * 0.6) return 'PERIODS';
  const nl = labels.map(norm);
  const cong = nl.filter((l) => l && congregationNames.some((n) => n === l || n.includes(l) || l.includes(n))).length;
  if (cong >= 2 && cong >= labels.length * 0.6) return 'CONGREGATIONS';
  return null;
}

// ------------------------------------------------------------------------------------------------------------------------------ the comparison model

const KIND_LABEL = { PERIODS: 'Reporting periods', VISITS: 'Visits', CONGREGATIONS: 'Congregations', FILES: 'Saved records' };

/** The indicators of a block: label -> { cells by column key }. Duplicate labels are merged when equal and dropped when they disagree. */
function indicatorsOf(block) {
  const { v, h, labelCol, columns, data } = block;
  const seen = new Map();
  const limits = [];
  let rowNo = 0;
  for (const r of data) {
    rowNo++;
    const raw = labelCol === undefined ? `Row ${rowNo}` : (v.at(r, labelCol) ?? '');
    if (!raw || parseNumber(raw) !== null) continue;
    const label = raw.replace(/\s+/g, ' ').slice(0, 80);
    const values = {};
    let any = false;
    for (const col of columns) {
      const t = v.at(r, col.c);
      const n = t == null ? null : parseNumber(t);
      values[col.c] = n ? { ...n, ref: cellRef(...v.ref(r, col.c)) } : null;
      if (n) any = true;
    }
    if (!any) continue;                                  // a section title or a row without numbers
    const key = norm(label);
    if (!key) continue;
    if (seen.has(key)) {
      const first = seen.get(key);
      const same = columns.every((col) => (first.values[col.c]?.value ?? null) === (values[col.c]?.value ?? null));
      if (same) { limits.push(`"${label}" appears more than once with the same values and was counted once.`); }
      else if (!first.conflict) { first.conflict = true; limits.push(`"${label}" appears more than once with different values, so it was left out of the comparison.`); }
      continue;
    }
    seen.set(key, { label, values, conflict: false });
  }
  if (block.truncated) limits.push('This table has more than 600 rows; only the first 600 were analysed.');
  return { rows: [...seen.values()].filter((x) => !x.conflict), limits };
}

const GROWTH = /publisher|pioneer|stud(y|ies)|hours?|attendance|placements?|return visits?|baptis|baptiz|new |bible|transferee|revival|interested|literature|videos?|average|meeting/i;
const DECLINE = /inactive|irregular|absent|dropped|removed|late|unreported|disfellow|deceased|moved out|missing/i;
/** +1 when more of it is good news, -1 when more of it needs attention, 0 when the name does not say. */
export function polarity(label) { const t = String(label); if (DECLINE.test(t)) return -1; if (GROWTH.test(t)) return 1; return 0; }

const isTotal = (label) => /^(grand\s+)?(total|sum|overall)\b/i.test(label.trim());

/** Previous and current value of one indicator, and what changed. */
export function change(prev, curr) {
  if (!prev || !curr) return { status: 'UNAVAILABLE', direction: 'unavailable', note: !prev && !curr ? 'No value in either column' : !prev ? 'No value in the previous column' : 'No value in the current column' };
  if (prev.unit !== curr.unit) return { status: 'UNAVAILABLE', direction: 'unavailable', note: 'The two values are not the same kind of number' };
  const difference = round(curr.value - prev.value, 4);
  const direction = difference > 0 ? 'increase' : difference < 0 ? 'decrease' : 'unchanged';
  let percent = null, note = '';
  if (prev.value !== 0) percent = round((difference / Math.abs(prev.value)) * 100, 1);
  else if (difference !== 0) note = 'No percentage: the previous value was 0';
  return { status: 'OK', direction, difference, percent, note };
}

function orderKeys(columns) {
  // chronological when every label is a date or month we can read, otherwise the order of the sheet
  return columns;
}

/** Everything a worksheet offers to compare: the groups, their columns and the recommended pair. Cheap enough to send with every sheet. */
export function overview(snapshot, { congregations = [] } = {}) {
  const congregationNames = congregations.map(norm).filter(Boolean);
  const grid = gridOf(snapshot);
  if (grid.cells.size < 6) return { available: false, message: NONE, groups: [], blocks: [] };
  const found = [];
  for (const transposed of [false, true]) found.push(...findBlocks(view(grid, transposed), congregationNames).map((b) => ({ ...b, transposed })));
  // the same table can be seen from both sides: keep the one whose headers are the periods or congregations
  const groups = [];
  let n = 0;
  for (const b of found) {
    if (groups.length >= MAX_BLOCKS) break;
    const ind = indicatorsOf(b);
    if (ind.rows.length < 1) continue;
    const kind = b.kind === 'PERIODS' && b.columns.some((c) => /visit/i.test(c.label)) ? 'VISITS' : b.kind;
    const columns = orderKeys(b.columns.filter((c) => (kind === 'CONGREGATIONS' ? true : isPeriodLabel(c.label))).map((c) => ({ key: String(c.c), label: c.label })));
    if (columns.length < 2) continue;
    // an identical group (same indicators and columns) found from the other side is the same table
    const signature = `${kind}|${columns.map((c) => norm(c.label)).join('~')}|${ind.rows.map((r) => norm(r.label)).join('~')}`;
    if (groups.some((g) => g.signature === signature)) continue;
    n++;
    groups.push({
      id: `g${n}`, kind, title: `${KIND_LABEL[kind]}: ${columns.map((c) => c.label).slice(0, 4).join(', ')}${columns.length > 4 ? ' ...' : ''}`, signature,
      columns, indicators: ind.rows.length, recommended: { previous: columns[columns.length - 2].key, current: columns[columns.length - 1].key },
      _block: b, _ind: ind,
    });
  }
  if (!groups.length) return { available: false, message: NONE, groups: [], blocks: [] };
  return { available: true, message: '', groups };
}

export const NONE = 'No comparable records were found in this Excel file. Upload a file containing matching indicators across different periods, visits, or congregations to generate a comparative report.';

const publicGroup = ({ _block, _ind, signature, ...g }) => g;

/** The groups to send with a worksheet, and the recommended report built from the first one. */
export function comparisonFor(snapshot, ctx = {}) {
  const o = overview(snapshot, ctx);
  if (!o.available) return { available: false, message: o.message, groups: [], report: null };
  const first = o.groups[0];
  return { available: true, message: '', groups: o.groups.map(publicGroup), report: buildReport(first, first.recommended.previous, first.recommended.current, ctx) };
}

/** The report for a chosen group and pair of columns. null when the choice is not one of the offered ones. */
export function reportFor(snapshot, groupId, a, b, ctx = {}) {
  const o = overview(snapshot, ctx);
  const g = o.groups?.find((x) => x.id === groupId);
  if (!g) return null;
  const keys = g.columns.map((c) => c.key);
  if (!keys.includes(String(a)) || !keys.includes(String(b)) || String(a) === String(b)) return null;
  return buildReport(g, String(a), String(b), ctx);
}

function buildReport(group, aKey, bKey, ctx) {
  const colA = group.columns.find((c) => c.key === aKey), colB = group.columns.find((c) => c.key === bKey);
  const rows = group._ind.rows.map((row) => {
    const prev = row.values[aKey] ?? null, curr = row.values[bKey] ?? null;
    const ch = change(prev, curr);
    const pol = polarity(row.label);
    return {
      label: row.label, total: isTotal(row.label), unit: (curr ?? prev)?.unit ?? '', polarity: pol,
      previous: prev ? prev.value : null, current: curr ? curr.value : null, previousCell: prev?.ref ?? null, currentCell: curr?.ref ?? null,
      difference: ch.difference ?? null, percent: ch.percent ?? null, direction: ch.direction, note: ch.note ?? '',
    };
  });
  return finishReport(rows, {
    kind: group.kind, previousLabel: colA.label, currentLabel: colB.label, fileName: ctx.fileName ?? '', congregation: ctx.congregationName ?? '',
    sheet: ctx.sheetName ?? '', limits: group._ind.limits, criteria: criteria(group, colA, colB),
  });
}

function criteria(group, a, b) {
  const what = group.kind === 'CONGREGATIONS' ? 'congregation' : group.kind === 'VISITS' ? 'visit' : 'reporting period';
  return `Indicators were matched by name between the ${what} "${a.label}" (earlier) and the ${what} "${b.label}" (later), using the numbers saved in the worksheet. Blank cells are treated as not available, never as zero.`;
}

// ------------------------------------------------------------------------------------------------------------------------------ the report

const upper = (t) => t.charAt(0).toUpperCase() + t.slice(1);
const lowerFirst = (t) => (t.length > 1 && t[1] === t[1].toLowerCase() ? t.charAt(0).toLowerCase() + t.slice(1) : t);
const unitWord = (unit) => (unit === '%' ? ' percent' : '');
const money = (unit, n) => (unit === 'currency' ? `pesos ${say(n)}` : `${say(n)}${unitWord(unit)}`);

function sentenceFor(r) {
  const nm = r.label;
  if (r.direction === 'unavailable') return `${nm} cannot be compared: ${r.note.charAt(0).toLowerCase()}${r.note.slice(1)}.`;
  if (r.direction === 'unchanged') return `${nm} stayed at ${money(r.unit, r.current)}.`;
  const up = r.direction === 'increase';
  const word = r.unit === '%' ? 'points' : '';
  const diff = `${say(Math.abs(r.difference))}${word ? ` ${word}` : r.unit === 'currency' ? ' pesos' : ''}`;
  const pct = r.percent !== null ? `, or ${say(Math.abs(r.percent))} percent` : '';
  const why = r.percent === null && r.note ? `; a percentage cannot be given because the previous value was 0` : '';
  return `${nm} ${up ? 'increased' : 'decreased'} from ${money(r.unit, r.previous)} to ${money(r.unit, r.current)}, ${up ? 'an increase' : 'a decrease'} of ${diff}${pct}${why}.`;
}

function finishReport(rows, m) {
  const compared = rows.filter((r) => r.direction !== 'unavailable');
  const inc = compared.filter((r) => r.direction === 'increase'), dec = compared.filter((r) => r.direction === 'decrease'), same = compared.filter((r) => r.direction === 'unchanged');
  const unavailable = rows.filter((r) => r.direction === 'unavailable');
  const improvements = compared.filter((r) => (r.direction === 'increase' && r.polarity === 1) || (r.direction === 'decrease' && r.polarity === -1));
  const attention = compared.filter((r) => (r.direction === 'decrease' && r.polarity === 1) || (r.direction === 'increase' && r.polarity === -1));
  const neutral = compared.filter((r) => r.direction !== 'unchanged' && r.polarity === 0);
  const size = (r) => (r.percent !== null ? Math.abs(r.percent) : Math.abs(r.difference ?? 0) / 1000);
  const biggest = [...compared.filter((r) => r.direction !== 'unchanged' && !r.total)].sort((x, y) => size(y) - size(x));
  const title = `${KIND_LABEL[m.kind]}: ${m.previousLabel} compared with ${m.currentLabel}`;
  const scope = [m.congregation && `Congregation: ${m.congregation}`, m.fileName && `File: ${m.fileName}`, m.sheet && `Worksheet: ${m.sheet}`].filter(Boolean);

  const executive = [`${compared.length} of ${rows.length} indicators could be compared between ${m.previousLabel} and ${m.currentLabel}: ${inc.length} increased, ${dec.length} decreased and ${same.length} did not change.`];
  if (unavailable.length) executive.push(`${unavailable.length} indicator${unavailable.length === 1 ? ' has' : 's have'} no value in one of the two columns and ${unavailable.length === 1 ? 'is' : 'are'} not compared.`);
  const topUp = biggest.find((r) => r.direction === 'increase'), topDown = biggest.find((r) => r.direction === 'decrease');
  if (topUp) executive.push(`The largest increase is ${lowerFirst(sentenceFor(topUp))}`);
  if (topDown) executive.push(`The largest decrease is ${lowerFirst(sentenceFor(topDown))}`);
  if (!compared.length) executive.length = 0, executive.push('None of the indicators has a value in both columns, so nothing could be compared.');

  const limitations = [...m.limits];
  if (unavailable.length) limitations.push(`${unavailable.length} indicator${unavailable.length === 1 ? '' : 's'} missing a value in one of the two columns: ${unavailable.slice(0, 8).map((r) => r.label).join(', ')}${unavailable.length > 8 ? ' ...' : ''}.`);
  if (rows.some((r) => r.unit === '' && r.direction !== 'unavailable') && rows.some((r) => r.unit === '%')) limitations.push('Percentage indicators change in points; the percentage change shown for them compares the two percentages.');
  limitations.push('Improvement and attention are only named for indicators whose meaning says so (for example more publishers or fewer inactive); every other change is shown neutrally.');
  limitations.push('This compares the numbers saved in an imported historical Excel file; they are not verified live GoPreach reports.');

  const method = [m.criteria, `${rows.length} indicator${rows.length === 1 ? '' : 's'} analysed.`, 'The original spreadsheet is never changed.'];

  const voice = [`${title}.`, scope.join('. ') ? `${scope.join('. ')}.` : ''];
  voice.push(...executive);
  const spoken = [...biggest].slice(0, 12);
  if (spoken.length) { voice.push('Important changes.'); voice.push(...spoken.map(sentenceFor)); }
  if (improvements.length) voice.push(`Key improvements: ${improvements.slice(0, 8).map((r) => r.label).join(', ')}.`);
  if (attention.length) voice.push(`Areas for attention: ${attention.slice(0, 8).map((r) => r.label).join(', ')}.`);
  if (neutral.length && !improvements.length && !attention.length) voice.push(`Other changes: ${neutral.slice(0, 8).map((r) => r.label).join(', ')}.`);
  if (same.length) voice.push(`${same.length} indicator${same.length === 1 ? '' : 's'} did not change${same.length <= 6 ? `: ${same.map((r) => r.label).join(', ')}` : ''}.`);
  voice.push(...limitations.slice(0, limitations.length - 2).slice(0, 6));
  voice.push(`This report compares the file ${m.fileName || 'you saved'}, matching indicators by name.`);

  const charted = compared.filter((r) => !r.total && r.unit === '' && r.previous !== null && r.current !== null).slice(0, 12);
  const chart = charted.length >= 2 ? { type: 'bar', previousLabel: m.previousLabel, currentLabel: m.currentLabel, series: charted.map((r) => ({ label: r.label, previous: r.previous, current: r.current })) } : null;

  return {
    title, kind: m.kind, scope: { congregation: m.congregation, fileName: m.fileName, sheet: m.sheet, previous: m.previousLabel, current: m.currentLabel },
    counts: { indicators: rows.length, compared: compared.length, increased: inc.length, decreased: dec.length, unchanged: same.length, unavailable: unavailable.length },
    executive, table: rows,
    improvements: improvements.map((r) => r.label), attention: attention.map((r) => r.label), otherChanges: neutral.map((r) => r.label), unchanged: same.map((r) => r.label),
    limitations, method, chart, voice: voice.filter(Boolean),
  };
}

// ------------------------------------------------------------------------------------------------------------------------------ two saved files

/** label -> value for the "current" figures of a worksheet: the last value column of each table, else the rows that carry exactly one number. */
export function figuresOf(snapshot, congregations = []) {
  const grid = gridOf(snapshot);
  const out = new Map();
  const add = (label, n, ref) => { const k = norm(label); if (k && !out.has(k)) out.set(k, { label: label.replace(/\s+/g, ' ').slice(0, 80), ...n, ref }); else if (k && out.has(k) && out.get(k).value !== n.value) out.get(k).conflict = true; };
  const v = view(grid, false);
  for (let r = 0; r < v.rows; r++) {
    let label = null; const nums = [];
    for (let c = 0; c < v.cols; c++) { const t = v.at(r, c); if (t == null) continue; const n = parseNumber(t); if (n === null) { if (label === null) label = t; } else nums.push({ n, c }); }
    if (label && !isPeriodLabel(label) && nums.length >= 1) { const last = nums[nums.length - 1]; add(label, last.n, cellRef(r, last.c)); }
  }
  return [...out.values()].filter((x) => !x.conflict);
}

/** A report comparing the figures of two saved worksheets (for example two visits of one congregation, or two congregations). */
export function reportForFiles(a, b, ctx = {}) {
  const fa = figuresOf(a.snapshot), fb = figuresOf(b.snapshot);
  const mapB = new Map(fb.map((x) => [norm(x.label), x]));
  const rows = [], limits = [];
  let onlyA = 0;
  for (const x of fa) {
    const y = mapB.get(norm(x.label));
    if (!y) { onlyA++; continue; }
    const ch = change({ value: x.value, unit: x.unit }, { value: y.value, unit: y.unit });
    rows.push({ label: x.label, total: isTotal(x.label), unit: y.unit, polarity: polarity(x.label), previous: x.value, current: y.value, previousCell: x.ref, currentCell: y.ref, difference: ch.difference ?? null, percent: ch.percent ?? null, direction: ch.direction, note: ch.note ?? '' });
  }
  const onlyB = fb.length - rows.length;
  if (onlyA) limits.push(`${onlyA} indicator${onlyA === 1 ? '' : 's'} appear only in the earlier file and ${onlyA === 1 ? 'was' : 'were'} not compared.`);
  if (onlyB > 0) limits.push(`${onlyB} indicator${onlyB === 1 ? '' : 's'} appear only in the later file and ${onlyB === 1 ? 'was' : 'were'} not compared.`);
  if (!rows.length) return null;
  const kind = ctx.sameCongregation === false ? 'CONGREGATIONS' : 'FILES';
  return finishReport(rows, {
    kind, previousLabel: a.label, currentLabel: b.label, fileName: `${a.fileName} and ${b.fileName}`, congregation: ctx.congregationName ?? '', sheet: `${a.sheetName} / ${b.sheetName}`, limits,
    criteria: `Indicators were matched by name between "${a.label}" (earlier) and "${b.label}" (later). For a row with several numbers the last one is used.`,
  });
}
