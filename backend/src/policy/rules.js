/**
 * Server-side port of firestore.rules for every collection except the three drawing collections (see drawings.js).
 *
 * This is a FAITHFUL port: each `allow` below mirrors one `allow` in firestore.rules, including the places where that file
 * is deliberately coarse (plain `isSignedIn()`), so moving to this server does not change who can do what today. The
 * known weak spots the rules file itself documents are NOT tightened here; they are listed in backend/README.md as a
 * separate hardening step, to be done with a data audit rather than as a side effect of the migration.
 *
 * Conventions:
 *   - `actor`    who is calling (people/{personId}); `actor.known` = that document exists (Firestore `exists(people/me)`).
 *   - `old`      the stored document's data, or null when it does not exist (Firestore `resource.data`).
 *   - `next`     the document being written (Firestore `request.resource.data`).
 *   - Firestore `x.get('k', d)` becomes `x.k ?? d`.
 *   - Subcollections arrive as the collection path "parentCollection/parentId/sub" (e.g. interestedPeople/abc/visits).
 */
import { ok, deny } from './index.js';

const ROLES_CHAT = ['ADMIN_PER_CONGREGATION', 'COORDINATOR_ELDER'];
const ROLES_REPORTS = ['ADMIN_PER_CONGREGATION', 'COORDINATOR_ELDER', 'REGULAR_ELDER', 'SERVICE_OVERSEER', 'SECRETARY'];
const ROLES_WIDE = ['ADMIN_PER_CONGREGATION', 'COORDINATOR_ELDER', 'SERVICE_OVERSEER', 'SECRETARY']; // same set as CONGREGATION_WIDE_ROLES in index.js
const ROLES_APP_SETTINGS = ROLES_REPORTS;

const nul = (v) => v ?? null;
const same = (a, b) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
const live = (doc) => (doc && !doc.deleted ? doc.data : null);

/** Keys whose value differs between two documents (Firestore `request.resource.data.diff(resource.data).affectedKeys()`). */
function affectedKeys(a, b) {
  const keys = new Set([...Object.keys(a ?? {}), ...Object.keys(b ?? {})]);
  return [...keys].filter((k) => !same(a?.[k], b?.[k]));
}

/** Everything a rule needs to know about the caller beyond the actor fields: their optional access grant. */
export async function loadGrant(personId, get) {
  const g = live(await get('userAccessGrants', personId));
  if (!g) return { has: false, permissions: [], scopeType: null, scopeCongregationIds: [] };
  return {
    has: true,
    permissions: Array.isArray(g.permissions) ? g.permissions : [],
    scopeType: g.scopeType ?? null,
    scopeCongregationIds: Array.isArray(g.scopeCongregationIds) ? g.scopeCongregationIds : [],
  };
}

