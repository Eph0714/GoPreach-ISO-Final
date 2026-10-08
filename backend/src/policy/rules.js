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
import { ok, deny, isSlotHolder } from './index.js';

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
  if (!g) return { has: false, permissions: [], scopeType: null, scopeCongregationIds: [], circuitCode: null };
  return {
    has: true,
    permissions: Array.isArray(g.permissions) ? g.permissions : [],
    scopeType: g.scopeType ?? null,
    scopeCongregationIds: Array.isArray(g.scopeCongregationIds) ? g.scopeCongregationIds : [],
    circuitCode: g.circuitCode ?? null,
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
    /** Congregation-wide roles only (no Regular Elder): they write any publisher's report of their own congregation. */
    canManageReportsAsCongregationRole: (cong) => roleIn(ROLES_WIDE, cong),
    canManageRoleAssignmentsFor: (cong) => roleIn(ROLES_WIDE, cong),
    canManageTerritoryAssignmentsFor: (cong) => roleIn(ROLES_WIDE, cong),
    canManageTrashFor: (cong) => actor.known && (sa || (cong != null && inCong(cong) && ROLES_WIDE.includes(actor.adminRole))),
    canManageCreditHourCategories: () => actor.known && (sa || actor.adminRole === 'ADMIN_PER_CONGREGATION'),
    inMyActiveCongregation: (cong) => sa || (actor.known && inCong(cong)),
    hasManageUsers: () => grant.permissions.includes('MANAGE_USERS'),
    /** Admin / Coordinator Elder / Service Overseer / Secretary of that congregation (never a grant account): may send its Field Service Report to the Circuit Overseer. */
    canSubmitFieldServiceFor: (cong) => actor.known && !grant.has && inCong(cong) && ROLES_WIDE.includes(actor.adminRole),
    /** A Circuit Overseer: a grant that carries a Circuit Code, for a congregation inside its scope. */
    isCircuitOverseerFor: (cong) => grant.has && grant.circuitCode != null && grantScopeIncludes(cong),
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
  'interestedPeople', 'monthlyReports', 'roleAssignments', 'coFieldServiceReportEvents', 'coReceivedReports', 'meetingAttendance', 'meetingAttendanceEvents', 'congregationComparativeReports', 'comparativeReportHistory', 'comparativeReportRemarks', 'groups', 'mapPins', 'territoryAssignments',
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
      // A grant-based account (Circuit Overseer) always reads its OWN role assignment: mirrors firestore.rules.
      return !grant.has || h.restrictedAllows('VIEW_ELDERS', nul(data.congregationId)) || h.restrictedAllows('VIEW_PUBLISHERS', nul(data.congregationId)) || nul(data.personId) === h.me;
    case 'monthlyReports':
      // A Circuit Overseer reads the ACTUAL records, but only of a service month the congregation has submitted.
      if (grant.has && grant.circuitCode != null) return h.isCircuitOverseerFor(nul(data.congregationId)) && (await coMonthVisible(get, data.congregationId, data.periodMonth));
      return ['VIEW_PUBLISHER_REPORTS', 'VIEW_GROUP_REPORTS', 'VIEW_CONGREGATION_REPORTS'].some((p) => h.restrictedAllows(p, nul(data.congregationId)));
    case 'coFieldServiceReportEvents':
      return h.isCircuitOverseerFor(nul(data.congregationId)) || h.canSubmitFieldServiceFor(nul(data.congregationId));
    // The frozen copy of a sent month: the Circuit Overseer whose assignment covers that congregation (so a transfer moves it with the assignment),
    // and the congregation's own senders.
    case 'coReceivedReports':
      return h.isCircuitOverseerFor(nul(data.congregationId)) || h.canSubmitFieldServiceFor(nul(data.congregationId));
    // Which received reports a Circuit Overseer has opened: only that overseer sees their own marks.
    case 'coReportReads':
      return nul(data.coPersonId) === h.me;
    // Meeting attendance: its own congregation (any member), or the Circuit Overseer of that congregation.
    case 'meetingAttendance':
      return h.isCircuitOverseerFor(nul(data.congregationId)) || (!grant.has && inCongOf(actor, data.congregationId));
    case 'meetingAttendanceEvents':
      return h.isCircuitOverseerFor(nul(data.congregationId)) || h.canManageReportsAsCongregationRole(nul(data.congregationId));
    case 'meetingAttendanceSettings': case 'congregationMonthlyStatistics': return true;
    // Comparative Reports: the congregation-wide roles of that congregation; the Circuit Overseer only once it was sent (never a draft).
    case 'congregationComparativeReports':
      return h.canManageReportsAsCongregationRole(nul(data.congregationId)) ||
        (h.isCircuitOverseerFor(nul(data.congregationId)) && ['SUBMITTED', 'RETURNED', 'RECEIVED'].includes(data.status));
    case 'comparativeReportHistory': case 'comparativeReportRemarks':
      return h.isCircuitOverseerFor(nul(data.congregationId)) || h.canManageReportsAsCongregationRole(nul(data.congregationId));
    case 'coFieldServiceMonthStatus': return true;
    case 'groupChats': return chatAllowsRead(h, data);
    case 'presence': return h.inMyActiveCongregation(nul(data.congregationId));
    case 'dashboardModuleLayouts': return id === h.me;
    case 'passwordResetRequests': return true;
    // A Circuit Overseer (grant) reads only the congregations on their grant; everyone else is unchanged.
    case 'territoryAssignments': case 'territoryAssignmentBarangays': case 'mapPins':
      return h.restrictedAllows('VIEW_CONGREGATIONS', nul(data.congregationId));
    case 'publisherTerritoryAssignments':
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

    // Circuit Overseer module: Circuit Codes and the congregation -> overseer links are Super-Admin writes only
    // (the Super Admin returned above). Everyone signed in may read them.

    case 'circuitCodes': case 'congregationCircuits':
      return deny('Only the Super Admin manages Circuit Codes and Circuit Overseer assignments');

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

    case 'coFieldServiceMonthStatus': return writeMonthStatus(h, { id, next, old, isCreate, isUpdate, verdict });

    case 'meetingAttendance': return writeAttendance(h, { id, next, old, isCreate, isUpdate, isDelete, verdict });

    case 'meetingAttendanceSettings':
      return verdict(
        !isDelete && id === next.congregationId && h.canManageReportsAsCongregationRole(nul(next.congregationId)) &&
          ['ROUNDED', 'EXACT'].includes(next.roundingMode) && nul(next.updatedBy) === h.me,
        'Only the congregation-wide roles of that congregation set the attendance rounding',
      );

    case 'meetingAttendanceEvents':
      return verdict(isCreate && nul(next.userId) === h.me && h.canManageReportsAsCongregationRole(nul(next.congregationId)), 'Attendance history is append-only');

    case 'congregationMonthlyStatistics': return writeStatistics(h, { id, next, old, isCreate, isUpdate, verdict });

    case 'congregationComparativeReports': return writeComparativeReport(h, { id, next, old, isCreate, isUpdate, isDelete, verdict });

    case 'comparativeReportHistory':
      return verdict(
        isCreate && nul(next.userId) === h.me && (h.canManageReportsAsCongregationRole(nul(next.congregationId)) || h.isCircuitOverseerFor(nul(next.congregationId))),
        'Comparative Report history is append-only',
      );

    case 'comparativeReportRemarks': {
      if (!isCreate || nul(next.authorUserId) !== h.me || !h.isCircuitOverseerFor(nul(next.congregationId)) || !next.remark) return deny('Only the Circuit Overseer adds remarks, and only once');
      const report = live(await h.get('congregationComparativeReports', next.comparativeReportId ?? '-'));
      return verdict(!!report && ['SUBMITTED', 'RETURNED'].includes(report.status), 'Remarks go on a submitted or returned report');
    }

    case 'coReceivedReports': return await writeReceivedReport(h, { id, next, isCreate, verdict });

    case 'coReportReads':
      return verdict(
        !isDelete && nul(next.coPersonId) === h.me && id === `${h.me}_${next.receivedReportId}` && h.isCircuitOverseerFor(nul(next.congregationId)),
        'A Circuit Overseer marks only their own reads, for a congregation they hold',
      );

    case 'coFieldServiceReportEvents':
      return verdict(isCreate && nul(next.userId) === h.me && (h.canSubmitFieldServiceFor(nul(next.congregationId)) || h.isCircuitOverseerFor(nul(next.congregationId))), 'History is append-only');

    case 'preachingTimeRecords':
      if (isDelete) return deny('Only the Super Admin deletes preaching time records');
      // A month submitted to the Circuit Overseer (Submitted / Received) is locked: no hours or minutes are added, edited or moved.
      for (const rec of [old, next]) {
        if (rec && (await fsDayLocked(get, rec.congregationId, rec.date))) return deny(MONTH_LOCKED);
      }
      return ok();

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
        if (collection === 'creditHourRecords' || collection === 'plannerDays') {
          for (const rec of [old, isDelete ? null : next]) {
            if (rec && (await fsDayLocked(get, actor.congregationId, rec.dayStart))) return deny(MONTH_LOCKED);
          }
        }
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

const MONTH_LOCKED = 'This Field Service Month has been submitted to the Circuit Overseer and is locked';
const MS_8H = 8 * 3600 * 1000;

/** First instant of the (UTC+8) service month containing [ms] — how periodMonth is stored. */
function monthStartOf(ms) {
  const d = new Date(Number(ms) + MS_8H);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1) - MS_8H;
}

