import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { rateLimit } from './rateLimit.js';
import { config } from './config.js';
import { hashPassword } from './passwords.js';
import { maskEmail } from './loginApproval.js';
import { loadActor } from './policy/index.js';

/**
 * Two-factor sign-in, device sessions, trusted devices, remote logout and the security log of an account.
 *
 *   SIGN-IN   POST /v1/auth/login (accounts.js) -> password right -> this module decides: no 2FA / a trusted device -> tokens; else 202 { twoFactorRequired, challengeId,
 *             challengeToken, methods } and POST /v1/auth/2fa/verify { challengeId, challengeToken, code | recoveryCode | emailCode, trustDevice? } -> tokens.
 *             POST /v1/auth/2fa/email-code { challengeId, challengeToken } sends a code to the verified recovery e-mail.
 *   SETTINGS  GET  /v1/security/status
 *             POST /v1/security/2fa/totp/setup        (recent login)      -> { secret, otpauthUri }  (pending until confirmed)
 *             POST /v1/security/2fa/totp/confirm      { code }            -> turns 2FA on, returns the recovery codes ONCE
 *             POST /v1/security/2fa/disable           { code | recoveryCode }  (recent login)
 *             POST /v1/security/2fa/recovery-codes    { code | recoveryCode }  (recent login) -> 10 new codes (the old ones stop working)
 *             POST /v1/security/recovery-email        { email } (recent login) -> sends a code;  POST /v1/security/recovery-email/verify { code }
 *   DEVICES   GET  /v1/security/devices               -> this account's sessions (current one marked) and trusted devices; never a token
 *             POST /v1/security/devices/:id/rename    { name }
 *             POST /v1/security/devices/:id/revoke    (recent login)      -> that session ends NOW: its refresh tokens are deleted and its access tokens are refused
 *             POST /v1/security/devices/revoke-others (recent login)      -> every session but this one
 *             POST /v1/security/devices/revoke-all    (recent login)      -> every session, this one included
 *             POST /v1/security/trusted/current       { code | recoveryCode } (recent login) -> { trustedToken } for THIS device only (the device keeps it, secretly)
 *             POST /v1/security/trusted/:id/remove
 *   LOG       GET  /v1/security/events ; GET /v1/security/notices ; POST /v1/security/notices/seen
 *   ADMIN     GET  /v1/admin/security/sessions?q&status&type&role&congregation&since&limit    Super Admin: everyone; Admin: their own congregation
 *             GET  /v1/admin/security/users/:personId/history
 *             POST /v1/admin/security/sessions/:sessionId/revoke ; POST /v1/admin/security/users/:personId/revoke-all
 *             GET  /v1/admin/security/events?personId&type&limit   (Super Admin)
 *
 * A session is a record the server checks on EVERY request (auth.js): the access token carries its id, so revoking the record (status = revoked) refuses the very next call
 * with 401 { error: 'session-ended' } and deleting the session's refresh tokens stops it renewing. Removing a list entry without that would not stop a device; nothing here does.
 * No password, code, recovery code, secret or token is ever stored in clear or written to the security log; the TOTP secret is encrypted with a key derived from AUTH_SECRET.
 * The log is append-only: there is no route that edits or deletes an entry.
 */
export const SESSION_EXPIRES_MS = 90 * 86_400_000; // the refresh token's life: a session idle longer than this can no longer renew
export const TRUSTED_DAYS = Number(process.env.TRUSTED_DEVICE_DAYS ?? 30);
const RECENT_LOGIN_MS = 10 * 60_000;
const CHALLENGE_MS = 10 * 60_000;
const MAX_TRIES = 5;
const EMAIL_CODE_MS = 10 * 60_000;
const RECOVERY_CODES = 10;
const RECOVERY_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

const sha256 = (s) => createHash('sha256').update(s).digest('hex');
const same = (a, b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));
const clean = (v, max) => (typeof v === 'string' ? v.replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, max) : '');
const newId = () => randomBytes(18).toString('base64url');

// ---- RFC 4648 base32, RFC 4226 HOTP, RFC 6238 TOTP (SHA-1, 6 digits, 30 s: what every authenticator app implements; no private variant) ------------------------------
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
export const base32 = {
  encode(buf) {
    let bits = 0; let value = 0; let out = '';
    for (const byte of buf) { value = (value << 8) | byte; bits += 8; while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5; } }
    if (bits > 0) out += B32[(value << (5 - bits)) & 31];
    return out;
  },
  decode(text) {
    let bits = 0; let value = 0; const out = [];
    for (const ch of String(text).toUpperCase().replace(/[=\s-]/g, '')) {
      const i = B32.indexOf(ch);
      if (i < 0) throw new Error('bad base32');
      value = (value << 5) | i; bits += 5;
      if (bits >= 8) { out.push((value >>> (bits - 8)) & 255); bits -= 8; }
    }
    return Buffer.from(out);
  },
};
export function hotp(secret, counter, digits = 6) {
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(counter));
  const h = createHmac('sha1', secret).update(msg).digest();
  const o = h[h.length - 1] & 15;
  const bin = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(bin % 10 ** digits).padStart(digits, '0');
}
export const totpStep = (atMs, step = 30) => Math.floor(atMs / 1000 / step);
export const totp = (secret, atMs = Date.now(), digits = 6) => hotp(secret, totpStep(atMs), digits);
/** The step the code belongs to (this one, or one before / after for clock drift), else null. */
export function matchTotp(secret, code, atMs = Date.now(), window = 1) {
  if (!/^\d{6}$/.test(String(code ?? '').trim())) return null;
  const now = totpStep(atMs);
  for (let d = -window; d <= window; d += 1) if (same(hotp(secret, now + d), String(code).trim())) return now + d;
  return null;
}

// ---- secrets at rest ---------------------------------------------------------------------------------------------------------------------------------------------------
const key = (secret, purpose) => createHash('sha256').update(`gopreach-${purpose}|${secret}`).digest();
export function seal(plain, secret) {
  const iv = randomBytes(12);
  const c = createCipheriv('aes-256-gcm', key(secret, '2fa'), iv);
  const enc = Buffer.concat([c.update(plain, 'utf8'), c.final()]);
  return Buffer.concat([iv, c.getAuthTag(), enc]).toString('base64url');
}
export function unseal(blob, secret) {
  const raw = Buffer.from(blob, 'base64url');
  const d = createDecipheriv('aes-256-gcm', key(secret, '2fa'), raw.subarray(0, 12));
  d.setAuthTag(raw.subarray(12, 28));
  return Buffer.concat([d.update(raw.subarray(28)), d.final()]).toString('utf8');
}
const recoveryHash = (code, secret) => createHmac('sha256', key(secret, 'recovery')).update(String(code).toUpperCase().replace(/[^A-Z0-9]/g, '')).digest('hex');
export function newRecoveryCodes(secret) {
  const codes = Array.from({ length: RECOVERY_CODES }, () => {
    const pick = () => Array.from({ length: 5 }, () => RECOVERY_ALPHABET[randomInt(RECOVERY_ALPHABET.length)]).join('');
    return `${pick()}-${pick()}`;
  });
  return { codes, stored: codes.map((c) => ({ h: recoveryHash(c, secret), usedAt: null })) };
}

