import express from 'express';
import { randomBytes } from 'node:crypto';
import { loadActor } from './policy/index.js';
import { canReadDoc, loadGrant } from './policy/rules.js';

/**
 * Attachments of a Circuit Overseer announcement. Unlike the general file store (whose download link is a secret token anyone can pass on),
 * these files have NO public link:
 *
 *   PUT    /v1/announcements/:id/files?name=<file name>   raw bytes -> { id, name, mime, size }   the sender / Circuit Overseer of the congregation
 *   GET    /v1/announcements/:id/files/:fileId[?inline=1]  the bytes, only for someone who may read the announcement
 *   DELETE /v1/announcements/:id/files/:fileId                                                       the sender / Circuit Overseer of the congregation
 *
 * Every request is checked against the announcement itself (draft, targeted elders, archived ...), so a guessed URL gives nothing to an elder
 * who was not addressed. The type, the declared MIME type and the first bytes of the file must all agree and be on the allowed list.
 * (No malware scanner is available on this host; the allow-list, the content check, "nosniff" and forced downloads are the protection.)
 */
export const MAX_ANNOUNCEMENT_FILE_BYTES = 10 * 1024 * 1024;
export const MAX_FILES_PER_ANNOUNCEMENT = 20;
const ID_RE = /^[A-Za-z0-9_-]{1,100}$/;

const ZIP = (b) => b.length > 3 && b[0] === 0x50 && b[1] === 0x4b && (b[2] === 0x03 || b[2] === 0x05);
const OLE = (b) => b.length > 7 && b[0] === 0xd0 && b[1] === 0xcf && b[2] === 0x11 && b[3] === 0xe0;
// plain text / CSV: no NUL bytes in the first 8 KB (a binary file renamed to .txt is refused)
const text = (b) => !b.subarray(0, 8192).includes(0);

