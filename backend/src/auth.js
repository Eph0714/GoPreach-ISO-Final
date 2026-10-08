import { createRemoteJWKSet, jwtVerify, SignJWT } from 'jose';
import { config } from './config.js';

/**
 * Who is calling. Two kinds of bearer token are understood:
 *   1. GoPreach's own access token (HS256, signed with AUTH_SECRET, issued by /v1/auth/login and /v1/auth/refresh) - claim `sub` is the person id.
 *   2. While ALLOW_FIREBASE_TOKENS is not "false": a Firebase ID token, verified against Google's public keys (no secret needed).
 *      This only keeps older app builds working during the move; it is switched off once everyone has updated.
 * The person id for a Firebase token is the part of the email before "@".
 *
 * Tests may use the header `X-Dev-Person: <personId>` when AUTH_MODE=dev; that mode is refused in production.
 */
const JWKS = createRemoteJWKSet(new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'));
export const ACCESS_TOKEN_SECONDS = 60 * 60;
const ISSUER = 'gopreach';

export function personIdFromEmail(email) {
  return typeof email === 'string' && email.includes('@') ? email.split('@')[0] : null;
}

const key = (secret) => new TextEncoder().encode(secret);

/** A signed access token for [personId]. [authTime] (ms) is when the password was last typed; it carries over refreshes. */
export async function signAccessToken(personId, authTime, secret) {
  return new SignJWT({ at: authTime })
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(personId)
    .setIssuer(ISSUER)
    .setIssuedAt()
    .setExpirationTime(`${ACCESS_TOKEN_SECONDS}s`)
    .sign(key(secret));
}

export function authenticate({ devMode = false, secret = config.authSecret, allowFirebase = config.allowFirebaseTokens } = {}) {
  if (devMode && config.nodeEnv === 'production') throw new Error('AUTH_MODE=dev is not allowed in production');
  return async (req, res, next) => {
    try {
      if (devMode && req.headers['x-dev-person']) {
        req.personId = String(req.headers['x-dev-person']);
        req.authTime = Date.now();
        return next();
      }
      const header = req.headers.authorization ?? '';
      const token = header.startsWith('Bearer ') ? header.slice(7) : null;
      if (!token) return res.status(401).json({ error: 'Missing bearer token' });
      if (secret) {
        try {
          const { payload } = await jwtVerify(token, key(secret), { issuer: ISSUER, algorithms: ['HS256'] });
          if (payload.sub) {
            req.personId = payload.sub;
            req.authTime = Number(payload.at ?? 0);
            return next();
          }
        } catch { /* not one of ours: fall through to Firebase if still allowed */ }
      }
      if (!allowFirebase) return res.status(401).json({ error: 'Invalid token' });
      const { payload } = await jwtVerify(token, JWKS, {
        issuer: `https://securetoken.google.com/${config.firebaseProjectId}`,
        audience: config.firebaseProjectId,
      });
      const personId = personIdFromEmail(payload.email);
      if (!personId) return res.status(401).json({ error: 'Token has no email' });
      req.personId = personId;
      req.authTime = Number(payload.auth_time ?? 0) * 1000;
      next();
    } catch (e) {
      res.status(401).json({ error: 'Invalid token' });
    }
  };
}