const statusDoc = async (get, congregationId, periodMonth) =>
  congregationId == null || periodMonth == null ? null : live(await get('coFieldServiceMonthStatus', `${congregationId}_${Math.trunc(Number(periodMonth))}`));

/** Submitted or Received: the month's records are frozen for everyone but the Super Admin. */
async function fsMonthLocked(get, congregationId, periodMonth) {
  return ['SUBMITTED', 'RECEIVED'].includes((await statusDoc(get, congregationId, periodMonth))?.status);
}
const fsDayLocked = (get, congregationId, dayMillis) => (dayMillis == null ? false : fsMonthLocked(get, congregationId, monthStartOf(dayMillis)));

/** The Circuit Overseer sees a month only once it was submitted. */
async function coMonthVisible(get, congregationId, periodMonth) {
  return ['SUBMITTED', 'RECEIVED', 'RETURNED'].includes((await statusDoc(get, congregationId, periodMonth))?.status);
}

const inCongOf = (actor, cong) => actor.congregationId != null && actor.congregationId === cong;

const attCount = (v) => Number.isInteger(v) && v >= 0 && v <= 999999;

/**
 * Meeting attendance record: whole-number counts for the parts of its meeting type only, the real mean, and the official figure that
 * mean rounded half-up (ROUNDED) or kept (EXACT). Mirrors attValid in firestore.rules.
 */