/** Builds the helper functions firestore.rules defines at the top of the file, bound to one caller. */
function helpers(actor, grant, get) {
  const me = actor.personId;
  const sa = actor.isSuperAdmin;
  const inCong = (cong) => nul(actor.congregationId) === nul(cong);

  const grantScopeIncludes = (cong) =>
    grant.scopeType === 'ALL_CONGREGATIONS' || (grant.scopeType === 'SELECTED_CONGREGATIONS' && grant.scopeCongregationIds.includes(cong));
  const restrictedAllows = (permission, cong) => !grant.has || (grant.permissions.includes(permission) && grantScopeIncludes(cong));
  const restrictedAllowsPermission = (permission) => !grant.has || grant.permissions.includes(permission);

  const roleIn = (roles, cong) => actor.known && (sa || (inCong(cong) && roles.includes(actor.adminRole)));

  return {
    me, sa, grant, actor, get,
    restrictedAllows,
    restrictedAllowsPermission,
    /** Congregation filter for reads: own congregation, a record with none, or one the caller's grant scope covers. */
    readableCongregation: (cong) => cong == null || inCong(cong) || (grant.has && grantScopeIncludes(cong)),
    canManageGroupChatsFor: (cong) => roleIn(ROLES_CHAT, cong),
    canManagePublisherReportsFor: (cong) => roleIn(ROLES_REPORTS, cong),
    canManageRoleAssignmentsFor: (cong) => roleIn(ROLES_WIDE, cong),
    canManageTerritoryAssignmentsFor: (cong) => roleIn(ROLES_WIDE, cong),
    canManageTrashFor: (cong) => actor.known && (sa || (cong != null && inCong(cong) && ROLES_WIDE.includes(actor.adminRole))),
    canManageCreditHourCategories: () => actor.known && (sa || actor.adminRole === 'ADMIN_PER_CONGREGATION'),
    inMyActiveCongregation: (cong) => sa || (actor.known && inCong(cong)),
    hasManageUsers: () => grant.permissions.includes('MANAGE_USERS'),
    async isSlotHolderOfGroup(groupId) {
      if (groupId == null) return false;
      const g = live(await get('groups', groupId));
      return !!g && [g.overseerPersonId ?? '', g.servantPersonId ?? '', g.assistantPersonId ?? ''].includes(me);
    },
    async canEditPersonProfile(targetId) {
      if (!actor.known) return false;
      if (sa) return true;
      if (!ROLES_WIDE.includes(actor.adminRole) || actor.congregationId == null) return false;
      const t = live(await get('people', targetId));
      return !!t && [null, actor.congregationId].includes(nul(t.activeCongregationId));
    },
    async canManageCredentialsFor(targetId) {
      if (!actor.known) return false;
      if (sa) return true;
      const t = live(await get('people', targetId));
      if (!t || actor.congregationId == null || actor.congregationId !== nul(t.activeCongregationId)) return false;
      const targetRole = nul(t.activeAdminRole);
      return (actor.adminRole === 'ADMIN_PER_CONGREGATION' && [null, 'REGULAR_ELDER', 'MINISTERIAL_SERVANT'].includes(targetRole)) ||
        (['COORDINATOR_ELDER', 'SERVICE_OVERSEER', 'SECRETARY'].includes(actor.adminRole) && targetRole === null);
    },
    /** Same congregation by id, or by the congregation's display name (guards against duplicated congregation documents). */
    async inMyActiveCongregationByNameOrId(cong) {
      if (sa || (actor.known && inCong(cong))) return true;
      if (!actor.known || cong == null) return false;
      const nameOf = async (id) => (id == null ? null : nul(live(await get('congregations', id))?.name));
      const target = await nameOf(cong);
      return target != null && (await nameOf(actor.congregationId)) === target;
    },
  };
}

const isPrivilegedRoleType = (roleType) => roleType === 'ADMIN:SUPER_ADMIN' || roleType === 'ADMIN:CIRCUIT_OVERSEER';

/** Owner-only collections: the document's own publisherPersonId must be the caller. */
const OWNER_ONLY = new Set([
  'bibleTextCategories', 'bibleTextRecords', 'plannerDays', 'monthlyPlannerGoals', 'weeklyPlannerGoals', 'yearlyPlannerGoals',
  'creditHourRecords', 'ministryTimerSessions',
]);

/** Collections firestore.rules gives plain `allow read, write: if isSignedIn()` (explicitly or via the catch-all). */
const SIGNED_IN_ONLY = new Set([
  'forwardRequests', 'publisherForwardRequests', 'houseHolderAssignments', 'territories', 'schedules', 'elderTitles',
  'auditLog', 'sharedLocations', 'savedLocations', 'cartAssignments', 'locationSharingSettings', 'publisherVisibilitySettings',
  'midweekMeetingSchedules', 'publicTalkSchedules', 'announcements',
]);

const chatParticipant = (h, chat) => (chat?.participantIds ?? []).includes(h.me);
const chatAllowsRead = (h, chat) => h.sa || chatParticipant(h, chat) || h.canManageGroupChatsFor(nul(chat?.congregationId));

