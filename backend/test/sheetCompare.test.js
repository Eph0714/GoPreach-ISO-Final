import { test } from 'node:test';
import assert from 'node:assert/strict';
import { comparisonFor, reportFor, reportForFiles, change, parseNumber, polarity, NONE } from '../src/sheetCompare.js';

/** A worksheet snapshot from rows of text (null / '' = empty cell), the way readSnapshots stores one. */
function sheet(rowsOfText) {
  const cells = [];
  rowsOfText.forEach((row, r) => row.forEach((t, c) => { if (t !== null && t !== undefined && t !== '') cells.push([r, c, String(t), 0]); }));
  return { rows: rowsOfText.length, cols: Math.max(...rowsOfText.map((r) => r.length)), cells, merges: [], formulas: [], colWidths: [], rowHeights: [], styles: [] };
}
const byLabel = (report, label) => report.table.find((r) => r.label === label);

const PERIOD_COLUMNS = sheet([
  ['Visit report - Alpha Congregation'],
  [],
  ['Indicator', 'Jan-Jun 2026', 'Jul-Oct 2026'],
  ['Publishers', 80, 84],
  ['Regular pioneers', 12, 15],
  ['Auxiliary pioneers', 6, 6],
  ['Bible studies', 40, 31],
  ['Inactive publishers', 5, 8],
  ['New publishers', 0, 4],
  ['Meeting attendance average', 95, ''],
  ['Total', 100, 110],
]);

test('periods as columns: two reporting periods are compared indicator by indicator', () => {
  const c = comparisonFor(PERIOD_COLUMNS, { fileName: 'visit.xlsx', congregationName: 'Alpha', sheetName: 'Sheet1' });
  assert.equal(c.available, true);
  assert.equal(c.groups[0].kind, 'PERIODS');
  assert.deepEqual(c.groups[0].columns.map((x) => x.label), ['Jan-Jun 2026', 'Jul-Oct 2026']);
  const r = c.report;
  assert.equal(r.scope.previous, 'Jan-Jun 2026');
  assert.equal(r.scope.fileName, 'visit.xlsx');
  assert.deepEqual([byLabel(r, 'Publishers').difference, byLabel(r, 'Publishers').percent], [4, 5]);
  assert.deepEqual([byLabel(r, 'Regular pioneers').difference, byLabel(r, 'Regular pioneers').percent, byLabel(r, 'Regular pioneers').direction], [3, 25, 'increase']);
  assert.equal(byLabel(r, 'Auxiliary pioneers').direction, 'unchanged');
  assert.deepEqual([byLabel(r, 'Bible studies').difference, byLabel(r, 'Bible studies').percent], [-9, -22.5]);
});

test('math: percentage change, zero baseline and negative values are exact and safe', () => {
  const r = comparisonFor(PERIOD_COLUMNS, {}).report;
  const nw = byLabel(r, 'New publishers');
  assert.equal(nw.previous, 0); assert.equal(nw.percent, null); assert.equal(nw.difference, 4);
  assert.match(nw.note, /previous value was 0/);
  assert.deepEqual(change({ value: -10, unit: '' }, { value: -5, unit: '' }), { status: 'OK', direction: 'increase', difference: 5, percent: 50, note: '' });
  assert.equal(change({ value: 0, unit: '' }, { value: 0, unit: '' }).direction, 'unchanged');
  assert.equal(change({ value: 3, unit: '%' }, { value: 3, unit: '' }).status, 'UNAVAILABLE');
});

test('a blank cell is not available, never zero', () => {
  const r = comparisonFor(PERIOD_COLUMNS, {}).report;
  const att = byLabel(r, 'Meeting attendance average');
  assert.equal(att.direction, 'unavailable'); assert.equal(att.current, null); assert.equal(att.difference, null);
  assert.equal(r.counts.unavailable, 1);
  assert.ok(r.limitations.some((l) => /Meeting attendance average/.test(l)));
});

test('improvement, attention and neutral changes follow the meaning of the indicator', () => {
  const r = comparisonFor(PERIOD_COLUMNS, {}).report;
  assert.ok(r.improvements.includes('Regular pioneers') && r.improvements.includes('Publishers'));
  assert.ok(r.attention.includes('Bible studies'), 'fewer studies needs attention');
  assert.ok(r.attention.includes('Inactive publishers'), 'more inactive publishers needs attention');
  assert.ok(!r.improvements.includes('Inactive publishers'));
  assert.ok(r.unchanged.includes('Auxiliary pioneers'));
  assert.equal(polarity('Total'), 0);
  assert.ok(r.otherChanges.includes('Total'), 'an unnamed meaning is reported neutrally');
});

