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
import { mountSecurity } from './security.js';

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

  /** A passkey sign-in that was verified (a passkey is its own second factor): opens a device session and issues the tokens. Any number of devices may be signed in at once. */
  async function startSession(personId, authTime, { device } = {}) {
    const ds = await sec.createSession(personId, sec.deviceInfo(device), null, 'passkey');
    return { ...(await issue(personId, authTime, ds.sessionId)), sessionId: ds.sessionId };
  }
  const refuseActive = (res, active) => res.status(409).json({ error: 'session-active', device: active.device ?? null, lastActiveAt: active.lastActiveAt });

  const sec = mountSecurity(app, { store, auth, enabled, mailer, issue, secret, limits: limits.security });
  // Older apps still ask for login requests: there are none any more (devices no longer queue behind one session), so they simply see an empty list.
  const approvals = mountLoginApproval(app, {
    store, auth, enabled, mailer, limits: limits.approval, requestMs: loginRequestMs, getSettings: () => sec.approvalSettings(),
    onDecision: (personId, type, c, req) => sec.event(personId, type, { result: type.endsWith('APPROVED') ? 'success' : 'rejected', notify: true, by: req?.personId ?? personId, meta: { device: c.data.device?.name ?? c.data.device ?? null, ip: c.data.ip ?? null, requestId: c.id, via: c.data.approvedBy ?? 'device' } }),
  });

  mountPasskeys(app, { store, auth, enabled, loginLimit, issue, startSession, refuseActive, passkeyConfig: limits.passkey ?? config.passkey });

  async function checkPassword(personId, password) {
    const account = await store.getAccount(personId);
    if (!account || account.disabled) return { ok: false, account };
    const { ok, upgrade } = await verifyPassword(password, account.pwHash, firebaseHash);
    if (ok && upgrade) await store.setPassword(personId, await hashPassword(password)); // an imported Firebase hash becomes a native one
    return { ok, account };
  }

  app.post('/v1/auth/login', enabled, sprayLimit, loginLimit, async (req, res) => {
    const { email, password, device, trustedToken, approval } = req.body ?? {};
    const personId = personIdFromEmail(email);
    if (!personId || typeof password !== 'string' || password.length === 0 || password.length > 200) return res.status(401).json({ error: 'invalid-credentials' });
    try {
      if (tooMany(personId)) return res.status(429).json({ error: 'too-many-requests' });
      const { ok, account } = await checkPassword(personId, password);
      if (!ok) {
        noteFail(personId);
        if (account) await sec.event(personId, 'LOGIN_FAILED', { result: 'failure', meta: { device: sec.deviceInfo(device).name } }); // an unknown account is never named in the log
        return res.status(401).json({ error: 'invalid-credentials' });
      }
      accountFails.delete(personId);
      await store.touchLogin(personId);
      // An account that asks for approval of new devices: a device it has not trusted gets a login request, and NOTHING is issued (no session, no tokens) until the request is
      // approved by one of the account's own signed-in devices or settled by an e-mail code; the approval is spent here, once. A correct password alone never opens it.
      if (await sec.approvalRequired(personId)) {
        const info = sec.deviceInfo(device);
        if (!(await sec.deviceIsTrusted(personId, trustedToken, info))) {
          const approved = approval && typeof approval === 'object' ? await approvals.redeem(personId, approval) : false;
          if (!approved) {
            const request = await approvals.createRequest(personId, { device: info.name, lastActiveAt: null }, { device: info, ip: req.ip });
            if (request.tooMany) return res.set('Retry-After', String(request.retryAfter ?? 60)).status(429).json({ error: 'too-many-requests', retryAfter: request.retryAfter ?? 60 });
            await sec.event(personId, 'LOGIN_APPROVAL_REQUESTED', { result: 'pending', deviceId: info.deviceId, notify: true, meta: { device: info.name, os: info.os, ip: request.ip ?? null, requestId: request.requestId, expiresAt: request.expiresAt } });
            return res.status(409).json({ error: 'session-active', ...request });
          }
          await sec.event(personId, 'LOGIN_APPROVAL_USED', { deviceId: info.deviceId, meta: { device: info.name } });
        }
      }
      // The password is right. An account with two-factor sign-in answers 202 and the device must prove the second factor (a trusted device may skip it); nothing else is
      // issued before that. Without 2FA the sign-in completes. Either way ANY number of devices may be signed in; each one is a session the account holder can end.
      const gate = await sec.gate(personId, { device, ip: req.ip, trustedToken });
      if (gate.challenge) return res.status(202).json(gate.challenge);
      const ds = await sec.createSession(personId, gate.info, req.ip, gate.method);
      res.json({ ...(await issue(personId, Date.now(), ds.sessionId)), sessionId: ds.sessionId });
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
      let sessionId = row.sessionId;
      const device = sessionId ? await store.secGet('dsession', sessionId) : null;
      if (device) {
        // a revoked (or someone else's) device session can never renew
        if (device.personId !== row.personId || device.data.status !== 'active') return res.status(401).json({ error: 'session-ended' });
        await store.secPut('dsession', device.id, device.personId, { ...device.data, lastActiveAt: Date.now() });
      } else {
        const session = await store.getSession(row.personId);
        if (session ? session.sessionId !== sessionId : sessionId) return res.status(401).json({ error: 'session-ended' }); // replaced or ended elsewhere
        if (!session) { // a sign-in from before sessions existed becomes a device session
          const ds = await sec.createSession(row.personId, sec.deviceInfo(null), null, 'password');
          sessionId = ds.sessionId;
        } else await store.touchSession(row.personId, sessionId, Date.now());
      }
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
      if (row?.sessionId) {
        await store.endSession(row.personId, row.sessionId).catch(() => {});
        await sec.endOwn(row.personId, row.sessionId).catch(() => {}); // the device session ends: its access token is refused from now on
      }
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
      await sec.onPasswordChanged(req.personId, req.sessionId); // their sessions end now, trusted devices are no longer trusted
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
      await sec.onPasswordReset(personId, req.personId);
      await audit(store, req.personId, 'ACCOUNT_PASSWORD_RESET', 'Person', personId, 'temporary password set (the password itself is never recorded)');
      res.json({ ok: true });
    } catch (e) {
      console.error('password reset failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });
}
