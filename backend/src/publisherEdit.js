import { loadActor, isWide, isSlotHolder, authorizeWrite, loadGrant } from './policy/index.js';
import { canManageCredentials, canManageCredentialsStrict, GROUP_PROFILE_FIELDS } from './policy/rules.js';
import { hashPassword } from './passwords.js';
import { newId } from './coAccess.js';

/**
 * ONE edit of a publisher record: personal details, FS Group, congregation, classification, username, account status and a new temporary password are
 * validated together and written in one transaction (all of it or none of it), then the password is set. The three things stay apart:
 *   - the publisher classification (Regular Pioneer, Publisher ... = the PUBLISHER:<category> role assignment),
 *   - the account's sign-in status (ACTIVE / INACTIVE / SUSPENDED = people.accountStatus),
 *   - the scope (the congregation and FS Group of the record).
 * Who may do what is decided here from the caller's ASSIGNED roles and the record's congregation and group, never from what the client says:
 *   Super Admin                                       everything, and the only one who may move a publisher to another congregation;
 *   Admin / Coordinator Elder / Service Overseer / Secretary of that congregation   everything inside their congregation (the sign-in fields follow the
 *                                                     credential rules: Admin for publishers, elders and servants, the others for publishers);
 *   Overseer / Servant / Assistant of the publisher's FS Group   personal details of that group's publishers only.
 * Everything else is refused. The same rules in rules.js guard the ordinary sync path, and every document is checked against them as well.
 */
export const CATEGORIES = [
  'REGULAR_PIONEER', 'SPECIAL_PIONEER', 'AUXILIARY_PIONEER', 'REGULAR_PUBLISHER', 'UNBAPTIZED_PUBLISHER', 'IRREGULAR_PUBLISHER',
  'INACTIVE_PUBLISHER', 'REPROOF_PUBLISHER', 'REMOVED_PUBLISHER',
];
export const ACCOUNT_STATUSES = ['ACTIVE', 'INACTIVE', 'SUSPENDED'];
const USERNAME_RE = /^[A-Za-z0-9._-]{3,40}$/;
const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

/** The personal details a publisher record keeps, with how each is checked (the same set the website and the app forms edit). */
const TEXT = { firstName: 80, lastName: 80, middleInitial: 3, extensionName: 10, address: 300, contact: 60, province: 100, cityMunicipality: 100, barangay: 100, contactPerson: 100, contactPersonNumber: 60, remarks: 500 };
const REQUIRED = ['firstName', 'lastName', 'address'];   // Contact, Province, Municipality and Barangay may be left blank (a value that is given is still checked)
export const PROFILE_KEYS = GROUP_PROFILE_FIELDS;

class Refused extends Error {
  constructor(status, message, field) { super(message); this.status = status; this.field = field; }
}
const refuse = (status, message, field) => { throw new Refused(status, message, field); };

/** The validated personal details of [input] (only the keys that were sent), or a Refused error naming the field. */
export function checkProfile(input, now = Date.now()) {
  const out = {};
  for (const [k, v] of Object.entries(input ?? {})) {
    if (!PROFILE_KEYS.includes(k)) refuse(422, `"${k}" is not a field of a publisher record`, k);
    if (k in TEXT) {
      if (v !== null && typeof v !== 'string') refuse(422, 'Enter text', k);
      const t = (v ?? '').trim();
      if (t.length > TEXT[k]) refuse(422, `At most ${TEXT[k]} characters`, k);
      if (REQUIRED.includes(k) && t === '') refuse(422, 'This field is required', k);
      out[k] = t === '' ? null : t;
    } else if (k === 'email') {
      const t = typeof v === 'string' ? v.trim() : '';
      if (t && (t.length > 120 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t))) refuse(422, 'Enter a valid email address', k);
      out[k] = t || null;
    } else if (k === 'gender') {
      if (v !== null && v !== '' && !['MALE', 'FEMALE'].includes(v)) refuse(422, 'Choose Male or Female', k);
      out[k] = v || null;
    } else if (k === 'birthdate' || k === 'baptismalDate') {
      if (v !== null && !(Number.isFinite(v) && v <= now + 86400000)) refuse(422, 'Enter a date that is not in the future', k);
      out[k] = v ?? null;
    } else {
      const lim = k === 'gpsLat' ? 90 : 180;
      if (v !== null && !(typeof v === 'number' && Number.isFinite(v) && Math.abs(v) <= lim)) refuse(422, 'Enter a valid coordinate', k);
      out[k] = v ?? null;
    }
  }
  return out;
}

const live = (row) => (row && !row.deleted ? row : null);