function attendanceValid(d) {
  const mid = d.meetingType === 'MIDWEEK';
  const wk = d.meetingType === 'WEEKEND';
  const midParts = [d.treasuresAttendance, d.applyYourselfAttendance, d.livingAsChristiansAttendance];
  const wkParts = [d.publicMeetingAttendance, d.watchtowerStudyAttendance];
  const shapeOk = (mid && midParts.every(attCount) && wkParts.every((v) => v == null)) || (wk && wkParts.every(attCount) && midParts.every((v) => v == null));
  if (!shapeOk || !['ROUNDED', 'EXACT'].includes(d.roundingMode)) return false;
  const parts = mid ? midParts : wkParts;
  const mean = parts.reduce((a, b) => a + b, 0) / parts.length;
  if (Math.abs(Number(d.calculatedAverage) - mean) >= 0.0001) return false;
  return d.roundingMode === 'ROUNDED' ? Math.abs(Number(d.officialAttendance) - Math.floor(mean + 0.5)) < 0.0001 : Math.abs(Number(d.officialAttendance) - mean) <= 0.0051;
}

async function monthFrozen(get, congregationId, serviceMonth) {
  const s = live(await get('congregationMonthlyStatistics', `${congregationId}_${Math.trunc(Number(serviceMonth))}`));
  return s?.snapshotStatus === 'RECEIVED';
}

