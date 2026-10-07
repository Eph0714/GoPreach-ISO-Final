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
  assert.equal(await del('pubA', 'monthlyReports', 'rep-pubA'), false, 'a publisher cannot delete a record');
  assert.equal(await del('elderA', 'monthlyReports', 'rep-pubA'), false, 'a Regular Elder without a group cannot');
  assert.equal(await del('adminB', 'monthlyReports', 'rep-pubA'), false, 'another congregation cannot');
  assert.equal(await del('soA', 'monthlyReports', 'rep-pubA'), true, 'a congregation-wide role deletes within its own congregation');
  assert.equal(await del('sa', 'monthlyReports', 'rep-posted'), true, 'the Super Admin deletes anywhere');
});

test('publisher submission: submitted reports lock; access request, grant, reverse and resubmit', async () => {
  const own = (status, extra = {}) => ({ congregationId: 'congA', publisherPersonId: 'pubA', periodMonth: 1650000000000, status, bibleStudiesCount: 1, ...extra });
  await put('monthlyReports', 'lv1-sub', own('SUBMITTED'));
  await put('monthlyReports', 'lv1-req', own('ACCESS_REQUESTED', { accessRequestReason: 'typo' }));
  await put('monthlyReports', 'lv1-grant', own('ACCESS_GRANTED'));
  assert.equal(await set('pubA', 'monthlyReports', 'lv1-sub', own('SUBMITTED', { bibleStudiesCount: 9 })), false, 'a submitted report is locked for its publisher');
  assert.equal(await set('pubA', 'monthlyReports', 'lv1-sub', own('ACCESS_REQUESTED', { accessRequestReason: 'typo', accessRequestedAt: 5 })), true, 'the publisher may request access');
  assert.equal(await set('pubA', 'monthlyReports', 'lv1-sub', own('ACCESS_REQUESTED', { accessRequestReason: 'x', bibleStudiesCount: 50 })), false, 'the request cannot carry other changes');
  assert.equal(await set('pubA', 'monthlyReports', 'lv1-req', own('ACCESS_REQUESTED', { accessRequestReason: 'typo', bibleStudiesCount: 7 })), false, 'locked while the request is pending');
  assert.equal(await set('pubA', 'monthlyReports', 'lv1-req', own('ACCESS_GRANTED')), false, 'a publisher cannot grant themselves access');
  assert.equal(await set('soA', 'monthlyReports', 'lv1-req', own('ACCESS_GRANTED', { accessRequestReason: 'typo' })), true, 'the person in charge approves');
  assert.equal(await set('pubA', 'monthlyReports', 'lv1-grant', own('ACCESS_GRANTED', { bibleStudiesCount: 4 })), true, 'with access granted the publisher corrects');
  assert.equal(await set('pubA', 'monthlyReports', 'lv1-grant', own('CORRECTED', { bibleStudiesCount: 4 })), true, 'and submits again');
  assert.equal(await set('adminB', 'monthlyReports', 'lv1-sub', own('RETURNED')), false, 'another congregation cannot reverse');
  assert.equal(await set('adminA', 'monthlyReports', 'lv1-sub', own('RETURNED', { correctionReason: 'recount' })), true, 'the person in charge reverses a submission');
});

