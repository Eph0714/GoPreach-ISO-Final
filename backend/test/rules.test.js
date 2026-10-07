import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { loadActor, authorizeWrite, canReadRow, loadGrant } from '../src/policy/index.js';

/**
 * One test per ported rule from firestore.rules (everything except the drawing collections, which api.test.js covers).
 * `write(who, op, collection, id, data)` runs the same policy code the push endpoint runs; `reads(who, collection, id)`
 * runs the pull filter on a stored row.
 */
let store;

const person = (activeAdminRole, activeCongregationId = 'congA', extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId, ...extra });
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));

before(async () => {
  store = new MemoryStore();
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('people', 'adminA', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'coordA', person('COORDINATOR_ELDER'));
  await put('people', 'soA', person('SERVICE_OVERSEER'));
  await put('people', 'elderA', person('REGULAR_ELDER'));
  await put('people', 'pubA', person(null));
  await put('people', 'pubA2', person(null));
  await put('people', 'newbie', { isSuperAdmin: false, activeAdminRole: null, activeCongregationId: null }); // never signed in
  await put('people', 'adminB', person('ADMIN_PER_CONGREGATION', 'congB'));
  await put('people', 'pubB', person(null, 'congB'));
  await put('people', 'slot', person(null)); // group overseer of g1
  await put('people', 'restricted', person('CIRCUIT_OVERSEER', 'congA'));
  await put('userAccessGrants', 'restricted', { permissions: ['VIEW_CONGREGATIONS', 'MANAGE_GROUPS', 'VIEW_GROUPS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congB'] });
  await put('groups', 'g1', { congregationId: 'congA', overseerPersonId: 'slot', status: 'ACTIVE' });
  await put('groups', 'gB', { congregationId: 'congB', status: 'ACTIVE' });
  await put('congregations', 'congA', { name: 'Alpha' });
  await put('congregations', 'congA2', { name: 'Alpha' }); // duplicate document with the same display name
  await put('congregations', 'congB', { name: 'Beta' });
  await put('roleAssignments', 'ra1', { congregationId: 'congA', roleType: 'PUBLISHER', groupId: 'g1', personId: 'pubA' });
  await put('monthlyReports', 'rep-pubA', { congregationId: 'congA', publisherPersonId: 'pubA', status: 'DRAFT' });
  await put('monthlyReports', 'rep-posted', { congregationId: 'congA', publisherPersonId: 'pubA', status: 'POSTED' });
  await put('monthlyReports', 'rep-submitted', { congregationId: 'congA', publisherPersonId: 'pubA', status: 'SUBMITTED', note: 'x' });
  await put('interestedPeople', 'rvA', { congregationId: 'congA', publisherPersonId: 'pubA', pipelineStage: 'RETURN_VISIT' });
  await put('interestedPeople', 'bsA', { congregationId: 'congA', publisherPersonId: 'pubA', pipelineStage: 'BIBLE_STUDY' });
  await put('interestedPeople', 'rvB', { congregationId: 'congB', publisherPersonId: 'pubB', pipelineStage: 'RETURN_VISIT' });
  await put('interestedPeople/rvA/visits', 'v1', { createdByPersonId: 'pubA', interestedPersonId: 'rvA' });
  await put('interestedPeople/rvA/visits', 'v2', { createdByPersonId: 'pubA2', interestedPersonId: 'rvA' });
  await put('groupChats', 'chat1', { congregationId: 'congA', participantIds: ['pubA', 'pubA2'] });
  await put('groupChats/chat1/messages', 'm1', { senderId: 'pubA', text: 'hi', createdAt: 1 });
  await put('deletedRecords', 'del1', { congregationId: 'congA', deletedByPersonId: 'pubA' });
  await put('mapPins', 'pin1', { congregationId: 'congA', createdByPersonId: 'pubA' });
  await put('territoryAssignments', 'ta1', { congregationId: 'congA' });
  await put('plannerDays', 'day1', { publisherPersonId: 'pubA' });
  await put('creditHourCategories', 'cat1', { name: 'Other' });
  await put('preachingTimeRecords', 'pt1', { publisherPersonId: 'pubA' });
  await put('schedules', 's1', { x: 1 });
});