/**
 * Sensitive collections whose records carry a congregationId. firestore.rules lets any signed-in user read many of these
 * (the rules language cannot filter a query, so it could not scope them); this server filters every pull row by row, so it
 * additionally keeps a congregation's records inside that congregation. To loosen one, remove it from this set.
 * Deliberately NOT here: people (an admin must see never-signed-in members, whose congregation is still empty),
 * forwardRequests/publisherForwardRequests/houseHolderAssignments (cross-congregation by design), and global lookups.
 */
const CONGREGATION_SCOPED_READS = new Set([
  'interestedPeople', 'monthlyReports', 'roleAssignments', 'groups', 'mapPins', 'territoryAssignments',
  'publisherTerritoryAssignments', 'territoryAssignmentBarangays',
]);

/** Splits "interestedPeople/abc/visits" into its parts; null for a top-level collection. */
function subPath(collection) {
  const parts = collection.split('/');
  return parts.length === 3 ? { parent: parts[0], parentId: parts[1], name: parts[2] } : null;
}

// ------------------------------------------------------------------------------------------------------------------
// READ

/** May the caller receive this document in a pull? `data` is the stored document, `id` its id. */
export async function canReadDoc(actor, grant, collection, id, data, get) {
  if (!actor.known) return false;
  const h = helpers(actor, grant, get);
  if (h.sa) return true;
  if (!(await baseRead(h, actor, grant, collection, id, data, get))) return false;
  const sub = subPath(collection);
  if (sub?.parent === 'interestedPeople' && sub.name === 'visits') {
    const parent = live(await get('interestedPeople', sub.parentId));
    return !parent || h.readableCongregation(nul(parent.congregationId));
  }
  if (!sub && CONGREGATION_SCOPED_READS.has(collection)) return h.readableCongregation(nul(data.congregationId));
  return true;
}

async function baseRead(h, actor, grant, collection, id, data, get) { // match /{document=**} { allow read, write: if isSuperAdmin }
  const sub = subPath(collection);
  if (sub) {
    if (sub.parent === 'groupChats' && sub.name === 'messages') {
      const chat = live(await get('groupChats', sub.parentId));
      return chatAllowsRead(h, chat);
    }
    return true; // visits and every other subcollection: signed-in
  }
  switch (collection) {
    case 'people': return true;
    case 'userAccessGrants': return id === h.me || h.hasManageUsers();
    case 'congregations': return h.restrictedAllows('VIEW_CONGREGATIONS', id);
    case 'groups': return h.restrictedAllows('VIEW_GROUPS', nul(data.congregationId));
    case 'deletedRecords': return nul(data.deletedByPersonId) === h.me || h.canManageTrashFor(nul(data.congregationId));
    case 'roleAssignments':
      return !grant.has || h.restrictedAllows('VIEW_ELDERS', nul(data.congregationId)) || h.restrictedAllows('VIEW_PUBLISHERS', nul(data.congregationId));
    case 'monthlyReports':
      return ['VIEW_PUBLISHER_REPORTS', 'VIEW_GROUP_REPORTS', 'VIEW_CONGREGATION_REPORTS'].some((p) => h.restrictedAllows(p, nul(data.congregationId)));
    case 'groupChats': return chatAllowsRead(h, data);
    case 'presence': return h.inMyActiveCongregation(nul(data.congregationId));
    case 'dashboardModuleLayouts': return id === h.me;
    case 'passwordResetRequests': return true;
    case 'territoryAssignments': case 'publisherTerritoryAssignments': case 'territoryAssignmentBarangays': case 'mapPins':
    case 'preachingTimeRecords': case 'interestedPeople': case 'creditHourCategories': case 'appSettings':
      return true;
    default:
      if (OWNER_ONLY.has(collection)) return data.publisherPersonId === h.me;
      return true; // SIGNED_IN_ONLY collections and the catch-all
  }
}

// ------------------------------------------------------------------------------------------------------------------
// WRITE

/**
 * `op` is 'set' or 'delete'; `existing` the stored row (tombstones included); `next` the new document for a set.
 * Returns { ok: true } or { ok: false, reason }.
 */
