import { createHash } from 'node:crypto';
import { rateLimit } from './rateLimit.js';
import { hashPassword, verifyPassword } from './passwords.js';
import { personIdFromEmail } from './auth.js';

/**
 * PIN and Pattern sign-in for the WEBSITE, the browser equivalent of the app's on-device PIN / Pattern.
 *
 * The app keeps the account's password encrypted on the phone and unlocks it with the PIN. A browser has no safe place for a password, so here the
 * secret is verified by the SERVER instead and replaces only the password check; everything after it is the normal sign-in (two-factor, new-device
 * approval, device session, security log). Nothing is a way around those.
 *
 *   POST /v1/auth/quick-login/enroll     { method, secret, deviceKey }   (login, password typed in the last 10 minutes) -> saves it for this browser
 *   POST /v1/auth/quick-login/disable    { method?, deviceKey }          (login) -> removes it (one method, or both) from this browser
 *   POST /v1/auth/quick-login/status      { deviceKey }                   (login) -> { pin, pattern } for the caller on this browser
 *   POST /v1/public/quick-login/available { email, deviceKey }            -> { pin, pattern } (booleans only, and only with this browser's secret key)
 *   POST /v1/auth/quick-login/login      { email, method, secret, deviceKey, device, trustedToken, approval } -> like /v1/auth/login
 *
 * A "deviceKey" is 32 random bytes the website keeps in an encrypted, HTTP-only cookie; the server stores only its hash. The PIN / pattern is stored
 * as a salted scrypt hash, never the secret. Same throttle as the app: 5 wrong tries lock it (30 s, doubling to 15 min), 15 switch it off.
 */
const FAILURES_BEFORE_LOCK = 5;
const FAILURES_BEFORE_DISABLE = 15;
const BASE_LOCK_MS = 30_000;
const MAX_LOCK_MS = 15 * 60_000;
const COMMON_PINS = new Set(['123456', '654321', '123123', '112233', '121212', '696969', '159753', '147258', '258369', '123321', '111222', '101010', '202020', '246810', '135790', '142536', '159357', '789456']);
const sha256 = (s) => createHash('sha256').update(s).digest('hex');

export const lockDurationMs = (failures) => {
  if (failures < FAILURES_BEFORE_LOCK || failures % FAILURES_BEFORE_LOCK !== 0) return 0;
  return Math.min(BASE_LOCK_MS * 2 ** Math.min(failures / FAILURES_BEFORE_LOCK - 1, 10), MAX_LOCK_MS);
};

/** null when [pin] is acceptable, else why not (the same rules as the app's QuickLoginPolicy). */
export function pinProblem(pin) {
  if (typeof pin !== 'string' || !/^\d{6}$/.test(pin)) return 'Use exactly 6 digits.';
  if ([...pin].every((c) => c === pin[0])) return 'That PIN is too easy to guess. Avoid repeating one digit.';
  const steps = [...pin].slice(1).map((c, i) => Number(c) - Number(pin[i]));
  if (steps.every((s) => s === 1) || steps.every((s) => s === -1)) return 'That PIN is too easy to guess. Avoid running digits like 123456.';
  if (COMMON_PINS.has(pin)) return 'That PIN is too common. Choose a less predictable one.';
  if (pin.slice(0, 3) === pin.slice(3)) return 'That PIN is too easy to guess. Avoid repeating a short group.';
  return null;
}

/** [secret] is the dots in the order drawn, "0-4-8-..." (0..8, row by row); the same rules as the app. */
export function patternProblem(secret) {
  if (typeof secret !== 'string' || !/^\d(-\d){0,8}$/.test(secret)) return 'That pattern isn\'t valid. Try again.';
  const nodes = secret.split('-').map(Number);
  if (nodes.length < 6) return 'Connect at least 6 dots.';
  if (new Set(nodes).size !== nodes.length) return 'That pattern isn\'t valid. Try again.';
  const steps = nodes.slice(1).map((n, i) => n - nodes[i]);
  if (steps.every((s) => s === 1) || steps.every((s) => s === -1)) return 'That pattern is too easy to guess. Avoid drawing the dots in order.';
  return null;
}

const problemOf = (method, secret) => (method === 'pin' ? pinProblem(secret) : method === 'pattern' ? patternProblem(secret) : 'Unknown method.');
const validKey = (k) => typeof k === 'string' && k.length >= 32 && k.length <= 100;

