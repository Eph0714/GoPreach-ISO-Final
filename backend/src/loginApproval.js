import { createHash, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { rateLimit } from './rateLimit.js';
import { config } from './config.js';

/**
 * Protecting the signed-in phone. When the PASSWORD (or a passkey) is right but another device already holds the account's session, the new
 * device does NOT replace it. It gets a login request that can only be settled one of these ways:
 *
 *   - the signed-in device taps "Yes, It's Me"  (POST /v1/auth/login-requests/:id/approve, with that device's own session)
 *   - an e-mail code sent to the address registered when the request was made (when the server can send mail and the person has an address)
 *   - a passkey sign-in (passkeys.js: a verified assertion is its own proof)
 *
 * Nothing is ever approved because the first device is offline or silent: a request that is not approved in LOGIN_REQUEST_MS expires and is refused.
 * Denied or expired requests change nothing; the first session carries on. Only a request that was APPROVED, once, takes the session over (and that ends
 * the old one, the account still has exactly one session).
 *
 *   GET  /v1/auth/login-requests                 (login)  -> the pending requests for the signed-in account (what the first phone shows)
 *   POST /v1/auth/login-requests/:id/approve     (login)  -> "Yes, It's Me"
 *   POST /v1/auth/login-requests/:id/deny        (login)  -> "No, Deny Access"
 *   POST /v1/auth/login-requests/status          { requestId, pollToken }            -> { status: pending | approved | denied | expired }
 *   POST /v1/auth/login             { email, password, approval: { requestId, pollToken } }  -> once the request is approved, the password sign-in carrying it takes the
 *                                                  session over (the request is spent by that one use)
 *   POST /v1/auth/login-requests/email-code      { requestId, pollToken }            -> sends the code to the masked address
 *   POST /v1/auth/login-requests/email-verify    { requestId, pollToken, code }      -> approves the request when the code is right
 *
 * The poll token is a secret only the requesting device holds (the server keeps its hash), so another caller cannot read or consume the request.
 */
export const LOGIN_REQUEST_MS = 5 * 60_000;
const CODE_MS = 10 * 60_000;
const CODE_COOLDOWN_MS = 60_000;
const MAX_CODES = 3;
const MAX_CODE_TRIES = 5;
const sha256 = (s) => createHash('sha256').update(s).digest('hex');
const same = (a, b) => a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));

export const maskEmail = (email) => {
  const [local, domain] = String(email ?? '').split('@');
  if (!local || !domain) return null;
  return `${local.slice(0, 1)}${'*'.repeat(Math.max(2, Math.min(local.length - 1, 6)))}@${domain}`;
};
const maskIp = (ip) => {
  const v4 = /^(?:::ffff:)?(\d+)\.(\d+)\.\d+\.\d+$/.exec(ip ?? '');
  return v4 ? `${v4[1]}.${v4[2]}.*.*` : null;
};

/**
 * Sends mail through the SMTP account in the server settings; null when none is set up (the e-mail method is then simply not offered). Synchronous on purpose
 * (no top-level await anywhere in the server: some hosts load the entry file with require(), which refuses it): the mail library is loaded on the first send.
 */
export function smtpMailer(mail = config.mail) {
  if (!mail?.host || !mail.from) return null;
  let transport = null;
  return {
    async send(to, subject, text) {
      if (!transport) {
        const { default: nodemailer } = await import('nodemailer');
        transport = nodemailer.createTransport({ host: mail.host, port: mail.port, secure: mail.secure, auth: mail.user ? { user: mail.user, pass: mail.pass } : undefined });
      }
      return transport.sendMail({ from: mail.from, to, subject, text });
    },
  };
}

