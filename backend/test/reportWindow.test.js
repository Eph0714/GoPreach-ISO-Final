import { test } from 'node:test';
import assert from 'node:assert/strict';
import { reportWindow, manilaDate, daysInMonth, windowMessage } from '../src/reportWindow.js';

// Manila noon of a calendar date, as a server instant
const at = (y, m, d, h = 12) => Date.UTC(y, m - 1, d, h) - 8 * 3600_000;
const periodOf = (y, m) => at(y, m, 1, 0);
const stateOn = (y, m, d, h) => reportWindow(periodOf(y, m), at(y, m, d, h)).state;

test('a 31-day month opens on the 30th and the 31st only', () => {
  assert.equal(stateOn(2026, 10, 29), 'before');
  assert.equal(stateOn(2026, 10, 30), 'open');
  assert.equal(stateOn(2026, 10, 31), 'open');
});

test('a 30-day month opens on the 29th and the 30th only', () => {
  assert.equal(stateOn(2026, 11, 28), 'before');
  assert.equal(stateOn(2026, 11, 29), 'open');
  assert.equal(stateOn(2026, 11, 30), 'open');
});

test('February is worked out from the real calendar: 27-28 normally, 28-29 in a leap year', () => {
  assert.equal(daysInMonth(2027, 2), 28);
  assert.equal(stateOn(2027, 2, 26), 'before');
  assert.equal(stateOn(2027, 2, 27), 'open');
  assert.equal(stateOn(2027, 2, 28), 'open');
  assert.equal(daysInMonth(2028, 2), 29);
  assert.equal(stateOn(2028, 2, 27), 'before');
  assert.equal(stateOn(2028, 2, 28), 'open');
  assert.equal(stateOn(2028, 2, 29), 'open');
  assert.equal(daysInMonth(2100, 2), 28, '2100 is not a leap year');
});

test('the day is the day in Manila, not in UTC: 23:30 on the 29th in Manila is still the 29th', () => {
  const late29 = at(2026, 10, 29, 23) + 30 * 60_000;   // 23:30 Manila = 15:30 UTC the same day
  assert.equal(reportWindow(periodOf(2026, 10), late29).state, 'before');
  const early30 = at(2026, 10, 30, 0) + 60_000;        // 00:01 Manila on the 30th = 16:01 UTC on the 29th
  assert.equal(reportWindow(periodOf(2026, 10), early30).state, 'open');
  assert.deepEqual(manilaDate(early30), { year: 2026, month: 10, day: 30 });
});

test('a month that has ended can still be sent late; one that has not started cannot', () => {
  assert.equal(reportWindow(periodOf(2026, 9), at(2026, 10, 5)).state, 'late');
  assert.equal(reportWindow(periodOf(2026, 9), at(2026, 10, 5)).open, true);
  assert.equal(reportWindow(periodOf(2026, 11), at(2026, 10, 31)).state, 'future');
  assert.equal(reportWindow(periodOf(2026, 11), at(2026, 10, 31)).open, false);
});

test('a month start stored a few hours off (another zone) still means the same service month', () => {
  const utcMidnight = Date.UTC(2026, 9, 1, 0);          // 08:00 Manila on 1 October
  const localMidnightElsewhere = Date.UTC(2026, 8, 30, 22);   // 06:00 Manila on 1 October
  assert.equal(reportWindow(utcMidnight, at(2026, 10, 30)).state, 'open');
  assert.equal(reportWindow(localMidnightElsewhere, at(2026, 10, 30)).state, 'open');
});

test('the message names the days', () => {
  assert.match(windowMessage(reportWindow(periodOf(2026, 10), at(2026, 10, 3))), /October 30 and 31/);
  assert.match(windowMessage(reportWindow(periodOf(2028, 2), at(2028, 2, 3))), /February 28 and 29/);
  assert.match(windowMessage(reportWindow(periodOf(2026, 11), at(2026, 10, 3))), /not started/);
});