export function mountPublisherEdit(app, store, auth) {
  app.post('/v1/publishers/:personId/edit', auth, async (req, res) => {
    const id = req.params.personId;
    const body = req.body && typeof req.body === 'object' ? req.body : {};
    try {
      if (!ID_RE.test(id)) refuse(400, 'Bad request');
      // checked before anything is written, so a bad password never leaves half an edit behind
      const wantsPassword = body.password !== undefined && body.password !== null && body.password !== '';
      if (wantsPassword && (typeof body.password !== 'string' || body.password.length < 6 || body.password.length > 200)) refuse(422, 'The password must have 6 to 200 characters', 'password');
      const pwHash = wantsPassword ? await hashPassword(body.password) : null;
      if (wantsPassword && !(await store.getAccount(id))) refuse(404, 'This person has no sign-in account yet');

      const result = await store.transaction(async (tx) => {
        const actor = await loadActor(req.personId, tx.get, tx.list);
        if (!actor.known) refuse(403, 'Unknown user');
        const grant = await loadGrant(actor.personId, tx.get);
        const personRow = live(await tx.get('people', id));
        if (!personRow) {
          if (body.baseVersion != null) { const e = new Refused(409, 'This record was deleted on another device, so your change was not saved and the record was not recreated. If you need it back, restore it from Deleted Records.'); e.conflict = true; throw e; }
          refuse(404, 'This publisher was not found');
        }
        const person = personRow.data;
        const home = person.activeCongregationId ?? null;
        const assignmentRow = home == null ? null : (await tx.list('roleAssignments', home)).find((r) => r.data.personId === id && String(r.data.roleType ?? '').startsWith('PUBLISHER:'));
        if (!assignmentRow) refuse(404, 'This publisher was not found');
        const assignment = assignmentRow.data;
        const cong = assignment.congregationId;

        // ---- who is asking, and for which part of the record
        const sa = actor.isSuperAdmin;
        const wide = !sa && !grant.has && isWide(actor, cong);
        const slot = !sa && !wide && !grant.has && (await isSlotHolder(actor, assignment.groupId ?? null, cong, tx.get));
        if (!sa && !wide && !slot) refuse(403, 'You may not edit this publisher');
        const full = sa || wide;

        if (body.baseVersion != null && Number(body.baseVersion) !== personRow.version) {
          const e = new Refused(409, 'This record was changed on another device while you were editing, so nothing was saved. What you typed is still in the form: reload the page to see the latest version, then make your change again.'); e.conflict = true; throw e;
        }

        const touches = (k) => body[k] !== undefined;
        const personNext = { ...person };
        const changed = [];
        const audit = [];
        const log = (action, details, congregationId = cong) => audit.push({ action, details, congregationId });

        // ---- personal details
        const profile = touches('person') ? checkProfile(body.person) : {};
        if (profile.birthdate && profile.baptismalDate && profile.baptismalDate < profile.birthdate) refuse(422, 'The baptismal date cannot be before the birth date', 'baptismalDate');
        const lat = 'gpsLat' in profile ? profile.gpsLat : (person.gpsLat ?? null), lng = 'gpsLng' in profile ? profile.gpsLng : (person.gpsLng ?? null);
        if ((lat == null) !== (lng == null)) refuse(422, 'Give both the latitude and the longitude, or neither', 'gpsLat');
        const profileKeys = Object.keys(profile).filter((k) => (profile[k] ?? null) !== (person[k] ?? null));
        for (const k of profileKeys) personNext[k] = profile[k];
        if (profileKeys.length) changed.push('details');

        // ---- the parts only the congregation-wide roles may change
        let newCong = cong;
        if (touches('congregationId') && body.congregationId !== cong) {
          if (!sa) refuse(403, 'Only the Super Admin moves a publisher to another congregation', 'congregationId');
          const target = live(await tx.get('congregations', String(body.congregationId)));
          if (!target) refuse(422, 'That congregation does not exist', 'congregationId');
          newCong = String(body.congregationId);
        }
        const moving = newCong !== cong;
        let groupId = assignment.groupId ?? null;
        if (touches('groupId') || moving) {
          const wanted = touches('groupId') && body.groupId ? String(body.groupId) : null;
          if (!full) { if ((wanted ?? null) !== groupId) refuse(403, 'Only the Admin, Coordinator Elder, Service Overseer or Secretary moves a publisher between FS Groups', 'groupId'); }
          else {
            if (wanted !== null) {
              const g = live(await tx.get('groups', wanted));
              if (!g || g.data.congregationId !== newCong || (g.data.status ?? 'ACTIVE') !== 'ACTIVE') refuse(422, 'That FS Group does not belong to the congregation', 'groupId');
            }
            groupId = moving && !touches('groupId') ? null : wanted;
          }
        }
        let roleType = assignment.roleType;
        if (touches('category')) {
          const category = String(body.category);
          if (!CATEGORIES.includes(category)) refuse(422, 'That is not a publisher classification', 'category');
          if (!full) refuse(403, 'Only the Admin, Coordinator Elder, Service Overseer or Secretary changes the classification', 'category');
          roleType = `PUBLISHER:${category}`;
        }

        // ---- the sign-in: username, account status, a new temporary password (the credential rules decide who)
        const wantsUsername = touches('username') && String(body.username ?? '').trim() !== String(person.username ?? '');
        const wantsStatus = touches('accountStatus') && body.accountStatus !== (person.accountStatus ?? 'ACTIVE');
        // The congregation roles manage sign-ins as before. An Overseer, Servant or Assistant of the publisher's own FS Group may change the username and set a temporary password of a plain
        // publisher of that group (never whether the account may be used, and the password is never kept readable).
        const strictCredentials = wantsUsername || wantsStatus || wantsPassword ? await canManageCredentialsStrict(actor, grant, id, tx.get) : false;
        const groupCredentials = !strictCredentials && (wantsUsername || wantsPassword) && !wantsStatus ? await canManageCredentials(actor, grant, id, tx.get) : false;
        if (wantsUsername || wantsStatus || wantsPassword) {
          if (!strictCredentials && !groupCredentials) refuse(403, 'You may not change the sign-in of this person', wantsUsername ? 'username' : wantsStatus ? 'accountStatus' : 'password');
        }
        if (wantsUsername) {
          const username = String(body.username).trim();
          if (!USERNAME_RE.test(username)) refuse(422, 'A username has 3 to 40 letters, numbers, dots, dashes or underscores', 'username');
          const taken = (await tx.list('people')).some((r) => r.id !== id && String(r.data.username ?? '').toLowerCase() === username.toLowerCase());
          if (taken) refuse(409, 'That username is already taken', 'username');
          log('ACCOUNT_MGMT_USERNAME_CHANGE', `username: "${person.username ?? ''}" -> "${username}"`);
          personNext.username = username; changed.push('username');
        }
        if (wantsStatus) {
          if (!ACCOUNT_STATUSES.includes(body.accountStatus)) refuse(422, 'Choose Active, Inactive or Suspended', 'accountStatus');
          log('ACCOUNT_MGMT_STATUS_CHANGE', `status: ${person.accountStatus ?? 'ACTIVE'} -> ${body.accountStatus}`);
          personNext.accountStatus = body.accountStatus; changed.push('accountStatus');
        }
        if (wantsPassword) {
          // the person must choose their own at the next sign-in; a Super Admin's temporary password is never kept readable
          personNext.isTemporaryCredential = true;
          personNext.temporaryPassword = sa || groupCredentials ? null : body.password;
          log('ACCOUNT_MGMT_PASSWORD_RESET', 'temporary password set (the password itself is never recorded)');
          changed.push('password');
        }
        if (moving) personNext.activeCongregationId = newCong;

        // ---- every document is also checked against the rules of the ordinary write path
        const assignmentNext = { ...assignment, roleType, groupId, congregationId: newCong };
        const assignmentChanged = roleType !== assignment.roleType || groupId !== (assignment.groupId ?? null) || moving;
        if (assignmentChanged) {
          assignmentNext.lastEditedByPersonId = actor.personId; assignmentNext.lastEditedAt = Date.now();
          if (roleType !== assignment.roleType) { log('PUBLISHER_CLASSIFICATION_CHANGE', `classification: ${assignment.roleType.slice(10)} -> ${roleType.slice(10)}`); changed.push('classification'); }
          if (moving) { log('CHANGE_PUBLISHER_CONGREGATION', `from ${cong} to ${newCong}`, newCong); changed.push('congregation'); }
          else if (groupId !== (assignment.groupId ?? null)) { log('CHANGE_PUBLISHER_GROUP', `from ${assignment.groupId ?? 'unassigned'} to ${groupId ?? 'unassigned'}`); changed.push('group'); }
          if (!sa) {
            const v = await authorizeWrite(actor, 'set', 'roleAssignments', assignmentRow.id, assignmentNext, assignmentRow, tx.get);
            if (!v.ok) refuse(403, v.reason ?? 'Not allowed');
          }
          await tx.put('roleAssignments', assignmentRow.id, assignmentNext, actor.personId);
        }
        const personChanged = JSON.stringify(personNext) !== JSON.stringify(person);
        if (personChanged) {
          if (!sa) {
            const v = await authorizeWrite(actor, 'set', 'people', id, personNext, personRow, tx.get);
            if (!v.ok) refuse(403, v.reason ?? 'Not allowed');
          }
          await tx.put('people', id, personNext, actor.personId);
        }
        if (changed.length) log('PUBLISHER_RECORD_EDIT', `changed: ${changed.join(', ')}`);
        for (const a of audit) {
          await tx.put('auditLog', newId(), { actorPersonId: actor.personId, action: a.action, targetType: 'Person', targetId: id, congregationId: a.congregationId, timestamp: Date.now(), details: a.details.slice(0, 300) }, actor.personId);
        }
        return { changed };
      });

      // the sign-in itself: hashed by the server, the person's other sessions end (the same as the Account Management reset)
      if (wantsPassword) { await store.setPassword(id, pwHash); await store.deleteRefreshTokens(id); await store.endSession(id); }
      res.json({ status: 'success', changed: result.changed });
    } catch (e) {
      if (e instanceof Refused) return res.status(e.status).json({ status: e.conflict ? 'conflict' : 'error', message: e.message, error: e.message, field: e.field ?? null });
      console.error('publisher edit failed', e);
      res.status(500).json({ status: 'error', message: 'Server error' });
    }
  });
}
