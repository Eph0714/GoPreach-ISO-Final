import { jwtVerify, SignJWT } from 'jose';
import { config } from './config.js';

/**
 * Who is calling. The only accepted credential is GoPreach's own access token (HS256, signed with AUTH_SECRET, issued by /v1/auth/login
 * and /v1/auth/refresh); its claim `sub` is the person id. There is no third-party identity provider.
 *
 * Tests may use the header `X-Dev-Person: <personId>` when AUTH_MODE=dev; that mode is refused in production.
 */
export const ACCESS_TOKEN_SECONDS = 60 * 60;
const ISSUER = 'gopreach';

/** The person id of a sign-in address: the part before "@" (the app signs in as "<personId>@gopreach.internal"). */
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

export function authenticate({ devMode = false, secret = config.authSecret } = {}) {
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
      if (!secret) return res.status(401).json({ error: 'Invalid token' });
      const { payload } = await jwtVerify(token, key(secret), { issuer: ISSUER, algorithms: ['HS256'] });
      if (!payload.sub) return res.status(401).json({ error: 'Invalid token' });
      req.personId = payload.sub;
      req.authTime = Number(payload.at ?? 0);
      next();
    } catch (e) {
      res.status(401).json({ error: 'Invalid token' });
    }
  };
}
