import express from 'express';
import { randomBytes } from 'node:crypto';
import { TYPES } from './announcementFiles.js';
import { coAccess, newId, ID_RE, audit, jsonErrors } from './coAccess.js';
import { loadGrant } from './policy/index.js';

/**
 * The Circuit Overseer's File Manager: private folders and files, kept in the database (folder / file rows in co_folders / co_files, the bytes
 * in the file store under `co-files/<owner>/<id>`, which no other route serves).
 *
 *   GET    /v1/co/files/list?folderId=&sort=&dir=       the folders and files of one folder, with its path
 *   GET    /v1/co/files/tree                            every folder (for "Move to" and the sidebar) and the storage used
 *   GET    /v1/co/files/search?q=&page=&pageSize=       files and folders by name in ALL folders, with where each one is
 *   POST   /v1/co/files/folders  { name, parentId }     PATCH /folders/:id { name?, parentId? }     DELETE /folders/:id[?confirm=1]
 *   PUT    /v1/co/files/upload?name=&folderId=          raw bytes (one file per request)
 *   GET    /v1/co/files/:id                             details          PATCH /:id { name?, folderId? }       DELETE /:id (to the recycle bin)
 *   GET    /v1/co/files/:id/download[?inline=1]         the bytes (a preview only for PDF, images and text)
 *   POST   /v1/co/files/bulk { action: move|delete, fileIds, folderIds, targetFolderId }
 *   GET    /v1/co/files/trash   POST /trash/restore { kind, id }   DELETE /trash/:kind/:id (forever)   DELETE /trash (empty it)
 *
 * The Super-Admin can also BROWSE any Circuit Overseer's files, read-only (list, tree, search, details, download), under
 *   GET    /v1/admin/co-accounts                        every Circuit Overseer account, with its circuit code, status and file count
 *   GET    /v1/admin/co-files/<coPersonId>/...          the same read routes as above, for that Circuit Overseer's own files
 * The owner is always the Circuit Overseer's immutable person id (never a username), anything but GET answers 403, a person who is not a Circuit
 * Overseer answers 404, and every file opened or downloaded this way is written to the audit log with the Super-Admin as the actor.
 *
 * Deleting only moves to the recycle bin; "forever" removes the row and the bytes together. The type, the declared MIME type and the first bytes
 * of every upload must agree and be on the allowed list; executables and scripts are never accepted.
 */
export const MAX_FILE_MB = Number(process.env.CO_FILE_MAX_MB ?? 25);
export const STORAGE_QUOTA_MB = Number(process.env.CO_STORAGE_MB ?? 500);
const MB = 1024 * 1024;