/** [finishSignIn(req, res, personId, body)] is the shared tail of the password sign-in (approval, second factor, session, tokens). */
export function mountQuickLogin(app, { store, auth, enabled, sec, finishSignIn, limits = {} }) {
  const recent = (req) => Date.now() - (req.authTime ?? 0) <= 10 * 60_000;
  const keyLimit = rateLimit({ windowMs: 10 * 60_000, max: 60, key: (req) => `${req.ip}|${sha256(String(req.body?.deviceKey ?? '')).slice(0, 16)}`, ...limits.quick });
  const actLimit = rateLimit({ windowMs: 10 * 60_000, max: 60, key: (req) => req.personId ?? req.ip, ...limits.act });
  const docId = (personId, method, deviceKey) => `${personId}:${method}:${sha256(deviceKey).slice(0, 40)}`;
  const getDoc = (personId, method, deviceKey) => store.secGet('qlogin', docId(personId, method, deviceKey));

  app.post('/v1/auth/quick-login/enroll', enabled, auth, actLimit, async (req, res) => {
    const { method, secret, deviceKey } = req.body ?? {};
    if (!validKey(deviceKey)) return res.status(400).json({ error: 'This browser cannot keep a PIN or pattern.' });
    if (!recent(req)) return res.status(403).json({ error: 'requires-recent-login' });
    const problem = problemOf(method, secret);
    if (problem) return res.status(422).json({ error: problem });
    const hash = await hashPassword(secret);
    await store.secPut('qlogin', docId(req.personId, method, deviceKey), req.personId, { method, hash, failures: 0, lockedUntil: 0, createdAt: Date.now() });
    await sec.event(req.personId, 'QUICK_LOGIN_ENABLED', { meta: { method } });
    res.json({ ok: true });
  });

  app.post('/v1/auth/quick-login/disable', enabled, auth, actLimit, async (req, res) => {
    const { method, deviceKey } = req.body ?? {};
    if (!validKey(deviceKey)) return res.status(400).json({ error: 'Unknown browser.' });
    for (const m of method ? [method] : ['pin', 'pattern']) {
      if (m !== 'pin' && m !== 'pattern') continue;
      await store.secDelete('qlogin', docId(req.personId, m, deviceKey));
      await sec.event(req.personId, 'QUICK_LOGIN_DISABLED', { meta: { method: m } });
    }
    res.json({ ok: true });
  });

  app.post('/v1/auth/quick-login/status', enabled, auth, actLimit, async (req, res) => {
    const { deviceKey } = req.body ?? {};
    if (!validKey(deviceKey)) return res.json({ pin: false, pattern: false });
    res.json({ pin: !!(await getDoc(req.personId, 'pin', deviceKey)), pattern: !!(await getDoc(req.personId, 'pattern', deviceKey)) });
  });

  app.post('/v1/public/quick-login/available', enabled, keyLimit, async (req, res) => {
    const { email, deviceKey } = req.body ?? {};
    const none = { pin: false, pattern: false };
    if (!validKey(deviceKey)) return res.json(none);
    const personId = personIdFromEmail(email);
    if (!personId) return res.json(none);
    res.json({ pin: !!(await getDoc(personId, 'pin', deviceKey)), pattern: !!(await getDoc(personId, 'pattern', deviceKey)) });
  });

  app.post('/v1/auth/quick-login/login', enabled, keyLimit, async (req, res) => {
    const { email, method, secret, deviceKey } = req.body ?? {};
    const wrong = () => res.status(401).json({ error: 'invalid-credentials' });
    const personId = personIdFromEmail(email);
    if ((method !== 'pin' && method !== 'pattern') || !personId || !validKey(deviceKey) || typeof secret !== 'string' || secret.length > 40) return wrong();
    try {
      const d = await getDoc(personId, method, deviceKey);
      const account = d ? await store.getAccount(personId) : null;
      if (!d || !account || account.disabled) return wrong(); // looks exactly like a wrong secret: nothing about the account is revealed
      const remaining = (d.data.lockedUntil ?? 0) - Date.now();
      if (remaining > 0) return res.status(429).json({ error: 'locked', retryAfterMs: remaining });
      const { ok } = await verifyPassword(secret, d.data.hash, null);
      if (!ok) {
        const failures = (d.data.failures ?? 0) + 1;
        await sec.event(personId, 'LOGIN_FAILED', { result: 'failure', meta: { method } });
        if (failures >= FAILURES_BEFORE_DISABLE) {
          await store.secDelete('qlogin', d.id);
          await sec.event(personId, 'QUICK_LOGIN_DISABLED', { meta: { method, reason: 'too-many-wrong-tries' } });
          return res.status(401).json({ error: 'disabled' });
        }
        const lock = lockDurationMs(failures);
        await store.secPut('qlogin', d.id, personId, { ...d.data, failures, lockedUntil: lock ? Date.now() + lock : 0 });
        return lock ? res.status(429).json({ error: 'locked', retryAfterMs: lock }) : res.status(401).json({ error: 'invalid-credentials', triesLeft: FAILURES_BEFORE_LOCK - (failures % FAILURES_BEFORE_LOCK) });
      }
      await store.secPut('qlogin', d.id, personId, { ...d.data, failures: 0, lockedUntil: 0, lastUsedAt: Date.now() });
      await store.touchLogin(personId);
      return await finishSignIn(req, res, personId, `${method}`);
    } catch (e) {
      console.error('quick login failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  /** Called when the person's password changes: a PIN / pattern must never outlive the password it was set up with. */
  const revokeAll = async (personId) => {
    for (const d of await store.secList('qlogin', personId)) await store.secDelete('qlogin', d.id);
  };
  return { revokeAll };
}
