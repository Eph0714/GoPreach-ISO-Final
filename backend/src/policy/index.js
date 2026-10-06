import { congregationOf } from '../store.js';
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

/**
 * READ: what a signed-in user may receive in a pull. Super Admin sees everything; everyone else only records of their own
 * active congregation (or records that belong to no congregation, like shared lookup tables). This is stricter than the
 * Firestore rules, which let any signed-in user read many collections — see CROSS_CONGREGATION below for the exceptions.
 */
const CROSS_CONGREGATION = new Set(['forwardRequests']); // sent between congregations: visible to both ends
// Reference data with no congregation that every signed-in user needs.
const GLOBAL_READ = new Set(['elderTitles', 'appSettings', 'creditHourCategories', 'bibleTextCategories']);

export function canRead(actor, collection, data) {
  if (!actor.known) return false;
  if (actor.isSuperAdmin) return true;
  if (collection === 'territoryDrawingAudits') return isWide(actor, data?.congregationId);
  if (CROSS_CONGREGATION.has(collection)) {
    return [data?.congregationId, data?.fromCongregationId, data?.toCongregationId].includes(actor.congregationId);
  }
  const owner = congregationOf(collection, data);
  // A record with no congregation is readable only if it is shared reference data or is the caller's own — never by default.
  if (owner == null) return GLOBAL_READ.has(collection) || data?.publisherPersonId === actor.personId || data?.personId === actor.personId;
  return owner === actor.congregationId;
}

/**
 * WRITE: returns { ok: true } or { ok: false, reason }. `existing` is the stored document (or null).
 *
 * Fully ported from firestore.rules: territoryDrawings, territoryDrawingAudits, territoryBounds.
 * Every other collection currently gets the SAFE DEFAULT below (own congregation only, no cross-congregation writes,
 * no self-promotion of people/roleAssignments). The remaining per-collection rules still need to be ported one by one
 * before Firebase is switched off — see backend/README.md "Policy porting checklist".
 */
export async function authorizeWrite(actor, op, collection, id, data, existing, get) {
  if (!actor.known) return deny('Unknown user');
  if (collection === 'territoryDrawings') return authorizeDrawingWrite(actor, op, id, data, existing, get);
  if (collection === 'territoryDrawingAudits') return authorizeAuditWrite(actor, op, data, existing, get);
  if (collection === 'territoryBounds') return authorizeBoundsWrite(actor, op, id, data, existing, get);
  return authorizeDefault(actor, op, collection, data, existing);
}

export const ok = () => ({ ok: true });
export const deny = (reason) => ({ ok: false, reason });

function authorizeDefault(actor, op, collection, data, existing) {
  if (actor.isSuperAdmin) return ok();
  const target = op === 'delete' ? existing?.data : data;
  const owner = congregationOf(collection, target ?? existing?.data);
  if (op === 'delete' && !existing) return ok(); // deleting something already gone is harmless
  if (owner != null && owner !== actor.congregationId) return deny('Other congregation');
  if (existing && owner != null && congregationOf(collection, existing.data) !== actor.congregationId) return deny('Other congregation');
  // Never let a non-super-admin mint a Super Admin or move a person into another congregation.
  if (collection === 'people') {
    if (data?.isSuperAdmin === true && existing?.data?.isSuperAdmin !== true) return deny('Cannot grant Super Admin');
    if (existing && data?.id && data.id !== id) return deny('Id mismatch');
  }
  if (collection === 'roleAssignments' && /SUPER_ADMIN|CIRCUIT_OVERSEER/.test(String(data?.roleType ?? '')) && !actor.isSuperAdmin) {
    return deny('Only the Super Admin assigns that role');
  }
  return ok();
}
