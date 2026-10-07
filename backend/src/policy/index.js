import { congregationOf } from '../store.js';
import { authorizeRulesWrite, canReadDoc, loadGrant } from './rules.js';
import { authorizeDrawingWrite, authorizeAuditWrite, authorizeBoundsWrite } from './drawings.js';

/** Roles that may manage a congregation's territory (same set as firestore.rules `canManageTerritoryAssignmentsFor`). */
export const CONGREGATION_WIDE_ROLES = ['ADMIN_PER_CONGREGATION', 'COORDINATOR_ELDER', 'SERVICE_OVERSEER', 'SECRETARY'];

/**
 * Who is calling, resolved from the people/{personId} document (the same denormalized fields the Firestore rules use:
 * isSuperAdmin, activeCongregationId, activeAdminRole).
 */
export async function loadActor(personId, get) {
  const doc = await get('people', personId);
  const p = doc && !doc.deleted ? doc.data : null;
  return {
    personId,
    known: !!p,
    isSuperAdmin: p?.isSuperAdmin === true,
    congregationId: p?.activeCongregationId ?? null,
    adminRole: p?.activeAdminRole ?? null,
  };
}

export const isWide = (actor, congregationId) =>
  actor.isSuperAdmin || (actor.congregationId != null && actor.congregationId === congregationId && CONGREGATION_WIDE_ROLES.includes(actor.adminRole));

/** True when [actor] holds the Overseer / Servant / Assistant slot of [groupId] (group record is the source of truth). */
export async function isSlotHolder(actor, groupId, congregationId, get) {
  if (!groupId) return false;
  const g = await get('groups', groupId);
  if (!g || g.deleted) return false;
  const d = g.data;
  return [d.overseerPersonId, d.servantPersonId, d.assistantPersonId].includes(actor.personId) &&
    (congregationId === undefined || d.congregationId === congregationId);
}

const DRAWING_COLLECTIONS = new Set(['territoryDrawings', 'territoryDrawingAudits', 'territoryBounds']);

/**
 * READ: may [actor] receive this stored row in a pull? The three drawing collections keep their own (stricter, tested)
 * read rules; every other collection follows firestore.rules via rules.js. `grant` comes from loadGrant().
 */
export async function canReadRow(actor, grant, row, get) {
  if (!actor.known) return false;
  if (DRAWING_COLLECTIONS.has(row.collection)) return canReadDrawing(actor, row.collection, row.data);
  return canReadDoc(actor, grant, row.collection, row.id, row.data ?? {}, get);
}

function canReadDrawing(actor, collection, data) {
  if (actor.isSuperAdmin) return true;
  if (collection === 'territoryDrawingAudits') return isWide(actor, data?.congregationId);
  const owner = congregationOf(collection, data);
  return owner != null && owner === actor.congregationId;
}

/**
 * WRITE: returns { ok: true } or { ok: false, reason }. `existing` is the stored row (tombstones included) or null.
 * Drawings use drawings.js; every other collection is the port of firestore.rules in rules.js.
 */
export async function authorizeWrite(actor, op, collection, id, data, existing, get) {
  if (!actor.known) return deny('Unknown user');
  if (collection === 'territoryDrawings') return authorizeDrawingWrite(actor, op, id, data, existing, get);
  if (collection === 'territoryDrawingAudits') return authorizeAuditWrite(actor, op, data, existing, get);
  if (collection === 'territoryBounds') return authorizeBoundsWrite(actor, op, id, data, existing, get);
  if (op === 'delete' && (!existing || existing.deleted)) return ok(); // deleting something already gone is harmless
  const grant = await loadGrant(actor.personId, get);
  return authorizeRulesWrite(actor, grant, op, collection, id, data, existing, get);
}

export const ok = () => ({ ok: true });
export const deny = (reason) => ({ ok: false, reason });

export { loadGrant };