export async function authorizeRulesWrite(actor, grant, op, collection, id, next, existing, get) {
  const h = helpers(actor, grant, get);
  if (h.sa) return ok();
  const old = live(existing);
  const isCreate = op === 'set' && old == null;
  const isUpdate = op === 'set' && old != null;
  const isDelete = op === 'delete';
  const verdict = (allowed, reason = 'Not allowed') => (allowed ? ok() : deny(reason));

  const sub = subPath(collection);
  if (sub) return writeSubcollection(h, sub, { op, id, next, old, isCreate, isUpdate, isDelete, verdict });

  switch (collection) {
    case 'passwordResetRequests':
      return ok(); // create: anyone; update/delete: signed-in

    case 'people': {
      if (isCreate) return verdict((next.isSuperAdmin ?? false) === false, 'Cannot grant Super Admin');
      if (isDelete) return ok();
      if ((next.isSuperAdmin ?? false) !== (old.isSuperAdmin ?? false)) return deny('Cannot change Super Admin');
      if (id === h.me) return ok();
      if (await h.canEditPersonProfile(id)) return ok();
      const onlyAccount = affectedKeys(next, old).every((k) => ['username', 'accountStatus'].includes(k));
      return verdict(onlyAccount && (await h.canManageCredentialsFor(id)), 'Not allowed to edit this person');
    }

    case 'userAccessGrants':
      return verdict(h.hasManageUsers(), 'Only the Super Admin or a MANAGE_USERS grant');

    case 'congregations':
      if (isCreate) return verdict(h.restrictedAllowsPermission('ADD_CONGREGATIONS'));
      if (isUpdate) return verdict(h.restrictedAllows('EDIT_CONGREGATIONS', id));
      return verdict(h.restrictedAllows('DELETE_CONGREGATIONS', id));

    case 'groups': return writeGroups(h, { next, old, isCreate, isUpdate, isDelete, verdict });

    case 'territoryAssignments': case 'publisherTerritoryAssignments': case 'territoryAssignmentBarangays': {
      if (isCreate) return verdict(h.canManageTerritoryAssignmentsFor(nul(next.congregationId)));
      if (isDelete) return verdict(h.canManageTerritoryAssignmentsFor(nul(old?.congregationId)));
      const fixed = nul(next.congregationId) === nul(old.congregationId) &&
        (collection !== 'territoryAssignmentBarangays' || nul(next.barangayId) === nul(old.barangayId));
      return verdict(h.canManageTerritoryAssignmentsFor(nul(old.congregationId)) && fixed, 'Congregation and barangay cannot change');
    }

    case 'deletedRecords':
      if (isCreate) return verdict(nul(next.deletedByPersonId) === h.me, 'Deleted-by must be you');
      if (isUpdate) return deny('Deleted records are never edited');
      return verdict(nul(old?.deletedByPersonId) === h.me || h.canManageTrashFor(nul(old?.congregationId)));

    case 'mapPins':
      if (isCreate) return verdict(nul(next.createdByPersonId) === h.me && h.inMyActiveCongregation(nul(next.congregationId)), 'Pins are created as yourself in your congregation');
      return verdict(nul(old?.createdByPersonId) === h.me || h.canManageTerritoryAssignmentsFor(nul(old?.congregationId)));

    case 'roleAssignments': return writeRoleAssignments(h, { next, old, isCreate, isUpdate, isDelete, verdict });

    case 'monthlyReports': return writeMonthlyReports(h, { op, next, old, verdict });

    case 'preachingTimeRecords':
      return isDelete ? deny('Only the Super Admin deletes preaching time records') : ok();

    case 'groupChats':
      if (isCreate) return verdict(h.canManageGroupChatsFor(next.congregationId));
      if (isUpdate) {
        const participantEdit = chatParticipant(h, old) && next.congregationId === old.congregationId && same(next.participantIds, old.participantIds);
        return verdict(h.canManageGroupChatsFor(old.congregationId) || participantEdit);
      }
      return verdict(h.canManageGroupChatsFor(old?.congregationId));

    case 'interestedPeople':
      if (isCreate) return verdict(await h.inMyActiveCongregationByNameOrId(nul(next.congregationId)), 'Other congregation');
      if (isUpdate) {
        return verdict((await h.inMyActiveCongregationByNameOrId(nul(old.congregationId))) || h.canManagePublisherReportsFor(nul(next.congregationId)), 'Other congregation');
      }
      return verdict(await h.inMyActiveCongregationByNameOrId(nul(old?.congregationId)), 'Other congregation');

    case 'presence':
      return verdict(id === h.me && (isDelete || nul(next.congregationId) === nul(actor.congregationId)), 'Presence is your own');

    case 'dashboardModuleLayouts':
      return verdict(id === h.me, 'Your own layout only');

    case 'creditHourCategories':
      if (isCreate) return verdict(h.canManageCreditHourCategories() || /^default_[a-z_]+$/.test(id), 'Only admins manage categories');
      return verdict(h.canManageCreditHourCategories(), 'Only admins manage categories');

    case 'appSettings':
      return verdict(actor.known && ROLES_APP_SETTINGS.includes(actor.adminRole), 'Only Super Admin, Admins and Elders change settings');

    default:
      if (OWNER_ONLY.has(collection)) {
        if (isCreate) return verdict(next.publisherPersonId === h.me, 'Records are created as yourself');
        return verdict(old?.publisherPersonId === h.me, 'Not your record');
      }
      // SIGNED_IN_ONLY collections and the catch-all: any known signed-in user.
      return ok();
  }
}

