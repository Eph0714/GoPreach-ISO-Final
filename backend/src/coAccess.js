import { randomBytes } from 'node:crypto';
import { loadActor, loadGrant } from './policy/index.js';

/**
 * Who may use the Circuit Overseer's private modules (File Manager, Previous Manual Record): a Circuit Overseer (an access grant that carries a
 * Circuit Code) and the Super-Admin. Everything they store is private to them: the owner of every row is the signed-in person, and every route
 * filters by that owner on the server, so a guessed id of someone else's file or record answers 404.
 */
export async function coAccess(store, personId) {
  const get = (c, i) => store.get(c, i);
  const actor = await loadActor(personId, get);
  if (!actor.known) return { allowed: false };
  const grant = await loadGrant(personId, get);
  const isCo = grant.has && grant.circuitCode != null;
  return { allowed: actor.isSuperAdmin || isCo, actor, grant, isCo, isSuper: actor.isSuperAdmin };
}

/** The congregations this person may pick: all of them for the Super-Admin and an "all congregations" grant, otherwise the grant's list. */
export async function allowedCongregations(store, access) {
  const rows = await store.list('congregations');
  const all = rows.filter((r) => r.data.isDeleted !== true).map((r) => ({ id: r.id, name: String(r.data.name ?? r.id) }));
  const scopeAll = access.isSuper || access.grant?.scopeType === 'ALL_CONGREGATIONS';
  const ids = access.grant?.scopeCongregationIds ?? [];
  return all.filter((c) => scopeAll || (access.grant?.scopeType === 'SELECTED_CONGREGATIONS' && ids.includes(c.id))).sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
}

export const newId = () => randomBytes(12).toString('base64url');
export const ID_RE = /^[A-Za-z0-9_-]{8,40}$/;

/** Writes the same audit entry the app writes (collection `auditLog`) for an important action. Never blocks the action itself. */
export async function audit(store, personId, action, targetType, targetId, details) {
  try {
    await store.transaction((tx) => tx.put('auditLog', newId(), {
      actorPersonId: personId, action, targetType, targetId, congregationId: null, timestamp: Date.now(), details: String(details ?? '').slice(0, 300),
    }, personId));
  } catch (e) {
    console.error('audit log failed', action, e.message);
  }
}

/** express error handler for these routers: too-large bodies and bad JSON answer a clear JSON message instead of an HTML page. */
export function jsonErrors(err, req, res, next) {
  if (res.headersSent) return next(err);
  if (err?.type === 'entity.too.large') return res.status(413).json({ error: 'The file is too large' });
  if (err?.type === 'entity.parse.failed') return res.status(400).json({ error: 'Bad request' });
  console.error('co route failed', err);
  return res.status(500).json({ error: 'Server error' });
}