// ---- what a device says about itself --------------------------------------------------------------------------------------------------------------------------------
const TYPES = new Set(['phone', 'tablet', 'desktop', 'web', 'other']);
/** A device label (older apps) or an object { id, name, model, type, os, osVersion, appVersion }: only these fields, trimmed. */
export function deviceInfo(d) {
  const o = typeof d === 'string' ? { name: d } : d && typeof d === 'object' ? d : {};
  const type = clean(o.type, 12).toLowerCase();
  return {
    deviceId: clean(o.id ?? o.deviceId, 80) || null, name: clean(o.name, 80) || 'Unknown device', model: clean(o.model, 80) || null, type: TYPES.has(type) ? type : 'other',
    os: clean(o.os, 40) || null, osVersion: clean(o.osVersion, 40) || null, appVersion: clean(o.appVersion, 30) || null,
  };
}
const maskIp = (ip) => {
  const v4 = /^(?:::ffff:)?(\d+)\.(\d+)\.\d+\.\d+$/.exec(ip ?? '');
  return v4 ? `${v4[1]}.${v4[2]}.*.*` : null;
};
export const sessionStatus = (d, now = Date.now()) => (d.status === 'revoked' ? 'revoked' : now - d.lastActiveAt > SESSION_EXPIRES_MS ? 'expired' : 'active');

