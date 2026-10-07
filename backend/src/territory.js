import { randomBytes } from 'node:crypto';
import { isWide } from './policy/index.js';

/**
 * Territory Assignment: assigns every barangay of a municipality to exactly one FS Group per congregation.
 *
 * Server port of TerritoryAssignmentRepository's three Firestore transactions (save, remove a group's province, remove one
 * assignment). Each barangay claim has the deterministic id `${congregationId}_${barangayId}`, and every write
 * transaction on this server locks the global sequence counter first (see MysqlStore.transaction), so two admins
 * claiming the same barangay at once are serialized: the second one finds the claim and gets a `conflict`, never a
 * silent overwrite.
 *
 * Who may call: Super Admin, or Admin / Coordinator Elder / Service Overseer / Secretary of that congregation (the same set
 * firestore.rules `canManageTerritoryAssignmentsFor` allows).
 *
 * Every function takes the open transaction `tx` and returns a plain result object; the HTTP layer maps `status` to a code.
 */
export const MAX_BARANGAYS_PER_SAVE = 400;

const ASSIGNMENTS = 'territoryAssignments';
const CLAIMS = 'territoryAssignmentBarangays';

const newId = () => randomBytes(15).toString('base64url'); // 20 characters, like a Firestore auto id
const claimId = (congregationId, barangayId) => `${congregationId}_${barangayId}`;
const isStr = (v) => typeof v === 'string' && v.length > 0 && v.length <= 190 && !v.includes('/');
const isInt = (v) => Number.isInteger(v);
const text = (v) => typeof v === 'string' && v.length <= 200;

const denied = { status: 'denied', message: 'Not allowed to manage this congregation\'s territory.' };
const error = (message) => ({ status: 'error', message });

/** Returns an error message for a malformed save request, or null. */
export function validateSave(b) {
  if (!b || typeof b !== 'object') return 'Bad request';
  if (!isStr(b.congregationId) || !isStr(b.groupId) || !text(b.groupName) || !isInt(b.provinceId) || !text(b.provinceName)) return 'Bad request';
  if (!Array.isArray(b.municipalities) || b.municipalities.length > MAX_BARANGAYS_PER_SAVE) return 'Bad request';
  for (const m of b.municipalities) {
    if (!isInt(m?.muncityId) || !text(m?.muncityName) || !Array.isArray(m?.barangays)) return 'Bad request';
    for (const x of m.barangays) if (!isInt(x?.id) || !text(x?.name)) return 'Bad request';
  }
  return null;
}

