/**
 * Storage of the Circuit Overseer's private data: the File Manager (folders and file metadata; the bytes live in the file store under
 * `co-files/<owner>/<id>`) and the Previous Manual Record (imported Excel files, each kept as sheet snapshots that look like the original, see sheetSnapshot.js).
 *
 * This data is NOT part of the sync API (documents / push / pull): phones never mirror it, and nothing here is reachable except through the
 * permission-checked routes in coFiles.js and coManual.js. Every method takes the owner first and only ever touches that owner's rows.
 *
 * Two implementations with the same contract: MemoryCoData (tests, running without a database) and MysqlCoData (production).
 */

/** Adds `co_<name>` methods to a store class that forward to `this.co.<name>` (the store may be wrapped in a Proxy, so no nested objects). */
export const CO_METHODS = ['listFolders', 'putFolders', 'removeFolders', 'listFiles', 'putFiles', 'removeFiles', 'addImport', 'findImport', 'listImports', 'getImport', 'deleteImport', 'getSheet', 'saveSheet', 'sheetsMatching', 'updateImportRemarks'];
export function attachCo(StoreClass) {
  for (const m of CO_METHODS) StoreClass.prototype[`co_${m}`] = function forward(...args) { return this.co[m](...args); };
}

export class MemoryCoData {
  constructor() { this.folders = new Map(); this.files = new Map(); this.imports = new Map(); this.sheets = new Map(); }

  async listFolders(owner) { return [...this.folders.values()].filter((r) => r.owner === owner).map((r) => ({ ...r })); }
  async putFolders(owner, rows) { for (const r of rows) this.folders.set(r.id, { ...r, owner }); }
  async removeFolders(owner, ids) { for (const id of ids) if (this.folders.get(id)?.owner === owner) this.folders.delete(id); }
  async listFiles(owner) { return [...this.files.values()].filter((r) => r.owner === owner).map((r) => ({ ...r })); }
  async putFiles(owner, rows) { for (const r of rows) this.files.set(r.id, { ...r, owner }); }
  async removeFiles(owner, ids) { for (const id of ids) if (this.files.get(id)?.owner === owner) this.files.delete(id); }

  /** [sheets]: [{ index, name, rows, cols, data, search }]; the import row keeps only their names and sizes. */
  async addImport(owner, imp, sheets) {
    this.imports.set(imp.id, { ...imp, owner, sheets: sheets.map(({ index, name, rows, cols }) => ({ index, name, rows, cols })) });
    for (const s of sheets) this.sheets.set(`${imp.id}:${s.index}`, { importId: imp.id, owner, index: s.index, name: s.name, data: structuredClone(s.data), search: s.search });
  }

  async findImport(owner, { hash, congregationId }) {
    return [...this.imports.values()].find((i) => i.owner === owner && i.contentHash === hash && i.congregationId === congregationId) ?? null;
  }

  async listImports(owner) { return [...this.imports.values()].filter((i) => i.owner === owner).map((i) => structuredClone(i)); }
  async getImport(owner, id) { const i = this.imports.get(id); return i && i.owner === owner ? structuredClone(i) : null; }

  async getSheet(owner, importId, index) {
    const s = this.sheets.get(`${importId}:${index}`);
    return s && s.owner === owner ? { index: s.index, name: s.name, data: structuredClone(s.data), search: s.search } : null;
  }

  async saveSheet(owner, importId, index, data, search) {
    const s = this.sheets.get(`${importId}:${index}`);
    if (!s || s.owner !== owner) return false;
    s.data = structuredClone(data);
    s.search = search;
    return true;
  }

  /** The "importId:index" of every sheet of [importIds] (null = all of the owner's) whose text holds ALL of [words]. */
  async sheetsMatching(owner, importIds, words) {
    const hits = new Set();
    for (const s of this.sheets.values()) {
      if (s.owner === owner && (!importIds || importIds.includes(s.importId)) && words.every((w) => s.search.includes(w))) hits.add(`${s.importId}:${s.index}`);
    }
    return hits;
  }

  async updateImportRemarks(owner, importId, remarks) {
    const imp = this.imports.get(importId);
    if (!imp || imp.owner !== owner) return false;
    imp.remarks = remarks;
    return true;
  }

  async deleteImport(owner, id) {
    if (this.imports.get(id)?.owner !== owner) return false;
    this.imports.delete(id);
    for (const k of [...this.sheets.keys()]) if (k.startsWith(`${id}:`)) this.sheets.delete(k);
    return true;
  }
}