async function writeAttendance(h, { id, next, old, isCreate, isUpdate, isDelete, verdict }) {
  if (isDelete) return deny('Attendance records are never hard-deleted (set deleted = true)');
  const cong = nul(next.congregationId);
  if (!h.canManageReportsAsCongregationRole(cong)) return deny('Only the congregation-wide roles of that congregation manage attendance');
  if (id !== `${next.congregationId}_${next.meetingType}_${Math.trunc(Number(next.meetingDate))}`) return deny('The id must be congregation + meeting type + date');
  if (Number(next.serviceMonth) !== monthStartOf(next.meetingDate)) return deny('The service month must match the meeting date');
  if (nul(next.updatedBy) !== h.me) return deny('Records are written as yourself');
  if (!attendanceValid(next)) return deny('The attendance counts or their calculation are not valid');
  if (isUpdate && (old.congregationId !== next.congregationId || old.meetingType !== next.meetingType || Number(old.meetingDate) !== Number(next.meetingDate))) return deny('Congregation, meeting type and date cannot change');
  if (await monthFrozen(h.get, next.congregationId, next.serviceMonth)) return deny('This month\'s statistics were received by the Circuit Overseer and are frozen');
  return ok();
}

/** Historical monthly statistics: written with the report's submission and tied to its status; frozen once received. */
const CMP_EDIT_KEYS = ['reportSnapshot', 'sourceSnapshotIds', 'attendanceRoundingMode', 'updatedAt'];
const CMP_SEND_KEYS = ['status', 'version', 'reportSnapshot', 'sourceSnapshotIds', 'attendanceRoundingMode', 'submittedBy', 'submittedByName', 'submittedByRole', 'submittedAt', 'updatedAt'];
const CMP_CO_KEYS = ['status', 'receivedBy', 'receivedByName', 'receivedAt', 'returnedBy', 'returnedByName', 'returnedAt', 'returnReason', 'currentCoRemarks', 'updatedAt'];

