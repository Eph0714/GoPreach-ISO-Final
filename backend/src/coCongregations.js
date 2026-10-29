import express from 'express';
import { coAccess, newId, ID_RE } from './coAccess.js';

/**
 * Congregation Management for the Circuit Overseer: enrol a congregation under their OWN account, list the congregations they hold, and edit their details.
 *
 * Everything here is decided on the server, from the signed-in account alone:
 *  - the caller must be a Circuit Overseer (an access grant that carries a Circuit Code), not a Super-Admin (who has the full module already), with an active
 *    account and an active Circuit Code that really belongs to them;
 *  - the Circuit Overseer a congregation is given to is always the caller. A request that names another overseer is refused, never "corrected";
 *  - one transaction creates the congregation, its Circuit link and the widened scope of the overseer's own grant, so there is no half-enrolled congregation;
 *  - a congregation of another overseer answers 404 for list, read and edit, whatever id is sent.
 * The write rules of the general sync (rules.js) are untouched: a Circuit Overseer's grant is still view-only, so only this route can add a congregation for them.
 */
const MAX_LANGUAGES = 10;
const CODE_RE = /^[A-Z0-9][A-Z0-9 _.-]{0,29}$/;
const norm = (s) => String(s ?? '').trim().toUpperCase().replace(/\s+/g, ' ');
const text = (v, max) => String(v ?? '').trim().replace(/\s+/g, ' ').slice(0, max);

/** The cleaned fields of a request, or { error, field }. `partial` (an edit) leaves out the code, which a Circuit Overseer never changes. */
function readFields(body, { partial }) {
  const name = norm(body?.name).slice(0, 120);
  const province = text(body?.province, 100);
  const cityMunicipality = text(body?.cityMunicipality, 100);
  const barangay = text(body?.barangay, 100);
  if (!name) return { error: 'The congregation name is required.', field: 'name' };
  if (!province) return { error: 'Choose the province or city.', field: 'province' };
  if (!cityMunicipality) return { error: 'Choose the municipality or city.', field: 'cityMunicipality' };
  if (!barangay) return { error: 'Choose the barangay.', field: 'barangay' };
  const languages = [];
  for (const l of Array.isArray(body?.languages) ? body.languages : []) {
    const v = text(l, 40);
    if (v && !languages.some((x) => x.toLowerCase() === v.toLowerCase())) languages.push(v);
  }
  if (languages.length > MAX_LANGUAGES) return { error: `At most ${MAX_LANGUAGES} languages.`, field: 'languages' };
  const out = { name, province, cityMunicipality, barangay, languages, address: [barangay, cityMunicipality, province].join(', ') };
  if (!partial) {
    const code = norm(body?.code);
    if (!code) return { error: 'The congregation code is required.', field: 'code' };
    if (!CODE_RE.test(code)) return { error: 'The code may use letters, numbers, spaces, dots, dashes and underscores (up to 30 characters).', field: 'code' };
    out.code = code;
  }
  return out;
}

const where = (c) => [norm(c.name), norm(c.province), norm(c.cityMunicipality), norm(c.barangay)].join('|');

/** The congregation as the app shows it (never another overseer's data: callers only pass their own rows). */
const out = (id, c, link) => ({
  id, name: c.name ?? '', code: c.code ?? '', province: c.province ?? null, cityMunicipality: c.cityMunicipality ?? null, barangay: c.barangay ?? null, address: c.address ?? '',
  languages: c.languages ?? [], status: c.status ?? 'ACTIVE', createdAt: c.createdAt ?? 0, createdByPersonId: c.createdByPersonId ?? '', enrolledByPersonId: c.enrolledByPersonId ?? null,
  circuitCode: link?.circuitCode ?? null, assignedAt: link?.assignedAt ?? null,
});