/** extension -> { mimes the phone may declare, sniff: does the content match } */
const TYPES = {
  pdf: { mimes: ['application/pdf'], sniff: (b) => b.subarray(0, 5).toString('latin1') === '%PDF-' },
  doc: { mimes: ['application/msword'], sniff: OLE },
  docx: { mimes: ['application/vnd.openxmlformats-officedocument.wordprocessingml.document'], sniff: ZIP },
  odt: { mimes: ['application/vnd.oasis.opendocument.text'], sniff: ZIP },
  txt: { mimes: ['text/plain'], sniff: text },
  rtf: { mimes: ['application/rtf', 'text/rtf'], sniff: (b) => b.subarray(0, 5).toString('latin1') === '{\\rtf' },
  xls: { mimes: ['application/vnd.ms-excel'], sniff: OLE },
  xlsx: { mimes: ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'], sniff: ZIP },
  ods: { mimes: ['application/vnd.oasis.opendocument.spreadsheet'], sniff: ZIP },
  csv: { mimes: ['text/csv', 'text/plain', 'application/csv', 'application/vnd.ms-excel'], sniff: text },
  ppt: { mimes: ['application/vnd.ms-powerpoint'], sniff: OLE },
  pptx: { mimes: ['application/vnd.openxmlformats-officedocument.presentationml.presentation'], sniff: ZIP },
  odp: { mimes: ['application/vnd.oasis.opendocument.presentation'], sniff: ZIP },
  jpg: { mimes: ['image/jpeg'], sniff: (b) => b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff },
  jpeg: { mimes: ['image/jpeg'], sniff: (b) => b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff },
  png: { mimes: ['image/png'], sniff: (b) => b.length > 7 && b[0] === 0x89 && b.subarray(1, 4).toString('latin1') === 'PNG' },
  webp: { mimes: ['image/webp'], sniff: (b) => b.length > 11 && b.subarray(0, 4).toString('latin1') === 'RIFF' && b.subarray(8, 12).toString('latin1') === 'WEBP' },
};
const INLINE_OK = new Set(['application/pdf', 'image/jpeg', 'image/png', 'image/webp']);
const GENERIC = new Set(['application/octet-stream', 'binary/octet-stream', '']);

/** Returns { ok: true, ext, mime } or { ok: false, reason }. The file name is only used for its extension and to show it back. */
export function checkAnnouncementFile(name, declaredMime, data) {
  const clean = String(name ?? '').replace(/[\\/\u0000-\u001f]/g, '').trim();
  if (!clean || clean.length > 200) return { ok: false, reason: 'A file name of up to 200 characters is needed' };
  const ext = clean.includes('.') ? clean.split('.').pop().toLowerCase() : '';
  const type = TYPES[ext];
  if (!type) return { ok: false, reason: `Files of type ".${ext}" are not allowed` };
  if (!Buffer.isBuffer(data) || data.length === 0) return { ok: false, reason: 'Empty file' };
  if (data.length > MAX_ANNOUNCEMENT_FILE_BYTES) return { ok: false, reason: 'The file is larger than 10 MB' };
  const declared = String(declaredMime ?? '').split(';')[0].trim().toLowerCase();
  if (!GENERIC.has(declared) && !type.mimes.includes(declared)) return { ok: false, reason: 'The file type does not match its name' };
  if (!type.sniff(data)) return { ok: false, reason: 'The file content does not match its type' };
  return { ok: true, ext, mime: type.mimes[0], name: clean };
}

const pathOf = (announcementId, fileId) => `announcement-files/${announcementId}/${fileId}`;

export function mountAnnouncementFiles(app, store, auth) {
  const raw = express.raw({ type: () => true, limit: MAX_ANNOUNCEMENT_FILE_BYTES + 1024 });
  const get = (c, i) => store.get(c, i);

  /** The announcement (null when it does not exist yet) and what this caller may do with it. */
  async function access(personId, announcementId) {
    const actor = await loadActor(personId, get);
    if (!actor.known) return { actor, known: false };
    const grant = await loadGrant(personId, get);
    const row = await store.get('announcements', announcementId);
    const data = row && !row.deleted ? row.data : null;
    const isCo = grant.has && grant.circuitCode != null;
    if (data == null) {
      // Not synced yet (a draft being composed): only a Circuit Overseer or the Super-Admin may add files, and only its sender may read them back.
      return { actor, known: true, data: null, canWrite: actor.isSuperAdmin || isCo, canRead: false };
    }
    const targeted = data.recipientScope != null;
    const canRead = await canReadDoc(actor, grant, 'announcements', announcementId, data, get);
    const sender = data.createdByPersonId === personId;
    const coOfCongregation = isCo && (grant.scopeType === 'ALL_CONGREGATIONS' || (grant.scopeType === 'SELECTED_CONGREGATIONS' && grant.scopeCongregationIds.includes(data.congregationId)));
    // Only announcements written by a Circuit Overseer have secure attachments; the original ones keep their own image/attachment fields.
    const canWrite = targeted && (actor.isSuperAdmin || sender || coOfCongregation);
    return { actor, known: true, data, canWrite, canRead: targeted && canRead };
  }

  app.put('/v1/announcements/:id/files', auth, raw, async (req, res) => {
    const announcementId = String(req.params.id ?? '');
    if (!ID_RE.test(announcementId)) return res.status(400).json({ error: 'Bad announcement' });
    try {
      const a = await access(req.personId, announcementId);
      if (!a.known || !a.canWrite) return res.status(403).json({ error: 'Not allowed' });
      const verdict = checkAnnouncementFile(req.query.name, req.headers['content-type'], req.body);
      if (!verdict.ok) return res.status(400).json({ error: verdict.reason });
      const existing = a.data?.attachments?.length ?? 0;
      if (existing >= MAX_FILES_PER_ANNOUNCEMENT) return res.status(400).json({ error: `At most ${MAX_FILES_PER_ANNOUNCEMENT} files per announcement` });
      const fileId = randomBytes(12).toString('base64url');
      // The random token required by the shared file table is never revealed: these files are only served by the checked route below.
      await store.putFile({ path: pathOf(announcementId, fileId), token: randomBytes(24).toString('base64url'), owner: req.personId, mime: verdict.mime, data: req.body });
      res.json({ id: fileId, name: verdict.name, mime: verdict.mime, size: req.body.length });
    } catch (e) {
      console.error('announcement file upload failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.get('/v1/announcements/:id/files/:fileId', auth, async (req, res) => {
    const announcementId = String(req.params.id ?? '');
    const fileId = String(req.params.fileId ?? '');
    if (!ID_RE.test(announcementId) || !ID_RE.test(fileId)) return res.status(404).end();
    try {
      const a = await access(req.personId, announcementId);
      const own = !a.data && a.known && (await store.getFileOwner?.(pathOf(announcementId, fileId))) === req.personId;
      if (!a.known || !(a.canRead || a.canWrite || own)) return res.status(404).end(); // 404, not 403: do not confirm that it exists
      const file = await store.getFileByPath(pathOf(announcementId, fileId));
      if (!file) return res.status(404).end();
      const name = String(a.data?.attachments?.find((x) => x.id === fileId)?.name ?? 'attachment').replace(/[^\w.\- ()]/g, '_');
      const inline = req.query.inline === '1' && INLINE_OK.has(file.mime);
      res.set('Content-Type', file.mime);
      res.set('X-Content-Type-Options', 'nosniff');
      res.set('Cache-Control', 'private, no-store');
      res.set('Content-Security-Policy', "default-src 'none'; sandbox");
      res.set('Content-Disposition', `${inline ? 'inline' : 'attachment'}; filename="${name}"; filename*=UTF-8''${encodeURIComponent(name)}`);
      res.send(file.data);
    } catch (e) {
      console.error('announcement file download failed', e);
      res.status(500).end();
    }
  });

  app.delete('/v1/announcements/:id/files/:fileId', auth, async (req, res) => {
    const announcementId = String(req.params.id ?? '');
    const fileId = String(req.params.fileId ?? '');
    if (!ID_RE.test(announcementId) || !ID_RE.test(fileId)) return res.status(400).json({ error: 'Bad request' });
    try {
      const a = await access(req.personId, announcementId);
      const own = !a.data && a.known && (await store.getFileOwner?.(pathOf(announcementId, fileId))) === req.personId;
      if (!a.known || !(a.canWrite || own)) return res.status(403).json({ error: 'Not allowed' });
      await store.deleteFile(pathOf(announcementId, fileId));
      res.json({ ok: true });
    } catch (e) {
      console.error('announcement file delete failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });
}
