import { randomBytes } from 'node:crypto';
import {
  generateRegistrationOptions, verifyRegistrationResponse,
  generateAuthenticationOptions, verifyAuthenticationResponse,
} from '@simplewebauthn/server';
import { config } from './config.js';

/**
 * Passkeys (WebAuthn) as one more way to sign in to the same GoPreach account.
 *
 *   POST /v1/auth/passkey/register/options  (login)        -> the options for creating a passkey on this phone
 *   POST /v1/auth/passkey/register/verify   { response }   (login)  -> checks and stores the new passkey
 *   POST /v1/auth/passkey/login/options                    -> { sessionId, options }       (no login needed, rate limited)
 *   POST /v1/auth/passkey/login/verify      { sessionId, response } -> the same token pair as a password sign-in, plus the username
 *   POST /v1/auth/passkey/list              (login)        -> the caller's passkeys
 *   POST /v1/auth/passkey/delete            { id } (login) -> removes one of the caller's passkeys
 *   GET  /.well-known/assetlinks.json                      -> ties the Android app to this domain (required for passkeys on Android)
 *
 * The server stores only each passkey's PUBLIC key; the private key never leaves the phone. A passkey is bound to this domain (the relying
 * party id), so it cannot be used on a look-alike site. The one-time challenges are kept in memory for a few minutes (a single server process).
 * Nothing is enabled until PASSKEY_RP_ID and the app's signing certificate fingerprint(s) (ANDROID_CERT_SHA256) are configured.
 */
const CHALLENGE_MS = 5 * 60_000;
const b64url = (buf) => Buffer.from(buf).toString('base64url');

/** "AA:BB:..." or "aabb..." -> 32 bytes, or null when it is not a SHA-256 value. */
export function fingerprintBytes(text) {
  const hex = String(text ?? '').replace(/[^0-9a-fA-F]/g, '');
  return hex.length === 64 ? Buffer.from(hex, 'hex') : null;
}

/** The Android origin a passkey assertion carries: "android:apk-key-hash:<base64url of the signing certificate's SHA-256>". */
export function androidOrigin(fingerprint) {
  const bytes = fingerprintBytes(fingerprint);
  return bytes ? `android:apk-key-hash:${b64url(bytes)}` : null;
}