async function write(who, op, collection, id, data) {
  return store.transaction(async (tx) => {
    const actor = await loadActor(who, tx.get);
    const existing = await tx.get(collection, id);
    const verdict = await authorizeWrite(actor, op, collection, id, data, existing, tx.get);
    return verdict.ok;
  });
}
const set = (who, collection, id, data) => write(who, 'set', collection, id, data);
const del = (who, collection, id) => write(who, 'delete', collection, id);
const stored = async (collection, id) => (await store.get(collection, id)).data;

async function reads(who, collection, id) {
  const get = (c, i) => store.get(c, i);
  const actor = await loadActor(who, get);
  const grant = await loadGrant(who, get);
  const row = await store.get(collection, id);
  return canReadRow(actor, grant, { collection, id, data: row.data, deleted: row.deleted }, get);
}

// ---- people -------------------------------------------------------------------------------------------------------

test('people: nobody grants themselves or anyone Super Admin', async () => {
  assert.equal(await set('pubA', 'people', 'pubA', { ...(await stored('people', 'pubA')), isSuperAdmin: true }), false);
  assert.equal(await set('adminA', 'people', 'pubA', { ...(await stored('people', 'pubA')), isSuperAdmin: true }), false);
  assert.equal(await set('adminA', 'people', 'fresh', { isSuperAdmin: true }), false);
  assert.equal(await set('adminA', 'people', 'fresh', { isSuperAdmin: false, activeCongregationId: 'congA' }), true);
});

test('people: you edit yourself; congregation-wide roles edit people of their congregation; nobody else', async () => {
  const p = await stored('people', 'pubA');
  assert.equal(await set('pubA', 'people', 'pubA', { ...p, phone: '1' }), true);
  assert.equal(await set('pubA', 'people', 'pubA2', { ...(await stored('people', 'pubA2')), phone: '1' }), false);
  for (const who of ['adminA', 'coordA', 'soA']) assert.equal(await set(who, 'people', 'pubA', { ...p, phone: '2' }), true, who);
  assert.equal(await set('elderA', 'people', 'pubA', { ...p, phone: '3' }), false, 'a Regular Elder never edits others');
  assert.equal(await set('adminB', 'people', 'pubA', { ...p, phone: '4' }), false, 'other congregation');
  assert.equal(await set('adminA', 'people', 'newbie', { ...(await stored('people', 'newbie')), phone: '5' }), true, 'never-signed-in members are editable');
  assert.equal(await set('adminA', 'people', 'pubB', { ...(await stored('people', 'pubB')), phone: '6' }), false);
});

test('userAccessGrants: only the Super Admin (or a MANAGE_USERS grant) writes them', async () => {
  assert.equal(await set('adminA', 'userAccessGrants', 'pubA', { permissions: ['MANAGE_USERS'] }), false);
  assert.equal(await set('sa', 'userAccessGrants', 'pubA', { permissions: [] }), true);
  assert.equal(await set('restricted', 'userAccessGrants', 'pubA2', { permissions: [] }), false, 'no MANAGE_USERS');
  assert.equal(await reads('pubA', 'userAccessGrants', 'restricted'), false);
  assert.equal(await reads('restricted', 'userAccessGrants', 'restricted'), true, 'your own grant');
});

test('congregations: restricted users need the matching permission and scope; built-in roles are unrestricted', async () => {
  assert.equal(await set('adminA', 'congregations', 'congNew', { name: 'New' }), true);
  assert.equal(await set('restricted', 'congregations', 'congNew2', { name: 'New' }), false, 'no ADD_CONGREGATIONS');
  assert.equal(await set('restricted', 'congregations', 'congB', { name: 'Beta 2' }), false, 'no EDIT_CONGREGATIONS');
  assert.equal(await reads('restricted', 'congregations', 'congB'), true);
  assert.equal(await reads('restricted', 'congregations', 'congA'), false, 'outside the grant scope');
});

// ---- groups and role assignments -----------------------------------------------------------------------------------