test('meeting attendance: managers write valid records, nobody else; frozen months are history', async () => {
  const SEP = 1788192000000; const DAY = 86400000;
  const date = SEP + 2 * DAY;
  const mid = (parts, extra = {}) => { const avg = (parts[0] + parts[1] + parts[2]) / 3; return { congregationId: 'congA', meetingType: 'MIDWEEK', meetingDate: date, serviceMonth: SEP, treasuresAttendance: parts[0], applyYourselfAttendance: parts[1], livingAsChristiansAttendance: parts[2], publicMeetingAttendance: null, watchtowerStudyAttendance: null, calculatedAverage: avg, officialAttendance: Math.floor(avg + 0.5), roundingMode: 'ROUNDED', deleted: false, updatedBy: '', ...extra }; };
  const id = `congA_MIDWEEK_${date}`;
  assert.equal(await set('soA', 'meetingAttendance', id, mid([79, 80, 85], { updatedBy: 'soA' })), true, 'a Service Overseer adds 79/80/85 → 81');
  assert.equal(await set('soA', 'meetingAttendance', id, mid([79, 80, 85], { updatedBy: 'soA', officialAttendance: 82 })), false, 'a wrong official figure');
  assert.equal(await set('soA', 'meetingAttendance', id, mid([79, -1, 85], { updatedBy: 'soA' })), false, 'a negative count');
  assert.equal(await set('soA', 'meetingAttendance', 'congA_dup', mid([70, 70, 70], { updatedBy: 'soA' })), false, 'a record under another id');
  assert.equal(await set('pubA', 'meetingAttendance', id, mid([79, 80, 85], { updatedBy: 'pubA' })), false, 'a publisher');
  assert.equal(await set('adminB', 'meetingAttendance', id, mid([79, 80, 85], { updatedBy: 'adminB' })), false, 'another congregation');
  await put('meetingAttendance', id, mid([79, 80, 85], { updatedBy: 'soA' }));
  assert.equal(await del('soA', 'meetingAttendance', id), false, 'never hard-deleted');
  assert.equal(await set('soA', 'meetingAttendance', id, mid([79, 80, 85], { updatedBy: 'soA', deleted: true })), true, 'a soft delete');
  await put('congregationMonthlyStatistics', `congA_${SEP}`, { congregationId: 'congA', serviceMonth: SEP, snapshotStatus: 'RECEIVED' });
  assert.equal(await set('soA', 'meetingAttendance', id, mid([79, 80, 85], { updatedBy: 'soA', remarks: 'late' })), false, 'a received month is frozen');
  assert.equal(await set('sa', 'meetingAttendance', id, mid([79, 80, 85], { updatedBy: 'sa', remarks: 'late' })), true, 'the Super Admin is not bound');
  assert.equal(await set('adminA', 'meetingAttendanceSettings', 'congA', { congregationId: 'congA', roundingMode: 'EXACT', updatedBy: 'adminA' }), true, 'a manager sets the rounding');
  assert.equal(await set('adminA', 'meetingAttendanceSettings', 'congA', { congregationId: 'congA', roundingMode: 'CEIL', updatedBy: 'adminA' }), false, 'an unknown mode');
  assert.equal(await set('pubA', 'meetingAttendanceSettings', 'congA', { congregationId: 'congA', roundingMode: 'EXACT', updatedBy: 'pubA' }), false, 'a publisher');
});

