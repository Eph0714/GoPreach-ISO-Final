import { createHash, randomBytes } from 'node:crypto';
import { rateLimit } from './rateLimit.js';
import { loadActor } from './policy/index.js';
import { loadGrant, canManageCredentials } from './policy/rules.js';
import { hashPassword, verifyPassword } from './passwords.js';
import { signAccessToken, ACCESS_TOKEN_SECONDS, personIdFromEmail } from './auth.js';
import { config } from './config.js';
import { mountPasskeys } from './passkeys.js';
import { audit } from './coAccess.js';
import { mountLoginApproval } from './loginApproval.js';

/**
 * GoPreach's own sign-in (replaces Firebase Auth). The app still signs in with "<personId>@<domain>" and a password.
 *
 *   POST /v1/auth/login     { email, password }             -> { accessToken, refreshToken, expiresAt, personId }   (no login needed, rate limited)
 *   POST /v1/auth/refresh   { refreshToken }                -> a new pair (each refresh token works once)
 *   POST /v1/auth/logout    { refreshToken }                -> ends that session
 *   POST /v1/auth/reauth    { password }           (login)  -> a new pair stamped "password just typed"
 *   POST /v1/auth/password  { newPassword }        (login)  -> changes the caller's password; needs a password typed in the last 10 minutes
 *   POST /v1/auth/accounts  { personId, password } (login)  -> creates an account for someone else (Super-Admin or an admin-role holder only)
 *   POST /v1/auth/accounts/:personId/password { password } (login) -> sets a temporary password for someone whose credentials the caller may manage
 *
 * Failed sign-ins are limited per address and per account, and a wrong password and an unknown account look identical.
 *
 * One account, one session. A password sign-in that is not the only one answers 409 { error: 'session-active', device, lastActiveAt, requestId, pollToken, methods } once the
 * PASSWORD is right (nothing is revealed before that) and starts a login request the signed-in device must approve (see loginApproval.js); a passkey sign-in repeated with
 * endOtherSession: true takes the session over (a verified passkey is its own proof). Taking over revokes the other session on the server: its
 * refresh tokens are deleted and its access tokens (they carry the session id) are refused with 401 { error: 'session-ended' }. A session nobody has used for
 * SESSION_IDLE_MS no longer blocks anyone. The device label is only what the app said about itself when it signed in.
 */
const REFRESH_DAYS = 90;
const RECENT_LOGIN_MS = 10 * 60_000;
export const SESSION_IDLE_MS = 12 * 3_600_000;
const cleanDevice = (d) => (typeof d === 'string' ? d.replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, 80) || null : null);
const PERSON_ID_RE = /^[A-Za-z0-9_-]{6,64}$/;
const sha256 = (s) => createHash('sha256').update(s).digest('hex');