/** A congregation's formal Comparative Report (see firestore.rules congregationComparativeReports): DRAFT -> SUBMITTED -> RECEIVED, or SUBMITTED -> RETURNED -> SUBMITTED. */
function writeComparativeReport(h, { id, next, old, isCreate, isUpdate, isDelete, verdict }) {
  const periodsValid = (d) => Number(d.periodAStart) <= Number(d.periodAEnd) && Number(d.periodBStart) <= Number(d.periodBEnd) &&
    Number(d.periodAEnd) <= Date.now() && Number(d.periodBEnd) <= Date.now() &&
    !(Number(d.periodAStart) <= Number(d.periodBEnd) && Number(d.periodBStart) <= Number(d.periodAEnd));
  if (isDelete) return verdict(h.canManageReportsAsCongregationRole(nul(old.congregationId)) && old.status === 'DRAFT', 'Only a draft Comparative Report can be deleted');
  if (isCreate) {
    const wanted = `${next.congregationId}_${Math.trunc(Number(next.periodAStart))}_${Math.trunc(Number(next.periodAEnd))}_${Math.trunc(Number(next.periodBStart))}_${Math.trunc(Number(next.periodBEnd))}`;
    return verdict(
      h.canManageReportsAsCongregationRole(nul(next.congregationId)) && id === wanted && periodsValid(next) && nul(next.createdBy) === h.me &&
        !!next.reportSnapshot && Number(next.version ?? 0) === 1 &&
        (next.status === 'DRAFT' || (next.status === 'SUBMITTED' && nul(next.submittedBy) === h.me)),
      'Only the congregation-wide roles create a Comparative Report, with valid past periods',
    );
  }
  if (!isUpdate) return deny('That change to a Comparative Report is not allowed');
  if (next.congregationId !== old.congregationId || next.periodAStart !== old.periodAStart || next.periodAEnd !== old.periodAEnd ||
    next.periodBStart !== old.periodBStart || next.periodBEnd !== old.periodBEnd || next.createdBy !== old.createdBy) return deny('The congregation, periods and creator cannot change');
  const changed = affectedKeys(next, old);
  const cong = nul(old.congregationId);
  if (h.canManageReportsAsCongregationRole(cong) && ['DRAFT', 'RETURNED'].includes(old.status)) {
    if (next.status === old.status && Number(next.version) === Number(old.version) && !!next.reportSnapshot && changed.every((k) => CMP_EDIT_KEYS.includes(k))) return ok();
    if (next.status === 'SUBMITTED' && Number(next.version) === Number(old.version) + (old.status === 'RETURNED' ? 1 : 0) && nul(next.submittedBy) === h.me &&
      !!next.reportSnapshot && changed.every((k) => CMP_SEND_KEYS.includes(k))) return ok();
  }
  if (h.isCircuitOverseerFor(cong) && Number(next.version) === Number(old.version)) {
    if (old.status === 'SUBMITTED' && ['RECEIVED', 'RETURNED'].includes(next.status) && (next.status === 'RECEIVED' || !!next.returnReason) && changed.every((k) => CMP_CO_KEYS.includes(k))) return ok();
    if (['SUBMITTED', 'RETURNED'].includes(old.status) && next.status === old.status && changed.every((k) => ['currentCoRemarks', 'updatedAt'].includes(k))) return ok();
  }
  return deny('That change to the Comparative Report is not allowed');
}

const MAX_RECEIVED_ROWS = 800;

/** The frozen copy of a month's report, written once with the send: one document per congregation + month + send number. */
async function writeReceivedReport(h, { id, next, isCreate, verdict }) {
  if (!isCreate) return deny('A received report is never edited or deleted');
  const cong = nul(next.congregationId);
  const month = Math.trunc(Number(next.periodMonth));
  const version = Math.trunc(Number(next.version));
  if (id !== `${cong}_${month}_${version}`) return deny('The id must be congregation + month + send number');
  if (!Array.isArray(next.rows) || next.rows.length > MAX_RECEIVED_ROWS) return deny('The report rows are missing or too many');
  const status = live(await h.get('coFieldServiceMonthStatus', `${cong}_${month}`));
  if (!status || status.status !== 'SUBMITTED' || Number(status.version) !== version) return deny('A received report follows the send that created it');
  return verdict(h.canSubmitFieldServiceFor(cong) && nul(next.submittedByPersonId) === h.me, 'Only the congregation sending the report saves its copy');
}

async function writeStatistics(h, { id, next, old, isCreate, isUpdate, verdict }) {
  if (!isCreate && !isUpdate) return deny('Statistics snapshots are never deleted');
  const cong = nul(next.congregationId);
  if (id !== `${cong}_${Math.trunc(Number(next.serviceMonth))}`) return deny('The id must be congregation + service month');
  const status = live(await h.get('coFieldServiceMonthStatus', id));
  if (!status || status.status !== next.snapshotStatus) return deny('The snapshot must follow the report\'s status');
  if (isCreate) return verdict(h.canSubmitFieldServiceFor(cong) && next.snapshotStatus === 'SUBMITTED', 'Only the congregation sending the report creates its statistics');
  if (old.congregationId !== next.congregationId || Number(old.serviceMonth) !== Number(next.serviceMonth)) return deny('Congregation and month cannot change');
  if (h.canSubmitFieldServiceFor(cong) && old.snapshotStatus !== 'RECEIVED' && ['SUBMITTED', 'NOT_SUBMITTED'].includes(next.snapshotStatus)) return ok();
  if (h.isCircuitOverseerFor(cong) && affectedKeys(next, old).every((k) => ['snapshotStatus', 'receivedDate', 'updatedAt'].includes(k))) return ok();
  return deny('That change to the statistics snapshot is not allowed');
}

