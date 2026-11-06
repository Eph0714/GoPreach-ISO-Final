import { rateLimit } from './rateLimit.js';

/**
 * What changed in each release of the app, for the update window: GET /v1/public/app-releases?since=1.169.30 -> { releases: [{ version, date, notes }] } newest first,
 * only the releases newer than `since` when it is given. The release notes are written when a release is published (scripts/release.mjs keeps them in
 * scripts/release-history.json); this reads that file from GitHub and keeps it for a few minutes. The phone asks its own server instead of GitHub, which some
 * networks block or throttle, so the update window can always list every change since the installed version. No login (the person has not updated yet,
 * and a release note is public information).
 */
const SOURCE = 'https://raw.githubusercontent.com/Eph0714/GoPreach-ISO-Final/main/scripts/release-history.json';

const parts = (v) => String(v ?? '').split('.').map((n) => Number.parseInt(n, 10) || 0);
export function isNewer(current, latest) {
  const a = parts(current), b = parts(latest);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const x = a[i] ?? 0, y = b[i] ?? 0;
    if (x !== y) return y > x;
  }
  return false;
}

export function mountAppReleases(app, { fetchImpl = globalThis.fetch, url = SOURCE, ttlMs = 5 * 60_000, publicLimit = {} } = {}) {
  let cache = null; // { at, releases }
  const limit = rateLimit({ windowMs: 10 * 60_000, max: 120, ...publicLimit });

  async function load() {
    if (cache && Date.now() - cache.at < ttlMs) return cache.releases;
    try {
      const res = await fetchImpl(url, { headers: { 'user-agent': 'GoPreach-API' }, signal: AbortSignal.timeout(10_000) });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const list = await res.json();
      if (!Array.isArray(list)) throw new Error('not a list');
      const releases = list
        .filter((e) => e && typeof e.version === 'string')
        .map((e) => ({ version: e.version, date: typeof e.date === 'string' ? e.date : '', notes: typeof e.notes === 'string' ? e.notes.trim() : '' }));
      cache = { at: Date.now(), releases };
      return releases;
    } catch (e) {
      if (cache) return cache.releases; // a stale list beats none
      throw e;
    }
  }

  app.get('/v1/public/app-releases', limit, async (req, res) => {
    try {
      const since = typeof req.query.since === 'string' ? req.query.since : '';
      let releases = await load();
      if (since) releases = releases.filter((r) => isNewer(since, r.version));
      releases = [...releases].sort((a, b) => (isNewer(a.version, b.version) ? 1 : -1)).slice(0, 50);
      res.json({ releases });
    } catch (e) {
      console.error('app releases failed', e?.message ?? e);
      res.status(502).json({ error: 'The release notes are not available right now' });
    }
  });
}