function writeGroups(h, { next, old, isCreate, isUpdate, isDelete, verdict }) {
  const g = h.grant;
  if (isCreate) {
    return verdict((!g.has && h.canManageRoleAssignmentsFor(nul(next.congregationId))) || (g.has && h.restrictedAllows('MANAGE_GROUPS', nul(next.congregationId))));
  }
  if (isDelete) {
    return verdict((!g.has && h.canManageRoleAssignmentsFor(nul(old?.congregationId))) || (g.has && h.restrictedAllows('MANAGE_GROUPS', nul(old?.congregationId))));
  }
  const noGrantUpdate = !g.has && nul(next.congregationId) === nul(old.congregationId) && (
    h.canManageRoleAssignmentsFor(nul(old.congregationId)) ||
    ([old.overseerPersonId ?? '', old.servantPersonId ?? '', old.assistantPersonId ?? ''].includes(h.me) && (next.status ?? 'ACTIVE') === (old.status ?? 'ACTIVE'))
  );
  return verdict(noGrantUpdate || (g.has && h.restrictedAllows('MANAGE_GROUPS', nul(old.congregationId))));
}

async function writeRoleAssignments(h, { next, old, isCreate, isUpdate, isDelete, verdict }) {
  const g = h.grant;
  const sa = h.sa;
  const restrictedManage = (cong) => g.has && (h.restrictedAllows('MANAGE_ELDERS', cong) || h.restrictedAllows('MANAGE_PUBLISHERS', cong));
  if (isCreate) {
    const cong = nul(next.congregationId);
    return verdict(
      (!g.has && h.canManageRoleAssignmentsFor(cong) && (sa || !isPrivilegedRoleType(next.roleType ?? ''))) || restrictedManage(cong),
      'Not allowed to assign that role',
    );
  }
  if (isDelete) {
    const cong = nul(old?.congregationId);
    return verdict(
      (!g.has && h.canManageRoleAssignmentsFor(cong) && (sa || !isPrivilegedRoleType(old?.roleType ?? ''))) || restrictedManage(cong),
      'Not allowed to remove that role',
    );
  }
  const cong = nul(old.congregationId);
  const manage = (!g.has && h.canManageRoleAssignmentsFor(cong) &&
    (sa || (!isPrivilegedRoleType(old.roleType ?? '') && !isPrivilegedRoleType(next.roleType ?? '')))) || restrictedManage(cong);
  if (manage) return ok();
  // A Group Overseer/Servant/Assistant moving members into or out of a group whose slot they hold: group link fields only.
  const groupFieldsOnly = affectedKeys(next, old).every((k) => ['groupId', 'regularElderRole', 'lastEditedByPersonId', 'lastEditedAt'].includes(k));
  const slot = (await h.isSlotHolderOfGroup(nul(old.groupId))) || (await h.isSlotHolderOfGroup(nul(next.groupId)));
  return verdict(!g.has && groupFieldsOnly && slot, 'Not allowed to edit this role assignment');
}