export function mountLoginApproval(app, { store, auth, enabled, mailer = null, limits = {}, requestMs = LOGIN_REQUEST_MS }) {
  const statusLimit = rateLimit({ windowMs: 60_000, max: 90, key: (req) => req.ip, ...limits.status });
  const codeLimit = rateLimit({ windowMs: 10 * 60_000, max: 12, key: (req) => `${req.ip}|${String(req.body?.requestId ?? '').slice(0, 64)}`, ...limits.code });
  const made = new Map(); // personId -> recent request times (so a stranger who knows the password cannot flood the owner's phone)
  const recentMade = (id) => (made.get(id) ?? []).filter((t) => Date.now() - t < 15 * 60_000);
  const busy = new Set();

  const emailOf = async (personId) => {
    const p = await store.get('people', personId);
    const e = String(p?.data?.email ?? '').trim();
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e) ? e : null;
  };

  /** Called by the password sign-in when another live session holds the account. Returns what the requesting device needs to carry on. */
  async function createRequest(personId, active, { device, ip }) {
    if (recentMade(personId).length >= 5) return { tooMany: true };
    made.set(personId, [...recentMade(personId), Date.now()]);
    for (const old of await store.listChallenges(personId, 'loginreq')) if (old.data.status === 'pending') await store.putChallenge(old.id, { ...old, data: { ...old.data, status: 'superseded' } });
    const id = randomBytes(12).toString('base64url');
    const pollToken = randomBytes(24).toString('base64url');
    const email = mailer ? await emailOf(personId) : null; // fixed now: changing the address during the sign-in changes nothing
    const expiresAt = Date.now() + requestMs;
    await store.putChallenge(id, {
      personId, kind: 'loginreq', expiresAt,
      data: { status: 'pending', device: device ?? null, ip: maskIp(ip), createdAt: Date.now(), pollHash: sha256(pollToken), email, codeHash: null, codeSentAt: 0, codesSent: 0, tries: 0 },
    });
    return {
      requestId: id, pollToken, expiresAt,
      methods: { device: true, email: email ? { masked: maskEmail(email) } : null },
      device: active.device ?? null, lastActiveAt: active.lastActiveAt,
    };
  }

  /** The request, if [pollToken] is the one issued with it and it is still pending / approved. */
  async function owned(req) {
    const { requestId, pollToken } = req.body ?? {};
    if (typeof requestId !== 'string' || typeof pollToken !== 'string' || requestId.length > 40 || pollToken.length > 80) return null;
    const c = await store.getChallenge(requestId);
    return c && c.kind === 'loginreq' && same(c.data.pollHash, sha256(pollToken)) ? c : null;
  }

  /** Spends an approved request for [personId] (the login endpoint calls this after the password is right). True only once, only for the account it was made for. */
  async function redeem(personId, approval) {
    const c = await owned({ body: approval });
    if (!c || c.personId !== personId || busy.has(c.id)) return false;
    busy.add(c.id);
    try {
      const fresh = await store.getChallenge(c.id);
      if (!fresh || fresh.data.status !== 'approved') return false;
      await store.putChallenge(c.id, { ...fresh, data: { ...fresh.data, status: 'used' } }); // single use, before anything is issued
      return true;
    } finally { busy.delete(c.id); }
  }

  app.get('/v1/auth/login-requests', enabled, auth, async (req, res) => {
    const pending = (await store.listChallenges(req.personId, 'loginreq')).filter((c) => c.data.status === 'pending');
    res.json({ requests: pending.map((c) => ({ id: c.id, device: c.data.device, ip: c.data.ip, createdAt: c.data.createdAt, expiresAt: c.expiresAt })) });
  });

  const decide = (status) => async (req, res) => {
    const c = await store.getChallenge(String(req.params.id).slice(0, 40));
    // Only the account's own signed-in device may decide, and only about a request made against that account.
    if (!c || c.kind !== 'loginreq' || c.personId !== req.personId) return res.status(404).json({ error: 'not-found' });
    if (c.data.status !== 'pending') return res.status(409).json({ error: 'not-pending', status: c.data.status });
    await store.putChallenge(c.id, { ...c, data: { ...c.data, status, decidedAt: Date.now() } });
    res.json({ ok: true, status });
  };
  app.post('/v1/auth/login-requests/:id/approve', enabled, auth, decide('approved'));
  app.post('/v1/auth/login-requests/:id/deny', enabled, auth, decide('denied'));

  app.post('/v1/auth/login-requests/status', enabled, statusLimit, async (req, res) => {
    const c = await owned(req);
    if (!c) return res.json({ status: 'expired' }); // unknown, expired and wrong token look the same
    res.json({ status: ['pending', 'approved', 'denied'].includes(c.data.status) ? c.data.status : 'expired', expiresAt: c.expiresAt });
  });

  app.post('/v1/auth/login-requests/email-code', enabled, codeLimit, async (req, res) => {
    const c = await owned(req);
    if (!c || c.data.status !== 'pending' || !c.data.email || !mailer) return res.status(409).json({ error: 'not-available' });
    const now = Date.now();
    if (now - c.data.codeSentAt < CODE_COOLDOWN_MS) return res.status(429).json({ error: 'wait', retryAfter: Math.ceil((CODE_COOLDOWN_MS - (now - c.data.codeSentAt)) / 1000) });
    if (c.data.codesSent >= MAX_CODES) return res.status(429).json({ error: 'too-many-codes' });
    const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
    try {
      await mailer.send(c.data.email, 'Your GoPreach verification code', `Your GoPreach verification code is ${code}. It works once and expires in 10 minutes.\n\nIf you did not try to sign in, do not share this code: someone else may know your password, so change it.`);
    } catch (e) {
      console.error('verification e-mail failed', e?.message);
      return res.status(502).json({ error: 'mail-failed' });
    }
    await store.putChallenge(c.id, { ...c, expiresAt: Math.max(c.expiresAt, now + CODE_MS), data: { ...c.data, codeHash: sha256(`${c.id}:${code}`), codeExpiresAt: now + CODE_MS, codeSentAt: now, codesSent: c.data.codesSent + 1, tries: 0 } });
    res.json({ ok: true, masked: maskEmail(c.data.email) });
  });

  app.post('/v1/auth/login-requests/email-verify', enabled, codeLimit, async (req, res) => {
    const c = await owned(req);
    const code = String(req.body?.code ?? '').trim();
    if (!c || c.data.status !== 'pending' || !c.data.codeHash || Date.now() > (c.data.codeExpiresAt ?? 0)) return res.status(409).json({ error: 'not-available' });
    if (!/^\d{6}$/.test(code)) return res.status(400).json({ error: 'bad-code' });
    if (c.data.tries >= MAX_CODE_TRIES) return res.status(429).json({ error: 'too-many-tries' });
    if (!same(c.data.codeHash, sha256(`${c.id}:${code}`))) {
      const tries = c.data.tries + 1;
      // Too many wrong guesses end the request: a new sign-in (and a new password check) is needed.
      await store.putChallenge(c.id, { ...c, data: { ...c.data, tries, ...(tries >= MAX_CODE_TRIES ? { status: 'denied' } : {}) } });
      return res.status(401).json({ error: 'wrong-code', triesLeft: Math.max(0, MAX_CODE_TRIES - tries) });
    }
    await store.putChallenge(c.id, { ...c, data: { ...c.data, status: 'approved', codeHash: null, approvedBy: 'email' } }); // the code can never be used again
    res.json({ status: 'approved' });
  });

  return { createRequest, redeem };
}