const ZIP = (b) => b.length > 3 && b[0] === 0x50 && b[1] === 0x4b && (b[2] === 0x03 || b[2] === 0x05);
const EXTRA_TYPES = {
  gif: { mimes: ['image/gif'], sniff: (b) => b.length > 5 && ['GIF87a', 'GIF89a'].includes(b.subarray(0, 6).toString('latin1')) },
  zip: { mimes: ['application/zip', 'application/x-zip-compressed'], sniff: ZIP },
};
const ALLOWED = { ...TYPES, ...EXTRA_TYPES };
const GENERIC = new Set(['application/octet-stream', 'binary/octet-stream', '']);
/** What the browser may show inside the page (always with a sandboxing CSP and "nosniff"). */
const PREVIEW = { 'application/pdf': 'application/pdf', 'image/jpeg': 'image/jpeg', 'image/png': 'image/png', 'image/webp': 'image/webp', 'image/gif': 'image/gif', 'text/plain': 'text/plain; charset=utf-8' };
/** Executable / script / web-page endings: never typed into a file name, so ".exe.txt" tricks do not appear. */
const RISKY_EXT = new Set(['exe', 'bat', 'cmd', 'com', 'msi', 'scr', 'dll', 'js', 'mjs', 'vbs', 'ps1', 'sh', 'jar', 'php', 'html', 'htm', 'svg', 'apk', 'lnk', 'reg']);
const BAD_NAME =/[\\/:*?"<>|\u0000-\u001f]/;

/** A folder or file name the user typed, or null when it is empty, too long, a path, or not allowed. */
export function cleanName(raw, max = 150) {
  const name = String(raw ?? '').replace(/\s+/g, ' ').trim();
  if (!name || name.length > max || BAD_NAME.test(name) || name === '.' || name === '..' || name.endsWith('.')) return null;
  return name;
}

/** Returns { ok, ext, mime, name } or { ok: false, reason } for an uploaded file. */
export function checkUpload(name, declaredMime, data) {
  const clean = cleanName(name, 200);
  if (!clean) return { ok: false, reason: 'The file name is empty, too long or has characters that are not allowed' };
  const ext = clean.includes('.') ? clean.split('.').pop().toLowerCase() : '';
  const type = ALLOWED[ext];
  if (!type) return { ok: false, reason: `Files of type ".${ext || '?'}" are not allowed` };
  if (!Buffer.isBuffer(data) || data.length === 0) return { ok: false, reason: 'The file is empty' };
  if (data.length > MAX_FILE_MB * MB) return { ok: false, reason: `The file is larger than ${MAX_FILE_MB} MB` };
  const declared = String(declaredMime ?? '').split(';')[0].trim().toLowerCase();
  if (!GENERIC.has(declared) && !type.mimes.includes(declared)) return { ok: false, reason: 'The file type does not match its name' };
  if (!type.sniff(data)) return { ok: false, reason: 'The file content does not match its type' };
  return { ok: true, ext, mime: type.mimes[0], name: clean };
}

const blobPath = (owner, id) => `co-files/${owner}/${id}`;
const sameName = (a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }) === 0;
const SORTS = {
  name: (a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base', numeric: true }),
  type: (a, b) => (a.ext ?? '').localeCompare(b.ext ?? '') || SORTS.name(a, b),
  size: (a, b) => (a.size ?? 0) - (b.size ?? 0) || SORTS.name(a, b),
  uploaded: (a, b) => a.createdAt - b.createdAt || SORTS.name(a, b),
  modified: (a, b) => a.updatedAt - b.updatedAt || SORTS.name(a, b),
};

/** A person who really is a Circuit Overseer: an access grant with a Circuit Code on a live person record. */
async function isCircuitOverseerAccount(store, personId) {
  const get = (c, i) => store.get(c, i);
  const person = await get('people', personId);
  if (!person || person.deleted) return false;
  const grant = await loadGrant(personId, get);
  return grant.has && grant.circuitCode != null;
}

export function mountCoFiles(app, store, auth) {
  const router = express.Router();
  const raw = express.raw({ type: () => true, limit: MAX_FILE_MB * MB + 1024 });
  const json = express.json({ limit: '100kb' });

  /** Every route: signed in, a Circuit Overseer (or the Super-Admin), then run with that person as the owner. */
  const guard = (handler) => async (req, res) => {
    try {
      const access = await coAccess(store, req.personId);
      if (!access.allowed) return res.status(403).json({ error: 'The File Manager is for the Circuit Overseer' });
      let owner = req.personId;
      if (req.coOwner) {
        // The Super-Admin looking at one Circuit Overseer's files: read-only, and only a real Circuit Overseer account.
        if (!access.isSuper) return res.status(403).json({ error: 'Only the Super-Admin browses the files of a Circuit Overseer' });
        if (req.method !== 'GET') return res.status(403).json({ error: 'The Super-Admin views these files; changes are made by the Circuit Overseer' });
        if (!/^[A-Za-z0-9_-]{2,60}$/.test(String(req.coOwner)) || !(await isCircuitOverseerAccount(store, req.coOwner))) return res.status(404).json({ error: 'Circuit Overseer not found' });
        owner = req.coOwner;
      }
      const [folders, files] = await Promise.all([store.co_listFolders(owner), store.co_listFiles(owner)]);
      const ctx = { owner, folders, files, folderMap: new Map(folders.map((f) => [f.id, f])), live: (r) => r.deletedAt == null };
      ctx.pathOf = (folderId) => {
        const names = [];
        for (let cur = ctx.folderMap.get(folderId), guardN = 0; cur && guardN < 100; cur = ctx.folderMap.get(cur.parentId), guardN++) names.unshift({ id: cur.id, name: cur.name });
        return names;
      };
      ctx.pathText = (folderId) => ['File Manager', ...ctx.pathOf(folderId).map((p) => p.name)].join(' / ');
      ctx.descendants = (folderId) => {
        const out = new Set([folderId]);
        let grew = true;
        while (grew) {
          grew = false;
          for (const f of folders) if (!out.has(f.id) && out.has(f.parentId)) { out.add(f.id); grew = true; }
        }
        return out;
      };
      ctx.usedBytes = () => files.reduce((n, f) => n + f.size, 0);
      if (req.coOwner && /^\/[A-Za-z0-9_-]{8,40}(\/download)?$/.test(req.path)) {   // a file opened or downloaded by the Super-Admin
        await audit(store, req.personId, 'SUPER_CO_FILE_VIEW', 'CoFile', req.path.split('/')[1], `files of Circuit Overseer ${owner}${req.path.endsWith('/download') ? ' (download)' : ''}`);
      }
      return await handler(req, res, ctx);
    } catch (e) {
      console.error('co files failed', req.method, req.path, e);
      return res.status(500).json({ error: 'Server error' });
    }
  };
  const idOk = (id) => ID_RE.test(String(id ?? ''));
  /** The parent folder named in a request: null = the top level; a missing / deleted / foreign folder is an error. */
  const parentOf = (ctx, id) => {
    if (id == null || id === '' || id === 'root') return { ok: true, id: null };
    const f = idOk(id) ? ctx.folderMap.get(id) : null;
    return f && ctx.live(f) ? { ok: true, id: f.id } : { ok: false };
  };
  const folderOut = (ctx, f) => ({ id: f.id, name: f.name, parentId: f.parentId, createdAt: f.createdAt, updatedAt: f.updatedAt, itemCount: ctx.folders.filter((x) => x.parentId === f.id && ctx.live(x)).length + ctx.files.filter((x) => x.folderId === f.id && ctx.live(x)).length });
  const fileOut = (ctx, f) => ({ id: f.id, name: f.name, ext: f.ext, mime: f.mime, size: f.size, folderId: f.folderId, folderPath: ctx.pathText(f.folderId), createdAt: f.createdAt, updatedAt: f.updatedAt, previewable: f.mime in PREVIEW });
  /** A free name inside a folder: "report.pdf", then "report (2).pdf", "report (3).pdf" ... */
  const freeFileName = (ctx, folderId, name, exceptId) => {
    const taken = (n) => ctx.files.some((f) => ctx.live(f) && f.id !== exceptId && f.folderId === folderId && sameName(f.name, n));
    if (!taken(name)) return name;
    const dot = name.lastIndexOf('.');
    const [stem, tail] = dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ''];
    for (let i = 2; i < 1000; i++) if (!taken(`${stem} (${i})${tail}`)) return `${stem} (${i})${tail}`;
    return `${stem} (${randomBytes(3).toString('hex')})${tail}`;
  };
  const folderTaken = (ctx, parentId, name, exceptId) => ctx.folders.some((f) => ctx.live(f) && f.id !== exceptId && f.parentId === parentId && sameName(f.name, name));

  router.get('/list', auth, guard(async (req, res, ctx) => {
    const parent = parentOf(ctx, req.query.folderId);
    if (!parent.ok) return res.status(404).json({ error: 'Folder not found' });
    const sort = SORTS[req.query.sort] ?? SORTS.name;
    const dir = req.query.dir === 'desc' ? -1 : 1;
    const folders = ctx.folders.filter((f) => ctx.live(f) && f.parentId === parent.id).sort((a, b) => (req.query.sort === 'uploaded' ? a.createdAt - b.createdAt : req.query.sort === 'modified' ? a.updatedAt - b.updatedAt : SORTS.name(a, b)) * dir);
    const files = ctx.files.filter((f) => ctx.live(f) && f.folderId === parent.id).sort((a, b) => sort(a, b) * dir);
    res.json({ folderId: parent.id, path: ctx.pathOf(parent.id), folders: folders.map((f) => folderOut(ctx, f)), files: files.map((f) => fileOut(ctx, f)), maxFileMb: MAX_FILE_MB });
  }));

  router.get('/tree', auth, guard(async (req, res, ctx) => {
    const trash = ctx.folders.filter((f) => !ctx.live(f) && f.deletedWith == null).length + ctx.files.filter((f) => !ctx.live(f) && f.deletedWith == null).length;
    res.json({
      folders: ctx.folders.filter(ctx.live).map((f) => ({ id: f.id, name: f.name, parentId: f.parentId })).sort(SORTS.name),
      usage: { usedBytes: ctx.usedBytes(), quotaBytes: STORAGE_QUOTA_MB * MB, files: ctx.files.filter(ctx.live).length, trashItems: trash },
      maxFileMb: MAX_FILE_MB,
    });
  }));

  router.get('/search', auth, guard(async (req, res, ctx) => {
    const words = String(req.query.q ?? '').toLowerCase().split(/\s+/).filter(Boolean).slice(0, 8);
    if (!words.length) return res.json({ total: 0, items: [], page: 1, pageSize: 50 });
    const pageSize = Math.min(Math.max(Number.parseInt(req.query.pageSize, 10) || 50, 1), 200);
    const page = Math.max(Number.parseInt(req.query.page, 10) || 1, 1);
    const match = (name) => words.every((w) => name.toLowerCase().includes(w));
    const hit = [
      ...ctx.folders.filter((f) => ctx.live(f) && match(f.name)).map((f) => ({ kind: 'folder', id: f.id, name: f.name, ext: '', size: null, createdAt: f.createdAt, updatedAt: f.updatedAt, folderId: f.parentId, folderPath: ctx.pathText(f.parentId) })),
      ...ctx.files.filter((f) => ctx.live(f) && match(f.name)).map((f) => ({ kind: 'file', id: f.id, name: f.name, ext: f.ext, size: f.size, createdAt: f.createdAt, updatedAt: f.updatedAt, folderId: f.folderId, folderPath: ctx.pathText(f.folderId) })),
    ].sort((a, b) => (a.kind === b.kind ? 0 : a.kind === 'folder' ? -1 : 1) || SORTS.name(a, b));
    res.json({ total: hit.length, items: hit.slice((page - 1) * pageSize, page * pageSize), page, pageSize });
  }));

  router.post('/folders', auth, json, guard(async (req, res, ctx) => {
    const name = cleanName(req.body?.name);
    if (!name) return res.status(400).json({ error: 'Type a folder name (no / \\ : * ? " < > |)' });
    const parent = parentOf(ctx, req.body?.parentId);
    if (!parent.ok) return res.status(404).json({ error: 'Folder not found' });
    if (folderTaken(ctx, parent.id, name)) return res.status(409).json({ error: 'A folder with this name already exists here' });
    if (ctx.pathOf(parent.id).length >= 12) return res.status(400).json({ error: 'Folders can be nested up to 12 levels' });
    const now = Date.now();
    const row = { id: newId(), name, parentId: parent.id, createdAt: now, updatedAt: now, deletedAt: null, deletedWith: null };
    await store.co_putFolders(ctx.owner, [row]);
    await audit(store, ctx.owner, 'CO_FOLDER_CREATE', 'CoFolder', row.id, name);
    res.status(201).json(folderOut({ ...ctx, folders: [...ctx.folders, row], files: ctx.files, live: ctx.live }, row));
  }));

  router.patch('/folders/:id', auth, json, guard(async (req, res, ctx) => {
    const f = idOk(req.params.id) ? ctx.folderMap.get(req.params.id) : null;
    if (!f || !ctx.live(f)) return res.status(404).json({ error: 'Folder not found' });
    const next = { ...f };
    if (req.body?.name !== undefined) {
      const name = cleanName(req.body.name);
      if (!name) return res.status(400).json({ error: 'Type a folder name (no / \\ : * ? " < > |)' });
      next.name = name;
    }
    if (req.body && 'parentId' in req.body) {
      const parent = parentOf(ctx, req.body.parentId);
      if (!parent.ok) return res.status(404).json({ error: 'Destination folder not found' });
      if (parent.id && ctx.descendants(f.id).has(parent.id)) return res.status(400).json({ error: 'A folder cannot be moved into itself' });
      next.parentId = parent.id;
    }
    if (folderTaken(ctx, next.parentId, next.name, f.id)) return res.status(409).json({ error: 'A folder with this name already exists there' });
    next.updatedAt = Date.now();
    await store.co_putFolders(ctx.owner, [next]);
    await audit(store, ctx.owner, 'CO_FOLDER_UPDATE', 'CoFolder', f.id, `${f.name} -> ${next.name}`);
    res.json(folderOut(ctx, next));
  }));

  /** The rows below a folder (itself, its subfolders and their files) that are still live. */
  const subtree = (ctx, folderId) => {
    const ids = ctx.descendants(folderId);
    return { folders: ctx.folders.filter((f) => ids.has(f.id) && ctx.live(f)), files: ctx.files.filter((f) => ids.has(f.folderId) && ctx.live(f)) };
  };

  router.delete('/folders/:id', auth, guard(async (req, res, ctx) => {
    const f = idOk(req.params.id) ? ctx.folderMap.get(req.params.id) : null;
    if (!f || !ctx.live(f)) return res.status(404).json({ error: 'Folder not found' });
    const sub = subtree(ctx, f.id);
    const folderCount = sub.folders.length - 1;
    if ((folderCount > 0 || sub.files.length > 0) && req.query.confirm !== '1') {
      return res.status(409).json({ error: 'This folder is not empty', needsConfirm: true, folders: folderCount, files: sub.files.length });
    }
    const now = Date.now();
    await store.co_putFolders(ctx.owner, sub.folders.map((x) => ({ ...x, deletedAt: now, deletedWith: x.id === f.id ? null : f.id, updatedAt: now })));
    await store.co_putFiles(ctx.owner, sub.files.map((x) => ({ ...x, deletedAt: now, deletedWith: f.id, updatedAt: now })));
    await audit(store, ctx.owner, 'CO_FOLDER_DELETE', 'CoFolder', f.id, `${f.name} (${folderCount} folders, ${sub.files.length} files) to the recycle bin`);
    res.json({ ok: true, folders: folderCount, files: sub.files.length });
  }));

  router.put('/upload', auth, raw, guard(async (req, res, ctx) => {
    const parent = parentOf(ctx, req.query.folderId);
    if (!parent.ok) return res.status(404).json({ error: 'Folder not found' });
    const verdict = checkUpload(req.query.name, req.headers['content-type'], req.body);
    if (!verdict.ok) return res.status(400).json({ error: verdict.reason });
    if (ctx.usedBytes() + req.body.length > STORAGE_QUOTA_MB * MB) return res.status(413).json({ error: `Your storage of ${STORAGE_QUOTA_MB} MB is full. Delete files (and empty the recycle bin) first` });
    const id = newId();
    const now = Date.now();
    // The bytes first: if saving them fails nothing else is written; if the row fails afterwards the bytes are removed again (no orphan).
    await store.putFile({ path: blobPath(ctx.owner, id), token: randomBytes(24).toString('base64url'), owner: ctx.owner, mime: verdict.mime, data: req.body });
    const row = { id, name: freeFileName(ctx, parent.id, verdict.name), ext: verdict.ext, mime: verdict.mime, size: req.body.length, folderId: parent.id, createdAt: now, updatedAt: now, deletedAt: null, deletedWith: null };
    try {
      await store.co_putFiles(ctx.owner, [row]);
    } catch (e) {
      await store.deleteFile(blobPath(ctx.owner, id));
      throw e;
    }
    await audit(store, ctx.owner, 'CO_FILE_UPLOAD', 'CoFile', id, `${row.name} (${row.size} bytes)`);
    res.status(201).json(fileOut(ctx, row));
  }));

  const fileOf = (ctx, id, { deleted = false } = {}) => {
    const f = idOk(id) ? ctx.files.find((x) => x.id === id) : null;
    return f && (deleted ? !ctx.live(f) : ctx.live(f)) ? f : null;
  };

  router.get('/trash', auth, guard(async (req, res, ctx) => {
    const items = [
      ...ctx.folders.filter((f) => !ctx.live(f) && f.deletedWith == null).map((f) => ({ kind: 'folder', id: f.id, name: f.name, deletedAt: f.deletedAt, size: null, contains: ctx.files.filter((x) => x.deletedWith === f.id).length })),
      ...ctx.files.filter((f) => !ctx.live(f) && f.deletedWith == null).map((f) => ({ kind: 'file', id: f.id, name: f.name, deletedAt: f.deletedAt, size: f.size, ext: f.ext })),
    ].sort((a, b) => b.deletedAt - a.deletedAt);
    res.json({ items });
  }));

  router.post('/trash/restore', auth, json, guard(async (req, res, ctx) => {
    const { kind, id } = req.body ?? {};
    const now = Date.now();
    const parentLive = (parentId) => parentId == null || (ctx.folderMap.get(parentId) && ctx.live(ctx.folderMap.get(parentId)));
    if (kind === 'file') {
      const f = fileOf(ctx, id, { deleted: true });
      if (!f) return res.status(404).json({ error: 'Not found' });
      const folderId = parentLive(f.folderId) ? f.folderId : null;
      await store.co_putFiles(ctx.owner, [{ ...f, folderId, name: freeFileName(ctx, folderId, f.name, f.id), deletedAt: null, deletedWith: null, updatedAt: now }]);
      await audit(store, ctx.owner, 'CO_FILE_RESTORE', 'CoFile', f.id, f.name);
      return res.json({ ok: true });
    }
    const folder = kind === 'folder' && idOk(id) ? ctx.folderMap.get(id) : null;
    if (!folder || ctx.live(folder)) return res.status(404).json({ error: 'Not found' });
    const parentId = parentLive(folder.parentId) ? folder.parentId : null;
    let name = folder.name;
    for (let i = 2; folderTaken(ctx, parentId, name, folder.id) && i < 1000; i++) name = `${folder.name} (${i})`;
    await store.co_putFolders(ctx.owner, [
      { ...folder, name, parentId, deletedAt: null, deletedWith: null, updatedAt: now },
      ...ctx.folders.filter((x) => x.deletedWith === folder.id).map((x) => ({ ...x, deletedAt: null, deletedWith: null, updatedAt: now })),
    ]);
    await store.co_putFiles(ctx.owner, ctx.files.filter((x) => x.deletedWith === folder.id).map((x) => ({ ...x, deletedAt: null, deletedWith: null, updatedAt: now })));
    await audit(store, ctx.owner, 'CO_FOLDER_RESTORE', 'CoFolder', folder.id, name);
    return res.json({ ok: true });
  }));

  /** Removes rows and bytes for good (files first, so a failure leaves rows that can still be retried, never bytes without a row). */
  const purge = async (ctx, folderIds, fileIds) => {
    for (const id of fileIds) await store.deleteFile(blobPath(ctx.owner, id));
    await store.co_removeFiles(ctx.owner, fileIds);
    await store.co_removeFolders(ctx.owner, folderIds);
  };

  router.delete('/trash/:kind/:id', auth, guard(async (req, res, ctx) => {
    if (req.params.kind === 'file') {
      const f = fileOf(ctx, req.params.id, { deleted: true });
      if (!f) return res.status(404).json({ error: 'Not found' });
      await purge(ctx, [], [f.id]);
      await audit(store, ctx.owner, 'CO_FILE_PURGE', 'CoFile', f.id, f.name);
      return res.json({ ok: true });
    }
    const folder = req.params.kind === 'folder' && idOk(req.params.id) ? ctx.folderMap.get(req.params.id) : null;
    if (!folder || ctx.live(folder)) return res.status(404).json({ error: 'Not found' });
    const folderIds = [folder.id, ...ctx.folders.filter((x) => x.deletedWith === folder.id).map((x) => x.id)];
    await purge(ctx, folderIds, ctx.files.filter((x) => x.deletedWith === folder.id).map((x) => x.id));
    await audit(store, ctx.owner, 'CO_FOLDER_PURGE', 'CoFolder', folder.id, folder.name);
    return res.json({ ok: true });
  }));

  router.delete('/trash', auth, guard(async (req, res, ctx) => {
    const files = ctx.files.filter((f) => !ctx.live(f)).map((f) => f.id);
    const folders = ctx.folders.filter((f) => !ctx.live(f)).map((f) => f.id);
    await purge(ctx, folders, files);
    await audit(store, ctx.owner, 'CO_TRASH_EMPTY', 'CoFile', null, `${files.length} files, ${folders.length} folders`);
    res.json({ ok: true, files: files.length, folders: folders.length });
  }));

  router.post('/bulk', auth, json, guard(async (req, res, ctx) => {
    const { action } = req.body ?? {};
    const fileIds = Array.isArray(req.body?.fileIds) ? req.body.fileIds.filter(idOk).slice(0, 500) : [];
    const folderIds = Array.isArray(req.body?.folderIds) ? req.body.folderIds.filter(idOk).slice(0, 500) : [];
    const files = fileIds.map((id) => fileOf(ctx, id)).filter(Boolean);
    const folders = folderIds.map((id) => ctx.folderMap.get(id)).filter((f) => f && ctx.live(f));
    if (!files.length && !folders.length) return res.status(400).json({ error: 'Nothing is selected' });
    const now = Date.now();
    if (action === 'move') {
      const target = parentOf(ctx, req.body?.targetFolderId);
      if (!target.ok) return res.status(404).json({ error: 'Destination folder not found' });
      for (const f of folders) {
        if (target.id && ctx.descendants(f.id).has(target.id)) return res.status(400).json({ error: `"${f.name}" cannot be moved into itself` });
        if (folderTaken(ctx, target.id, f.name, f.id)) return res.status(409).json({ error: `A folder named "${f.name}" already exists there` });
      }
      await store.co_putFolders(ctx.owner, folders.map((f) => ({ ...f, parentId: target.id, updatedAt: now })));
      const placed = [];
      for (const f of files) {
        const name = freeFileName({ ...ctx, files: [...ctx.files, ...placed.map((p) => ({ ...p }))] }, target.id, f.name, f.id);
        placed.push({ ...f, folderId: target.id, name, updatedAt: now });
      }
      await store.co_putFiles(ctx.owner, placed);
      await audit(store, ctx.owner, 'CO_BULK_MOVE', 'CoFile', null, `${files.length} files, ${folders.length} folders`);
      return res.json({ ok: true, files: files.length, folders: folders.length });
    }
    if (action === 'delete') {
      const folderRows = new Map();
      const fileRows = new Map();
      for (const f of folders) {
        const sub = subtree(ctx, f.id);
        for (const x of sub.folders) folderRows.set(x.id, { ...x, deletedAt: now, deletedWith: x.id === f.id ? null : f.id, updatedAt: now });
        for (const x of sub.files) fileRows.set(x.id, { ...x, deletedAt: now, deletedWith: f.id, updatedAt: now });
      }
      for (const f of files) if (!fileRows.has(f.id)) fileRows.set(f.id, { ...f, deletedAt: now, deletedWith: null, updatedAt: now });
      await store.co_putFolders(ctx.owner, [...folderRows.values()]);
      await store.co_putFiles(ctx.owner, [...fileRows.values()]);
      await audit(store, ctx.owner, 'CO_BULK_DELETE', 'CoFile', null, `${files.length} files, ${folders.length} folders to the recycle bin`);
      return res.json({ ok: true, files: files.length, folders: folders.length });
    }
    return res.status(400).json({ error: 'Unknown action' });
  }));

  router.get('/:id', auth, guard(async (req, res, ctx) => {
    const f = fileOf(ctx, req.params.id);
    if (!f) return res.status(404).json({ error: 'File not found' });
    res.json({ ...fileOut(ctx, f), owner: ctx.owner });
  }));

  router.get('/:id/download', auth, guard(async (req, res, ctx) => {
    const f = fileOf(ctx, req.params.id);
    if (!f) return res.status(404).end(); // 404, not 403: never confirm that someone else's file exists
    const blob = await store.getFileByPath(blobPath(ctx.owner, f.id));
    if (!blob) return res.status(404).json({ error: 'The stored file is missing' });
    const asciiName = f.name.replace(/[^\w.\- ()]/g, '_');
    const inlineType = req.query.inline === '1' ? PREVIEW[f.mime] ?? (f.ext === 'csv' ? PREVIEW['text/plain'] : null) : null;
    res.set('Content-Type', inlineType ?? f.mime);
    res.set('X-Content-Type-Options', 'nosniff');
    res.set('Cache-Control', 'private, no-store');
    res.set('Content-Security-Policy', "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; sandbox");
    res.set('Content-Disposition', `${inlineType ? 'inline' : 'attachment'}; filename="${asciiName}"; filename*=UTF-8''${encodeURIComponent(f.name)}`);
    res.send(blob.data);
  }));

  router.patch('/:id', auth, json, guard(async (req, res, ctx) => {
    const f = fileOf(ctx, req.params.id);
    if (!f) return res.status(404).json({ error: 'File not found' });
    const next = { ...f };
    if (req.body?.name !== undefined) {
      let name = cleanName(req.body.name, 200);
      if (!name) return res.status(400).json({ error: 'Type a file name (no / \\ : * ? " < > |)' });
      if (!name.toLowerCase().endsWith(`.${f.ext}`)) {
        const typed = name.includes('.') ? name.split('.').pop().toLowerCase() : '';
        if (ALLOWED[typed] || RISKY_EXT.has(typed)) return res.status(400).json({ error: `A file keeps its type: it must stay ".${f.ext}"` });
        name = `${name}.${f.ext}`;
      }
      next.name = name;
    }
    if (req.body && 'folderId' in req.body) {
      const parent = parentOf(ctx, req.body.folderId);
      if (!parent.ok) return res.status(404).json({ error: 'Destination folder not found' });
      next.folderId = parent.id;
    }
    if (ctx.files.some((x) => ctx.live(x) && x.id !== f.id && x.folderId === next.folderId && sameName(x.name, next.name))) {
      return res.status(409).json({ error: 'A file with this name already exists there' });
    }
    next.updatedAt = Date.now();
    await store.co_putFiles(ctx.owner, [next]);
    await audit(store, ctx.owner, 'CO_FILE_UPDATE', 'CoFile', f.id, `${f.name} -> ${next.name}`);
    res.json(fileOut(ctx, next));
  }));

  router.delete('/:id', auth, guard(async (req, res, ctx) => {
    const f = fileOf(ctx, req.params.id);
    if (!f) return res.status(404).json({ error: 'File not found' });
    const now = Date.now();
    await store.co_putFiles(ctx.owner, [{ ...f, deletedAt: now, deletedWith: null, updatedAt: now }]);
    await audit(store, ctx.owner, 'CO_FILE_DELETE', 'CoFile', f.id, `${f.name} to the recycle bin`);
    res.json({ ok: true });
  }));

  router.use(jsonErrors);
  app.use('/v1/co/files', router);
  // The Super-Admin's read-only view of one Circuit Overseer's files: the same routes, with that CO as the owner.
  app.use('/v1/admin/co-files/:coId', (req, res, next) => { req.coOwner = req.params.coId; next(); }, router);

  /** Every Circuit Overseer account, for the Super-Admin's picker: who, which circuit, whether the account is on, and how many files they keep. */
  app.get('/v1/admin/co-accounts', auth, async (req, res) => {
    try {
      const access = await coAccess(store, req.personId);
      if (!access.allowed || !access.isSuper) return res.status(403).json({ error: 'Only the Super-Admin sees the Circuit Overseer accounts' });
      const grants = await store.list('userAccessGrants');
      const out = [];
      for (const g of grants) {
        if (g.data?.circuitCode == null || g.deleted) continue;
        const person = await store.get('people', g.id);
        if (!person || person.deleted) continue;
        const files = (await store.co_listFiles(g.id)).filter((f) => f.deletedAt == null);
        const folders = (await store.co_listFolders(g.id)).filter((f) => f.deletedAt == null);
        const p = person.data;
        out.push({
          id: g.id, name: [p.firstName, p.lastName].filter(Boolean).join(' ') || p.username || g.id, username: p.username ?? '', circuitCode: String(g.data.circuitCode),
          status: p.accountStatus ?? 'ACTIVE', fileCount: files.length, folderCount: folders.length, bytes: files.reduce((n, f) => n + (f.size ?? 0), 0),
          congregations: Array.isArray(g.data.scopeCongregationIds) ? g.data.scopeCongregationIds.length : null, allCongregations: g.data.scopeType === 'ALL_CONGREGATIONS',
        });
      }
      out.sort((a, b) => a.circuitCode.localeCompare(b.circuitCode, undefined, { numeric: true }) || a.name.localeCompare(b.name));
      res.json({ accounts: out });
    } catch (e) {
      console.error('co accounts failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });
}