const folderRow = (r) => ({ id: r.id, name: r.name, parentId: r.parent_id, createdAt: Number(r.created_at), updatedAt: Number(r.updated_at), deletedAt: r.deleted_at == null ? null : Number(r.deleted_at), deletedWith: r.deleted_with ?? null });
const fileRow = (r) => ({ id: r.id, name: r.name, ext: r.ext, mime: r.mime, size: Number(r.size_bytes), folderId: r.folder_id, createdAt: Number(r.created_at), updatedAt: Number(r.updated_at), deletedAt: r.deleted_at == null ? null : Number(r.deleted_at), deletedWith: r.deleted_with ?? null });
const importRow = (r) => ({
  id: r.id, congregationId: r.congregation_id, congregationName: r.congregation_name, remarks: r.remarks ?? '', fileName: r.file_name,
  sheets: typeof r.sheets === 'string' ? JSON.parse(r.sheets) : r.sheets, contentHash: r.content_hash, createdAt: Number(r.created_at),
});
/** Escapes the LIKE wildcards of a search word. */
const likeEscape = (s) => s.replace(/[\\%_]/g, (c) => `\\${c}`);

export class MysqlCoData {
  constructor(pool) { this.pool = pool; }

  static async migrate(pool) {
    await pool.query(`CREATE TABLE IF NOT EXISTS co_folders (
      id VARCHAR(40) NOT NULL PRIMARY KEY, owner VARCHAR(190) NOT NULL, name VARCHAR(200) NOT NULL, parent_id VARCHAR(40) NULL,
      created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, deleted_at BIGINT NULL, deleted_with VARCHAR(40) NULL, KEY idx_owner (owner, parent_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`);
    await pool.query(`CREATE TABLE IF NOT EXISTS co_files (
      id VARCHAR(40) NOT NULL PRIMARY KEY, owner VARCHAR(190) NOT NULL, name VARCHAR(255) NOT NULL, ext VARCHAR(10) NOT NULL, mime VARCHAR(100) NOT NULL,
      size_bytes BIGINT NOT NULL, folder_id VARCHAR(40) NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, deleted_at BIGINT NULL, deleted_with VARCHAR(40) NULL,
      KEY idx_owner (owner, folder_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`);
    // the first, record-based design of the Previous Manual Record was never released: replace its tables
    const [old] = await pool.query("SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'co_manual_imports' AND column_name = 'sheet_name'");
    if (old.length) { await pool.query('DROP TABLE co_manual_imports'); await pool.query('DROP TABLE IF EXISTS co_manual_records'); }
    await pool.query(`CREATE TABLE IF NOT EXISTS co_manual_imports (
      id VARCHAR(40) NOT NULL PRIMARY KEY, owner VARCHAR(190) NOT NULL, congregation_id VARCHAR(190) NOT NULL, congregation_name VARCHAR(255) NOT NULL, remarks VARCHAR(500) NOT NULL DEFAULT '',
      file_name VARCHAR(255) NOT NULL, sheets JSON NOT NULL, content_hash CHAR(64) NOT NULL, created_at BIGINT NOT NULL,
      KEY idx_owner (owner, content_hash)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`);
    await pool.query(`CREATE TABLE IF NOT EXISTS co_manual_sheets (
      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, import_id VARCHAR(40) NOT NULL, owner VARCHAR(190) NOT NULL, sheet_index INT NOT NULL, name VARCHAR(255) NOT NULL,
      data LONGTEXT NOT NULL, search_text MEDIUMTEXT NOT NULL, UNIQUE KEY idx_sheet (import_id, sheet_index), KEY idx_owner (owner)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`);
  }

  async listFolders(owner) { const [rows] = await this.pool.query('SELECT * FROM co_folders WHERE owner = ?', [owner]); return rows.map(folderRow); }

  async putFolders(owner, rows) {
    for (const r of rows) {
      await this.pool.query(
        'INSERT INTO co_folders (id, owner, name, parent_id, created_at, updated_at, deleted_at, deleted_with) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), parent_id = VALUES(parent_id), updated_at = VALUES(updated_at), deleted_at = VALUES(deleted_at), deleted_with = VALUES(deleted_with)',
        [r.id, owner, r.name, r.parentId ?? null, r.createdAt, r.updatedAt, r.deletedAt ?? null, r.deletedWith ?? null],
      );
    }
  }

  async removeFolders(owner, ids) { if (ids.length) await this.pool.query(`DELETE FROM co_folders WHERE owner = ? AND id IN (${ids.map(() => '?').join(',')})`, [owner, ...ids]); }
  async listFiles(owner) { const [rows] = await this.pool.query('SELECT * FROM co_files WHERE owner = ?', [owner]); return rows.map(fileRow); }