function writeMonthlyReports(h, { op, next, old, verdict }) {
  if (h.grant.has) return deny('Restricted accounts have view-only access to reports');
  const mine = old?.publisherPersonId === h.me;
  if (old && old.status === 'POSTED' && mine) return deny('A posted report can no longer be changed');
  if (old && op === 'set' && ['SUBMITTED', 'CORRECTED'].includes(old.status) && mine &&
      !h.canManagePublisherReportsFor(old.congregationId) && !same(next, old)) {
    return deny('A submitted report is locked until it is unlocked or returned');
  }
  if (op === 'delete') return verdict(h.sa, 'Only the Super Admin deletes reports');
  const reported = nul(next.hoursRendered);
  const system = nul(next.systemCalculatedHours);
  const hoursOk = reported == null || system == null || reported === system || (next.hoursConfirmed === true && (next.hoursAdjustmentRemarks ?? '') !== '');
  return verdict((next.publisherPersonId === h.me && hoursOk) || h.canManagePublisherReportsFor(next.congregationId), 'Not allowed to write this report');
}

async function writeSubcollection(h, sub, { op, id, next, old, isCreate, isUpdate, isDelete, verdict }) {
  const { get } = h;

  if (sub.parent === 'groupChats' && sub.name === 'messages') {
    const chat = live(await get('groupChats', sub.parentId));
    const allowsRead = !!chat && chatAllowsRead(h, chat);
    if (isDelete) return deny('Messages are never hard-deleted');
    if (isCreate) return verdict(next.senderId === h.me && allowsRead, 'Not your message or not your chat');
    // update
    if (!allowsRead || next.senderId !== old.senderId || !same(next.createdAt, old.createdAt)) return deny('Not allowed');
    const senderEdit = h.me === old.senderId;
    const deleteForMe = same(next.text, old.text) && nul(next.attachmentUrl) === nul(old.attachmentUrl) &&
      (next.isDeletedForEveryone ?? false) === (old.isDeletedForEveryone ?? false);
    return verdict(senderEdit || deleteForMe, 'Only the sender edits a message');
  }

  if (sub.parent === 'interestedPeople' && sub.name === 'visits') {
    const parent = live(await get('interestedPeople', sub.parentId));
    if (!parent) return deny('Unknown record');
    const cong = nul(parent.congregationId);
    const stage = nul(parent.pipelineStage);
    const owner = nul(parent.publisherPersonId);
    if (!(await h.inMyActiveCongregationByNameOrId(cong))) return deny('Other congregation');
    if (isCreate) {
      return verdict(nul(next.createdByPersonId) === h.me && (stage !== 'BIBLE_STUDY' || h.sa || owner === h.me), 'Not allowed to add a visit here');
    }
    if (isUpdate) {
      const allowed = h.sa ||
        (stage === 'BIBLE_STUDY' && owner === h.me) ||
        (stage !== 'BIBLE_STUDY' && nul(old.createdByPersonId) === h.me);
      const fixed = nul(next.createdByPersonId) === nul(old.createdByPersonId) && nul(next.interestedPersonId) === nul(old.interestedPersonId);
      return verdict(allowed && fixed, 'Not allowed to edit this visit');
    }
    return verdict(h.sa || owner === h.me || (stage !== 'BIBLE_STUDY' && nul(old?.createdByPersonId) === h.me), 'Not allowed to delete this visit');
  }

  // Any other subcollection falls under the catch-all: signed-in.
  return ok();
}
