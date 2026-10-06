import { isWide, isSlotHolder, ok, deny } from './index.js';

/**
 * Server-side port of the drawing rules in firestore.rules (User Role → Congregation → FS Group → Territory):
 *   - Super Admin / Admin / Coordinator Elder / Service Overseer / Secretary of the drawing's congregation: anywhere on
 *     that congregation's map, including areas no FS Group has claimed (territoryId '').
 *   - Group Overseer / Servant / Assistant: only their own FS Group's territory — and the drawing's bounding box must sit
 *     inside that territory's published box (territoryBounds, written only by the wide roles).
 * The exact polygon-inside-territory test also runs in the app before saving; here the bounding box is the server's check.
 */
const STATUS_COLORS = { FINISHED: '#43A047', TO_CONTINUE: '#FBC02D', TO_DO: '#E53935' };

const num = (v) => typeof v === 'number' && Number.isFinite(v);

function shapeError(d) {
  if (![d.minLat, d.maxLat, d.minLng, d.maxLng].every(num)) return 'Bounding box missing';
  if (d.minLat < -90 || d.maxLat > 90 || d.minLng < -180 || d.maxLng > 180 || d.minLat > d.maxLat || d.minLng > d.maxLng) return 'Bounding box invalid';
  if (typeof d.geometryJson !== 'string' || !d.geometryJson.length || d.geometryJson.length > 60000) return 'Geometry invalid';
  try {
    const g = JSON.parse(d.geometryJson);
    if (g.type !== 'Polygon' || !Array.isArray(g.coordinates?.[0]) || g.coordinates[0].length < 4) return 'Not a closed polygon';
  } catch { return 'Geometry is not JSON'; }
  const color = STATUS_COLORS[d.status];
  if (!color || d.fillColor !== color) return 'Status and its color must match';
  if (!(num(d.fillOpacity) && d.fillOpacity >= 0 && d.fillOpacity <= 1)) return 'Opacity out of range';
  if ((d.remarks ?? '').length > 1000 || typeof (d.remarks ?? '') !== 'string') return 'Remarks too long';
  if ((d.name ?? '').length > 120 || typeof (d.name ?? '') !== 'string') return 'Name too long';
  return null;
}

async function claimOk(d, get) {
  const claim = await get('territoryAssignmentBarangays', d.territoryId);
  return !!claim && !claim.deleted && claim.data.congregationId === d.congregationId && claim.data.groupId === d.groupId;
}

async function insideBounds(d, get) {
  const b = await get('territoryBounds', d.territoryId);
  if (!b || b.deleted) return false;
  const t = b.data;
  return t.congregationId === d.congregationId && t.groupId === d.groupId &&
    t.minLat <= d.minLat && t.maxLat >= d.maxLat && t.minLng <= d.minLng && t.maxLng >= d.maxLng;
}

/** May [actor] draw for this congregation/group at all (wide role, or slot holder of the group)? */
async function canDrawFor(actor, congregationId, groupId, get) {
  return isWide(actor, congregationId) || (await isSlotHolder(actor, groupId, congregationId, get));
}

async function writeOk(actor, d, get) {
  const err = shapeError(d);
  if (err) return deny(err);
  if (d.updatedByUserId !== actor.personId) return deny('Editor stamp must be you');
  const wide = isWide(actor, d.congregationId);
  if (!wide && !(await isSlotHolder(actor, d.groupId, d.congregationId, get))) return deny('Not allowed to draw for this FS Group');
  if (!d.territoryId) {
    if (!wide) return deny('Only congregation-wide roles may draw outside an FS Group territory');
  } else if (!(await claimOk(d, get))) {
    return deny('Territory does not belong to that FS Group');
  }
  if (!wide && !(await insideBounds(d, get))) return deny('Outside your territory bounds');
  return ok();
}

export async function authorizeDrawingWrite(actor, op, id, d, existing, get) {
  if (op === 'delete') {
    if (!existing || existing.deleted) return ok();
    return (await canDrawFor(actor, existing.data.congregationId, existing.data.groupId, get)) ? ok() : deny('Not allowed to delete this drawing');
  }
  const base = await writeOk(actor, d, get);
  if (!base.ok) return base;
  if (!existing || existing.deleted) {
    return d.userId === actor.personId ? ok() : deny('Author must be you');
  }
  const old = existing.data;
  if (!(await canDrawFor(actor, old.congregationId, old.groupId, get))) return deny('Not allowed to edit this drawing');
  if (d.congregationId !== old.congregationId || d.userId !== old.userId || d.createdAt !== old.createdAt) return deny('Author, congregation and creation time cannot change');
  return ok();
}

/** Append-only audit trail: create only, by someone allowed to draw for that group. */
export async function authorizeAuditWrite(actor, op, d, existing, get) {
  if (op !== 'set' || (existing && !existing.deleted)) return deny('Audit rows are append-only');
  if (d.userId !== actor.personId) return deny('Audit author must be you');
  return (await canDrawFor(actor, d.congregationId, d.groupId, get)) ? ok() : deny('Not allowed');
}

/** Territory bounding boxes: written only by the congregation-wide roles, and only for a real claimed territory. */
export async function authorizeBoundsWrite(actor, op, id, d, existing, get) {
  if (op === 'delete') return existing && !isWide(actor, existing.data.congregationId) ? deny('Not allowed') : ok();
  if (!isWide(actor, d.congregationId)) return deny('Only congregation-wide roles publish territory bounds');
  const claim = await get('territoryAssignmentBarangays', id);
  if (!claim || claim.deleted || claim.data.congregationId !== d.congregationId || claim.data.groupId !== d.groupId) return deny('Unknown territory');
  if (![d.minLat, d.maxLat, d.minLng, d.maxLng].every(num) || d.minLat > d.maxLat || d.minLng > d.maxLng) return deny('Bounds invalid');
  return ok();
}
