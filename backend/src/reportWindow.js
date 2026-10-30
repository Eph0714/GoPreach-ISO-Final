/**
 * When a Publisher may SEND the Monthly Report: only on the last two calendar days of the reporting month, by the server's clock in the
 * service-month time zone (Asia/Manila, UTC+8, no daylight saving). Nothing is hardcoded: 31-day months open on the 30th, 30-day months on
 * the 29th, February on the 27th (28 days) or the 28th (29 days in a leap year).
 *
 * A report for a month that has already ended can still be sent late (the apps always allowed it); a month that has not started cannot be
 * reported. Preparing and editing a report is never restricted by this module, only sending it.
 *
 *   reportWindow(periodMonth, now)  ->  { state: 'before' | 'open' | 'late' | 'future', open, firstDay, lastDay, daysInMonth, label }
 *
 * `periodMonth` is the start of the service month (ms, any instant inside the 1st of that month in Manila is accepted) and `now` is the
 * server time in ms.
 */
export const MANILA_OFFSET_MS = 8 * 3600_000;
const WINDOW_DAYS = 2;

/** { year, month (1-12), day } of an instant in Manila. */
export function manilaDate(ms) {
  const d = new Date(Number(ms) + MANILA_OFFSET_MS);
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
}

export function daysInMonth(year, month) {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

export function reportWindow(periodMonth, now) {
  // 12 hours into the month's first day in Manila, so a start stored in a neighbouring zone still lands on the right month
  const p = manilaDate(Number(periodMonth) + 12 * 3600_000), n = manilaDate(now);
  const total = daysInMonth(p.year, p.month), firstDay = total - WINDOW_DAYS + 1;
  const label = firstDay === total - 1 ? `${MONTHS[p.month - 1]} ${firstDay} and ${total}` : `${MONTHS[p.month - 1]} ${firstDay}-${total}`;
  const ordinal = p.year * 12 + p.month, current = n.year * 12 + n.month;
  let state;
  if (ordinal > current) state = 'future';
  else if (ordinal < current) state = 'late';
  else state = n.day >= firstDay ? 'open' : 'before';
  return { state, open: state === 'open' || state === 'late', firstDay, lastDay: total, daysInMonth: total, label };
}

/** The message a Publisher reads when the report cannot be sent yet. */
export function windowMessage(w) {
  if (w.state === 'future') return 'This month has not started, so its report cannot be sent yet.';
  return `The report for this month can be sent on the last two days of the month (${w.label}). You can prepare it now.`;
}