const SEND_KEYS = ['status', 'version', 'submittedAt', 'submittedByPersonId', 'submittedByName', 'updatedAt'];
const UNDO_KEYS = ['status', 'coRemarks', 'coRemarksAt', 'coRemarksByName', 'undoneAt', 'undoneByPersonId', 'undoneByName', 'updatedAt'];
const CO_KEYS = ['status', 'receivedAt', 'receivedByPersonId', 'receivedByName', 'returnedAt', 'returnedByPersonId', 'returnedByName',
  'returnReason', 'coRemarks', 'coRemarksAt', 'coRemarksByName', 'updatedAt'];

/** The status of a congregation's service month (SUBMITTED -> RECEIVED, ... see firestore.rules coFieldServiceMonthStatus). */
function writeMonthStatus(h, { id, next, old, isCreate, isUpdate, verdict }) {
  const notFuture = (m) => Number(m) <= Date.now();
  if (isCreate) {
    return verdict(
      id === `${next.congregationId}_${Math.trunc(Number(next.periodMonth))}` && notFuture(next.periodMonth) && next.status === 'SUBMITTED' &&
        nul(next.coRemarks) == null && h.canSubmitFieldServiceFor(nul(next.congregationId)),
      'Only an Admin, Coordinator Elder, Service Overseer or Secretary sends a month that has started',
    );
  }
  if (!isUpdate) return deny('Status documents are never deleted');
  if (next.congregationId !== old.congregationId || next.periodMonth !== old.periodMonth) return deny('Congregation and month cannot change');
  const changed = affectedKeys(next, old);
  const cong = nul(old.congregationId);
  if (h.canSubmitFieldServiceFor(cong)) {
    if (['NOT_SUBMITTED', 'RETURNED'].includes(old.status) && next.status === 'SUBMITTED' && notFuture(old.periodMonth) && changed.every((k) => SEND_KEYS.includes(k))) return ok();
    if (old.status === 'SUBMITTED' && next.status === 'NOT_SUBMITTED' && nul(next.coRemarks) == null && changed.every((k) => UNDO_KEYS.includes(k))) return ok();
  }
  if (h.isCircuitOverseerFor(cong)) {
    const moves = (old.status === 'SUBMITTED' && ['RECEIVED', 'RETURNED'].includes(next.status)) ||
      (old.status === 'RECEIVED' && next.status === 'RETURNED') ||
      (['SUBMITTED', 'RECEIVED', 'RETURNED'].includes(old.status) && next.status === old.status);
    if (moves && changed.every((k) => CO_KEYS.includes(k))) return ok();
    if (old.status === 'SUBMITTED' && next.status === 'NOT_SUBMITTED' && nul(next.coRemarks) == null && changed.every((k) => UNDO_KEYS.includes(k))) return ok();
  }
  return deny('That change to the report status is not allowed');
}

