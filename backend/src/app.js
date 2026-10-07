import express from 'express';
import helmet from 'helmet';
import { authenticate } from './auth.js';
import { randomBytes } from 'node:crypto';
import { loadActor, authorizeWrite, canReadRow, loadGrant } from './policy/index.js';
import { rateLimit } from './rateLimit.js';
import { saveGroupTerritory, removeGroupTerritory, removeAssignment, validateSave } from './territory.js';

const MAX_OPS = 200;
const MAX_PULL = 1000;
// Top-level names, or subcollection paths like "interestedPeople/abc/visits".
const COLLECTION_RE = new RegExp("^[A-Za-z][A-Za-z0-9_]{0,63}(/[A-Za-z0-9_-]{1,100}/[A-Za-z][A-Za-z0-9_]{0,63})?$");
const ID_RE = /^[^/\\\s][^/\\]{0,189}$/;

/**
 * Builds the API. `store` is a MemoryStore or MysqlStore (see store.js).
 *
 *   GET  /v1/health
 *   POST /v1/sync/push   { ops: [{ collection, id, op: "set" | "delete", data? }] }
 *        -> { results: [{ collection, id, status: "ok" | "denied" | "invalid", version?, seq?, reason? }] }
 *   GET  /v1/sync/pull?since=<seq>&collections=a,b&limit=500
 *        -> { changes: [{ collection, id, data, deleted, version, seq }], cursor, hasMore }
 *   POST /v1/public/lookup-username, /v1/public/password-reset-request   (no login, rate limited)
 *   POST /v1/territory/save | remove-group | remove                         (atomic barangay claims)
 *
 * Every push op is authorized individually (role → congregation → FS Group → territory); a denied op never writes
 * and never blocks the others. Every pull is filtered to what the caller may read.
 */
