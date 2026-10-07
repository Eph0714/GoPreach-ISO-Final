/**
 * A small in-memory sliding-window limiter per client IP, for the two endpoints that need no login. One process serves the
 * whole API on Hostinger, so memory is enough; a restart simply forgets the counters.
 */
export function rateLimit({ windowMs, max }) {
  const hits = new Map();
  return (req, res, next) => {
    const now = Date.now();
    const recent = (hits.get(req.ip) ?? []).filter((t) => now - t < windowMs);
    if (recent.length >= max) {
      res.set('Retry-After', String(Math.ceil((recent[0] + windowMs - now) / 1000)));
      return res.status(429).json({ error: 'Too many requests. Try again later.' });
    }
    recent.push(now);
    hits.set(req.ip, recent);
    if (hits.size > 5000) for (const [ip, times] of hits) if (times.every((t) => now - t >= windowMs)) hits.delete(ip);
    next();
  };
}