  async putFiles(owner, rows) {
    for (const r of rows) {
      await this.pool.query(
        'INSERT INTO co_files (id, owner, name, ext, mime, size_bytes, folder_id, created_at, updated_at, deleted_at, deleted_with) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), folder_id = VALUES(folder_id), updated_at = VALUES(updated_at), deleted_at = VALUES(deleted_at), deleted_with = VALUES(deleted_with)',
        [r.id, owner, r.name, r.ext, r.mime, r.size, r.folderId ?? null, r.createdAt, r.updatedAt, r.deletedAt ?? null, r.deletedWith ?? null],
      );
    }
  }

  async removeFiles(owner, ids) { if (ids.length) await this.pool.query(`DELETE FROM co_files WHERE owner = ? AND id IN (${ids.map(() => '?').join(',')})`, [owner, ...ids]); }

  /** The import and all its sheets are saved in ONE transaction: a failure leaves nothing behind. */
  async addImport(owner, imp, sheets) {
    const conn = await this.pool.getConnection();
    try {
      await conn.beginTransaction();
      await conn.query(
        'INSERT INTO co_manual_imports (id, owner, congregation_id, congregation_name, remarks, file_name, sheets, content_hash, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [imp.id, owner, imp.congregationId, imp.congregationName, imp.remarks, imp.fileName, JSON.stringify(sheets.map(({ index, name, rows, cols }) => ({ index, name, rows, cols }))), imp.contentHash, imp.createdAt],
      );
      for (const s of sheets) {
        await conn.query('INSERT INTO co_manual_sheets (import_id, owner, sheet_index, name, data, search_text) VALUES (?, ?, ?, ?, ?, ?)', [imp.id, owner, s.index, s.name, JSON.stringify(s.data), s.search]);
      }
      await conn.commit();
    } catch (e) {
      await conn.rollback();
      throw e;
    } finally {
      conn.release();
    }
  }

  async findImport(owner, { hash, congregationId }) {
    const [rows] = await this.pool.query('SELECT * FROM co_manual_imports WHERE owner = ? AND content_hash = ? AND congregation_id = ? LIMIT 1', [owner, hash, congregationId]);
    return rows.length ? importRow(rows[0]) : null;
  }

  async listImports(owner) { const [rows] = await this.pool.query('SELECT * FROM co_manual_imports WHERE owner = ? ORDER BY created_at DESC', [owner]); return rows.map(importRow); }
  async getImport(owner, id) { const [rows] = await this.pool.query('SELECT * FROM co_manual_imports WHERE owner = ? AND id = ?', [owner, id]); return rows.length ? importRow(rows[0]) : null; }

  async getSheet(owner, importId, index) {
    const [rows] = await this.pool.query('SELECT sheet_index, name, data, search_text FROM co_manual_sheets WHERE owner = ? AND import_id = ? AND sheet_index = ?', [owner, importId, Number(index)]);
    if (!rows.length) return null;
    const r = rows[0];
    return { index: r.sheet_index, name: r.name, data: typeof r.data === 'string' ? JSON.parse(r.data) : r.data, search: r.search_text };
  }

  async saveSheet(owner, importId, index, data, search) {
    const [res] = await this.pool.query('UPDATE co_manual_sheets SET data = ?, search_text = ? WHERE owner = ? AND import_id = ? AND sheet_index = ?', [JSON.stringify(data), search, owner, importId, Number(index)]);
    return res.affectedRows === 1;
  }

  async sheetsMatching(owner, importIds, words) {
    const where = ['owner = ?'];
    const params = [owner];
    if (importIds) {
      if (!importIds.length) return new Set();
      where.push(`import_id IN (${importIds.map(() => '?').join(',')})`);
      params.push(...importIds);
    }
    for (const w of words) { where.push("search_text LIKE ? ESCAPE '\\\\'"); params.push(`%${likeEscape(w)}%`); }
    const [rows] = await this.pool.query(`SELECT import_id, sheet_index FROM co_manual_sheets WHERE ${where.join(' AND ')}`, params);
    return new Set(rows.map((r) => `${r.import_id}:${r.sheet_index}`));
  }

  async updateImportRemarks(owner, importId, remarks) {
    const [res] = await this.pool.query('UPDATE co_manual_imports SET remarks = ? WHERE owner = ? AND id = ?', [remarks, owner, importId]);
    return res.affectedRows === 1;
  }

  async deleteImport(owner, id) {
    const conn = await this.pool.getConnection();
    try {
      await conn.beginTransaction();
      const [res] = await conn.query('DELETE FROM co_manual_imports WHERE owner = ? AND id = ?', [owner, id]);
      if (res.affectedRows === 1) await conn.query('DELETE FROM co_manual_sheets WHERE owner = ? AND import_id = ?', [owner, id]);
      await conn.commit();
      return res.affectedRows === 1;
    } catch (e) {
      await conn.rollback();
      throw e;
    } finally {
      conn.release();
    }
  }
}
