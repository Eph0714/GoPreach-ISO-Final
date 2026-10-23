import express from 'express';
import { EventEmitter } from 'node:events';
import helmet from 'helmet';
import { authenticate } from './auth.js';
import { randomBytes } from 'node:crypto';
import { loadActor, authorizeWrite, canReadRow, loadGrant } from './policy/index.js';
import { rateLimit } from './rateLimit.js';
import { mountFiles } from './files.js';
import { mountVoice } from './voice.js';
import { mountAnnouncementFiles } from './announcementFiles.js';
import { mountPresence } from './presence.js';
import { mountCoFiles } from './coFiles.js';
import { mountCoManual } from './coManual.js';
import { mountPublisherEdit } from './publisherEdit.js';
import { mountAccounts } from './accounts.js';
import { saveGroupTerritory, removeGroupTerritory, removeAssignment, validateSave } from './territory.js';

const MAX_OPS = 200;
const MAX_PULL = 1000;
const MAX_WATCH_SECONDS = 25;
// Top-level names, or subcollection paths like "interestedPeople/abc/visits".
const COLLECTION_RE = new RegExp("^[A-Za-z][A-Za-z0-9_]{0,63}(/[A-Za-z0-9_-]{1,100}/[A-Za-z][A-Za-z0-9_]{0,63})?$");
const ID_RE = /^[^/\\\s][^/\\]{0,189}$/;

/**
 * Builds the API. `store` is a MemoryStore or MysqlStore (see store.js).
 *
 *   GET  /v1/health
 *   POST /v1/sync/push   { ops: [{ collection, id, op: "set" | "delete", data? }] }
 *        -> { results: [{ collection, id, status: "ok" | "denied" | "invalid", version?, seq?, reason? }] }
 *        An op may carry "baseVersion" (the version the device last saw): if the stored document is newer the op is refused with
 *        status "conflict" (and { current: { version, deleted } }), and a document that was deleted since is never brought back by an op that
 *        carries it (status "gone"). Ops without it behave as they always did.
 *   GET  /v1/sync/pull?since=<seq>&collections=a,b&limit=500
 *        -> { changes: [{ collection, id, data, deleted, version, seq }], cursor, hasMore }
 *   GET  /v1/sync/watch?since=<seq|head>&collections=a,b&timeout=<0..25 seconds>
 *        -> { changed: [{ collection, count, deletes, ids }], cursor }   the change feed: WHAT changed that the caller may read (never the contents),
 *        held open until something changes (long poll: works on plain HTTP hosting). Fetch the changed collections with /pull.
 *   POST /v1/public/lookup-username, /v1/public/password-reset-request   (no login, rate limited)
 *   POST /v1/territory/save | remove-group | remove                         (atomic barangay claims)
 *
 * Every push op is authorized individually (role → congregation → FS Group → territory); a denied op never writes
 * and never blocks the others. Every pull is filtered to what the caller may read.
 */