export function mountPasskeys(app, { store, auth, enabled, loginLimit, startSession, refuseActive, passkeyConfig = config.passkey }) {
  const { rpId, rpName, androidPackage, androidCertSha256, webOrigins = [] } = passkeyConfig;
  const origins = [...androidCertSha256.map(androidOrigin).filter(Boolean), ...webOrigins];
  const ready = (req, res, next) => (rpId && origins.length ? next() : res.status(503).json({ error: 'passkeys-not-configured' }));

  const challenges = new Map(); // key -> { challenge, personId?, expiresAt }
  const remember = (key, value) => {
    for (const [k, v] of challenges) if (v.expiresAt < Date.now()) challenges.delete(k);
    challenges.set(key, { ...value, expiresAt: Date.now() + CHALLENGE_MS });
  };
  const take = (key) => {
    const v = challenges.get(key);
    challenges.delete(key);
    return v && v.expiresAt >= Date.now() ? v : null;
  };

  // Which phone apps may use passkeys for this domain (Digital Asset Links).
  app.get('/.well-known/assetlinks.json', (req, res) => {
    const fingerprints = androidCertSha256.map(fingerprintBytes).filter(Boolean)
      .map((b) => [...b].map((x) => x.toString(16).padStart(2, '0').toUpperCase()).join(':'));
    res.json(fingerprints.length === 0 ? [] : [{
      relation: ['delegate_permission/common.handle_all_urls', 'delegate_permission/common.get_login_creds'],
      target: { namespace: 'android_app', package_name: androidPackage, sha256_cert_fingerprints: fingerprints },
    }]);
  });

  app.post('/v1/auth/passkey/register/options', enabled, ready, auth, async (req, res) => {
    try {
      const account = await store.getAccount(req.personId);
      if (!account || account.disabled) return res.status(401).json({ error: 'invalid-user' });
      const person = (await store.get('people', req.personId))?.data ?? {};
      const existing = await store.listPasskeys(req.personId);
      const options = await generateRegistrationOptions({
        rpName,
        rpID: rpId,
        userName: String(person.username || req.personId),
        userDisplayName: [person.firstName, person.lastName].filter(Boolean).join(' ') || String(person.username || req.personId),
        userID: new TextEncoder().encode(req.personId),
        attestationType: 'none',
        excludeCredentials: existing.map((p) => ({ id: p.credentialId, transports: p.transports })),
        authenticatorSelection: { residentKey: 'required', userVerification: 'preferred' },
      });
      remember(`reg:${req.personId}`, { challenge: options.challenge });
      res.json({ options });
    } catch (e) {
      console.error('passkey registration options failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/auth/passkey/register/verify', enabled, ready, auth, async (req, res) => {
    try {
      const saved = take(`reg:${req.personId}`);
      if (!saved) return res.status(400).json({ error: 'challenge-expired' });
      const verification = await verifyRegistrationResponse({
        response: req.body?.response,
        expectedChallenge: saved.challenge,
        expectedOrigin: origins,
        expectedRPID: rpId,
        requireUserVerification: false,
      });
      if (!verification.verified) return res.status(400).json({ error: 'not-verified' });
      const { credential } = verification.registrationInfo;
      const name = typeof req.body?.name === 'string' ? req.body.name.trim().slice(0, 60) : '';
      await store.addPasskey({
        credentialId: credential.id,
        personId: req.personId,
        publicKey: b64url(credential.publicKey),
        counter: credential.counter ?? 0,
        transports: credential.transports ?? [],
        name: name || 'Passkey',
        createdAt: Date.now(),
      });
      res.json({ ok: true });
    } catch (e) {
      console.error('passkey registration failed', e?.message);
      res.status(400).json({ error: 'not-verified' });
    }
  });

  app.post('/v1/auth/passkey/login/options', enabled, ready, loginLimit, async (req, res) => {
    try {
      const options = await generateAuthenticationOptions({ rpID: rpId, userVerification: 'preferred' });
      const sessionId = randomBytes(24).toString('base64url');
      remember(`login:${sessionId}`, { challenge: options.challenge });
      res.json({ sessionId, options });
    } catch (e) {
      console.error('passkey login options failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/auth/passkey/login/verify', enabled, ready, loginLimit, async (req, res) => {
    const { sessionId, response, device, endOtherSession } = req.body ?? {};
    if (typeof sessionId !== 'string' || sessionId.length > 100 || !response || typeof response.id !== 'string') return res.status(401).json({ error: 'invalid-credentials' });
    try {
      const saved = take(`login:${sessionId}`);
      if (!saved) return res.status(401).json({ error: 'invalid-credentials' });
      const passkey = await store.getPasskey(response.id);
      if (!passkey) return res.status(401).json({ error: 'invalid-credentials' });
      const account = await store.getAccount(passkey.personId);
      if (!account || account.disabled) return res.status(401).json({ error: 'invalid-credentials' });
      // The account the authenticator says it belongs to must be the one the stored passkey belongs to.
      const handle = response.response?.userHandle;
      if (handle && Buffer.from(String(handle), 'base64url').toString('utf8') !== passkey.personId) return res.status(401).json({ error: 'invalid-credentials' });
      const verification = await verifyAuthenticationResponse({
        response,
        expectedChallenge: saved.challenge,
        expectedOrigin: origins,
        expectedRPID: rpId,
        credential: { id: passkey.credentialId, publicKey: Buffer.from(passkey.publicKey, 'base64url'), counter: passkey.counter, transports: passkey.transports },
        requireUserVerification: false,
      });
      if (!verification.verified) return res.status(401).json({ error: 'invalid-credentials' });
      await store.updatePasskeyCounter(passkey.credentialId, verification.authenticationInfo.newCounter);
      await store.touchLogin(passkey.personId);
      const username = (await store.get('people', passkey.personId))?.data?.username ?? null;
      const started = await startSession(passkey.personId, Date.now(), { device, endOtherSession });
      if (started.conflict) return refuseActive(res, started.conflict);
      res.json({ ...started, username });
    } catch (e) {
      console.error('passkey login failed', e?.message);
      res.status(401).json({ error: 'invalid-credentials' });
    }
  });

  app.post('/v1/auth/passkey/list', enabled, auth, async (req, res) => {
    const list = await store.listPasskeys(req.personId);
    res.json({ passkeys: list.map((p) => ({ id: p.credentialId, name: p.name, createdAt: p.createdAt, lastUsedAt: p.lastUsedAt ?? null })) });
  });

  app.post('/v1/auth/passkey/delete', enabled, auth, async (req, res) => {
    const id = req.body?.id;
    if (typeof id !== 'string' || id.length > 300) return res.status(400).json({ error: 'bad-request' });
    res.json({ ok: await store.deletePasskey(req.personId, id) });
  });
}
