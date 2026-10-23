// The analysis behind the Previous Manual Record summary: everything numeric and every sentence that states a fact is produced here, by code, from the
// cells of the saved worksheet. The AI (aiSummary.js) may only explain these findings in plain words; it never supplies a figure.
import { overview, buildReport, isTotal, parseNumber, isPeriodLabel, polarity, say, norm } from './sheetCompare.js';

/** The indicators a Circuit Overseer looks for first; they get a card when the workbook has them. */
const KEY_INDICATORS = /publisher|pioneer|bible stud|stud(y|ies)|return visit|transferee|attendance|baptiz|baptis|new publisher|unbaptized/i;
const MAX_CARDS = 10;
const MAX_CHART_ROWS = 10;
const MAX_FINDINGS = 14;

const unitKind = (u) => (u === '%' ? 'percent' : u === 'currency' ? 'currency' : 'count');
const UNIT_TITLE = { count: 'Counts', percent: 'Percentages', currency: 'Amounts' };

/** The cells of one table column, read straight from the saved cells: how many are numbers, text or empty, and how many are negative. */
function columnQuality(block, col) {
  let numbers = 0, text = 0, empty = 0, negative = 0;
  for (const r of block.data) {
    const t = block.v.at(r, col.c);
    if (t == null || t === '') { empty++; continue; }
    const n = parseNumber(t);
    if (n === null) text++; else { numbers++; if (n.value < 0) negative++; }
  }
  return { numbers, text, empty, negative };
}

function validDate(parts) {
  const [a, b, y0] = parts.map(Number);
  const y = y0 < 100 ? 2000 + y0 : y0;
  const ok = (day, mon) => mon >= 1 && mon <= 12 && day >= 1 && day <= new Date(y, mon, 0).getDate();
  return ok(a, b) || ok(b, a);
}

/** Data-quality notes for the chosen group: missing values, text in numeric columns, incomplete periods, impossible dates. Nothing is changed or removed. */
function qualityNotes(group) {
  const notes = [...group._ind.limits];
  const block = group._block;
  const total = block.data.length;
  for (const col of group.columns) {
    const bcol = block.columns.find((c) => String(c.c) === col.key);
    if (!bcol) continue;
    const q = columnQuality(block, bcol);
    if (q.text) notes.push(`${q.text} cell${q.text === 1 ? '' : 's'} under "${col.label}" hold text instead of a number and ${q.text === 1 ? 'was' : 'were'} not used.`);
    if (q.negative) notes.push(`${q.negative} negative value${q.negative === 1 ? '' : 's'} under "${col.label}" ${q.negative === 1 ? 'was' : 'were'} used as written.`);
    if (total >= 4 && q.numbers / total < 0.5) notes.push(`"${col.label}" has a number for only ${q.numbers} of ${total} rows, so that period may be incomplete.`);
    const m = /^(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})$/.exec(col.label.trim());
    if (m && !validDate([m[1], m[2], m[3]])) notes.push(`The heading "${col.label}" looks like a date but is not a real calendar date.`);
  }
  return [...new Set(notes)];
}

function cardOf(row, columns, allValues) {
  return {
    label: row.label, unit: row.unit, polarity: row.polarity, direction: row.direction, previous: row.previous, current: row.current,
    difference: row.difference, percent: row.percent, note: row.note, previousCell: row.previousCell, currentCell: row.currentCell,
    values: columns.map((c, i) => ({ period: c.label, value: allValues[i] })),
  };
}

/** Bar-chart data per kind of number (never counts and percentages on one scale), all periods of the group, the largest indicators first. */
function chartsOf(group) {
  const out = [];
  for (const kind of ['count', 'percent', 'currency']) {
    const rows = group._ind.rows.filter((r) => !isTotal(r.label)).map((r) => {
      const cells = group.columns.map((c) => r.values[c.key] ?? null);
      const first = cells.find(Boolean);
      return { label: r.label, unit: first?.unit ?? '', values: cells.map((c) => (c ? c.value : null)), cells };
    }).filter((r) => r.values.some((v) => v !== null) && unitKind(r.unit) === kind && r.cells.every((c) => !c || unitKind(c.unit) === kind));
    if (rows.length < 2) continue;
    // the indicators a Circuit Overseer looks for first, then the largest
    const peak = (r) => Math.max(...r.values.filter((v) => v !== null).map(Math.abs));
    rows.sort((x, y) => (KEY_INDICATORS.test(y.label) ? 1 : 0) - (KEY_INDICATORS.test(x.label) ? 1 : 0) || peak(y) - peak(x));
    const shown = rows.slice(0, MAX_CHART_ROWS);
    out.push({
      id: `chart-${kind}`, kind, title: `${UNIT_TITLE[kind]} by reporting period`, unit: kind === 'percent' ? '%' : kind === 'currency' ? 'amount' : 'count',
      periods: group.columns.map((c) => c.label), series: shown.map((r) => ({ label: r.label, values: r.values })),
      omitted: rows.length - shown.length,
      note: `Bars show the saved figure of each indicator in each period.${rows.length > shown.length ? ` The ${shown.length} largest of ${rows.length} indicators are shown.` : ''} A missing value is shown as an empty space, not as zero.`,
    });
  }
  return out;
}

const evidenceOf = (r, report) => [
  { label: r.label, period: report.scope.previous, value: r.previous, cell: r.previousCell },
  { label: r.label, period: report.scope.current, value: r.current, cell: r.currentCell },
].filter((e) => e.value !== null);