test('groups: wide roles manage their congregation; a slot holder edits but cannot deactivate; grants are scoped', async () => {
  const g = await stored('groups', 'g1');
  assert.equal(await set('adminA', 'groups', 'g1', { ...g, name: 'Renamed' }), true);
  assert.equal(await set('adminA', 'groups', 'g9', { congregationId: 'congA' }), true);
  assert.equal(await set('adminA', 'groups', 'g1', { ...g, congregationId: 'congB' }), false, 'congregation cannot change');
  assert.equal(await set('adminB', 'groups', 'g1', { ...g, name: 'x' }), false);
  assert.equal(await set('slot', 'groups', 'g1', { ...g, name: 'Mine' }), true);
  assert.equal(await set('slot', 'groups', 'g1', { ...g, status: 'INACTIVE' }), false);
  assert.equal(await set('slot', 'groups', 'g9b', { congregationId: 'congA' }), false, 'a slot holder cannot create');
  assert.equal(await del('slot', 'groups', 'g1'), false);
  assert.equal(await del('pubA', 'groups', 'g1'), false);
  assert.equal(await set('restricted', 'groups', 'gB', { ...(await stored('groups', 'gB')), name: 'by grant' }), true, 'MANAGE_GROUPS in scope');
  assert.equal(await set('restricted', 'groups', 'g1', { ...g, name: 'by grant' }), false, 'outside scope');
});

test('roleAssignments: no self-elevation, no Super Admin / Circuit Overseer roles below the Super Admin', async () => {
  const ra = await stored('roleAssignments', 'ra1');
  assert.equal(await set('pubA', 'roleAssignments', 'raX', { congregationId: 'congA', roleType: 'ADMIN:COORDINATOR_ELDER' }), false);
  assert.equal(await set('adminA', 'roleAssignments', 'raX', { congregationId: 'congA', roleType: 'PUBLISHER' }), true);
  assert.equal(await set('adminA', 'roleAssignments', 'raX', { congregationId: 'congB', roleType: 'PUBLISHER' }), false, 'other congregation');
  assert.equal(await set('adminA', 'roleAssignments', 'raY', { congregationId: 'congA', roleType: 'ADMIN:SUPER_ADMIN' }), false);
  assert.equal(await set('adminA', 'roleAssignments', 'raY', { congregationId: 'congA', roleType: 'ADMIN:CIRCUIT_OVERSEER' }), false);
  assert.equal(await set('sa', 'roleAssignments', 'raY', { congregationId: 'congA', roleType: 'ADMIN:CIRCUIT_OVERSEER' }), true);
  assert.equal(await set('adminA', 'roleAssignments', 'ra1', { ...ra, roleType: 'ADMIN:SUPER_ADMIN' }), false, 'cannot upgrade into a privileged role');
  assert.equal(await set('elderA', 'roleAssignments', 'raZ', { congregationId: 'congA', roleType: 'PUBLISHER' }), false, 'Regular Elder never enrolls');
});

test('roleAssignments: a group slot holder may move members, touching only the group link fields', async () => {
  const ra = await stored('roleAssignments', 'ra1');
  assert.equal(await set('slot', 'roleAssignments', 'ra1', { ...ra, groupId: null }), true);
  assert.equal(await set('slot', 'roleAssignments', 'ra1', { ...ra, roleType: 'ADMIN:COORDINATOR_ELDER' }), false);
  assert.equal(await set('pubA2', 'roleAssignments', 'ra1', { ...ra, groupId: null }), false, 'not a slot holder');
});

// ---- monthly reports ---------------------------------------------------------------------------------------------

test('monthlyReports: publishers write only their own, and posted / submitted reports are locked', async () => {
  const draft = { congregationId: 'congA', publisherPersonId: 'pubA', status: 'DRAFT' };
  assert.equal(await set('pubA', 'monthlyReports', 'rep-pubA', { ...draft, status: 'SUBMITTED' }), true);
  assert.equal(await set('pubA2', 'monthlyReports', 'rep-pubA', { ...draft, studies: 9 }), false, 'someone else\'s report');
  assert.equal(await set('pubA2', 'monthlyReports', 'forged', { ...draft }), false, 'forging another publisher\'s report');
  assert.equal(await set('pubA', 'monthlyReports', 'rep-posted', { ...draft, studies: 1 }), false, 'posted');
  assert.equal(await del('pubA', 'monthlyReports', 'rep-posted'), false);
  const submitted = await stored('monthlyReports', 'rep-submitted');
  assert.equal(await set('pubA', 'monthlyReports', 'rep-submitted', { ...submitted, note: 'changed' }), false, 'locked after submitting');
  assert.equal(await set('pubA', 'monthlyReports', 'rep-submitted', { ...submitted }), true, 'an identical retry is accepted');
  assert.equal(await set('soA', 'monthlyReports', 'rep-submitted', { ...submitted, status: 'DRAFT' }), true, 'an admin-track role returns it');
  assert.equal(await del('soA', 'monthlyReports', 'rep-pubA'), false, 'only the Super Admin deletes');
  assert.equal(await del('sa', 'monthlyReports', 'rep-pubA'), true);
});

