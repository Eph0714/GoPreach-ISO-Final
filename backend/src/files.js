import express from 'express';
import { randomBytes } from 'node:crypto';
import { loadActor, isWide } from './policy/index.js';

/**
 * Uploaded files (profile photos, the app logo, announcement and chat attachments) for the Hostinger backend.
 *
 * - The bytes are stored in the database, NOT on the web server's disk: Hostinger replaces the application folder on every deploy,
 *   so anything written there would be lost. The database is backed up with everything else.
 * - Upload and delete need a login and follow the same ownership rules the Firebase Storage rules had:
 *     app-settings/...                    Super-Admin only
 *     groupChats/{chatId}/...             a participant of the chat, that congregation's Admin / Coordinator Elder, or the Super-Admin
 *     anything else (profiles/, announcements/, ...)   any signed-in, known person
 * - Download is by an unguessable token in the URL (like Firebase's download URLs), because the app's image loader cannot send a login header.
 *   The token is only ever returned to the person who uploaded the file.
 */
export const MAX_FILE_BYTES = 8 * 1024 * 1024;
const PATH_RE = /^[A-Za-z0-9][A-Za-z0-9_\-./ ()]{0,400}$/;

/** Returns true if [actor] may write the file at [path]. */
export async function mayWriteFile(actor, path, get) {
  if (!actor.known) return false;
  if (path.includes('..') || path.includes('//')) return false;
  if (path.startsWith('app-settings/')) return actor.isSuperAdmin;
  const chat = /^groupChats\/([^/]+)\//.exec(path);
  if (chat) {
    const doc = await get('groupChats', chat[1]);
    const data = doc && !doc.deleted ? doc.data : null;
    if (!data) return actor.isSuperAdmin;
    return actor.isSuperAdmin || (data.participantIds ?? []).includes(actor.personId) || isWide(actor, data.congregationId);
  }
  return true;
}

export function mountFiles(app, store, auth) {
  const raw = express.raw({ type: () => true, limit: MAX_FILE_BYTES });

  /** PUT /v1/files?path=<logical path>  (raw bytes, Content-Type = the file's type) -> { url } */
  app.put('/v1/files', auth, raw, async (req, res) => {
    const path = String(req.query.path ?? '');
    if (!PATH_RE.test(path)) return res.status(400).json({ error: 'Bad path' });
    const data = req.body;
    if (!Buffer.isBuffer(data) || data.length === 0) return res.status(400).json({ error: 'Empty file' });
    try {
      const actor = await loadActor(req.personId, (c, i) => store.get(c, i));
      if (!(await mayWriteFile(actor, path, (c, i) => store.get(c, i)))) return res.status(403).json({ error: 'Not allowed' });
      const token = randomBytes(24).toString('base64url');
      const mime = String(req.headers['content-type'] ?? 'application/octet-stream').slice(0, 100);
      await store.putFile({ path, token, owner: req.personId, mime, data });
      res.json({ url: `${req.protocol}://${req.get('host')}/v1/files/${token}` });
    } catch (e) {
      console.error('file upload failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  /** DELETE /v1/files?path=...  -> 200 (also when it is already gone) */
  app.delete('/v1/files', auth, async (req, res) => {
    const path = String(req.query.path ?? '');
    if (!PATH_RE.test(path)) return res.status(400).json({ error: 'Bad path' });
    try {
      const actor = await loadActor(req.personId, (c, i) => store.get(c, i));
      if (!(await mayWriteFile(actor, path, (c, i) => store.get(c, i)))) return res.status(403).json({ error: 'Not allowed' });
      await store.deleteFile(path);
      res.json({ ok: true });
    } catch (e) {
      console.error('file delete failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  /** GET /v1/files/<token> -> the bytes (images inline, everything else as a download). No login: the token is the secret. */
  app.get('/v1/files/:token', async (req, res) => {
    const token = String(req.params.token ?? '');
    if (!/^[A-Za-z0-9_-]{20,64}$/.test(token)) return res.status(404).end();
    try {
      const file = await store.getFileByToken(token);
      if (!file) return res.status(404).end();
      res.set('Content-Type', file.mime);
      res.set('X-Content-Type-Options', 'nosniff');
      res.set('Cache-Control', 'private, max-age=3600');
      if (!file.mime.startsWith('image/')) res.set('Content-Disposition', 'attachment');
      res.send(file.data);
    } catch (e) {
      console.error('file download failed', e);
      res.status(500).end();
    }
  });
}
