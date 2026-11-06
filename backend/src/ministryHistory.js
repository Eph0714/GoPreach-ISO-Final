import { loadActor } from './policy/index.js';
import { recordVisibility, groupMembersOf } from './ministryScope.js';

/**
 * Ministry History: a record of everything done in the Ministry (Searching, Return Visit, Bible Study): enrolling a record, logging / editing / deleting a visit,
 * moving a record between lists, assigning or forwarding it, capturing its location, editing it, moving it to the Recycle Bin and back. It is written HERE,
 * by the server, at the moment the app or the website saves the record or the visit (one place, so it is the same for both and cannot be skipped or edited
 * by a client). `ministryHistory` documents are never writable or readable through the sync; the apps read them through GET /v1/ministry/history.
 */
export const HISTORY = 'ministryHistory';
const VISITS_RE = /^interestedPeople\/([A-Za-z0-9_-]{1,100})\/visits$/;
const TZ = 'Asia/Manila';

const FIELDS = {
  name: 'Name', gender: 'Gender', address: 'Place of Origin', province: 'Province', cityMunicipality: 'Municipality / City', barangay: 'Barangay',
  spouse: 'Spouse', children: 'Children', ageYears: 'Age', language: 'Language', literaturePlace: 'Literature Place', religion: 'Religion',
  contact: 'Contact', notes: 'Notes', remarks: 'Remarks',
};
const STAGES = { SEARCHING: 'Searching', RETURN_VISIT: 'Return Visit', BIBLE_STUDY: 'Bible Study' };
const stageLabel = (s) => STAGES[s] ?? 'Searching';
const human = (s) => String(s ?? '').toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
const show = (v) => (v === null || v === undefined || v === '' ? '—' : String(v));
const when = (ms) => (Number.isFinite(ms) ? new Date(ms).toLocaleString('en-PH', { timeZone: TZ, year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }) : '—');
const day = (ms) => (Number.isFinite(ms) ? new Date(ms).toLocaleDateString('en-PH', { timeZone: TZ, year: 'numeric', month: 'short', day: 'numeric' }) : '—');
const fullName = (p) => [p?.firstName, p?.lastName].filter(Boolean).join(' ').trim();
const same = (a, b) => (a ?? null) === (b ?? null) || (a ?? '') === (b ?? '');

async function personName(tx, id) {
  if (!id) return null;
  const p = await tx.get('people', id);
  return p && !p.deleted ? (fullName(p.data) || null) : null;
}

/** What a visit log holds, as label/value rows. */
function visitDetails(v) {
  const rows = [];
  rows.push({ label: 'Visit date', value: when(v.visitTime ?? v.visitDate) });
  if (v.outcome) rows.push({ label: 'Outcome', value: human(v.outcome) });
  if (v.topicDiscussed) rows.push({ label: 'Topic discussed', value: v.topicDiscussed });
  if (Number.isFinite(v.timeConsumedMinutes) && v.timeConsumedMinutes > 0) rows.push({ label: 'Time spent', value: `${Math.floor(v.timeConsumedMinutes / 60)}h ${v.timeConsumedMinutes % 60}m` });
  if (v.followUpDate) rows.push({ label: 'Follow-up', value: day(v.followUpDate) });
  if (Number.isFinite(v.visitLat) && Number.isFinite(v.visitLng)) rows.push({ label: 'Location', value: `${v.visitLat.toFixed(5)}, ${v.visitLng.toFixed(5)}` });
  return rows;
}

const VISIT_FIELDS = {
  visitDate: ['Visit date', when], visitTime: null, outcome: ['Outcome', human], topicDiscussed: ['Topic discussed', show],
  timeConsumedMinutes: ['Time spent (minutes)', show], followUpDate: ['Follow-up', day],
};