/** Save the full desired barangay set for every municipality a group holds inside one province (a declarative end state). */
export async function saveGroupTerritory(tx, actor, body, now = Date.now()) {
  const { congregationId, groupId, groupName, provinceId, provinceName } = body;
  if (!isWide(actor, congregationId)) return denied;
  const selections = body.municipalities.filter((m) => m.barangays.length > 0);
  if (selections.length === 0) return error('Select at least one barangay.');
  const total = selections.reduce((n, m) => n + m.barangays.length, 0);
  if (total > MAX_BARANGAYS_PER_SAVE) {
    return error(`You can assign at most ${MAX_BARANGAYS_PER_SAVE} barangays in one save. Split this into two assignments.`);
  }

  const existingAssignments = (await tx.list(ASSIGNMENTS, congregationId))
    .filter((a) => a.data.groupId === groupId && a.data.provinceId === provinceId);
  const existingAssignmentIds = new Set(existingAssignments.map((a) => a.id));
  const existingClaims = (await tx.list(CLAIMS, congregationId)).filter((c) => existingAssignmentIds.has(c.data.assignmentId));
  const existingByMuncity = new Map(existingAssignments.map((a) => [a.data.muncityId, a]));
  const selectedMuncityIds = new Set(selections.map((m) => m.muncityId));

  const plans = selections.map((selection) => {
    const existing = existingByMuncity.get(selection.muncityId) ?? null;
    const existingBarangayIds = new Set(existingClaims.filter((c) => c.data.muncityId === selection.muncityId).map((c) => c.data.barangayId));
    const wanted = new Set(selection.barangays.map((b) => b.id));
    return {
      selection,
      existing,
      assignmentId: existing?.id ?? newId(),
      toAdd: selection.barangays.filter((b) => !existingBarangayIds.has(b.id)),
      toRemoveIds: [...existingBarangayIds].filter((id) => !wanted.has(id)),
      unchanged: selection.barangays.filter((b) => existingBarangayIds.has(b.id)),
    };
  });
  const droppedAssignments = existingAssignments.filter((a) => !selectedMuncityIds.has(a.data.muncityId));
  const droppedIds = new Set(droppedAssignments.map((a) => a.id));
  const droppedClaims = existingClaims.filter((c) => droppedIds.has(c.data.assignmentId));

  // All checks before any write: every newly claimed barangay must be free in this congregation.
  for (const plan of plans) {
    for (const b of plan.toAdd) {
      const claim = await tx.get(CLAIMS, claimId(congregationId, b.id));
      if (claim && !claim.deleted) {
        return { status: 'conflict', barangayName: b.name, takenByGroupName: claim.data.groupName || 'another group' };
      }
    }
  }

  const actorId = actor.personId;
  const assignmentIds = [];
  for (const plan of plans) {
    const { selection } = plan;
    assignmentIds.push(plan.assignmentId);
    if (plan.existing) {
      await tx.put(ASSIGNMENTS, plan.assignmentId, {
        ...plan.existing.data, groupId, provinceName, muncityName: selection.muncityName, updatedAt: now, updatedByPersonId: actorId,
      }, actorId);
    } else {
      await tx.put(ASSIGNMENTS, plan.assignmentId, {
        congregationId, groupId, provinceId, provinceName, muncityId: selection.muncityId, muncityName: selection.muncityName,
        createdAt: now, createdByPersonId: actorId, updatedAt: now, updatedByPersonId: actorId,
      }, actorId);
    }
    for (const barangayId of plan.toRemoveIds) await tx.remove(CLAIMS, claimId(congregationId, barangayId), actorId);
    for (const b of plan.toAdd) {
      await tx.put(CLAIMS, claimId(congregationId, b.id), {
        congregationId, assignmentId: plan.assignmentId, groupId, groupName, provinceId,
        muncityId: selection.muncityId, muncityName: selection.muncityName, barangayId: b.id, barangayName: b.name,
        createdAt: now, createdByPersonId: actorId,
      }, actorId);
    }
    // Keeps the denormalized group / municipality names right when only the group itself changed.
    for (const b of plan.unchanged) {
      const claim = existingClaims.find((c) => c.id === claimId(congregationId, b.id));
      await tx.put(CLAIMS, claimId(congregationId, b.id), { ...claim.data, groupId, groupName, muncityName: selection.muncityName }, actorId);
    }
  }
  for (const claim of droppedClaims) await tx.remove(CLAIMS, claim.id, actorId);
  for (const a of droppedAssignments) await tx.remove(ASSIGNMENTS, a.id, actorId);
  return { status: 'success', groupId, assignmentIds };
}

/** Removes every municipality a group holds in one province (the card-level "remove entire assignment"). */
export async function removeGroupTerritory(tx, actor, { congregationId, groupId, provinceId }) {
  if (!isStr(congregationId) || !isStr(groupId) || !isInt(provinceId)) return error('Bad request');
  if (!isWide(actor, congregationId)) return denied;
  const assignments = (await tx.list(ASSIGNMENTS, congregationId)).filter((a) => a.data.groupId === groupId && a.data.provinceId === provinceId);
  const ids = new Set(assignments.map((a) => a.id));
  const claims = (await tx.list(CLAIMS, congregationId)).filter((c) => ids.has(c.data.assignmentId));
  for (const c of claims) await tx.remove(CLAIMS, c.id, actor.personId);
  for (const a of assignments) await tx.remove(ASSIGNMENTS, a.id, actor.personId);
  return { status: 'success', groupId, removedAssignments: assignments.length, removedBarangays: claims.length };
}

/** Hard-deletes one assignment and every barangay claim that belongs to it. */
export async function removeAssignment(tx, actor, { assignmentId }) {
  if (!isStr(assignmentId)) return error('Bad request');
  const assignment = await tx.get(ASSIGNMENTS, assignmentId);
  if (!assignment || assignment.deleted) return { status: 'success', assignmentId, removedBarangays: 0 }; // already gone
  // Authorized against the stored congregation, never one the caller sends.
  if (!isWide(actor, assignment.data.congregationId)) return denied;
  const claims = (await tx.list(CLAIMS, assignment.data.congregationId)).filter((c) => c.data.assignmentId === assignmentId);
  for (const c of claims) await tx.remove(CLAIMS, c.id, actor.personId);
  await tx.remove(ASSIGNMENTS, assignmentId, actor.personId);
  return { status: 'success', assignmentId, removedBarangays: claims.length };
}
