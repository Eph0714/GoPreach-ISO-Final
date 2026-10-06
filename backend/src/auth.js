import { createRemoteJWKSet, jwtVerify } from 'jose';
import { config } from './config.js';

/**
 * Logins stay on Firebase Auth for now: the app signs in with Firebase as before and sends its ID token; the server only
 * VERIFIES it against Google's public keys (no secret, no service account needed). The person id is the part of the
 * email before "@" — the same identifier firestore.rules `personIdFromToken()` uses.
 *
 * Tests may use the header `X-Dev-Person: <personId>` when AUTH_MODE=dev; that mode is refused in production.
 */
const JWKS = createRemoteJWKSet(new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'));

export function personIdFromEmail(email) {
  return typeof email === 'string' && email.includes('@') ? email.split('@')[0] : null;
}

export function authenticate({ devMode = false } = {}) {
  if (devMode && config.nodeEnv === 'production') throw new Error('AUTH_MODE=dev is not allowed in production');
  return async (req, res, next) => {
    try {
      if (devMode && req.headers['x-dev-person']) {
        req.personId = String(req.headers['x-dev-person']);
        return next();
      }
      const header = req.headers.authorization ?? '';
      const token = header.startsWith('Bearer ') ? header.slice(7) : null;
      if (!token) return res.status(401).json({ error: 'Missing bearer token' });
      const { payload } = await jwtVerify(token, JWKS, {
        issuer: `https://securetoken.google.com/${config.firebaseProjectId}`,
        audience: config.firebaseProjectId,
      });
      const personId = personIdFromEmail(payload.email);
      if (!personId) return res.status(401).json({ error: 'Token has no email' });
      req.personId = personId;
      next();
    } catch (e) {
      res.status(401).json({ error: 'Invalid token' });
    }
  };
}