test('monthlyReports: a Pioneer\'s adjusted hours need confirmation and remarks; restricted accounts are view-only', async () => {
  const base = { congregationId: 'congA', publisherPersonId: 'pubA2', status: 'DRAFT' };
  assert.equal(await set('pubA2', 'monthlyReports', 'rep-pubA2', { ...base, hoursRendered: 12, systemCalculatedHours: 10 }), false);
  assert.equal(await set('pubA2', 'monthlyReports', 'rep-pubA2', { ...base, hoursRendered: 12, systemCalculatedHours: 10, hoursConfirmed: true, hoursAdjustmentRemarks: 'rounding' }), true);
  assert.equal(await set('restricted', 'monthlyReports', 'rep-r', { congregationId: 'congB', publisherPersonId: 'restricted' }), false);
});

// ---- owner-only collections --------------------------------------------------------------------------------------

test('owner-only collections: create as yourself, touch only your own records, read only your own', async () => {
  assert.equal(await set('pubA', 'plannerDays', 'day2', { publisherPersonId: 'pubA' }), true);
  assert.equal(await set('pubA', 'plannerDays', 'day3', { publisherPersonId: 'pubA2' }), false, 'created for someone else');
  assert.equal(await set('pubA2', 'plannerDays', 'day1', { publisherPersonId: 'pubA2' }), false, 'taking over someone else\'s record');
  assert.equal(await del('pubA2', 'plannerDays', 'day1'), false);
  assert.equal(await reads('pubA', 'plannerDays', 'day1'), true);
  assert.equal(await reads('pubA2', 'plannerDays', 'day1'), false);
  assert.equal(await reads('adminA', 'plannerDays', 'day1'), false, 'not even an admin');
  assert.equal(await reads('sa', 'plannerDays', 'day1'), true);
});

// ---- chat --------------------------------------------------------------------------------------------------------

test('groupChats: only chat managers create; participants never rewrite members; messages cannot be spoofed or hard-deleted', async () => {
  const chat = await stored('groupChats', 'chat1');
  assert.equal(await set('adminA', 'groupChats', 'chat2', { congregationId: 'congA', participantIds: ['pubA'] }), true);
  assert.equal(await set('elderA', 'groupChats', 'chat3', { congregationId: 'congA', participantIds: [] }), false);
  assert.equal(await set('adminB', 'groupChats', 'chat4', { congregationId: 'congA', participantIds: [] }), false);
  assert.equal(await set('pubA', 'groupChats', 'chat1', { ...chat, lastMessage: 'hi' }), true, 'a participant updates the rollup');
  assert.equal(await set('pubA', 'groupChats', 'chat1', { ...chat, participantIds: ['pubA', 'pubB'] }), false);
  assert.equal(await set('pubB', 'groupChats', 'chat1', { ...chat, lastMessage: 'x' }), false, 'not a participant');
  assert.equal(await reads('pubA', 'groupChats', 'chat1'), true);
  assert.equal(await reads('pubB', 'groupChats', 'chat1'), false);
  assert.equal(await reads('adminA', 'groupChats', 'chat1'), true, 'a chat manager of that congregation');

  assert.equal(await set('pubA', 'groupChats/chat1/messages', 'm2', { senderId: 'pubA', text: 'yo', createdAt: 2 }), true);
  assert.equal(await set('pubA', 'groupChats/chat1/messages', 'm3', { senderId: 'pubA2', text: 'forged', createdAt: 3 }), false);
  assert.equal(await set('pubB', 'groupChats/chat1/messages', 'm4', { senderId: 'pubB', text: 'intruder', createdAt: 4 }), false);
  const m1 = await stored('groupChats/chat1/messages', 'm1');
  assert.equal(await set('pubA', 'groupChats/chat1/messages', 'm1', { ...m1, text: 'edited' }), true, 'the sender edits');
  assert.equal(await set('pubA2', 'groupChats/chat1/messages', 'm1', { ...m1, text: 'hijack' }), false);
  assert.equal(await set('pubA2', 'groupChats/chat1/messages', 'm1', { ...m1, deletedForPersonIds: ['pubA2'] }), true, 'delete for me');
  assert.equal(await set('pubA', 'groupChats/chat1/messages', 'm1', { ...m1, createdAt: 99 }), false, 'ordering is immutable');
  assert.equal(await del('pubA', 'groupChats/chat1/messages', 'm1'), false);
  assert.equal(await reads('pubA2', 'groupChats/chat1/messages', 'm1'), true);
  assert.equal(await reads('pubB', 'groupChats/chat1/messages', 'm1'), false);
});