/** The events one saved document stands for: [{ type, category, summary, details }] (empty when nothing worth recording changed). */
async function eventsFor(tx, { collection, id, before, after }) {
  const visits = VISITS_RE.exec(collection);
  if (visits) {
    if (!before && after) return [{ type: 'VISIT_LOGGED', category: 'VISIT', summary: 'Visit logged', details: visitDetails(after) }];
    if (before && !after) return [{ type: 'VISIT_DELETED', category: 'VISIT', summary: 'Visit deleted', details: visitDetails(before) }];
    const changes = [];
    for (const [k, def] of Object.entries(VISIT_FIELDS)) {
      if (!def || same(before[k], after[k])) continue;
      changes.push({ label: def[0], from: def[1](before[k]), to: def[1](after[k]) });
    }
    return changes.length ? [{ type: 'VISIT_EDITED', category: 'VISIT', summary: 'Visit edited', details: [...visitDetails(after), ...changes.map((c) => ({ label: `Changed: ${c.label}`, value: `${c.from} → ${c.to}` }))] }] : [];
  }
  // a Searching / Return Visit / Bible Study record
  if (!before && after) {
    const rows = [{ label: 'List', value: stageLabel(after.pipelineStage) }];
    for (const k of ['province', 'cityMunicipality', 'barangay']) if (after[k]) rows.push({ label: FIELDS[k], value: after[k] });
    if (after.contact) rows.push({ label: 'Contact', value: after.contact });
    const owner = await personName(tx, after.publisherPersonId);
    if (owner) rows.push({ label: 'Assigned publisher', value: owner });
    if (Number.isFinite(after.gpsLat) && Number.isFinite(after.gpsLng)) rows.push({ label: 'Location', value: `${after.gpsLat.toFixed(5)}, ${after.gpsLng.toFixed(5)}` });
    return [{ type: 'ENROLLED', category: 'ENROLLED', summary: `Enrolled a new ${stageLabel(after.pipelineStage)} record`, details: rows }];
  }
  if (before && !after) return [{ type: 'DELETED', category: 'CHANGE', summary: 'Record deleted permanently', details: [{ label: 'List', value: stageLabel(before.pipelineStage) }] }];

  const out = [];
  if (!same(before.pipelineStage, after.pipelineStage)) {
    out.push({ type: 'MOVED', category: 'CHANGE', summary: `Moved from ${stageLabel(before.pipelineStage)} to ${stageLabel(after.pipelineStage)}`, details: [{ label: 'From', value: stageLabel(before.pipelineStage) }, { label: 'To', value: stageLabel(after.pipelineStage) }] });
  }
  if (!same(before.status ?? 'ACTIVE', after.status ?? 'ACTIVE')) {
    const archived = (after.status ?? 'ACTIVE') !== 'ACTIVE';
    out.push({ type: archived ? 'ARCHIVED' : 'RESTORED', category: 'CHANGE', summary: archived ? 'Moved to the Recycle Bin' : 'Restored from the Recycle Bin', details: [{ label: 'Status', value: `${human(before.status ?? 'ACTIVE')} → ${human(after.status ?? 'ACTIVE')}` }] });
  }
  if (!same(before.congregationId, after.congregationId)) {
    out.push({ type: 'TRANSFERRED', category: 'CHANGE', summary: 'Transferred to another congregation', details: [] });
  } else if (!same(before.publisherPersonId, after.publisherPersonId)) {
    const [from, to] = await Promise.all([personName(tx, before.publisherPersonId), personName(tx, after.publisherPersonId)]);
    out.push({ type: 'ASSIGNED', category: 'CHANGE', summary: to ? `Assigned to ${to}` : 'Left unassigned', details: [{ label: 'From', value: from ?? 'Unassigned' }, { label: 'To', value: to ?? 'Unassigned' }] });
  }
  if (!before.pendingForwardRequestId && after.pendingForwardRequestId) {
    out.push({ type: 'FORWARD_REQUESTED', category: 'CHANGE', summary: 'Forward requested', details: [] });
  }
  const moved = !same(before.gpsLat, after.gpsLat) || !same(before.gpsLng, after.gpsLng);
  if (moved) {
    const has = Number.isFinite(after.gpsLat) && Number.isFinite(after.gpsLng);
    out.push({ type: 'LOCATION', category: 'CHANGE', summary: has ? 'Location captured or updated' : 'Location removed', details: has ? [{ label: 'Coordinates', value: `${after.gpsLat.toFixed(6)}, ${after.gpsLng.toFixed(6)}` }] : [] });
  }
  const edits = [];
  for (const [k, label] of Object.entries(FIELDS)) if (!same(before[k], after[k])) edits.push({ label, value: `${show(before[k])} → ${show(after[k])}` });
  if (edits.length) out.push({ type: 'EDITED', category: 'CHANGE', summary: 'Record details edited', details: edits });
  return out;
}

/**
 * Called inside the transaction of a push op, after the document was written: records what that write meant. Never throws (a history problem must not
 * undo a real save). [version] makes the entry ids unique and repeatable per saved version.
 */
export async function recordMinistryEvents(tx, { collection, id, op, before, after, version, actorId, at = Date.now() }) {
  try {
    const visits = VISITS_RE.exec(collection);
    if (collection !== 'interestedPeople' && !visits) return;
    const recordId = visits ? visits[1] : id;
    const live = await tx.get('interestedPeople', recordId);
    const record = live && !live.deleted ? live.data : (collection === 'interestedPeople' ? (after ?? before) : null);
    if (!record) return;
    const events = await eventsFor(tx, { collection, id, before: before ?? null, after: op === 'delete' ? null : after });
    if (!events.length) return;
    const actorName = await personName(tx, actorId);
    for (const [i, e] of events.entries()) {
      const entryId = `${collection.replace(/\//g, '_')}_${id}_v${version}_${i}`;
      await tx.put(HISTORY, entryId, {
        id: entryId, ...e, at, actorPersonId: actorId, actorName,
        recordId, recordName: record.name ?? '', stage: record.pipelineStage ?? 'SEARCHING', ownerId: record.publisherPersonId ?? '', congregationId: record.congregationId ?? null,
        visitId: visits ? id : null,
      }, actorId);
    }
  } catch (e) {
    console.error('ministry history failed', e?.message ?? e);
  }
}

// ---- the one-time backfill: records and visits that exist from before the History ----------------------------------------------------------------------------
const filling = new Map();