export function mountAccounts(app, store, auth, { secret = config.authSecret, firebaseHash = config.firebaseHash, limits = {}, mailer = null, loginRequestMs } = {}) {
  const enabled = (req, res, next) => (secret ? next() : res.status(503).json({ error: 'Sign-in is not configured on the server (AUTH_SECRET).' }));
  // The website signs everybody in from one server address, so the limit follows the address AND the account tried, and a session renewal has a limit of its own:
  // renewals used to share the sign-in allowance and quietly used it up. Wrong passwords are still capped per account below (10 in 15 minutes).
  const accountKey = (req) => `${req.ip}|${String(req.body?.email ?? req.body?.username ?? '').toLowerCase().slice(0, 200)}`;
  const loginLimit = rateLimit({ windowMs: 10 * 60_000, max: 30, key: accountKey, ...limits.login });
  const sprayLimit = rateLimit({ windowMs: 10 * 60_000, max: 400, key: (req) => req.ip, ...limits.spray }); // one address trying many accounts
  const refreshLimit = rateLimit({ windowMs: 10 * 60_000, max: 600, key: (req) => req.ip, ...limits.refresh });
  const accountFails = new Map(); // personId -> recent failure times
  const recentFails = (personId) => (accountFails.get(personId) ?? []).filter((t) => Date.now() - t < 15 * 60_000);
  const tooMany = (personId) => recentFails(personId).length >= 10;
  const noteFail = (personId) => accountFails.set(personId, [...recentFails(personId), Date.now()]);

  async function issue(personId, authTime, sessionId = null) {
    const refreshToken = randomBytes(32).toString('base64url');
    await store.addRefreshToken({ tokenHash: sha256(refreshToken), personId, authTime, expiresAt: Date.now() + REFRESH_DAYS * 86_400_000, sessionId });
    return {
      accessToken: await signAccessToken(personId, authTime, secret, sessionId),
      refreshToken,
      expiresAt: Date.now() + ACCESS_TOKEN_SECONDS * 1000,
      personId,
    };
  }

  /** A password / passkey sign-in that was verified: claims the account's session, then issues the tokens. { conflict } when another live session holds it. */
  async function startSession(personId, authTime, { device, endOtherSession } = {}) {
    const sessionId = randomBytes(18).toString('base64url');
    const claim = await store.claimSession(personId, { sessionId, device: cleanDevice(device), now: Date.now(), idleMs: SESSION_IDLE_MS, replace: endOtherSession === true });
    if (!claim.ok) return { conflict: claim.active };
    await store.deleteRefreshTokens(personId); // nothing of any earlier session survives
    return issue(personId, authTime, sessionId);
  }
  const refuseActive = (res, active) => res.status(409).json({ error: 'session-active', device: active.device ?? null, lastActiveAt: active.lastActiveAt });

  const approvals = mountLoginApproval(app, { store, auth, enabled, mailer, limits: limits.approval, requestMs: loginRequestMs });

  mountPasskeys(app, { store, auth, enabled, loginLimit, issue, startSession, refuseActive, passkeyConfig: limits.passkey ?? config.passkey });

  async function checkPassword(personId, password) {
    const account = await store.getAccount(personId);
    if (!account || account.disabled) return { ok: false, account };
    const { ok, upgrade } = await verifyPassword(password, account.pwHash, firebaseHash);
    if (ok && upgrade) await store.setPassword(personId, await hashPassword(password)); // an imported Firebase hash becomes a native one
    return { ok, account };
  }

  app.post('/v1/auth/login', enabled, sprayLimit, loginLimit, async (req, res) => {
    const { email, password, device, approval } = req.body ?? {};
    const personId = personIdFromEmail(email);
    if (!personId || typeof password !== 'string' || password.length === 0 || password.length > 200) return res.status(401).json({ error: 'invalid-credentials' });
    try {
      if (tooMany(personId)) return res.status(429).json({ error: 'too-many-requests' });
      const { ok } = await checkPassword(personId, password);
      if (!ok) { noteFail(personId); return res.status(401).json({ error: 'invalid-credentials' }); }
      accountFails.delete(personId);
      await store.touchLogin(personId);
      // The password alone never takes over a live session (a client's endOtherSession is ignored): the other device must approve, or an e-mail code / passkey must prove it.
      // An approved login request (the password is right, and the request was approved by the signed-in device or by an e-mail code) is spent here, once.
      const approved = approval && typeof approval === 'object' ? await approvals.redeem(personId, approval) : false;
      const started = await startSession(personId, Date.now(), { device, endOtherSession: approved });
      if (started.conflict) {
        const request = await approvals.createRequest(personId, started.conflict, { device: cleanDevice(device), ip: req.ip });
        if (request.tooMany) return res.status(429).json({ error: 'too-many-requests' });
        return res.status(409).json({ error: 'session-active', ...request });
      }
      res.json(started);
    } catch (e) {
      console.error('login failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/auth/refresh', enabled, refreshLimit, async (req, res) => {
    const token = req.body?.refreshToken;
    if (typeof token !== 'string' || token.length > 200) return res.status(401).json({ error: 'invalid-token' });
    try {
      const row = await store.takeRefreshToken(sha256(token));
      if (!row) return res.status(401).json({ error: 'invalid-token' });
      const account = await store.getAccount(row.personId);
      if (!account || account.disabled) return res.status(401).json({ error: 'invalid-token' });
      const session = await store.getSession(row.personId);
      let sessionId = row.sessionId;
      if (session ? session.sessionId !== sessionId : sessionId) return res.status(401).json({ error: 'session-ended' }); // replaced or ended elsewhere
      if (!session) { // a sign-in from before sessions existed adopts the first one
        sessionId = randomBytes(18).toString('base64url');
        const claim = await store.claimSession(row.personId, { sessionId, device: null, now: Date.now(), idleMs: SESSION_IDLE_MS, replace: false });
        if (!claim.ok) return res.status(401).json({ error: 'session-ended' });
      } else await store.touchSession(row.personId, sessionId, Date.now());
      res.json(await issue(row.personId, row.authTime, sessionId));
    } catch (e) {
      console.error('refresh failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/auth/logout', enabled, async (req, res) => {
    const token = req.body?.refreshToken;
    if (typeof token === 'string' && token.length <= 200) {
      const row = await store.takeRefreshToken(sha256(token)).catch(() => null);
      if (row?.sessionId) await store.endSession(row.personId, row.sessionId).catch(() => {}); // signing out frees the account for the next sign-in
    }
    res.json({ ok: true });
  });

  app.post('/v1/auth/reauth', enabled, auth, loginLimit, async (req, res) => {
    const password = req.body?.password;
    if (typeof password !== 'string' || !password) return res.status(401).json({ error: 'invalid-credentials' });
    try {
      if (tooMany(req.personId)) return res.status(429).json({ error: 'too-many-requests' });
      const { ok } = await checkPassword(req.personId, password);
      if (!ok) { noteFail(req.personId); return res.status(401).json({ error: 'invalid-credentials' }); }
      res.json(await issue(req.personId, Date.now(), req.sessionId));
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
      res.json(await issue(req.personId, req.authTime, req.sessionId));
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

  app.post('/v1/auth/accounts/:personId/password', enabled, auth, async (req, res) => {
    const { personId } = req.params;
    const { password } = req.body ?? {};
    if (!PERSON_ID_RE.test(personId)) return res.status(400).json({ error: 'bad-person-id' });
    if (typeof password !== 'string' || password.length < 6 || password.length > 200) return res.status(400).json({ error: 'weak-password' });
    try {
      const get = (c, i) => store.get(c, i);
      const actor = await loadActor(req.personId, get, (c, cong) => store.list(c, cong));
      if (!actor.known || !(await canManageCredentials(actor, await loadGrant(req.personId, get), personId, get))) return res.status(403).json({ error: 'Not allowed' });
      if (!(await store.getAccount(personId))) return res.status(404).json({ error: 'invalid-user' });
      await store.setPassword(personId, await hashPassword(password));
      await store.deleteRefreshTokens(personId); // sessions made with the old password end
      await store.endSession(personId);
      await audit(store, req.personId, 'ACCOUNT_PASSWORD_RESET', 'Person', personId, 'temporary password set (the password itself is never recorded)');
      res.json({ ok: true });
    } catch (e) {
      console.error('password reset failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });
}