// ---- interested people and visits --------------------------------------------------------------------------------

test('interestedPeople: same-congregation members only; the receiving congregation\'s admins may take a forward', async () => {
  assert.equal(await set('pubA', 'interestedPeople', 'new1', { congregationId: 'congA' }), true);
  assert.equal(await set('pubA', 'interestedPeople', 'new2', { congregationId: 'congB' }), false);
  assert.equal(await set('pubB', 'interestedPeople', 'rvA', { ...(await stored('interestedPeople', 'rvA')), name: 'x' }), false);
  assert.equal(await set('pubA2', 'interestedPeople', 'rvA', { ...(await stored('interestedPeople', 'rvA')), name: 'x' }), true);
  assert.equal(await set('adminB', 'interestedPeople', 'rvA', { ...(await stored('interestedPeople', 'rvA')), congregationId: 'congB' }), true, 'forward accepted by the receiving admin');
  assert.equal(await del('pubB', 'interestedPeople', 'rvB'), true);
  assert.equal(await set('pubA', 'interestedPeople', 'dup', { congregationId: 'congA2' }), true, 'a duplicate congregation document with the same name still matches');
});

test('visits: you log and edit your own; Bible study history belongs to its enrolled publisher', async () => {
  const v1 = await stored('interestedPeople/rvA/visits', 'v1');
  assert.equal(await set('pubA2', 'interestedPeople/rvA/visits', 'v3', { createdByPersonId: 'pubA2', interestedPersonId: 'rvA' }), true);
  assert.equal(await set('pubA2', 'interestedPeople/rvA/visits', 'v4', { createdByPersonId: 'pubA', interestedPersonId: 'rvA' }), false, 'creator must be you');
  assert.equal(await set('pubB', 'interestedPeople/rvA/visits', 'v5', { createdByPersonId: 'pubB', interestedPersonId: 'rvA' }), false, 'other congregation');
  assert.equal(await set('pubA', 'interestedPeople/rvA/visits', 'v1', { ...v1, notes: 'mine' }), true);
  assert.equal(await set('pubA2', 'interestedPeople/rvA/visits', 'v1', { ...v1, notes: 'theirs' }), false, 'someone else\'s entry');
  assert.equal(await set('pubA', 'interestedPeople/rvA/visits', 'v1', { ...v1, createdByPersonId: 'pubA2' }), false, 'creator cannot change');
  assert.equal(await del('pubA', 'interestedPeople/rvA/visits', 'v2'), true, 'the record\'s owner may clear the history');
  assert.equal(await set('pubA2', 'interestedPeople/bsA/visits', 'b1', { createdByPersonId: 'pubA2', interestedPersonId: 'bsA' }), false, 'Bible study: owner only');
  assert.equal(await set('pubA', 'interestedPeople/bsA/visits', 'b1', { createdByPersonId: 'pubA', interestedPersonId: 'bsA' }), true);
  assert.equal(await set('sa', 'interestedPeople/bsA/visits', 'b2', { createdByPersonId: 'sa', interestedPersonId: 'bsA' }), true);
});

// ---- the smaller rules ---------------------------------------------------------------------------------------------