async function backfill(store, congregation) {
  if (await store.get('ministryHistoryBackfill', congregation)) return;
  if (filling.has(congregation)) return filling.get(congregation);
  const run = (async () => {
    const records = await store.list('interestedPeople', congregation);
    const entries = [];
    for (const r of records) {
      const d = r.data ?? {};
      const base = { actorName: null, recordId: r.id, recordName: d.name ?? '', stage: d.pipelineStage ?? 'SEARCHING', ownerId: d.publisherPersonId ?? '', congregationId: d.congregationId ?? congregation };
      const creator = d.createdByPersonId ?? d.publisherPersonId ?? '';
      entries.push({ id: `bf_enrolled_${r.id}`, type: 'ENROLLED', category: 'ENROLLED', summary: `Enrolled a new ${stageLabel(d.pipelineStage)} record`, at: d.createdAt ?? 0, actorPersonId: creator, details: [{ label: 'List', value: stageLabel(d.pipelineStage) }, ...['province', 'cityMunicipality', 'barangay'].filter((k) => d[k]).map((k) => ({ label: FIELDS[k], value: d[k] }))], visitId: null, ...base });
      for (const v of await store.list(`interestedPeople/${r.id}/visits`)) {
        const vd = v.data ?? {};
        entries.push({ id: `bf_visit_${r.id}_${v.id}`, type: 'VISIT_LOGGED', category: 'VISIT', summary: 'Visit logged', at: vd.createdAt ?? vd.visitTime ?? vd.visitDate ?? 0, actorPersonId: vd.createdByPersonId ?? vd.publisherPersonId ?? '', details: visitDetails(vd), visitId: v.id, ...base });
      }
    }
    const names = new Map();
    await store.transaction(async (tx) => {
      for (const e of entries) {
        if (e.actorPersonId && !names.has(e.actorPersonId)) names.set(e.actorPersonId, await personName(tx, e.actorPersonId));
        await tx.put(HISTORY, e.id, { ...e, actorName: names.get(e.actorPersonId) ?? null }, 'system');
      }
      await tx.put('ministryHistoryBackfill', congregation, { congregationId: congregation, at: Date.now(), count: entries.length }, 'system');
    });
  })().finally(() => filling.delete(congregation));
  filling.set(congregation, run);
  return run;
}

const TYPES = new Set(['ENROLLED', 'VISIT', 'CHANGE']);

export function mountMinistryHistory(app, store, auth) {
  /** GET ?scope=0|1|2 (My / FS Group / Congregation) &type=ENROLLED|VISIT|CHANGE &stage= &q= &before=<ms> &limit= &congregationId= (Super Admin) */
  app.get('/v1/ministry/history', auth, async (req, res) => {
    try {
      const cache = new Map();
      const get = (c, i) => { const key = c + '/' + i; if (!cache.has(key)) cache.set(key, store.get(c, i)); return cache.get(key); };
      const actor = await loadActor(req.personId, get, (c, cong) => store.list(c, cong));
      if (!actor.known) return res.status(403).json({ error: 'Unknown user' });
      const congregation = actor.isSuperAdmin ? String(req.query.congregationId ?? '') : actor.congregationId;
      if (!congregation) return res.json({ entries: [], hasMore: false });
      await backfill(store, congregation);

      const scope = [0, 1, 2].includes(Number(req.query.scope)) ? Number(req.query.scope) : 2;
      const type = TYPES.has(String(req.query.type)) ? String(req.query.type) : null;
      const stage = STAGES[String(req.query.stage)] ? String(req.query.stage) : null;
      const q = String(req.query.q ?? '').trim().toLowerCase();
      const before = Number(req.query.before) || Infinity;
      const limit = Math.min(Math.max(Number(req.query.limit) || 50, 1), 100);
      const { visible } = await recordVisibility(store, get, actor, congregation);
      const members = scope === 1 ? await groupMembersOf(store, actor, congregation) : null;

      const rows = (await store.list(HISTORY, congregation)).map((r) => r.data).filter((e) => {
        if (!e || !(e.at < before)) return false;
        if (type && e.category !== type) return false;
        if (stage && e.stage !== stage) return false;
        if (scope === 0 && !(e.ownerId === actor.personId || e.actorPersonId === actor.personId)) return false;
        if (scope === 1 && !(members.has(e.ownerId) || members.has(e.actorPersonId))) return false;
        if (!(e.actorPersonId === actor.personId || visible({ publisherPersonId: e.ownerId, pipelineStage: e.stage }))) return false;
        if (q && !`${e.recordName} ${e.summary} ${e.actorName ?? ''}`.toLowerCase().includes(q)) return false;
        return true;
      }).sort((a, b) => b.at - a.at || String(b.id).localeCompare(String(a.id)));

      const page = rows.slice(0, limit);
      // whether the record can still be opened (it may have been deleted since)
      const out = [];
      for (const e of page) {
        const rec = await get('interestedPeople', e.recordId);
        out.push({ ...e, recordExists: !!rec && !rec.deleted });
      }
      res.json({ entries: out, hasMore: rows.length > limit });
    } catch (e) {
      console.error('ministry history failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });
}