test('the voice reads natural sentences in a logical order and skips nothing it should say', () => {
  const v = comparisonFor(PERIOD_COLUMNS, { fileName: 'visit.xlsx' }).report.voice;
  assert.ok(v[0].startsWith('Reporting periods: Jan-Jun 2026 compared with Jul-Oct 2026'));
  assert.ok(v.includes('Regular pioneers increased from 12 to 15, an increase of 3, or 25 percent.'));
  assert.ok(v.some((s) => /^New publishers increased from 0 to 4, an increase of 4; a percentage cannot be given/.test(s)));
  assert.ok(v.some((s) => /^Key improvements:/.test(s)) && v.some((s) => /^Areas for attention:/.test(s)));
  assert.ok(v.some((s) => /visit\.xlsx/.test(s)));
  assert.ok(v.every((s) => s.length > 3 && !/undefined|NaN|null/.test(s)), 'no raw values leak into speech');
  const order = ['Important changes.', 'Key improvements', 'Areas for attention'].map((p) => v.findIndex((s) => s.startsWith(p)));
  assert.ok(order[0] < order[1] && order[1] < order[2]);
});

test('periods as rows: the indicators are the columns', () => {
  const s = sheet([['Month', 'Publishers', 'Studies'], ['January 2026', 70, 30], ['February 2026', 72, 28], ['March 2026', 75, 35]]);
  const c = comparisonFor(s, {});
  assert.equal(c.available, true);
  assert.equal(c.groups[0].kind, 'PERIODS');
  assert.equal(c.groups[0].columns.length, 3);
  assert.deepEqual([byLabel(c.report, 'Publishers').previous, byLabel(c.report, 'Publishers').current], [72, 75]);
  const chosen = reportFor(s, c.groups[0].id, c.groups[0].columns[0].key, c.groups[0].columns[2].key, {});
  assert.equal(byLabel(chosen, 'Studies').difference, 5);
});

test('congregations: as columns and as rows', () => {
  const ctx = { congregations: ['Alpha', 'Bravo', 'Charlie'] };
  const cols = comparisonFor(sheet([['Indicator', 'Alpha', 'Bravo'], ['Publishers', 80, 60], ['Studies', 30, 45]]), ctx);
  assert.equal(cols.groups[0].kind, 'CONGREGATIONS');
  assert.equal(byLabel(cols.report, 'Studies').difference, 15);
  const rows = comparisonFor(sheet([['Congregation', 'Publishers', 'Studies'], ['Alpha', 80, 30], ['Bravo', 60, 45], ['Charlie', 40, 10]]), ctx);
  assert.equal(rows.groups[0].kind, 'CONGREGATIONS');
  assert.equal(rows.groups[0].columns.length, 3);
  assert.equal(byLabel(rows.report, 'Studies').current, 10);
});

test('visits are recognised from the headers', () => {
  const c = comparisonFor(sheet([['Indicator', 'First visit', 'Second visit'], ['Publishers', 50, 55]]), {});
  assert.equal(c.groups[0].kind, 'VISITS');
});

test('no comparable records: a clear message and no invented comparison', () => {
  const names = sheet([['Name', 'Hours', 'Studies'], ['Ana', 10, 2], ['Ben', 12, 3], ['Cora', 8, 1]]);
  const c = comparisonFor(names, {});
  assert.equal(c.available, false); assert.equal(c.report, null); assert.equal(c.message, NONE);
  assert.equal(comparisonFor(sheet([['Just a title'], ['and some text']]), {}).available, false);
  assert.equal(comparisonFor({ rows: 0, cols: 0, cells: [], merges: [] }, {}).available, false);
});

test('duplicate labels: equal rows count once, conflicting rows are left out and reported', () => {
  const s = sheet([['Indicator', '2025', '2026'], ['Publishers', 10, 12], ['Publishers', 10, 12], ['Studies', 5, 6], ['Studies', 9, 9]]);
  const r = comparisonFor(s, {}).report;
  assert.equal(r.table.filter((x) => x.label === 'Publishers').length, 1);
  assert.equal(r.table.some((x) => x.label === 'Studies'), false);
  assert.ok(r.limitations.some((l) => /"Studies" appears more than once with different values/.test(l)));
  assert.ok(r.limitations.some((l) => /"Publishers" appears more than once with the same values/.test(l)));
});