export function mountSecurity(app, { store, auth, enabled, mailer = null, issue, secret = config.authSecret, limits = {}, trustedDays = TRUSTED_DAYS }) {
  const verifyLimit = rateLimit({ windowMs: 10 * 60_000, max: 40, key: (req) => `${req.ip}|${String(req.body?.challengeId ?? '').slice(0, 40)}`, ...limits.verify });
  const actLimit = rateLimit({ windowMs: 10 * 60_000, max: 60, key: (req) => req.personId ?? req.ip, ...limits.act });
  const recent = (req) => Date.now() - (req.authTime ?? 0) <= RECENT_LOGIN_MS;
  const needRecent = (req, res) => (recent(req) ? false : (res.status(403).json({ error: 'requires-recent-login' }), true));

  const usernameOf = async (personId) => (await store.get('people', personId))?.data?.username ?? null;

  /** Appends to the security log. Never throws: a failing log must not break a sign-in. [notify] also shows it to the person as a notice. */
  async function event(personId, type, { result = 'success', sessionId = null, deviceId = null, meta = {}, notify = false, by = null } = {}) {
    try {
      const id = `${Date.now().toString(36)}-${randomBytes(5).toString('hex')}`;
      await store.secPut('secevent', id, personId ?? '-', { at: Date.now(), personId: personId ?? null, username: personId ? await usernameOf(personId) : null, type, result, sessionId, deviceId, by, meta, notify, seenAt: null });
    } catch (e) { console.error('security log failed', type, e.message); }
  }

  const a2fa = async (personId) => (await store.secGet('a2fa', personId))?.data ?? { enabled: false };
  const saveA2fa = (personId, data) => store.secPut('a2fa', personId, personId, { ...data, updatedAt: Date.now() });

  async function trustedFor(personId) {
    return (await store.secList('trusted', personId)).filter((t) => !t.data.revokedAt && t.data.expiresAt > Date.now());
  }

  // ---- new-device approval (opt-in per account) --------------------------------------------------------------------------------------------------------------------
  /** Whether this account asks for approval of a device it has not trusted yet (the account holder turns it on in Security & Devices). */
  const approvalRequired = async (personId) => !!(await a2fa(personId)).deviceApproval?.enabled;

  /** A device is trusted only by the credential it keeps (the server keeps its hash), tied to the device id, not revoked, not expired: never by its name, address or user agent. */
  async function deviceIsTrusted(personId, trustedToken, info) {
    if (typeof trustedToken !== 'string' || trustedToken.length < 20 || trustedToken.length > 200) return false;
    const t = await store.secGet('trusted', sha256(trustedToken));
    if (!t || t.personId !== personId || t.data.revokedAt || t.data.expiresAt <= Date.now()) return false;
    if (t.data.deviceId && info?.deviceId && t.data.deviceId !== info.deviceId) return false;
    await store.secPut('trusted', t.id, personId, { ...t.data, lastUsedAt: Date.now() });
    return true;
  }

  /** A device session is created ONLY after the sign-in is complete (password, and the second factor when the account has one). */
  async function createSession(personId, info, ip, authMethod) {
    await cancelRecoveries(personId, 'signed-in'); // the real owner signing in ends any recovery started in their name
    const sessionId = newId();
    const known = (await store.secList('dsession', personId, { limit: 200 })).some((s) => s.data.deviceId && s.data.deviceId === info.deviceId);
    const doc = { sessionId, ...info, ip: maskIp(ip), location: null, authMethod, createdAt: Date.now(), lastActiveAt: Date.now(), status: 'active', revokedAt: null, revokedBy: null, revokedReason: null, customName: null };
    await store.secPut('dsession', sessionId, personId, doc);
    await event(personId, 'LOGIN_SUCCESS', { sessionId, deviceId: info.deviceId, meta: { method: authMethod, device: info.name } });
    if (!known) await event(personId, 'NEW_DEVICE_LOGIN', { sessionId, deviceId: info.deviceId, notify: true, meta: { device: info.name, os: info.os, ip: doc.ip, at: doc.createdAt } });
    return doc;
  }

  async function revokeSession(personId, sessionId, { by, reason }) {
    const s = await store.secGet('dsession', sessionId);
    if (!s || s.personId !== personId) return false;
    if (s.data.status !== 'revoked') await store.secPut('dsession', sessionId, personId, { ...s.data, status: 'revoked', revokedAt: Date.now(), revokedBy: by, revokedReason: reason });
    await store.deleteRefreshTokensBySession(personId, sessionId); // it can no longer renew; its access token is refused on the very next call (auth.js)
    return true;
  }
  async function revokeMany(personId, { except = null, by, reason }) {
    let n = 0;
    for (const s of await store.secList('dsession', personId, { limit: 1000 })) {
      if (s.id === except || s.data.status === 'revoked') continue;
      if (await revokeSession(personId, s.id, { by, reason })) n += 1;
    }
    return n;
  }
  async function removeTrust(personId, { except = null } = {}) {
    for (const t of await store.secList('trusted', personId)) if (!t.data.revokedAt && t.id !== except) await store.secPut('trusted', t.id, personId, { ...t.data, revokedAt: Date.now() });
  }

  /**
   * Called by the password sign-in once the password is right. { session } when the person may come in (no 2FA, or this device is trusted), else { challenge } to answer.
   */
  async function gate(personId, { device, ip, trustedToken }) {
    const info = deviceInfo(device);
    const s = await a2fa(personId);
    if (!s.enabled) return { info, method: 'password' };
    if (typeof trustedToken === 'string' && trustedToken.length >= 20 && trustedToken.length <= 200) {
      const t = await store.secGet('trusted', sha256(trustedToken));
      if (t && t.personId === personId && !t.data.revokedAt && t.data.expiresAt > Date.now() && (!t.data.deviceId || !info.deviceId || t.data.deviceId === info.deviceId)) {
        await store.secPut('trusted', t.id, personId, { ...t.data, lastUsedAt: Date.now() });
        return { info, method: 'trusted-device' };
      }
    }
    const challengeId = newId();
    const challengeToken = randomBytes(24).toString('base64url');
    const email = s.recoveryEmail?.verified && mailer ? s.recoveryEmail.address : null;
    await store.putChallenge(challengeId, {
      personId, kind: 'mfa', expiresAt: Date.now() + CHALLENGE_MS,
      data: { tokenHash: sha256(challengeToken), info, ip: maskIp(ip), tries: 0, email, codeHash: null, codeSentAt: 0, codesSent: 0 },
    });
    return {
      info,
      challenge: { twoFactorRequired: true, challengeId, challengeToken, expiresAt: Date.now() + CHALLENGE_MS, methods: { totp: !!s.totp?.confirmedAt, recovery: true, email: email ? { masked: maskEmail(email) } : null } },
    };
  }

  /** Checks a TOTP code, a recovery code or an e-mail code against what the account holds. Returns the method name, or null. A used code never works again. */
  async function checkSecondFactor(personId, s, { code, recoveryCode, emailCode }, challenge = null) {
    if (typeof code === 'string' && s.totp?.secret) {
      let secretBytes;
      try { secretBytes = base32.decode(unseal(s.totp.secret, secret)); } catch { return null; }
      const step = matchTotp(secretBytes, code);
      if (step !== null && step > (s.totp.lastStep ?? 0)) {
        await saveA2fa(personId, { ...s, totp: { ...s.totp, lastStep: step } }); // the same code cannot be used twice
        return 'totp';
      }
      return null;
    }
    if (typeof recoveryCode === 'string' && recoveryCode.length <= 40) {
      const h = recoveryHash(recoveryCode, secret);
      const i = (s.recoveryCodes ?? []).findIndex((c) => !c.usedAt && same(c.h, h));
      if (i < 0) return null;
      const codes = s.recoveryCodes.map((c, j) => (j === i ? { ...c, usedAt: Date.now() } : c));
      await saveA2fa(personId, { ...s, recoveryCodes: codes });
      return 'recovery-code';
    }
    if (typeof emailCode === 'string' && challenge?.data.codeHash && challenge.data.codeSentAt && Date.now() - challenge.data.codeSentAt < EMAIL_CODE_MS) {
      return same(challenge.data.codeHash, sha256(String(emailCode).trim())) ? 'email-code' : null;
    }
    return null;
  }

  // ---- the second step of a sign-in ----------------------------------------------------------------------------------------------------------------------------------
  const ownedChallenge = async (body) => {
    const { challengeId, challengeToken } = body ?? {};
    if (typeof challengeId !== 'string' || typeof challengeToken !== 'string' || challengeId.length > 40 || challengeToken.length > 80) return null;
    const c = await store.getChallenge(challengeId);
    return c && c.kind === 'mfa' && same(c.data.tokenHash, sha256(challengeToken)) ? c : null;
  };

  app.post('/v1/auth/2fa/verify', enabled, verifyLimit, async (req, res) => {
    try {
      const c = await ownedChallenge(req.body);
      if (!c) return res.status(401).json({ error: 'invalid-challenge' });
      if (c.data.tries >= MAX_TRIES) { await store.deleteChallenge(c.id); return res.status(429).json({ error: 'too-many-tries' }); }
      const s = await a2fa(c.personId);
      const method = s.enabled ? await checkSecondFactor(c.personId, s, req.body, c) : null;
      if (!method) {
        await store.putChallenge(c.id, { ...c, data: { ...c.data, tries: c.data.tries + 1 } });
        await event(c.personId, 'TWO_FACTOR_FAILED', { result: 'failure', deviceId: c.data.info.deviceId, meta: { device: c.data.info.name, ip: c.data.ip }, notify: c.data.tries + 1 >= 3 });
        return res.status(401).json({ error: 'invalid-code', triesLeft: Math.max(0, MAX_TRIES - c.data.tries - 1) });
      }
      await store.deleteChallenge(c.id); // single use
      await event(c.personId, 'TWO_FACTOR_SUCCESS', { deviceId: c.data.info.deviceId, meta: { method, device: c.data.info.name } });
      const ds = await createSession(c.personId, c.data.info, c.data.ip, `password+${method}`);
      const tokens = await issue(c.personId, Date.now(), ds.sessionId);
      const out = { ...tokens, sessionId: ds.sessionId };
      if (req.body?.trustDevice === true) Object.assign(out, await trustDevice(c.personId, ds));
      res.json(out);
    } catch (e) {
      console.error('2fa verify failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/auth/2fa/email-code', enabled, verifyLimit, async (req, res) => {
    try {
      const c = await ownedChallenge(req.body);
      if (!c) return res.status(401).json({ error: 'invalid-challenge' });
      if (!c.data.email || !mailer) return res.status(409).json({ error: 'email-not-available' });
      if (c.data.codesSent >= 3 || Date.now() - c.data.codeSentAt < 60_000) return res.status(429).json({ error: 'too-many-requests' });
      const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
      await store.putChallenge(c.id, { ...c, data: { ...c.data, codeHash: sha256(code), codeSentAt: Date.now(), codesSent: c.data.codesSent + 1 } });
      await mailer.send(c.data.email, 'Your GoPreach verification code', `Your GoPreach verification code is ${code}. It works once, for 10 minutes. If you did not try to sign in, change your password.`);
      res.json({ ok: true, masked: maskEmail(c.data.email) });
    } catch (e) {
      console.error('2fa email failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  /** Registers THIS device as trusted: a random credential the device keeps; the server keeps only its hash. Called after a verified second factor. */
  async function trustDevice(personId, ds) {
    const token = randomBytes(32).toString('base64url');
    const expiresAt = Date.now() + trustedDays * 86_400_000;
    await store.secPut('trusted', sha256(token), personId, { deviceId: ds.deviceId, name: ds.customName ?? ds.name, model: ds.model, os: ds.os, createdAt: Date.now(), expiresAt, lastUsedAt: null, revokedAt: null });
    await event(personId, 'TRUSTED_DEVICE_ADDED', { sessionId: ds.sessionId, deviceId: ds.deviceId, notify: true, meta: { device: ds.name, expiresAt } });
    return { trustedToken: token, trustedUntil: expiresAt };
  }

  // ---- settings of the signed-in account -------------------------------------------------------------------------------------------------------------------------------
  app.get('/v1/security/status', enabled, auth, async (req, res) => {
    const s = await a2fa(req.personId);
    res.json({
      twoFactor: {
        enabled: !!s.enabled, totpConfigured: !!s.totp?.confirmedAt, enabledAt: s.enabledAt ?? null,
        recoveryEmail: s.recoveryEmail?.address ? { masked: maskEmail(s.recoveryEmail.address), verified: !!s.recoveryEmail.verified } : null,
        recoveryCodesLeft: (s.recoveryCodes ?? []).filter((c) => !c.usedAt).length,
      },
      emailAvailable: !!mailer, trustedDays,
      trustedDevices: (await trustedFor(req.personId)).length,
      deviceApproval: !!s.deviceApproval?.enabled,
    });
  });

  /**
   * Turns "approve new devices" on or off (a recent sign-in is needed). Turning it on trusts THIS device (it returns that device's credential once), so the account can still
   * approve the next one; with no trusted device left a new device can only be settled by an e-mail code or a passkey (see loginApproval.js).
   */
  app.post('/v1/security/device-approval', enabled, auth, actLimit, async (req, res) => {
    if (needRecent(req, res)) return;
    try {
      const on = req.body?.enabled === true;
      const s = await a2fa(req.personId);
      await saveA2fa(req.personId, { ...s, deviceApproval: { enabled: on, at: Date.now() } });
      await event(req.personId, on ? 'DEVICE_APPROVAL_ON' : 'DEVICE_APPROVAL_OFF', { sessionId: req.sessionId ?? null, notify: true, by: req.personId });
      if (!on) return res.json({ ok: true, enabled: false });
      const ds = await store.secGet('dsession', req.sessionId ?? '');
      const trust = ds && ds.personId === req.personId ? await trustDevice(req.personId, { ...ds.data, sessionId: ds.id }) : {};
      res.json({ ok: true, enabled: true, ...trust });
    } catch (e) {
      console.error('device approval setting failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/security/2fa/totp/setup', enabled, auth, actLimit, async (req, res) => {
    if (needRecent(req, res)) return;
    const s = await a2fa(req.personId);
    if (s.enabled) return res.status(409).json({ error: 'already-enabled' });
    const raw = randomBytes(20);
    const secretText = base32.encode(raw);
    await saveA2fa(req.personId, { ...s, pendingTotp: seal(secretText, secret) });
    const account = (await usernameOf(req.personId)) ?? req.personId;
    res.json({ secret: secretText, otpauthUri: `otpauth://totp/GoPreach:${encodeURIComponent(account)}?secret=${secretText}&issuer=GoPreach&algorithm=SHA1&digits=6&period=30` });
  });

  app.post('/v1/security/2fa/totp/confirm', enabled, auth, actLimit, async (req, res) => {
    const s = await a2fa(req.personId);
    if (s.enabled || !s.pendingTotp) return res.status(409).json({ error: 'nothing-to-confirm' });
    let bytes;
    try { bytes = base32.decode(unseal(s.pendingTotp, secret)); } catch { return res.status(409).json({ error: 'nothing-to-confirm' }); }
    const step = matchTotp(bytes, req.body?.code);
    if (step === null) { await event(req.personId, 'TWO_FACTOR_FAILED', { result: 'failure', sessionId: req.sessionId, meta: { during: 'setup' } }); return res.status(401).json({ error: 'invalid-code' }); }
    const { codes, stored } = newRecoveryCodes(secret);
    const { pendingTotp, ...rest } = s;
    await saveA2fa(req.personId, { ...rest, enabled: true, enabledAt: Date.now(), totp: { secret: pendingTotp, confirmedAt: Date.now(), lastStep: step }, recoveryCodes: stored });
    await event(req.personId, 'TWO_FACTOR_ENABLED', { sessionId: req.sessionId, notify: true });
    res.json({ ok: true, recoveryCodes: codes }); // the only time they are shown
  });

  /** A sensitive change to the 2FA settings needs a recent password AND a current code (or a recovery code). */
  const proveSecondFactor = async (req, res) => {
    if (needRecent(req, res)) return null;
    const s = await a2fa(req.personId);
    if (!s.enabled) { res.status(409).json({ error: 'not-enabled' }); return null; }
    const method = await checkSecondFactor(req.personId, s, req.body);
    if (!method) { await event(req.personId, 'TWO_FACTOR_FAILED', { result: 'failure', sessionId: req.sessionId, meta: { during: 'settings' } }); res.status(401).json({ error: 'invalid-code' }); return null; }
    return { s: await a2fa(req.personId), method };
  };

  app.post('/v1/security/2fa/disable', enabled, auth, actLimit, async (req, res) => {
    const proof = await proveSecondFactor(req, res);
    if (!proof) return;
    await saveA2fa(req.personId, { enabled: false, recoveryEmail: proof.s.recoveryEmail ?? null });
    await removeTrust(req.personId);
    await event(req.personId, 'TWO_FACTOR_DISABLED', { sessionId: req.sessionId, notify: true });
    res.json({ ok: true });
  });

  app.post('/v1/security/2fa/recovery-codes', enabled, auth, actLimit, async (req, res) => {
    const proof = await proveSecondFactor(req, res);
    if (!proof) return;
    const { codes, stored } = newRecoveryCodes(secret);
    await saveA2fa(req.personId, { ...proof.s, recoveryCodes: stored });
    await event(req.personId, 'RECOVERY_CODES_REGENERATED', { sessionId: req.sessionId, notify: true });
    res.json({ recoveryCodes: codes });
  });

  app.post('/v1/security/recovery-email', enabled, auth, actLimit, async (req, res) => {
    if (needRecent(req, res)) return;
    const email = clean(req.body?.email, 190).toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return res.status(400).json({ error: 'bad-email' });
    if (!mailer) return res.status(409).json({ error: 'email-not-available' });
    const s = await a2fa(req.personId);
    const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
    await saveA2fa(req.personId, { ...s, recoveryEmail: { address: email, verified: false, codeHash: sha256(code), codeAt: Date.now(), tries: 0 } });
    await mailer.send(email, 'Confirm your GoPreach recovery e-mail', `Your GoPreach confirmation code is ${code}. It works for 10 minutes.`);
    res.json({ ok: true, masked: maskEmail(email) });
  });

  app.post('/v1/security/recovery-email/verify', enabled, auth, actLimit, async (req, res) => {
    const s = await a2fa(req.personId);
    const r = s.recoveryEmail;
    if (!r || r.verified || !r.codeHash || Date.now() - r.codeAt > EMAIL_CODE_MS || r.tries >= MAX_TRIES) return res.status(409).json({ error: 'nothing-to-confirm' });
    if (!same(r.codeHash, sha256(String(req.body?.code ?? '').trim()))) {
      await saveA2fa(req.personId, { ...s, recoveryEmail: { ...r, tries: r.tries + 1 } });
      return res.status(401).json({ error: 'invalid-code' });
    }
    await saveA2fa(req.personId, { ...s, recoveryEmail: { address: r.address, verified: true } });
    await event(req.personId, 'ACCOUNT_SECURITY_SETTINGS_CHANGED', { sessionId: req.sessionId, meta: { change: 'recovery e-mail confirmed' } });
    res.json({ ok: true });
  });

  // ---- devices ------------------------------------------------------------------------------------------------------------------------------------------------------------
  const present = (doc, currentId, trustedIds) => {
    const d = doc.data;
    return {
      sessionId: doc.id, current: doc.id === currentId, deviceId: d.deviceId, name: d.customName ?? d.name, originalName: d.name, model: d.model, type: d.type, os: d.os, osVersion: d.osVersion,
      appVersion: d.appVersion, ip: d.ip, location: d.location, authMethod: d.authMethod, loginAt: d.createdAt, lastActiveAt: d.lastActiveAt, status: sessionStatus(d),
      trusted: !!d.deviceId && trustedIds.has(d.deviceId), revokedAt: d.revokedAt, revokedBy: d.revokedBy, revokedReason: d.revokedReason,
    };
  };

  app.get('/v1/security/devices', enabled, auth, async (req, res) => {
    const trusted = await trustedFor(req.personId);
    const ids = new Set(trusted.map((t) => t.data.deviceId).filter(Boolean));
    const docs = await store.secList('dsession', req.personId, { limit: 200 });
    res.json({
      devices: docs.map((d) => present(d, req.sessionId, ids)).sort((a, b) => Number(b.current) - Number(a.current) || b.lastActiveAt - a.lastActiveAt),
      trusted: trusted.map((t) => ({ id: t.id.slice(0, 16), deviceId: t.data.deviceId, name: t.data.name, os: t.data.os, since: t.data.createdAt, expiresAt: t.data.expiresAt, lastUsedAt: t.data.lastUsedAt })),
    });
  });

  app.post('/v1/security/devices/:sessionId/rename', enabled, auth, actLimit, async (req, res) => {
    const s = await store.secGet('dsession', String(req.params.sessionId).slice(0, 60));
    const name = clean(req.body?.name, 80);
    if (!s || s.personId !== req.personId) return res.status(404).json({ error: 'not-found' });
    if (!name) return res.status(400).json({ error: 'name-required' });
    await store.secPut('dsession', s.id, req.personId, { ...s.data, customName: name });
    await event(req.personId, 'ACCOUNT_SECURITY_SETTINGS_CHANGED', { sessionId: req.sessionId, meta: { change: 'device renamed' } });
    res.json({ ok: true });
  });

  app.post('/v1/security/devices/revoke-others', enabled, auth, actLimit, async (req, res) => {
    if (needRecent(req, res)) return;
    const n = await revokeMany(req.personId, { except: req.sessionId, by: 'self', reason: 'log-out-others' });
    await event(req.personId, 'LOG_OUT_OTHER_DEVICES', { sessionId: req.sessionId, meta: { count: n }, notify: true });
    res.json({ ok: true, revoked: n });
  });

  app.post('/v1/security/devices/revoke-all', enabled, auth, actLimit, async (req, res) => {
    if (needRecent(req, res)) return;
    const n = await revokeMany(req.personId, { by: 'self', reason: 'log-out-all' });
    await event(req.personId, 'LOG_OUT_ALL_DEVICES', { sessionId: req.sessionId, meta: { count: n }, notify: true });
    res.json({ ok: true, revoked: n });
  });

  app.post('/v1/security/devices/:sessionId/revoke', enabled, auth, actLimit, async (req, res) => {
    if (needRecent(req, res)) return;
    const id = String(req.params.sessionId).slice(0, 60);
    const s = await store.secGet('dsession', id);
    if (!s || s.personId !== req.personId) return res.status(404).json({ error: 'not-found' });
    await revokeSession(req.personId, id, { by: 'self', reason: 'device-logout' });
    await event(req.personId, 'DEVICE_LOGOUT', { sessionId: id, deviceId: s.data.deviceId, notify: true, meta: { device: s.data.customName ?? s.data.name, current: id === req.sessionId } });
    res.json({ ok: true });
  });

  app.post('/v1/security/trusted/current', enabled, auth, actLimit, async (req, res) => {
    const proof = await proveSecondFactor(req, res);
    if (!proof) return;
    const ds = await store.secGet('dsession', req.sessionId ?? '');
    if (!ds || ds.personId !== req.personId) return res.status(409).json({ error: 'no-device-session' });
    res.json(await trustDevice(req.personId, { ...ds.data, sessionId: ds.id }));
  });

  app.post('/v1/security/trusted/:id/remove', enabled, auth, actLimit, async (req, res) => {
    if (needRecent(req, res)) return;
    const prefix = String(req.params.id).slice(0, 16);
    const t = (await store.secList('trusted', req.personId)).find((x) => x.id.startsWith(prefix) && !x.data.revokedAt);
    if (!t) return res.status(404).json({ error: 'not-found' });
    await store.secPut('trusted', t.id, req.personId, { ...t.data, revokedAt: Date.now() });
    // Deleting a trusted device also logs that device out: its sessions end on the server (the one in the caller's hand stays, only its trust goes),
    // so the device falls back to the sign-in screen, where the app forgets any saved password.
    let loggedOut = 0;
    for (const d of await store.secList('dsession', req.personId, { limit: 1000 })) {
      if (d.id === req.sessionId || d.data.status === 'revoked' || d.data.deviceId !== t.data.deviceId) continue;
      if (await revokeSession(req.personId, d.id, { by: 'self', reason: 'trusted-device-deleted' })) loggedOut += 1;
    }
    await event(req.personId, 'TRUSTED_DEVICE_REMOVED', { sessionId: req.sessionId, deviceId: t.data.deviceId, meta: { device: t.data.name, loggedOut } });
    res.json({ ok: true, loggedOut });
  });

  // ---- the person's own log and notices ----------------------------------------------------------------------------------------------------------------------------------
  const publicEvent = (e) => ({ id: e.id, at: e.data.at, type: e.data.type, result: e.data.result, deviceId: e.data.deviceId, sessionId: e.data.sessionId, by: e.data.by, meta: e.data.meta });
  /** Login-history filters (all optional): result=success|failure, type=A,B, q=text in the device name, from/to = epoch ms, notify=1 (only entries that raise a notice). */
  const historyFilter = (q) => {
    const types = String(q.type ?? '').split(',').map((t) => t.trim()).filter(Boolean);
    const result = String(q.result ?? '').toLowerCase();
    const text = String(q.q ?? '').trim().toLowerCase();
    const from = Number(q.from) || 0;
    const to = Number(q.to) || Infinity;
    return (e) => (!types.length || types.includes(e.data.type))
      && (!result || String(e.data.result ?? '').toLowerCase() === result)
      && (!text || String(e.data.meta?.device ?? '').toLowerCase().includes(text))
      && e.data.at >= from && e.data.at <= to
      && (q.notify !== '1' || e.data.notify);
  };
  app.get('/v1/security/events', enabled, auth, async (req, res) => {
    const limit = Math.min(Number.parseInt(String(req.query.limit ?? '100'), 10) || 100, 300);
    const all = await store.secList('secevent', req.personId, { limit: 300 });
    res.json({ events: all.filter(historyFilter(req.query)).slice(0, limit).map(publicEvent) });
  });
  /** Notification centre: unseen notices by default; all=1 also lists the ones already seen (each with seen:true/false). */
  /** The cheapest signed-in call: devices ask it every few seconds-minutes, and a session that was logged out gets 401 session-ended at once (auth.js), so the device signs itself out without anyone touching it. */
  app.get('/v1/auth/session', enabled, auth, (req, res) => res.json({ ok: true }));
  app.get('/v1/security/notices', enabled, auth, async (req, res) => {
    const mine = (await store.secList('secevent', req.personId, { limit: 300 })).filter((e) => e.data.notify);
    const wanted = req.query.all === '1' ? mine : mine.filter((e) => !e.data.seenAt);
    res.json({ notices: wanted.map((e) => ({ ...publicEvent(e), seen: !!e.data.seenAt })), unseen: mine.filter((e) => !e.data.seenAt).length });
  });
  app.post('/v1/security/notices/seen', enabled, auth, async (req, res) => {
    for (const e of (await store.secList('secevent', req.personId, { limit: 100 })).filter((x) => x.data.notify && !x.data.seenAt)) await store.secPut('secevent', e.id, e.personId, { ...e.data, seenAt: Date.now() });
    res.json({ ok: true });
  });

  // ---- the Super Admin's (and a congregation Admin's) view ----------------------------------------------------------------------------------------------------------------
  const get = (c, i) => store.get(c, i);
  /** Super Admin: everyone. An Admin of one congregation: only people of that congregation. Everyone else: nothing. */
  async function scopeOf(req, res) {
    const actor = await loadActor(req.personId, get, (c, cong) => store.list(c, cong));
    if (!actor.known) { res.status(403).json({ error: 'Not allowed' }); return null; }
    if (actor.isSuperAdmin) return { actor, all: true, congregationId: null };
    if (actor.adminRole === 'ADMIN_PER_CONGREGATION' && actor.congregationId) return { actor, all: false, congregationId: actor.congregationId };
    res.status(403).json({ error: 'Not allowed' });
    return null;
  }
  const roleLabelsOf = (assignments, personId) => assignments.filter((a) => a.data.personId === personId && a.data.status === 'ACTIVE').map((a) => String(a.data.roleType ?? '').replace(/^(ADMIN|PUBLISHER):/, '')).filter(Boolean);
  const inScope = (scope, person) => scope.all || (person && person.activeCongregationId === scope.congregationId);

  app.get('/v1/admin/security/sessions', enabled, auth, async (req, res) => {
    try {
      const scope = await scopeOf(req, res);
      if (!scope) return;
      const q = String(req.query.q ?? '').trim().toLowerCase().slice(0, 100);
      const { status, type, role, congregation } = req.query;
      const since = Number(req.query.since) || 0;
      const limit = Math.min(Number.parseInt(String(req.query.limit ?? '200'), 10) || 200, 500);
      const people = new Map((await store.list('people')).map((r) => [r.id, r.data]));
      const congs = new Map((await store.list('congregations')).map((r) => [r.id, r.data.name]));
      const assignments = await store.list('roleAssignments');
      const out = [];
      for (const doc of await store.secListKind('dsession', { limit: 3000 })) {
        const p = people.get(doc.personId);
        if (!inScope(scope, p)) continue;
        const d = doc.data;
        const st = sessionStatus(d);
        const roles = roleLabelsOf(assignments, doc.personId);
        const row = {
          sessionId: doc.id, personId: doc.personId, username: p?.username ?? null, name: p ? `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim() : null, congregationId: p?.activeCongregationId ?? null,
          congregation: congs.get(p?.activeCongregationId) ?? null, roles, deviceName: d.customName ?? d.name, type: d.type, os: d.os, osVersion: d.osVersion, appVersion: d.appVersion,
          loginAt: d.createdAt, lastActiveAt: d.lastActiveAt, status: st, authMethod: d.authMethod, ip: d.ip,
        };
        if (status && status !== st) continue;
        if (type && type !== d.type) continue;
        if (role && !roles.some((r) => r === role)) continue;
        if (congregation && congregation !== row.congregationId) continue;
        if (since && d.lastActiveAt < since) continue;
        if (q && ![row.username, row.name, row.congregation, row.deviceName].some((v) => String(v ?? '').toLowerCase().includes(q))) continue;
        out.push(row);
        if (out.length >= limit) break;
      }
      res.json({ sessions: out });
    } catch (e) {
      console.error('admin sessions failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  const targetInScope = async (scope, personId) => scope.all || inScope(scope, (await store.get('people', personId))?.data);

  app.get('/v1/admin/security/users/:personId/history', enabled, auth, async (req, res) => {
    const scope = await scopeOf(req, res);
    if (!scope) return;
    const personId = String(req.params.personId).slice(0, 100);
    if (!(await targetInScope(scope, personId))) return res.status(404).json({ error: 'not-found' });
    res.json({ events: (await store.secList('secevent', personId, { limit: 200 })).map(publicEvent) });
  });

  app.post('/v1/admin/security/sessions/:sessionId/revoke', enabled, auth, actLimit, async (req, res) => {
    const scope = await scopeOf(req, res);
    if (!scope) return;
    const s = await store.secGet('dsession', String(req.params.sessionId).slice(0, 60));
    if (!s || !(await targetInScope(scope, s.personId))) return res.status(404).json({ error: 'not-found' });
    await revokeSession(s.personId, s.id, { by: `admin:${req.personId}`, reason: 'admin-revoked' });
    await event(s.personId, 'ADMIN_SESSION_REVOKED', { sessionId: s.id, deviceId: s.data.deviceId, by: req.personId, notify: true, meta: { device: s.data.customName ?? s.data.name } });
    await event(req.personId, 'ADMIN_REVOKED_A_SESSION', { meta: { target: s.personId, device: s.data.name } });
    res.json({ ok: true });
  });

  app.post('/v1/admin/security/users/:personId/revoke-all', enabled, auth, actLimit, async (req, res) => {
    const scope = await scopeOf(req, res);
    if (!scope) return;
    const personId = String(req.params.personId).slice(0, 100);
    if (!(await targetInScope(scope, personId))) return res.status(404).json({ error: 'not-found' });
    const n = await revokeMany(personId, { by: `admin:${req.personId}`, reason: 'admin-revoked-all' });
    await event(personId, 'ADMIN_SESSION_REVOKED', { by: req.personId, notify: true, meta: { count: n, all: true } });
    await event(req.personId, 'ADMIN_REVOKED_A_SESSION', { meta: { target: personId, count: n } });
    res.json({ ok: true, revoked: n });
  });

  app.get('/v1/admin/security/events', enabled, auth, async (req, res) => {
    const scope = await scopeOf(req, res);
    if (!scope || !scope.all) { if (scope) res.status(403).json({ error: 'Not allowed' }); return; }
    const limit = Math.min(Number.parseInt(String(req.query.limit ?? '200'), 10) || 200, 500);
    const personId = String(req.query.personId ?? '').slice(0, 100);
    const type = String(req.query.type ?? '').slice(0, 60);
    const rows = personId ? await store.secList('secevent', personId, { limit: 500 }) : await store.secListKind('secevent', { limit: 1000 });
    res.json({ events: rows.filter((e) => !type || e.data.type === type).slice(0, limit).map((e) => ({ ...publicEvent(e), personId: e.data.personId, username: e.data.username })) });
  });

  // ---- Super Admin: how many new-device requests one account may raise, in how many minutes, and how long a request waits ----------------------------------------------------
  /** Safe bounds: the protection can be tuned but never switched off. */
  const APPROVAL_BOUNDS = { maxRequests: [1, 20], windowMinutes: [1, 120], expiryMinutes: [1, 30] };
  const APPROVAL_DEFAULTS = { maxRequests: 5, windowMinutes: 15, expiryMinutes: 5 };
  /** The settings in force (defaults until a Super Admin saves some); `custom` says whether any were saved. */
  async function approvalSettings() {
    const saved = (await store.secGet('secsettings', 'approval'))?.data ?? null;
    const out = { ...APPROVAL_DEFAULTS, custom: !!saved };
    for (const [k, [lo, hi]] of Object.entries(APPROVAL_BOUNDS)) {
      const n = Number(saved?.[k]);
      if (Number.isInteger(n) && n >= lo && n <= hi) out[k] = n;
    }
    return out;
  }
  const settingsReply = async () => ({ settings: await approvalSettings(), bounds: APPROVAL_BOUNDS, defaults: APPROVAL_DEFAULTS });
  app.get('/v1/admin/security/approval-limits', enabled, auth, async (req, res) => {
    const scope = await scopeOf(req, res);
    if (!scope) return;
    if (!scope.all) return res.status(403).json({ error: 'Not allowed' });
    res.json(await settingsReply());
  });
  app.post('/v1/admin/security/approval-limits', enabled, auth, actLimit, async (req, res) => {
    const scope = await scopeOf(req, res);
    if (!scope) return;
    if (!scope.all) return res.status(403).json({ error: 'Not allowed' });
    if (needRecent(req, res)) return;
    const next = {};
    for (const [k, [lo, hi]] of Object.entries(APPROVAL_BOUNDS)) {
      const n = Number(req.body?.[k]);
      if (!Number.isInteger(n) || n < lo || n > hi) return res.status(400).json({ error: 'invalid', field: k, min: lo, max: hi });
      next[k] = n;
    }
    const before = await approvalSettings();
    await store.secPut('secsettings', 'approval', req.personId, { ...next, updatedBy: req.personId, updatedAt: Date.now() });
    await event(req.personId, 'APPROVAL_LIMITS_CHANGED', { meta: { before: { maxRequests: before.maxRequests, windowMinutes: before.windowMinutes, expiryMinutes: before.expiryMinutes }, after: next } });
    res.json(await settingsReply());
  });

  // ---- account recovery: lost the password AND the authenticator -------------------------------------------------------------------------------------------------------
  // 1. request   a code goes to the account's CONFIRMED recovery e-mail (the answer is the same for every username: nothing says whether an account exists)
  // 2. verify    the right code starts a waiting period (24 hours); the owner is told in the app and by e-mail, and signing in or pressing "This was not me" cancels it
  // 3. complete  after the wait, a new password is set: every session ends, trusted devices and two-factor are cleared (it is set up again). No administrator can shorten the wait or skip the code.
  const RECOVERY_WAIT_MS = limits.recoveryWaitMs ?? 24 * 3_600_000;
  const RECOVERY_CODE_MS = 15 * 60_000;
  const RECOVERY_LIVE_MS = 48 * 3_600_000; // how long after the wait the request can still be completed
  const recoveryLimit = rateLimit({ windowMs: 10 * 60_000, max: 10, key: (req) => req.ip, ...limits.recovery });
  const recoveryTryLimit = rateLimit({ windowMs: 10 * 60_000, max: 30, key: (req) => `${req.ip}|${String(req.body?.requestId ?? '').slice(0, 40)}`, ...limits.recoveryTry });
  const recoveryOf = async (req, res) => {
    const c = await store.getChallenge(String(req.body?.requestId ?? '').slice(0, 64));
    if (!c || c.kind !== 'recovery' || !same(c.data.pollHash, sha256(String(req.body?.pollToken ?? '')))) { res.status(404).json({ error: 'not-found' }); return null; }
    return c;
  };
  const recoveryStatus = (c) => (c.data.status === 'waiting' && Date.now() >= c.data.readyAt ? 'ready' : c.data.status);
  async function cancelRecoveries(personId, why) {
    let n = 0;
    for (const c of await store.listChallenges(personId, 'recovery')) {
      if (c.data.status !== 'waiting' && c.data.status !== 'code') continue;
      await store.putChallenge(c.id, { ...c, data: { ...c.data, status: 'cancelled' } });
      if (c.data.status === 'waiting') {
        n += 1;
        await event(personId, 'RECOVERY_CANCELLED', { notify: true, meta: { why } });
      }
    }
    return n;
  }

  app.post('/v1/public/recovery/request', enabled, recoveryLimit, async (req, res) => {
    if (!mailer) return res.status(409).json({ error: 'email-not-available' });
    const fake = () => res.json({ requestId: randomBytes(12).toString('base64url'), pollToken: randomBytes(24).toString('base64url'), expiresAt: Date.now() + RECOVERY_CODE_MS });
    const username = clean(req.body?.username, 100);
    if (!username) return res.status(400).json({ error: 'username-required' });
    const found = await store.findPersonByUsername(username);
    const personId = found?.id ?? null;
    const account = personId ? await store.getAccount(personId) : null;
    const email = personId ? (await a2fa(personId)).recoveryEmail : null;
    if (!personId || !account || account.disabled || !email?.verified) return fake();
    for (const old of await store.listChallenges(personId, 'recovery')) {
      if (old.data.status === 'code') await store.putChallenge(old.id, { ...old, data: { ...old.data, status: 'superseded' } });
    }
    const id = randomBytes(12).toString('base64url');
    const pollToken = randomBytes(24).toString('base64url');
    const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
    const expiresAt = Date.now() + RECOVERY_CODE_MS;
    await store.putChallenge(id, { personId, kind: 'recovery', expiresAt, data: { status: 'code', pollHash: sha256(pollToken), codeHash: sha256(`${id}:${code}`), tries: 0, email: email.address, createdAt: Date.now(), ip: maskIp(req.ip) } });
    await mailer.send(email.address, 'GoPreach account recovery code', `Your GoPreach account recovery code is ${code}. It works once, for 15 minutes. If you did not ask for it, ignore this e-mail: nothing changes unless the code is used.`);
    await event(personId, 'RECOVERY_CODE_SENT', { meta: { ip: maskIp(req.ip) } });
    res.json({ requestId: id, pollToken, expiresAt });
  });

  app.post('/v1/public/recovery/verify', enabled, recoveryTryLimit, async (req, res) => {
    const c = await recoveryOf(req, res);
    if (!c) return;
    if (c.data.status !== 'code') return res.status(409).json({ error: 'wrong-state', status: recoveryStatus(c) });
    if (c.data.tries >= MAX_TRIES) { await store.deleteChallenge(c.id); return res.status(429).json({ error: 'too-many-tries' }); }
    if (!same(c.data.codeHash, sha256(`${c.id}:${String(req.body?.code ?? '').trim()}`))) {
      await store.putChallenge(c.id, { ...c, data: { ...c.data, tries: c.data.tries + 1 } });
      return res.status(401).json({ error: 'invalid-code' });
    }
    const readyAt = Date.now() + RECOVERY_WAIT_MS;
    await store.putChallenge(c.id, { ...c, expiresAt: readyAt + RECOVERY_LIVE_MS, data: { ...c.data, status: 'waiting', readyAt, codeHash: null } });
    await event(c.personId, 'RECOVERY_STARTED', { notify: true, meta: { readyAt, ip: c.data.ip } });
    if (mailer && c.data.email) {
      mailer.send(c.data.email, 'GoPreach: account recovery started', `Someone entered the recovery code of your GoPreach account. In ${Math.round(RECOVERY_WAIT_MS / 3_600_000)} hours they can set a new password and turn off two-factor sign-in. If this was NOT you: sign in (that cancels it) or open Security & Device Management and press "This was not me", then change your password.`).catch(() => {});
    }
    res.json({ status: 'waiting', readyAt });
  });

  app.post('/v1/public/recovery/status', enabled, recoveryTryLimit, async (req, res) => {
    const c = await recoveryOf(req, res);
    if (!c) return;
    res.json({ status: recoveryStatus(c), readyAt: c.data.readyAt ?? null });
  });

  app.post('/v1/public/recovery/cancel', enabled, recoveryTryLimit, async (req, res) => {
    const c = await recoveryOf(req, res);
    if (!c) return;
    if (c.data.status === 'waiting' || c.data.status === 'code') {
      await store.putChallenge(c.id, { ...c, data: { ...c.data, status: 'cancelled' } });
      if (c.data.status === 'waiting') await event(c.personId, 'RECOVERY_CANCELLED', { notify: true, meta: { why: 'requester' } });
    }
    res.json({ ok: true });
  });

  app.post('/v1/public/recovery/complete', enabled, recoveryTryLimit, async (req, res) => {
    const c = await recoveryOf(req, res);
    if (!c) return;
    const status = recoveryStatus(c);
    if (status === 'waiting') return res.status(409).json({ error: 'not-yet', readyAt: c.data.readyAt });
    if (status !== 'ready') return res.status(409).json({ error: 'wrong-state', status });
    const newPassword = req.body?.newPassword;
    if (typeof newPassword !== 'string' || newPassword.length < 6 || newPassword.length > 200) return res.status(400).json({ error: 'weak-password' });
    const personId = c.personId;
    await store.setPassword(personId, await hashPassword(newPassword));
    await store.deleteRefreshTokens(personId);
    const ended = await revokeMany(personId, { by: 'recovery', reason: 'account-recovery' });
    await removeTrust(personId);
    const s = await a2fa(personId);
    await saveA2fa(personId, { enabled: false, recoveryEmail: s.recoveryEmail ?? null }); // two-factor and device approval start again from nothing
    await store.putChallenge(c.id, { ...c, data: { ...c.data, status: 'done' } });
    await event(personId, 'RECOVERY_COMPLETED', { notify: true, meta: { sessionsEnded: ended, ip: c.data.ip } });
    if (mailer && c.data.email) mailer.send(c.data.email, 'GoPreach: your password was reset', 'The password of your GoPreach account was reset through account recovery, every device was signed out and two-factor sign-in was turned off. If this was not you, contact your Admin at once.').catch(() => {});
    res.json({ ok: true });
  });

  // the owner, signed in: is a recovery waiting, and "This was not me"
  app.get('/v1/security/recovery', enabled, auth, async (req, res) => {
    const c = (await store.listChallenges(req.personId, 'recovery')).find((x) => x.data.status === 'waiting');
    res.json({ pending: c ? { id: c.id, readyAt: c.data.readyAt, createdAt: c.data.createdAt, ip: c.data.ip ?? null, status: recoveryStatus(c) } : null });
  });
  app.post('/v1/security/recovery/cancel', enabled, auth, actLimit, async (req, res) => {
    res.json({ ok: true, cancelled: await cancelRecoveries(req.personId, 'owner') });
  });

  return {
    gate, createSession, event, deviceInfo, approvalRequired, deviceIsTrusted, approvalSettings,
    /** The account's password changed: every other session ends and no trusted device stays trusted. */
    async onPasswordChanged(personId, keepSessionId) {
      const n = await revokeMany(personId, { except: keepSessionId ?? null, by: 'system', reason: 'password-changed' });
      await removeTrust(personId);
      await event(personId, 'PASSWORD_CHANGED', { sessionId: keepSessionId ?? null, notify: true, meta: { otherSessionsEnded: n } });
    },
    /** A password reset by an administrator: every session ends, trusted devices stop being trusted. */
    async onPasswordReset(personId, by) {
      await revokeMany(personId, { by: `admin:${by}`, reason: 'password-reset' });
      await removeTrust(personId);
      await event(personId, 'PASSWORD_CHANGED', { by, notify: true, meta: { reset: true } });
    },
    endOwn: (personId, sessionId) => revokeSession(personId, sessionId, { by: 'self', reason: 'signed-out' }),
  };
}