test('presence, dashboard layout, deleted records, map pins, territory assignments', async () => {
  assert.equal(await set('pubA', 'presence', 'pubA', { congregationId: 'congA' }), true);
  assert.equal(await set('pubA', 'presence', 'pubA', { congregationId: 'congB' }), false, 'appearing online elsewhere');
  assert.equal(await set('pubA', 'presence', 'pubA2', { congregationId: 'congA' }), false);
  assert.equal(await del('pubA', 'presence', 'pubA'), true);
  assert.equal(await set('pubA', 'dashboardModuleLayouts', 'pubA', { order: [] }), true);
  assert.equal(await set('pubA', 'dashboardModuleLayouts', 'pubA2', { order: [] }), false);

  assert.equal(await set('pubA', 'deletedRecords', 'del2', { congregationId: 'congA', deletedByPersonId: 'pubA' }), true);
  assert.equal(await set('pubA', 'deletedRecords', 'del3', { congregationId: 'congA', deletedByPersonId: 'pubA2' }), false);
  assert.equal(await set('pubA', 'deletedRecords', 'del1', { congregationId: 'congA', deletedByPersonId: 'pubA', x: 1 }), false, 'never edited');
  assert.equal(await reads('pubA', 'deletedRecords', 'del1'), true);
  assert.equal(await reads('pubA2', 'deletedRecords', 'del1'), false);
  assert.equal(await reads('soA', 'deletedRecords', 'del1'), true);
  assert.equal(await del('pubA2', 'deletedRecords', 'del1'), false);
  assert.equal(await del('soA', 'deletedRecords', 'del1'), true);

  assert.equal(await set('pubA', 'mapPins', 'pin2', { congregationId: 'congA', createdByPersonId: 'pubA' }), true);
  assert.equal(await set('pubA', 'mapPins', 'pin3', { congregationId: 'congB', createdByPersonId: 'pubA' }), false);
  assert.equal(await set('pubA', 'mapPins', 'pin4', { congregationId: 'congA', createdByPersonId: 'pubA2' }), false);
  assert.equal(await del('pubA2', 'mapPins', 'pin1'), false);
  assert.equal(await del('soA', 'mapPins', 'pin1'), true);

  assert.equal(await set('pubA', 'territoryAssignments', 'ta2', { congregationId: 'congA' }), false);
  assert.equal(await set('soA', 'territoryAssignments', 'ta2', { congregationId: 'congA' }), true);
  assert.equal(await set('soA', 'territoryAssignments', 'ta1', { congregationId: 'congB' }), false, 'congregation is fixed');
});

test('settings and lookups: credit hour categories, app settings, preaching time deletes', async () => {
  assert.equal(await set('pubA', 'creditHourCategories', 'default_other', { name: 'Other' }), true, 'first-run seeding');
  assert.equal(await set('pubA', 'creditHourCategories', 'custom1', { name: 'Mine' }), false);
  assert.equal(await set('adminA', 'creditHourCategories', 'custom1', { name: 'Admin' }), true);
  assert.equal(await set('coordA', 'creditHourCategories', 'cat1', { name: 'x' }), false, 'only the Admin Per Congregation or Super Admin');
  assert.equal(await set('elderA', 'appSettings', 'logo', { v: 1 }), true);
  assert.equal(await set('pubA', 'appSettings', 'logo', { v: 2 }), false);
  assert.equal(await set('pubA', 'preachingTimeRecords', 'pt1', { publisherPersonId: 'pubA' }), true);
  assert.equal(await del('adminA', 'preachingTimeRecords', 'pt1'), false);
  assert.equal(await del('sa', 'preachingTimeRecords', 'pt1'), true);
  assert.equal(await set('pubA', 'schedules', 's1', { x: 1 }), true, 'plain signed-in collections stay open to signed-in users');
});

// ---- reads, congregation scoping ---------------------------------------------------------------------------------

test('reads: sensitive collections stay inside the caller\'s congregation, with the documented exceptions', async () => {
  assert.equal(await reads('pubA', 'interestedPeople', 'rvA'), true);
  assert.equal(await reads('pubA', 'interestedPeople', 'rvB'), false);
  assert.equal(await reads('pubA', 'interestedPeople/rvA/visits', 'v1'), true);
  assert.equal(await reads('pubB', 'interestedPeople/rvA/visits', 'v1'), false);
  assert.equal(await reads('pubB', 'monthlyReports', 'rep-pubA'), false);
  assert.equal(await reads('adminA', 'monthlyReports', 'rep-pubA'), true);
  assert.equal(await reads('sa', 'monthlyReports', 'rep-pubA'), true);
  assert.equal(await reads('restricted', 'groups', 'gB'), true, 'inside the grant scope');
  assert.equal(await reads('restricted', 'groups', 'g1'), false);
  assert.equal(await reads('pubB', 'people', 'pubA'), true, 'people are readable by any signed-in user, like the rules');
  assert.equal(await reads('pubA', 'schedules', 's1'), true);
});