export function mountCoCongregations(app, store, auth) {
  const router = express.Router();
  const json = express.json({ limit: '20kb' });
  const refuse = (res, status, error, field) => res.status(status).json(field ? { error, field } : { error });

  /** Who is calling and may they enrol: a Circuit Overseer with an active account and an active Circuit Code of their own. */
  async function overseer(req, res) {
    const access = await coAccess(store, req.personId);
    if (!access.allowed || !access.isCo || access.isSuper) { refuse(res, 403, 'Congregation Management is for the Circuit Overseer.'); return null; }
    const person = (await store.get('people', req.personId))?.data ?? {};
    if (person.isDeleted === true || (person.accountStatus ?? 'ACTIVE') !== 'ACTIVE') { refuse(res, 403, 'Your account is not active, so a congregation cannot be enrolled.'); return null; }
    const code = access.grant.circuitCode;
    const doc = (await store.get('circuitCodes', code))?.data;
    if (!doc || doc.status !== 'ACTIVE' || doc.overseerPersonId !== req.personId) { refuse(res, 403, 'You have no active Circuit Code assigned to you, so a congregation cannot be enrolled. Ask the Super Admin.'); return null; }
    return { me: req.personId, code, grant: access.grant };
  }

  /** The congregations this overseer holds: the Circuit link names them AND their grant covers them. */
  async function mine(me) {
    const links = new Map((await store.list('congregationCircuits')).filter((l) => l.data.circuitOverseerPersonId === me).map((l) => [l.id, l.data]));
    const rows = (await store.list('congregations')).filter((r) => links.has(r.id));
    return rows.map((r) => ({ id: r.id, data: r.data, link: links.get(r.id) }));
  }

  /** GET /v1/co/congregations?q=  ->  { congregations }: only this overseer's, newest first (search over name, code and place). */
  router.get('/', async (req, res) => {
    try {
      const who = await overseer(req, res); if (!who) return;
      const q = norm(req.query.q);
      const rows = (await mine(who.me)).map((r) => out(r.id, r.data, r.link))
        .filter((c) => !q || [c.name, c.code, c.province, c.cityMunicipality, c.barangay].some((v) => norm(v).includes(q)))
        .sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0));
      return res.json({ congregations: rows });
    } catch (e) { console.error('co congregations list failed', e); return refuse(res, 500, 'Server error'); }
  });

  /** GET /v1/co/congregations/:id -> one of this overseer's congregations (404 for anyone else's). */
  router.get('/:id', async (req, res) => {
    try {
      const who = await overseer(req, res); if (!who) return;
      const row = (await mine(who.me)).find((r) => r.id === req.params.id);
      return row ? res.json(out(row.id, row.data, row.link)) : refuse(res, 404, 'Congregation not found');
    } catch (e) { console.error('co congregation read failed', e); return refuse(res, 500, 'Server error'); }
  });

  /** POST /v1/co/congregations  { name, code, province, cityMunicipality, barangay, languages?, requestId? }  ->  the new congregation (201). */
  router.post('/', json, async (req, res) => {
    try {
      const who = await overseer(req, res); if (!who) return;
      const body = req.body ?? {};
      // The owner is never taken from the request. Naming anyone else is refused outright (naming yourself changes nothing).
      for (const k of ['circuitOverseerPersonId', 'assignedToPersonId', 'assignedCoPersonId', 'overseerPersonId', 'coPersonId', 'circuitCode', 'enrolledByPersonId', 'createdByPersonId']) {
        if (body[k] != null && body[k] !== '' && body[k] !== who.me && body[k] !== who.code) return refuse(res, 403, 'A congregation can only be enrolled under your own Circuit Overseer account.');
      }
      const f = readFields(body, { partial: false });
      if (f.error) return refuse(res, 422, f.error, f.field);
      const requestId = body.requestId == null ? null : String(body.requestId);
      if (requestId != null && !ID_RE.test(requestId)) return refuse(res, 422, 'Bad request id.', 'requestId');
      const id = requestId ? `c${requestId}` : newId();
      const now = Date.now();

      const result = await store.transaction(async (tx) => {
        const existingNow = await tx.get('congregations', id);
        if (existingNow && !existingNow.deleted) {
          // the same enrolment sent again (the answer was lost, or the button was pressed twice): hand back what is there, add nothing
          if (existingNow.data.createdByPersonId === who.me) return { status: 200, body: { ...out(id, existingNow.data, (await tx.get('congregationCircuits', id))?.data), duplicate: true } };
          return { status: 409, body: { error: 'This request id is already used.' } };
        }
        // duplicates: the same code, or the same name in the same barangay, among ALL congregations (the message does not say whose)
        for (const r of await tx.list('congregations')) {
          if (norm(r.data.code) === f.code || where(r.data) === where(f)) return { status: 409, body: { error: 'A congregation with this code, or this name in this barangay, is already enrolled.', field: norm(r.data.code) === f.code ? 'code' : 'name' } };
        }
        const congregation = {
          id, name: f.name, address: f.address, province: f.province, cityMunicipality: f.cityMunicipality, barangay: f.barangay, code: f.code, languages: f.languages,
          createdAt: now, createdByPersonId: who.me, status: 'ACTIVE', enrolledByPersonId: who.me, enrolledByCircuitCode: who.code,
        };
        const link = { id, congregationId: id, circuitOverseerPersonId: who.me, circuitCode: who.code, assignedAt: now, assignedByPersonId: who.me };
        const grantNow = (await tx.get('userAccessGrants', who.me))?.data ?? {};
        const scope = Array.isArray(grantNow.scopeCongregationIds) ? grantNow.scopeCongregationIds : [];
        await tx.put('congregations', id, congregation, who.me);
        await tx.put('congregationCircuits', id, link, who.me);
        await tx.put('userAccessGrants', who.me, grantNow.scopeType === 'ALL_CONGREGATIONS' ? grantNow : { ...grantNow, scopeCongregationIds: [...new Set([...scope, id])], lastEditedByPersonId: who.me, lastEditedAt: now }, who.me);
        await tx.put('auditLog', newId(), { actorPersonId: who.me, action: 'CO_ENROLL_CONGREGATION', targetType: 'Congregation', targetId: id, congregationId: id, timestamp: now, details: `${f.name} (${f.code}) under ${who.code}` }, who.me);
        return { status: 201, body: out(id, congregation, link) };
      });
      return res.status(result.status).json(result.body);
    } catch (e) { console.error('co congregation enrol failed', e); return refuse(res, 500, 'Server error'); }
  });

  /** POST /v1/co/congregations/:id  { name, province, cityMunicipality, barangay, languages? } -> the updated congregation. The code, status and owner never change here. */
  router.post('/:id', json, async (req, res) => {
    try {
      const who = await overseer(req, res); if (!who) return;
      const body = req.body ?? {};
      for (const k of ['circuitOverseerPersonId', 'assignedToPersonId', 'overseerPersonId', 'coPersonId', 'circuitCode', 'status', 'code', 'enrolledByPersonId', 'createdByPersonId']) {
        if (body[k] != null && body[k] !== '') return refuse(res, 403, 'A Circuit Overseer edits the name, place and languages of their congregation only. The code, status and assigned overseer are managed by the Super Admin.');
      }
      const row = (await mine(who.me)).find((r) => r.id === req.params.id);
      if (!row) return refuse(res, 404, 'Congregation not found');
      if ((row.data.status ?? 'ACTIVE') !== 'ACTIVE') return refuse(res, 409, 'This congregation is inactive. The Super Admin reactivates it before it can be edited.');
      const f = readFields(body, { partial: true });
      if (f.error) return refuse(res, 422, f.error, f.field);
      const result = await store.transaction(async (tx) => {
        const cur = await tx.get('congregations', row.id);
        const link = (await tx.get('congregationCircuits', row.id))?.data;
        if (!cur || cur.deleted || link?.circuitOverseerPersonId !== who.me) return { status: 404, body: { error: 'Congregation not found' } };   // re-checked inside the transaction
        for (const r of await tx.list('congregations')) {
          if (r.id !== row.id && where(r.data) === where(f)) return { status: 409, body: { error: 'Another congregation with this name is already enrolled in this barangay.', field: 'name' } };
        }
        const next = { ...cur.data, name: f.name, address: f.address, province: f.province, cityMunicipality: f.cityMunicipality, barangay: f.barangay, languages: f.languages };
        await tx.put('congregations', row.id, next, who.me);
        await tx.put('auditLog', newId(), { actorPersonId: who.me, action: 'CO_EDIT_CONGREGATION', targetType: 'Congregation', targetId: row.id, congregationId: row.id, timestamp: Date.now(), details: `${f.name}` }, who.me);
        return { status: 200, body: out(row.id, next, link) };
      });
      return res.status(result.status).json(result.body);
    } catch (e) { console.error('co congregation edit failed', e); return refuse(res, 500, 'Server error'); }
  });

  app.use('/v1/co/congregations', auth, router);
}