export function createApp(store, { devAuth = false, health = () => ({}), publicLimits = {}, authOptions = {}, voiceOptions = {} } = {}) {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 1); // Hostinger's HTTPS proxy sits in front: use the real client address for rate limiting
  app.use(helmet());
  app.use(express.json({ limit: '2mb' }));

  app.get('/v1/health', (req, res) => res.json({ ok: true, ...health() }));

  // Every committed write wakes the watchers (the change feed below). One process: a short check interval covers anything this misses.
  const commits = new EventEmitter();
  commits.setMaxListeners(0);
  const runTransaction = store.transaction.bind(store);
  store.transaction = async (fn) => {
    const result = await runTransaction(fn);
    commits.emit('commit');
    return result;
  };

  const auth = authenticate({ devMode: devAuth, secret: authOptions.secret, store });
  // The authoritative date and time (never the caller's device clock): used to decide which service months may be reported.
  app.post('/v1/time', auth, (req, res) => res.json({ now: Date.now() }));

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
        const actor = await loadActor(req.personId, tx.get, tx.list);
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

  mountFiles(app, store, auth);
  mountVoice(app, store, auth, voiceOptions);
  mountAnnouncementFiles(app, store, auth);
  mountPresence(app, store, auth);
  mountCoFiles(app, store, auth);
  mountCoManual(app, store, auth);
  mountAccounts(app, store, auth, authOptions);
  mountPublisherEdit(app, store, auth);

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
          const actor = await loadActor(req.personId, tx.get, tx.list);
          const existing = await tx.get(collection, id);
          const verdict = await authorizeWrite(actor, op.op, collection, id, op.data, existing, tx.get);
          if (!verdict.ok) return { status: 'denied', reason: verdict.reason };
          // A device that says which version it last saw never overwrites a newer one, and never brings back a document that was deleted since.
          if (Number.isInteger(op.baseVersion) && existing && existing.version > op.baseVersion) {
            return existing.deleted
              ? { status: 'gone', reason: 'This record was deleted on another device.', current: { version: existing.version, deleted: true } }
              : { status: 'conflict', reason: 'This record was changed on another device.', current: { version: existing.version, deleted: false } };
          }
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
      const actor = await loadActor(req.personId, get, (c, cong) => store.list(c, cong));
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

  // The change feed. Holds the request open (at most MAX_WATCH_SECONDS) until a change the caller may read exists after `since`, then answers which
  // collections and ids changed - never the contents - so a device pulls only what it needs. `since=head` answers at once with the current position.
  // Each row is authorized exactly as in /pull, so a person is never told that a record they may not read changed.
  app.get('/v1/sync/watch', auth, async (req, res) => {
    const collections = req.query.collections ? String(req.query.collections).split(',').filter((c) => COLLECTION_RE.test(c)) : null;
    const seconds = Math.max(0, Math.min(Number.parseInt(String(req.query.timeout ?? '0'), 10) || 0, MAX_WATCH_SECONDS));
    try {
      if (req.query.since === 'head') return res.json({ changed: [], cursor: await store.headSeq() });
      let since = Number.parseInt(String(req.query.since ?? '0'), 10);
      if (!Number.isFinite(since) || since < 0) return res.status(400).json({ error: 'since must be >= 0 or "head"' });
      const cache = new Map();
      const get = (c, i) => {
        const key = c + '/' + i;
        if (!cache.has(key)) cache.set(key, store.get(c, i));
        return cache.get(key);
      };
      const actor = await loadActor(req.personId, get, (c, cong) => store.list(c, cong));
      if (!actor.known) return res.status(403).json({ error: 'Unknown user' });
      const deadline = Date.now() + seconds * 1000;
      let closed = false;
      req.on('close', () => { closed = true; });
      for (;;) {
        cache.clear();
        const grant = await loadGrant(req.personId, get);
        const rows = await store.changesSince(since, { collections, limit: 500 });
        if (rows.length) {
          const readable = await Promise.all(rows.map((r) => canReadRow(actor, grant, r, get)));
          const byCollection = new Map();
          rows.forEach((r, i) => {
            if (!readable[i]) return;
            const e = byCollection.get(r.collection) ?? { collection: r.collection, count: 0, deletes: 0, ids: [] };
            e.count += 1;
            if (r.deleted) e.deletes += 1;
            if (e.ids.length < 50) e.ids.push(r.id);
            byCollection.set(r.collection, e);
          });
          const cursor = rows[rows.length - 1].seq;
          if (byCollection.size) return res.json({ changed: [...byCollection.values()], cursor });
          since = cursor; // nothing here for this person: move past it and keep waiting
        }
        if (closed || Date.now() >= deadline) return res.json({ changed: [], cursor: since });
        await new Promise((resolve) => {
          const done = () => { clearTimeout(timer); commits.off('commit', done); resolve(); };
          const timer = setTimeout(done, Math.min(3000, Math.max(0, deadline - Date.now())));
          commits.once('commit', done);
          req.once('close', done);
        });
      }
    } catch (e) {
      console.error('watch failed', e);
      if (!res.headersSent) res.status(500).json({ error: 'Server error' });
    }
  });

  app.use((req, res) => res.status(404).json({ error: 'Not found' }));
  return app;
}