test('numbers: thousands separators, percentages, and values that are not numbers', () => {
  assert.deepEqual(parseNumber('1,250'), { value: 1250, unit: '' });
  assert.deepEqual(parseNumber('45%'), { value: 45, unit: '%' });
  assert.deepEqual(parseNumber('-3.5'), { value: -3.5, unit: '' });
  assert.equal(parseNumber('n/a'), null); assert.equal(parseNumber(''), null); assert.equal(parseNumber('12 pubs'), null);
  const s = sheet([['Indicator', 'Q1 2026', 'Q2 2026'], ['Attendance rate', '60%', '66%'], ['Offerings', '1,000', '1,250'], ['Note', 'n/a', 'pending']]);
  const r = comparisonFor(s, {}).report;
  assert.deepEqual([byLabel(r, 'Attendance rate').difference, byLabel(r, 'Attendance rate').percent], [6, 10]);
  assert.equal(byLabel(r, 'Offerings').percent, 25);
  assert.equal(r.table.some((x) => x.label === 'Note'), false, 'a row without any number is not an indicator');
});

test('a user can choose any pair; a pair that is not offered is refused', () => {
  const c = comparisonFor(PERIOD_COLUMNS, {});
  const g = c.groups[0];
  const swapped = reportFor(PERIOD_COLUMNS, g.id, g.columns[1].key, g.columns[0].key, {});
  assert.equal(byLabel(swapped, 'Publishers').difference, -4);
  assert.equal(reportFor(PERIOD_COLUMNS, g.id, g.columns[0].key, g.columns[0].key, {}), null);
  assert.equal(reportFor(PERIOD_COLUMNS, 'g99', '1', '2', {}), null);
  assert.equal(reportFor(PERIOD_COLUMNS, g.id, '77', '2', {}), null);
});

test('a chart is offered only when several plain indicators can be drawn', () => {
  assert.ok(comparisonFor(PERIOD_COLUMNS, {}).report.chart.series.length >= 2);
  const one = comparisonFor(sheet([['Indicator', '2025', '2026'], ['Publishers', 10, 12]]), {}).report;
  assert.equal(one.chart, null);
});

test('a large worksheet is analysed quickly and the original cells are untouched', () => {
  const rows = [['Indicator', 'Jan 2026', 'Feb 2026']];
  for (let i = 0; i < 2500; i++) rows.push([`Indicator ${i}`, i, i + 1]);
  const s = sheet(rows);
  const before = JSON.stringify(s.cells);
  const t0 = Date.now();
  const c = comparisonFor(s, {});
  assert.ok(Date.now() - t0 < 3000, 'took ' + (Date.now() - t0) + ' ms');
  assert.equal(c.report.counts.indicators, 600);
  assert.ok(c.report.limitations.some((l) => /only the first 600/.test(l)));
  assert.ok(c.report.voice.length < 40, 'the spoken report stays short');
  assert.equal(JSON.stringify(s.cells), before);
});

test('two saved files are compared by indicator name, and only what both have', () => {
  const a = { snapshot: sheet([['Indicator', 'Count'], ['Publishers', 50], ['Regular pioneers', 10], ['Studies', 20]]), label: 'March 2026 visit', fileName: 'march.xlsx', sheetName: 'S1' };
  const b = { snapshot: sheet([['Indicator', 'Count'], ['Publishers', 56], ['Regular pioneers', 10], ['Deaf publishers', 2]]), label: 'September 2026 visit', fileName: 'sept.xlsx', sheetName: 'S1' };
  const r = reportForFiles(a, b, { congregationName: 'Alpha', sameCongregation: true });
  assert.equal(r.counts.compared, 2);
  assert.equal(byLabel(r, 'Publishers').percent, 12);
  assert.equal(r.table.some((x) => x.label === 'Studies' || x.label === 'Deaf publishers'), false);
  assert.ok(r.limitations.some((l) => /appear only in the earlier file/.test(l)) && r.limitations.some((l) => /only in the later file/.test(l)));
  assert.equal(reportForFiles({ ...a, snapshot: sheet([['x', 1]]) }, b, {}), null);
});
