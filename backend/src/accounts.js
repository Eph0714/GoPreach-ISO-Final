import { createHash, randomBytes } from 'node:crypto';
import { rateLimit } from './rateLimit.js';
import { loadActor } from './policy/index.js';
import { hashPassword, verifyPassword } from './passwords.js';
import { signAccessToken, ACCESS_TOKEN_SECONDS, personIdFromEmail } from './auth.js';
import { config } from './config.js';

/**
 * GoPreach's own sign-in (replaces Firebase Auth). The app still signs in with "<personId>@<domain>" and a password.
 *
 *   POST /v1/auth/login     { email, password }             -> { accessToken, refreshToken, expiresAt, personId }   (no login needed, rate limited)
 *   POST /v1/auth/refresh   { refreshToken }                -> a new pair (each refresh token works once)
 *   POST /v1/auth/logout    { refreshToken }                -> ends that session
 *   POST /v1/auth/reauth    { password }           (login)  -> a new pair stamped "password just typed"
 *   POST /v1/auth/password  { newPassword }        (login)  -> changes the caller's password; needs a password typed in the last 10 minutes
 *   POST /v1/auth/accounts  { personId, password } (login)  -> creates an account for someone else (Super-Admin or an admin-role holder only)
 *
 * Failed sign-ins are limited per address and per account, and a wrong password and an unknown account look identical.
 */
const REFRESH_DAYS = 90;
const RECENT_LOGIN_MS = 10 * 60_000;
const PERSON_ID_RE = /^[A-Za-z0-9_-]{6,64}$/;
const sha256 = (s) => createHash('sha256').update(s).digest('hex');

export function mountAccounts(app, store, auth, { secret = config.authSecret, firebaseHash = config.firebaseHash, limits = {} } = {}) {
  const enabled = (req, res, next) => (secret ? next() : res.status(503).json({ error: 'Sign-in is not configured on the server (AUTH_SECRET).' }));
  const loginLimit = rateLimit({ windowMs: 10 * 60_000, max: 30, ...limits.login });
  const accountFails = new Map(); // personId -> recent failure times
  const recentFails = (personId) => (accountFails.get(personId) ?? []).filter((t) => Date.now() - t < 15 * 60_000);
  const tooMany = (personId) => recentFails(personId).length >= 10;
  const noteFail = (personId) => accountFails.set(personId, [...recentFails(personId), Date.now()]);

  async function issue(personId, authTime) {
    const refreshToken = randomBytes(32).toString('base64url');
    await store.addRefreshToken({ tokenHash: sha256(refreshToken), personId, authTime, expiresAt: Date.now() + REFRESH_DAYS * 86_400_000 });
    return {
      accessToken: await signAccessToken(personId, authTime, secret),
      refreshToken,
      expiresAt: Date.now() + ACCESS_TOKEN_SECONDS * 1000,
      personId,
    };
  }

  async function checkPassword(personId, password) {
    const account = await store.getAccount(personId);
    if (!account || account.disabled) return { ok: false, account };
    const { ok, upgrade } = await verifyPassword(password, account.pwHash, firebaseHash);
    if (ok && upgrade) await store.setPassword(personId, await hashPassword(password)); // an imported Firebase hash becomes a native one
    return { ok, account };
  }

  app.post('/v1/auth/login', enabled, loginLimit, async (req, res) => {
    const { email, password } = req.body ?? {};
    const personId = personIdFromEmail(email);
    if (!personId || typeof password !== 'string' || password.length === 0 || password.length > 200) return res.status(401).json({ error: 'invalid-credentials' });
    try {
      if (tooMany(personId)) return res.status(429).json({ error: 'too-many-requests' });
      const { ok } = await checkPassword(personId, password);
      if (!ok) { noteFail(personId); return res.status(401).json({ error: 'invalid-credentials' }); }
      accountFails.delete(personId);
      await store.touchLogin(personId);
      res.json(await issue(personId, Date.now()));
    } catch (e) {
      console.error('login failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/auth/refresh', enabled, loginLimit, async (req, res) => {
    const token = req.body?.refreshToken;
    if (typeof token !== 'string' || token.length > 200) return res.status(401).json({ error: 'invalid-token' });
    try {
      const row = await store.takeRefreshToken(sha256(token));
      if (!row) return res.status(401).json({ error: 'invalid-token' });
      const account = await store.getAccount(row.personId);
      if (!account || account.disabled) return res.status(401).json({ error: 'invalid-token' });
      res.json(await issue(row.personId, row.authTime));
    } catch (e) {
      console.error('refresh failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/auth/logout', enabled, async (req, res) => {
    const token = req.body?.refreshToken;
    if (typeof token === 'string' && token.length <= 200) await store.takeRefreshToken(sha256(token)).catch(() => {});
    res.json({ ok: true });
  });

  app.post('/v1/auth/reauth', enabled, auth, loginLimit, async (req, res) => {
    const password = req.body?.password;
    if (typeof password !== 'string' || !password) return res.status(401).json({ error: 'invalid-credentials' });
    try {
      if (tooMany(req.personId)) return res.status(429).json({ error: 'too-many-requests' });
      const { ok } = await checkPassword(req.personId, password);
      if (!ok) { noteFail(req.personId); return res.status(401).json({ error: 'invalid-credentials' }); }
      res.json(await issue(req.personId, Date.now()));
    } catch (e) {
      console.error('reauth failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/auth/password', enabled, auth, async (req, res) => {
    const newPassword = req.body?.newPassword;
    if (typeof newPassword !== 'string' || newPassword.length < 6 || newPassword.length > 200) return res.status(400).json({ error: 'weak-password' });
    if (!(Date.now() - (req.authTime ?? 0) <= RECENT_LOGIN_MS)) return res.status(403).json({ error: 'requires-recent-login' });
    try {
      if (!(await store.getAccount(req.personId))) return res.status(404).json({ error: 'invalid-user' });
      await store.setPassword(req.personId, await hashPassword(newPassword));
      await store.deleteRefreshTokens(req.personId); // every other signed-in phone must sign in again
      res.json(await issue(req.personId, req.authTime));
    } catch (e) {
      console.error('password change failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/auth/accounts', enabled, auth, async (req, res) => {
    const { personId, password } = req.body ?? {};
    if (typeof personId !== 'string' || !PERSON_ID_RE.test(personId)) return res.status(400).json({ error: 'bad-person-id' });
    if (typeof password !== 'string' || password.length < 6 || password.length > 200) return res.status(400).json({ error: 'weak-password' });
    try {
      const actor = await loadActor(req.personId, (c, i) => store.get(c, i));
      if (!actor.known || !(actor.isSuperAdmin || actor.adminRole)) return res.status(403).json({ error: 'Not allowed' });
      if (!(await store.createAccount(personId, await hashPassword(password)))) return res.status(409).json({ error: 'email-already-in-use' });
      res.status(201).json({ ok: true });
    } catch (e) {
      console.error('account creation failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });
}
