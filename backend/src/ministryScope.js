import { isWide } from './policy/index.js';

/**
 * Which Searching / Return Visit / Bible Study records (and the history of them) a person may see: the rule the record lists, the duplicate check and the
 * Ministry History all share.
 *   Super Admin and the congregation-wide roles (Admin, Coordinator Elder, Service Overseer, Secretary): every record of the congregation;
 *   a group role (Overseer / Servant / Assistant of an FS Group): the records of that group's publishers (and unassigned ones);
 *   a publisher: their own, the unassigned ones (never a Bible Study) and what the congregation lets publishers see of the others (publisherVisibilitySettings).
 */
const SHOW_KEY = { SEARCHING: 'showOthersSearching', RETURN_VISIT: 'showOthersReturnVisit', BIBLE_STUDY: 'showOthersBibleStudy' };

/** The publishers of the FS Group the actor belongs to (the actor included), from the active role assignments. */
export async function groupMembersOf(store, actor, congregation) {
  const rows = (await store.list('roleAssignments', congregation)).map((r) => r.data ?? {});
  const groupId = actor.groupId ?? rows.find((a) => a.personId === actor.personId && a.status === 'ACTIVE' && a.groupId && String(a.roleType ?? '').startsWith('PUBLISHER'))?.groupId ?? null;
  const members = new Set([actor.personId]);
  if (groupId) for (const a of rows) if (a.status === 'ACTIVE' && a.groupId === groupId && String(a.roleType ?? '').startsWith('PUBLISHER')) members.add(a.personId);
  return members;
}

/** @returns {Promise<{ wide: boolean, visible: (r: { publisherPersonId?: string, pipelineStage?: string }) => boolean }>} */
export async function recordVisibility(store, get, actor, congregation) {
  const wide = actor.isSuperAdmin || isWide(actor, congregation);
  let groupMembers = null;
  let showOthers = {};
  if (!wide && actor.groupId) groupMembers = await groupMembersOf(store, actor, congregation);
  else if (!wide) showOthers = (await get('publisherVisibilitySettings', congregation))?.data ?? {};
  const visible = (r) => {
    if (wide) return true;
    const owner = r.publisherPersonId ?? '';
    if (groupMembers) return groupMembers.has(owner) || owner === '';
    if (owner === actor.personId) return true;
    const stage = r.pipelineStage ?? 'SEARCHING';
    if (owner === '') return stage !== 'BIBLE_STUDY';
    return showOthers[SHOW_KEY[stage]] === true;
  };
  return { wide, visible };
}