export function createApp(store, { devAuth = false, health = () => ({}), publicLimits = {} } = {}) {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 1); // Hostinger's HTTPS proxy sits in front: use the real client address for rate limiting
  app.use(helmet());
  app.use(express.json({ limit: '2mb' }));

  app.get('/v1/health', (req, res) => res.json({ ok: true, ...health() }));

  const auth = authenticate({ devMode: devAuth });

  // ---- No login needed ---------------------------------------------------------------------------------------------
  // Firestore let anyone read `people` and create password-reset requests, because a person cannot sign in before the app
  // has turned their username into a sign-in identity. These two endpoints replace that, rate limited per IP.
  const usernameOk = (u) => typeof u === 'string' && u.length > 0 && u.length <= 100;
  const lookupLimit = rateLimit({ windowMs: 10 * 60_000, max: 30, ...publicLimits.lookup });
  const resetLimit = rateLimit({ windowMs: 60 * 60_000, max: 5, ...publicLimits.reset });

  /** POST { username } -> { person: { id, ...fields } | null }. The same person record Firestore returned to the sign-in screen. */
  app.post('/v1/public/lookup-username', lookupLimit, async (req, res) => {
    const username = req.body?.username;
    if (!usernameOk(username)) return res.status(400).json({ error: 'username is required' });
    try {
      const found = await store.findPersonByUsername(username);
      res.json({ person: found ? { ...found.data, id: found.id } : null });
    } catch (e) {
      console.error('lookup failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  /** POST { username } -> 202 always (it never reveals whether the username exists). Spec §4.5: the enrolling role sees the request. */
  app.post('/v1/public/password-reset-request', resetLimit, async (req, res) => {
    const username = req.body?.username;
    if (!usernameOk(username)) return res.status(400).json({ error: 'username is required' });
    try {
      const found = await store.findPersonByUsername(username);
      const id = randomBytes(15).toString('base64url');
      await store.transaction((tx) => tx.put('passwordResetRequests', id, {
        requestedUsername: username,
        personId: found?.id ?? null,
        targetPersonId: found?.data?.createdByPersonId ?? null,
        status: 'PENDING',
        requestedAt: Date.now(),
      }, 'public'));
      res.status(202).json({ ok: true });
    } catch (e) {
      console.error('password reset request failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  // ---- Territory assignment (atomic claims) -----------------------------------------------------------------------
  const HTTP = { success: 200, conflict: 409, denied: 403, error: 400 };
  const territory = (fn, validate = () => null) => async (req, res) => {
    const bad = validate(req.body);
    if (bad) return res.status(400).json({ status: 'error', message: bad });
    try {
      const result = await store.transaction(async (tx) => {
        const actor = await loadActor(req.personId, tx.get);
        if (!actor.known) return { status: 'denied', message: 'Unknown user' };
        return fn(tx, actor, req.body);
      });
      res.status(HTTP[result.status] ?? 400).json(result);
    } catch (e) {
      console.error('territory request failed', e);
      res.status(500).json({ status: 'error', message: 'Server error' });
    }
  };
  /** POST the full desired barangay set for a group inside one province. 409 { barangayName, takenByGroupName } when one is taken. */
  app.post('/v1/territory/save', auth, territory(saveGroupTerritory, validateSave));
  app.post('/v1/territory/remove-group', auth, territory(removeGroupTerritory));
  app.post('/v1/territory/remove', auth, territory(removeAssignment));

  app.post('/v1/sync/push', auth, async (req, res) => {
    const ops = req.body?.ops;
    if (!Array.isArray(ops) || ops.length === 0 || ops.length > MAX_OPS) {
      return res.status(400).json({ error: `ops must be an array of 1..${MAX_OPS}` });
    }
    const results = [];
    // One transaction per op: a rejected or malformed op can't poison the rest of the batch.
    for (const op of ops) {
      const { collection, id } = op ?? {};
      if (!COLLECTION_RE.test(collection ?? '') || !ID_RE.test(String(id ?? '')) || !['set', 'delete'].includes(op.op)) {
        results.push({ collection, id, status: 'invalid', reason: 'Bad collection, id or op' });
        continue;
      }
      if (op.op === 'set' && (typeof op.data !== 'object' || op.data === null || Array.isArray(op.data))) {
        results.push({ collection, id, status: 'invalid', reason: 'data must be an object' });
        continue;
      }
      try {
        const result = await store.transaction(async (tx) => {
          const actor = await loadActor(req.personId, tx.get);
          const existing = await tx.get(collection, id);
          const verdict = await authorizeWrite(actor, op.op, collection, id, op.data, existing, tx.get);
          if (!verdict.ok) return { status: 'denied', reason: verdict.reason };
          if (op.op === 'delete') {
            if (!existing || existing.deleted) return { status: 'ok', version: existing?.version ?? 0, seq: existing?.seq ?? 0 };
            return { status: 'ok', ...(await tx.remove(collection, id, req.personId)) };
          }
          return { status: 'ok', ...(await tx.put(collection, id, op.data, req.personId)) };
        });
        results.push({ collection, id, ...result });
      } catch (e) {
        console.error('push op failed', collection, id, e);
        results.push({ collection, id, status: 'invalid', reason: 'Server error' });
      }
    }
    res.json({ results });
  });

  app.get('/v1/sync/pull', auth, async (req, res) => {
    const since = Number.parseInt(String(req.query.since ?? '0'), 10);
    const limit = Math.min(Number.parseInt(String(req.query.limit ?? '500'), 10) || 500, MAX_PULL);
    const collections = req.query.collections ? String(req.query.collections).split(',').filter((c) => COLLECTION_RE.test(c)) : null;
    if (!Number.isFinite(since) || since < 0) return res.status(400).json({ error: 'since must be >= 0' });
    try {
      // Lookups made while judging one pull are cached for that pull (the same group or chat is checked for many rows).
      const cache = new Map();
      const get = (c, i) => {
        const key = c + '/' + i;
        if (!cache.has(key)) cache.set(key, store.get(c, i));
        return cache.get(key);
      };
      const actor = await loadActor(req.personId, get);
      if (!actor.known) return res.status(403).json({ error: 'Unknown user' });
      const grant = await loadGrant(req.personId, get);
      // Over-fetch a little so filtering rarely returns a short page; the cursor always advances past what was examined.
      const rows = await store.changesSince(since, { collections, limit: limit + 1 });
      const hasMore = rows.length > limit;
      const page = hasMore ? rows.slice(0, limit) : rows;
      const readable = await Promise.all(page.map((r) => canReadRow(actor, grant, r, get)));
      const changes = page
        .filter((_, i) => readable[i])
        .map((r) => ({ collection: r.collection, id: r.id, data: r.deleted ? null : r.data, deleted: r.deleted, version: r.version, seq: r.seq }));
      const cursor = page.length ? page[page.length - 1].seq : since;
      res.json({ changes, cursor, hasMore });
    } catch (e) {
      console.error('pull failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.use((req, res) => res.status(404).json({ error: 'Not found' }));
  return app;
}