/** The findings as facts, each with the cells it comes from; the AI may add an explanation to a finding but cannot add a finding or change its text. */
function findingsOf(report) {
  const rows = report.table;
  const compared = rows.filter((r) => r.direction !== 'unavailable');
  const out = [];
  const add = (kind, text, evidence, extra = {}) => { if (out.length < MAX_FINDINGS) out.push({ id: `f${out.length + 1}`, kind, priority: out.length + 1, text, evidence, ...extra }); };
  const size = (r) => (r.percent !== null ? Math.abs(r.percent) : Math.abs(r.difference ?? 0) / 1000);
  const moved = compared.filter((r) => r.direction !== 'unchanged' && !r.total).sort((x, y) => size(y) - size(x));
  const sentence = (r) => (r.direction === 'unchanged'
    ? `${r.label} stayed at ${say(r.current)}${r.unit === '%' ? ' percent' : ''}.`
    : `${r.label} ${r.direction === 'increase' ? 'increased' : 'decreased'} from ${say(r.previous)} to ${say(r.current)}${r.unit === '%' ? ' percent' : ''}, a change of ${say(r.difference)}${r.percent !== null ? ` (${say(r.percent)} percent)` : ''}.`);
  for (const r of moved.slice(0, 4)) add('fact', sentence(r), evidenceOf(r, report), { indicator: r.label, labels: [r.label] });
  const attention = moved.filter((r) => (r.direction === 'decrease' && r.polarity === 1) || (r.direction === 'increase' && r.polarity === -1)).slice(0, 3);
  for (const r of attention) {
    if (out.some((f) => f.indicator === r.label)) { out.find((f) => f.indicator === r.label).kind = 'pattern'; continue; }
    add('pattern', sentence(r), evidenceOf(r, report), { indicator: r.label, labels: [r.label] });
  }
  const unchanged = compared.filter((r) => r.direction === 'unchanged' && !r.total);
  if (unchanged.length) add('fact', `${unchanged.length} indicator${unchanged.length === 1 ? '' : 's'} did not change: ${unchanged.slice(0, 6).map((r) => r.label).join(', ')}${unchanged.length > 6 ? ', and others' : ''}.`, unchanged.slice(0, 6).flatMap((r) => evidenceOf(r, report)), { labels: unchanged.slice(0, 6).map((r) => r.label) });
  const zero = compared.filter((r) => r.previous === 0 && r.difference !== 0);
  if (zero.length) add('fact', `${zero.map((r) => r.label).slice(0, 5).join(', ')} started from 0, so no percentage change can be given.`, zero.slice(0, 5).flatMap((r) => evidenceOf(r, report)), { labels: zero.slice(0, 5).map((r) => r.label) });
  const missing = rows.filter((r) => r.direction === 'unavailable');
  if (missing.length) add('fact', `${missing.length} indicator${missing.length === 1 ? ' has' : 's have'} no value in one of the two periods and could not be compared: ${missing.slice(0, 6).map((r) => r.label).join(', ')}.`, [], { labels: missing.slice(0, 6).map((r) => r.label) });
  return out;
}

export const NOT_COMPARABLE = 'This worksheet does not contain matching indicators for two or more periods, visits or congregations, so no valid comparison can be produced.';

/**
 * The full analysis of one saved worksheet. [pick] chooses the group and the two columns to compare (defaults: the first group's last two columns).
 * Returns { available: false, message } when the worksheet has nothing to compare.
 */
export function analyze(snapshot, ctx = {}, pick = {}) {
  const o = overview(snapshot, ctx);
  if (!o.available) return { available: false, message: NOT_COMPARABLE, detail: o.message };
  const group = o.groups.find((g) => g.id === pick.group) ?? o.groups[0];
  const keys = group.columns.map((c) => c.key);
  const a = keys.includes(String(pick.a)) ? String(pick.a) : group.recommended.previous;
  const b = keys.includes(String(pick.b)) && String(pick.b) !== a ? String(pick.b) : group.recommended.current;
  const report = buildReport(group, a, b, ctx);
  const cards = report.table.filter((r) => r.direction !== 'unavailable' && !r.total).sort((x, y) => (KEY_INDICATORS.test(y.label) ? 1 : 0) - (KEY_INDICATORS.test(x.label) ? 1 : 0)).slice(0, MAX_CARDS).map((r) => {
    const src = group._ind.rows.find((x) => x.label === r.label);
    return cardOf(r, group.columns, group.columns.map((c) => src?.values[c.key]?.value ?? null));
  });
  const quality = qualityNotes(group);
  const rowsOf = group._ind.rows;
  return {
    available: true, generatedAt: new Date().toISOString(),
    source: { fileName: ctx.fileName ?? '', sheet: ctx.sheetName ?? '', congregation: ctx.congregationName ?? '', savedAt: ctx.savedAt ?? null },
    group: { id: group.id, kind: group.kind, title: group.title },
    groups: o.groups.map(({ _block, _ind, signature, ...g }) => g),
    selection: { previous: a, current: b },
    periods: group.columns.map((c) => ({ key: c.key, label: c.label })),
    report, cards, charts: chartsOf(group), findings: findingsOf(report), quality: { notes: quality, rows: rowsOf.length },
    executive: report.executive, narration: report.voice,
    // the shortlist the AI is given: indicator names that carry a meaning, never an unknown label (it could be a person's name)
    ai: null,
  };
}

/** Only labels that name a ministry indicator (or a total) may leave the server for the AI; everything else is counted, not named. */
export const shareable = (label) => isTotal(label) || KEY_INDICATORS.test(label) || polarity(label) !== 0;

export { isPeriodLabel, norm };