test('comparative reports: congregation drafts and sends, the overseer receives or returns, a received report is locked', async () => {
  await put('people', 'coC', person('CIRCUIT_OVERSEER', null));
  await put('userAccessGrants', 'coC', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congA'], circuitCode: 'NT01' });
  const jan = 1735689600000, mar = 1740787200000, apr = 1743465600000, jun = 1748736000000, far = 4070908800000;
  const id = `congA_${jan}_${mar}_${apr}_${jun}`;
  const draft = { congregationId: 'congA', reportNumber: 'CR-2026-0001', periodAStart: jan, periodAEnd: mar, periodBStart: apr, periodBEnd: jun, status: 'DRAFT', version: 1, reportSnapshot: '{}', createdBy: 'adminA', updatedAt: 1 };
  assert.equal(await set('pubA', 'congregationComparativeReports', id, { ...draft, createdBy: 'pubA' }), false, 'a publisher');
  assert.equal(await set('coC', 'congregationComparativeReports', id, { ...draft, createdBy: 'coC' }), false, 'the overseer');
  assert.equal(await set('adminB', 'congregationComparativeReports', id, { ...draft, createdBy: 'adminB' }), false, 'another congregation');
  assert.equal(await set('adminA', 'congregationComparativeReports', 'other', draft), false, 'the id is the periods');
  assert.equal(await set('adminA', 'congregationComparativeReports', `congA_${jan}_${mar}_${apr}_${far}`, { ...draft, periodBEnd: far }), false, 'a future month');
  assert.equal(await set('adminA', 'congregationComparativeReports', `congA_${jan}_${apr}_${mar}_${jun}`, { ...draft, periodAEnd: apr, periodBStart: mar }), false, 'overlapping periods');
  assert.equal(await set('adminA', 'congregationComparativeReports', id, draft), true, 'a manager drafts');
  await put('congregationComparativeReports', id, draft);
  assert.equal(await reads('coC', 'congregationComparativeReports', id), false, 'the overseer never sees a draft');
  assert.equal(await set('adminA', 'congregationComparativeReports', id, { ...draft, reportSnapshot: '{"x":1}', updatedAt: 2 }), true, 'a draft is editable');
  assert.equal(await set('adminA', 'congregationComparativeReports', id, { ...draft, status: 'RECEIVED', receivedBy: 'adminA', receivedAt: 3 }), false, 'a draft cannot jump to received');
  const sent = { ...draft, status: 'SUBMITTED', submittedBy: 'adminA', submittedByName: 'A', submittedAt: 3, updatedAt: 3 };
  assert.equal(await set('adminA', 'congregationComparativeReports', id, sent), true, 'send');
  await put('congregationComparativeReports', id, sent);
  assert.equal(await reads('coC', 'congregationComparativeReports', id), true, 'the overseer sees a sent report');
  assert.equal(await set('adminA', 'congregationComparativeReports', id, { ...sent, reportSnapshot: '{"x":2}' }), false, 'a sent report is read-only');
  assert.equal(await del('adminA', 'congregationComparativeReports', id), false, 'a sent report is not deletable');
  assert.equal(await set('coC', 'congregationComparativeReports', id, { ...sent, reportSnapshot: '{"x":2}', currentCoRemarks: 'x' }), false, 'the overseer never alters the snapshot');
  assert.equal(await set('coC', 'comparativeReportRemarks', 'r1', { comparativeReportId: id, congregationId: 'congA', authorUserId: 'coC', remark: 'Check July', createdAt: 4 }), true, 'a remark');
  assert.equal(await set('adminA', 'comparativeReportRemarks', 'r2', { comparativeReportId: id, congregationId: 'congA', authorUserId: 'adminA', remark: 'x', createdAt: 4 }), false, 'the congregation cannot remark');
  assert.equal(await set('coC', 'congregationComparativeReports', id, { ...sent, status: 'RETURNED', returnReason: '', returnedAt: 5 }), false, 'a return needs a reason');
  const returned = { ...sent, status: 'RETURNED', returnReason: 'July missing', returnedBy: 'coC', returnedAt: 5, updatedAt: 5 };
  assert.equal(await set('coC', 'congregationComparativeReports', id, returned), true, 'return');
  await put('congregationComparativeReports', id, returned);
  assert.equal(await set('adminA', 'congregationComparativeReports', id, { ...returned, reportSnapshot: '{"x":3}', updatedAt: 6 }), true, 'a returned report is corrected');
  assert.equal(await set('adminA', 'congregationComparativeReports', id, { ...returned, periodAStart: mar }), false, 'periods cannot change');
  assert.equal(await set('adminA', 'congregationComparativeReports', id, { ...returned, status: 'SUBMITTED', version: 1, submittedBy: 'adminA', submittedAt: 7 }), false, 'resubmitting bumps the version');
  const again = { ...returned, status: 'SUBMITTED', version: 2, submittedBy: 'adminA', submittedByName: 'A', submittedAt: 7, updatedAt: 7 };
  assert.equal(await set('adminA', 'congregationComparativeReports', id, again), true, 'resubmit');
  await put('congregationComparativeReports', id, again);
  const received = { ...again, status: 'RECEIVED', receivedBy: 'coC', receivedAt: 8, updatedAt: 8 };
  assert.equal(await set('coC', 'congregationComparativeReports', id, received), true, 'receive');
  await put('congregationComparativeReports', id, received);
  assert.equal(await set('adminA', 'congregationComparativeReports', id, { ...received, reportSnapshot: '{"x":4}' }), false, 'received: no edit');
  assert.equal(await set('adminA', 'congregationComparativeReports', id, { ...received, status: 'SUBMITTED', version: 3, submittedBy: 'adminA' }), false, 'received: no resubmit');
  assert.equal(await del('adminA', 'congregationComparativeReports', id), false, 'received: no delete');
  assert.equal(await set('coC', 'congregationComparativeReports', id, { ...received, status: 'RETURNED', returnReason: 'again' }), false, 'received: not returned again');
  assert.equal(await set('coC', 'comparativeReportRemarks', 'r3', { comparativeReportId: id, congregationId: 'congA', authorUserId: 'coC', remark: 'late', createdAt: 9 }), false, 'received: no remarks');
  assert.equal(await set('adminA', 'comparativeReportHistory', 'h1', { reportId: id, congregationId: 'congA', action: 'x', userId: 'adminA', at: 1 }), true, 'history as yourself');
  assert.equal(await set('adminA', 'comparativeReportHistory', 'h2', { reportId: id, congregationId: 'congA', action: 'x', userId: 'coC', at: 1 }), false, 'history as someone else');
});

test('active role scope: a Group Coordinator / Servant / Assistant writes only publishers of its own FS group', async () => {
  await put('people', 'grpElder', person('REGULAR_ELDER', 'congA', { activeGroupId: 'gG' }));
  await put('people', 'fakeGrp', person('REGULAR_ELDER', 'congA', { activeGroupId: 'gG' })); // claims the group but fills no slot
  await put('people', 'ceActing', person('COORDINATOR_ELDER', 'congA'));
  await put('groups', 'gG', { congregationId: 'congA', overseerPersonId: 'grpElder', status: 'ACTIVE' });
  await put('groups', 'gH', { congregationId: 'congA', overseerPersonId: 'someoneElse', status: 'ACTIVE' });
  await put('roleAssignments', 'raG', { personId: 'pubA', congregationId: 'congA', groupId: 'gG', status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  await put('roleAssignments', 'raH', { personId: 'pubA2', congregationId: 'congA', groupId: 'gH', status: 'ACTIVE', roleType: 'PUBLISHER:REGULAR_PUBLISHER' });
  const rec = (who, ra, extra = {}) => ({ congregationId: 'congA', publisherPersonId: who, periodMonth: 1700000000000, status: 'SUBMITTED', source: 'MANUAL', groupRoleAssignmentId: ra, ...extra });

  assert.equal(await set('grpElder', 'monthlyReports', 'g1', rec('pubA', 'raG')), true, 'a publisher of its own group');
  assert.equal(await set('grpElder', 'monthlyReports', 'g2', rec('pubA2', 'raH')), false, 'a publisher of another group');
  assert.equal(await set('grpElder', 'monthlyReports', 'g3', rec('pubA2', 'raG')), false, 'forging the assignment of another publisher');
  assert.equal(await set('grpElder', 'monthlyReports', 'g4', rec('pubA', null)), false, 'without the publisher assignment');
  assert.equal(await set('fakeGrp', 'monthlyReports', 'g5', rec('pubA', 'raG')), false, 'a person who fills no slot of the group');
  assert.equal(await set('elderA', 'monthlyReports', 'g6', rec('pubA', 'raG')), false, 'a Regular Elder without a group');
  assert.equal(await set('ceActing', 'monthlyReports', 'g7', rec('pubA2', null)), true, 'acting as Coordinator Elder: any publisher of the congregation');
  assert.equal(await del('grpElder', 'monthlyReports', 'g1'), true, 'deletes a stamped record of its own group');
  assert.equal(await del('ceActing', 'monthlyReports', 'g7'), true, 'a congregation-wide role deletes');
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

// ---- Circuit Overseer module ---------------------------------------------------------------------------------------

test('circuit codes and congregation links: Super Admin writes, everyone signed-in reads, nobody else writes', async () => {
  assert.equal(await set('sa', 'circuitCodes', 'NT01', { code: 'NT01', status: 'ACTIVE', overseerPersonId: null }), true);
  // set() only evaluates the rule; store the rows so the delete checks below act on real documents.
  await put('circuitCodes', 'NT01', { code: 'NT01', status: 'ACTIVE', overseerPersonId: null });
  await put('congregationCircuits', 'congA', { congregationId: 'congA', circuitOverseerPersonId: 'restricted', circuitCode: 'NT01' });
  assert.equal(await set('sa', 'congregationCircuits', 'congA', { congregationId: 'congA', circuitOverseerPersonId: 'restricted', circuitCode: 'NT01' }), true);
  for (const who of ['adminA', 'pubA', 'restricted', 'coordA']) {
    assert.equal(await set(who, 'circuitCodes', 'NT09', { code: 'NT09' }), false, `${who} cannot create a code`);
    assert.equal(await set(who, 'circuitCodes', 'NT01', { overseerPersonId: who }), false, `${who} cannot take over a code`);
    assert.equal(await del(who, 'circuitCodes', 'NT01'), false, `${who} cannot delete a code`);
    assert.equal(await set(who, 'congregationCircuits', 'congA', { circuitOverseerPersonId: who }), false, `${who} cannot move a congregation`);
    assert.equal(await del(who, 'congregationCircuits', 'congA'), false, `${who} cannot unassign a congregation`);
  }
  assert.equal(await reads('pubA', 'circuitCodes', 'NT01'), true);
  assert.equal(await reads('restricted', 'congregationCircuits', 'congA'), true);
});

test('roleAssignments: a grant-based account can always read its own role assignment, and only its own', async () => {
  await put('roleAssignments', 'ra-restricted', { personId: 'restricted', congregationId: null, roleType: 'ADMIN:CIRCUIT_OVERSEER' });
  assert.equal(await reads('restricted', 'roleAssignments', 'ra-restricted'), true);
  assert.equal(await reads('restricted', 'roleAssignments', 'ra1'), false, 'someone else\'s assignment outside its scope');
});

test('territory data: a grant-based account (Circuit Overseer) reads only its congregations; everyone else is unchanged', async () => {
  for (const [c, cong] of [['tB', 'congB'], ['tA', 'congA']]) {
    await put('territoryAssignments', `ta_${c}`, { congregationId: cong });
    await put('territoryAssignmentBarangays', `${cong}_1`, { congregationId: cong });
    await put('mapPins', `p_${c}`, { congregationId: cong, createdByPersonId: 'x' });
    await put('territoryDrawings', `d_${c}`, { congregationId: cong, userId: 'x', status: 'ACTIVE' });
    await put('territoryBounds', `${cong}_1`, { congregationId: cong });
  }
  for (const [col, idB, idA] of [
    ['territoryAssignments', 'ta_tB', 'ta_tA'], ['territoryAssignmentBarangays', 'congB_1', 'congA_1'], ['mapPins', 'p_tB', 'p_tA'],
    ['territoryDrawings', 'd_tB', 'd_tA'], ['territoryBounds', 'congB_1', 'congA_1'],
  ]) {
    assert.equal(await reads('restricted', col, idB), true, `${col}: inside the grant scope`);
    assert.equal(await reads('restricted', col, idA), false, `${col}: outside the grant scope`);
    assert.equal(await reads('pubA', col, idA), true, `${col}: a member of congA still reads it`);
  }
  assert.equal(await set('restricted', 'territoryDrawings', 'dNew', { congregationId: 'congB', userId: 'restricted', status: 'ACTIVE' }), false, 'a Circuit Overseer never draws');
  assert.equal(await set('restricted', 'mapPins', 'pNew', { congregationId: 'congB', createdByPersonId: 'restricted' }), false, 'nor pins');
});

// ---- Field Service Report workflow on the actual report ------------------------------------------------------------

test('month status: senders submit past months only, undo before receipt; the overseer receives / returns; nobody deletes', async () => {
  await put('people', 'coB', person('CIRCUIT_OVERSEER', null));
  await put('userAccessGrants', 'coB', { permissions: ['VIEW_CONGREGATIONS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congB'], circuitCode: 'NT01' });
  await put('people', 'soB', person('SERVICE_OVERSEER', 'congB'));
  await put('people', 'secB', person('SECRETARY', 'congB'));
  const month = 1790784000000;
  const FUTURE = 4102444800000;
  const id = `congB_${month}`;
  const fresh = { congregationId: 'congB', periodMonth: month, status: 'SUBMITTED', version: 1, submittedAt: 1 };

  assert.equal(await set('pubB', 'coFieldServiceMonthStatus', id, fresh), false, 'a plain publisher cannot send');
  assert.equal(await set('adminA', 'coFieldServiceMonthStatus', id, fresh), false, 'another congregation cannot send');
  assert.equal(await set('coB', 'coFieldServiceMonthStatus', id, fresh), false, 'the overseer cannot send');
  assert.equal(await set('adminB', 'coFieldServiceMonthStatus', 'wrong', fresh), false, 'the id must be congregation_month');
  assert.equal(await set('adminB', 'coFieldServiceMonthStatus', id, { ...fresh, status: 'RECEIVED' }), false, 'a first send is SUBMITTED');
  assert.equal(await set('adminB', 'coFieldServiceMonthStatus', `congB_${FUTURE}`, { ...fresh, periodMonth: FUTURE }), false, 'a future month cannot be sent');
  assert.equal(await set('adminB', 'coFieldServiceMonthStatus', id, fresh), true, 'an Admin sends a past month');
  await put('coFieldServiceMonthStatus', id, fresh);

  assert.equal(await set('secB', 'coFieldServiceMonthStatus', id, { ...fresh, status: 'NOT_SUBMITTED', coRemarks: null, undoneAt: 2 }), true, 'a sender undoes a SUBMITTED month');
  assert.equal(await set('pubB', 'coFieldServiceMonthStatus', id, { ...fresh, status: 'NOT_SUBMITTED', coRemarks: null }), false, 'a publisher cannot undo');
  assert.equal(await set('coB', 'coFieldServiceMonthStatus', id, { ...fresh, status: 'RECEIVED', receivedAt: 3, coRemarks: 'Complete' }), true, 'the overseer receives it');
  const received = { ...fresh, status: 'RECEIVED', receivedAt: 3, coRemarks: 'Complete' };
  await put('coFieldServiceMonthStatus', id, received);
  assert.equal(await set('adminB', 'coFieldServiceMonthStatus', id, { ...received, status: 'NOT_SUBMITTED', coRemarks: null }), false, 'the congregation cannot undo a RECEIVED month');
  assert.equal(await set('adminB', 'coFieldServiceMonthStatus', id, { ...received, status: 'RETURNED' }), false, 'the congregation cannot return a RECEIVED month');
  assert.equal(await set('coB', 'coFieldServiceMonthStatus', id, { ...received, coRemarks: 'Reviewed' }), true, 'the overseer edits the remarks');
  assert.equal(await set('coB', 'coFieldServiceMonthStatus', id, { ...received, submittedByName: 'forged' }), false, 'the overseer cannot touch submission fields');
  assert.equal(await set('coB', 'coFieldServiceMonthStatus', id, { ...received, status: 'RETURNED', returnedAt: 4, coRemarks: 'Check RV' }), true, 'the overseer returns it');
  const returned = { ...received, status: 'RETURNED', returnedAt: 4, coRemarks: 'Check RV' };
  await put('coFieldServiceMonthStatus', id, returned);
  assert.equal(await set('secB', 'coFieldServiceMonthStatus', id, { ...returned, status: 'SUBMITTED', version: 2, submittedAt: 5, coRemarks: 'forged' }), false, 'a re-send cannot forge remarks');
  assert.equal(await set('secB', 'coFieldServiceMonthStatus', id, { ...returned, status: 'SUBMITTED', version: 2, submittedAt: 5 }), true, 'the Secretary sends it again after a return');
  assert.equal(await del('adminB', 'coFieldServiceMonthStatus', id), false, 'never deleted');
  assert.equal(await set('coB', 'coFieldServiceReportEvents', 'e1', { reportId: id, congregationId: 'congB', userId: 'coB' }), true, 'the overseer logs its own action');
  assert.equal(await set('coB', 'coFieldServiceReportEvents', 'e2', { reportId: id, congregationId: 'congB', userId: 'adminB' }), false, 'events are written as yourself');
});

test('month lock and overseer visibility: Submitted / Received lock the records; the overseer sees only submitted months', async () => {
  const month = 1790784000000; // 1 Oct 2026 (UTC+8)
  const day = month + 5 * 86400000;
  const open = month + 40 * 86400000;
  const rec = { congregationId: 'congB', publisherPersonId: 'pubB', periodMonth: month, status: 'DRAFT' };
  await put('coFieldServiceMonthStatus', `congB_${month}`, { congregationId: 'congB', periodMonth: month, status: 'NOT_SUBMITTED', version: 1 });
  assert.equal(await set('pubB', 'monthlyReports', 'pubB_open', rec), true, 'a NOT_SUBMITTED month accepts the publisher');
  await put('coFieldServiceMonthStatus', `congB_${month}`, { congregationId: 'congB', periodMonth: month, status: 'SUBMITTED', version: 1 });
  assert.equal(await set('pubB', 'monthlyReports', 'pubB_sub', rec), false, 'a SUBMITTED month is locked for the publisher');
  assert.equal(await set('adminB', 'monthlyReports', 'pubB_man', { ...rec, source: 'MANUAL' }), false, 'and for the Secretary / Admin');
  assert.equal(await set('adminB', 'monthlyReports', 'pubB_other', { ...rec, periodMonth: open }), true, 'another month stays open');
  assert.equal(await set('sa', 'monthlyReports', 'pubB_sa', rec), true, 'the Super Admin is not bound');
  assert.equal(await set('pubB', 'preachingTimeRecords', 'pt1', { publisherPersonId: 'pubB', congregationId: 'congB', date: day, hoursConsumed: 1 }), false, 'hours of a locked month');
  assert.equal(await set('pubB', 'preachingTimeRecords', 'pt2', { publisherPersonId: 'pubB', congregationId: 'congB', date: open, hoursConsumed: 1 }), true, 'hours of an open month');
  assert.equal(await set('pubB', 'creditHourRecords', 'ch1', { publisherPersonId: 'pubB', dayStart: day, hours: 1 }), false, 'credit hours of a locked month');
  assert.equal(await set('pubB', 'plannerDays', 'pd1', { publisherPersonId: 'pubB', dayStart: day, totalMinutes: 30 }), false, 'a planner day of a locked month');
  await put('coFieldServiceMonthStatus', `congB_${month}`, { congregationId: 'congB', periodMonth: month, status: 'RETURNED', version: 1 });
  assert.equal(await set('pubB', 'monthlyReports', 'pubB_back', rec), true, 'a RETURNED month is open again');

  // visibility for the Circuit Overseer (RETURNED is still visible)
  await put('monthlyReports', 'act_1', { congregationId: 'congB', publisherPersonId: 'pubB', periodMonth: month, status: 'SUBMITTED' });
  await put('monthlyReports', 'act_2', { congregationId: 'congB', publisherPersonId: 'pubB', periodMonth: open, status: 'SUBMITTED' });
  assert.equal(await reads('coB', 'monthlyReports', 'act_1'), true, 'the overseer reads a submitted / returned month');
  assert.equal(await reads('coB', 'monthlyReports', 'act_2'), false, 'but not a month with no status');
  assert.equal(await reads('restricted', 'monthlyReports', 'act_1'), false, 'a grant outside the circuit does not');
});