async function writeMonthlyReports(h, { op, next, old, verdict }) {
  if (h.grant.has) return deny('Restricted accounts have view-only access to reports');
  // A month submitted to the Circuit Overseer (Submitted / Received) is locked: nobody but the Super Admin changes its records.
  for (const rec of [old, op === 'delete' ? null : next]) {
    if (rec && await fsMonthLocked(h.get, rec.congregationId, rec.periodMonth)) return deny(MONTH_LOCKED);
  }
  const mine = old?.publisherPersonId === h.me;
  // Level 1 (publisher → congregation): the one thing a publisher may still write to a submitted report is the access request.
  const accessRequestOnly = !!old && op === 'set' && ['SUBMITTED', 'CORRECTED', 'POSTED'].includes(old.status) && next?.status === 'ACCESS_REQUESTED' &&
    affectedKeys(next, old).every((k) => ['status', 'accessRequestReason', 'accessRequestedAt', 'lastEditedAt'].includes(k));
  if (old && old.status === 'POSTED' && mine && !accessRequestOnly) return deny('A posted report can no longer be changed');
  if (old && op === 'set' && ['SUBMITTED', 'CORRECTED', 'ACCESS_REQUESTED'].includes(old.status) && mine &&
      !h.canManagePublisherReportsFor(old.congregationId) && !same(next, old) && !accessRequestOnly) {
    return deny('A submitted report is locked: request edit access from the person in charge');
  }
  const groupOk = async (rec) => groupRoleMayWriteReport(h, rec);
  if (op === 'delete') {
    return verdict(h.sa || h.canManageReportsAsCongregationRole(old?.congregationId) || (await groupOk(old)), 'Not allowed to delete this report');
  }
  const reported = nul(next.hoursRendered);
  const system = nul(next.systemCalculatedHours);
  const hoursOk = reported == null || system == null || reported === system || (next.hoursConfirmed === true && (next.hoursAdjustmentRemarks ?? '') !== '');
  const groupWrite = (await groupOk(next)) && (!old || old.publisherPersonId === next.publisherPersonId);
  // A publisher only ever writes the statuses Open / Submitted / Corrected (or keeps Access Granted / Reversed while correcting) —
  // never Access Granted, Reversed or Posted for themselves; the person in charge makes those moves.
  const publisherStatusOk = ['DRAFT', 'SUBMITTED', 'CORRECTED'].includes(next.status) ||
    (!!old && ['ACCESS_GRANTED', 'RETURNED'].includes(old.status) && next.status === old.status) || accessRequestOnly;
  return verdict((next.publisherPersonId === h.me && hoursOk && publisherStatusOk) || h.canManageReportsAsCongregationRole(next.congregationId) || groupWrite, 'Not allowed to write this report');
}

/**
 * A Group Coordinator / Servant / Assistant (active role REGULAR_ELDER with a group) may write a report only for a publisher of THAT
 * FS Group: they must really fill a slot of the group, and the report must name the publisher's own active role assignment in that
 * group and congregation (mirrors groupRoleMayWriteReport in firestore.rules).
 */
async function groupRoleMayWriteReport(h, rec) {
  const { actor, get } = h;
  if (!rec || actor.adminRole !== 'REGULAR_ELDER' || !actor.groupId || !rec.groupRoleAssignmentId) return false;
  if (!(await isSlotHolder(actor, actor.groupId, undefined, get))) return false;
  const ra = live(await get('roleAssignments', rec.groupRoleAssignmentId));
  return !!ra && ra.personId === rec.publisherPersonId && ra.groupId === actor.groupId && ra.congregationId === rec.congregationId && ra.status === 'ACTIVE';
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
    // A visit dated in a month submitted to the Circuit Overseer is locked.
    for (const rec of [old, isDelete ? null : next]) {
      if (rec && (await fsDayLocked(get, cong, rec.visitDate))) return deny(MONTH_LOCKED);
    }
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

/** Does [grant] carry [permission] for [cong]? (Firestore `restrictedAllows` for a caller who HAS a grant.) */
export function grantAllows(grant, permission, cong) {
  return grant.has && grant.permissions.includes(permission) &&
    (grant.scopeType === 'ALL_CONGREGATIONS' || (grant.scopeType === 'SELECTED_CONGREGATIONS' && grant.scopeCongregationIds.includes(cong)));
}
