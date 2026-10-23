/**
 * A small in-memory sliding-window limiter per client IP, for the two endpoints that need no login. One process serves the
 * whole API on Hostinger, so memory is enough; a restart simply forgets the counters.
 *
 * [key] picks what is counted. The default is the client IP, but the website calls this API from ONE server address for every visitor, so sign-in counts
 * per address AND account instead: one person's attempts can never lock out everybody else on the website.
 */
export function rateLimit({ windowMs, max, key = (req) => req.ip }) {
  const hits = new Map();
  return (req, res, next) => {
    const now = Date.now();
    const k = key(req);
    const recent = (hits.get(k) ?? []).filter((t) => now - t < windowMs);
    if (recent.length >= max) {
      res.set('Retry-After', String(Math.ceil((recent[0] + windowMs - now) / 1000)));
      return res.status(429).json({ error: 'Too many requests. Try again later.' });
    }
    recent.push(now);
    hits.set(k, recent);
    if (hits.size > 5000) for (const [ip, times] of hits) if (times.every((t) => now - t >= windowMs)) hits.delete(ip);
    next();
  };
}
