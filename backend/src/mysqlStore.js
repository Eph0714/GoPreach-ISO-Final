import mysql from 'mysql2/promise';
import { congregationOf } from './store.js';

/** MySQL implementation of the store contract (see store.js). */
export class MysqlStore {
  static async connect(db) {
    const pool = mysql.createPool({
      host: db.host, port: db.port, user: db.user, password: db.password, database: db.database,
      waitForConnections: true, connectionLimit: 8, charset: 'utf8mb4',
      // JSON columns come back already parsed.
    });
    await pool.query('SELECT 1');
    // Uploaded files live in the database (the web server's folder is replaced on every deploy). Created on first start; safe to repeat.
    await pool.query(`CREATE TABLE IF NOT EXISTS file_blobs (
      path VARCHAR(500) NOT NULL PRIMARY KEY, token VARCHAR(64) NOT NULL, owner VARCHAR(190) NOT NULL, mime VARCHAR(100) NOT NULL,
      size_bytes INT NOT NULL, data LONGBLOB NOT NULL, updated_at BIGINT NOT NULL, UNIQUE KEY idx_token (token)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`);
    // Sign-in accounts (the person id is the key) and the long-lived refresh tokens that keep a phone signed in. Created on first start.
    await pool.query(`CREATE TABLE IF NOT EXISTS accounts (
      person_id VARCHAR(190) NOT NULL PRIMARY KEY, pw_hash VARCHAR(400) NOT NULL, disabled TINYINT(1) NOT NULL DEFAULT 0,
      created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, last_login BIGINT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`);
    await pool.query(`CREATE TABLE IF NOT EXISTS refresh_tokens (
      token_hash CHAR(64) NOT NULL PRIMARY KEY, person_id VARCHAR(190) NOT NULL, auth_time BIGINT NOT NULL, expires_at BIGINT NOT NULL,
      KEY idx_person (person_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`);
    return new MysqlStore(pool);
  }

  constructor(pool) { this.pool = pool; }

  static row(r) {
    const data = typeof r.data === 'string' ? JSON.parse(r.data) : r.data;
    return { data, version: r.version, seq: Number(r.seq), deleted: !!r.deleted };
  }

  async get(collection, id) {
    const [rows] = await this.pool.query('SELECT data, version, seq, deleted FROM documents WHERE collection = ? AND doc_id = ?', [collection, id]);
    return rows.length ? MysqlStore.row(rows[0]) : null;
  }

  /** Live (not deleted) documents of one collection, optionally only one congregation's. */
  async list(collection, congregationId, runner = this.pool) {
    const params = [collection];
    let where = 'collection = ? AND deleted = 0';
    if (congregationId !== undefined) { where += ' AND congregation_id = ?'; params.push(congregationId); }
    const [rows] = await runner.query(`SELECT doc_id, data, version, seq FROM documents WHERE ${where}`, params);
    return rows.map((r) => ({ id: r.doc_id, ...MysqlStore.row({ ...r, deleted: 0 }) }));
  }

  /** The people document whose username is exactly [username]. The SQL narrows it; the strict compare keeps it case-sensitive. */
  async findPersonByUsername(username) {
    const [rows] = await this.pool.query(
      "SELECT doc_id, data, version, seq FROM documents WHERE collection = 'people' AND deleted = 0 AND JSON_UNQUOTE(JSON_EXTRACT(data, '$.username')) = ? LIMIT 20",
      [username],
    );
    const hit = rows.map((r) => ({ id: r.doc_id, ...MysqlStore.row({ ...r, deleted: 0 }) })).find((r) => r.data?.username === username);
    return hit ? { id: hit.id, data: hit.data } : null;
  }

  /** All writes in one transaction that first locks the counter row, so sequence numbers follow commit order. */
  async transaction(fn) {
    const conn = await this.pool.getConnection();
    try {
      await conn.beginTransaction();
      const [[counter]] = await conn.query("SELECT value FROM counters WHERE name = 'seq' FOR UPDATE");
      let seq = Number(counter.value);
      const nextSeq = () => ++seq;
      const write = async (collection, id, data, deleted, actor) => {
        const [rows] = await conn.query('SELECT version FROM documents WHERE collection = ? AND doc_id = ? FOR UPDATE', [collection, id]);
        const version = (rows.length ? rows[0].version : 0) + 1;
        const s = nextSeq();
        await conn.query(
          `INSERT INTO documents (collection, doc_id, data, version, seq, deleted, congregation_id, updated_at, updated_by)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON DUPLICATE KEY UPDATE data = VALUES(data), version = VALUES(version), seq = VALUES(seq), deleted = VALUES(deleted),
             congregation_id = VALUES(congregation_id), updated_at = VALUES(updated_at), updated_by = VALUES(updated_by)`,
          [collection, id, JSON.stringify(data), version, s, deleted ? 1 : 0, congregationOf(collection, data), Date.now(), actor ?? null],
        );
        return { version, seq: s };
      };
      const tx = {
        get: async (collection, id) => {
          const [rows] = await conn.query('SELECT data, version, seq, deleted FROM documents WHERE collection = ? AND doc_id = ?', [collection, id]);
          return rows.length ? MysqlStore.row(rows[0]) : null;
        },
        list: (collection, congregationId) => this.list(collection, congregationId, conn),
        put: (collection, id, data, actor) => write(collection, id, data, false, actor),
        remove: async (collection, id, actor) => {
          const cur = await tx.get(collection, id);
          return write(collection, id, cur?.data ?? {}, true, actor);
        },
      };
      const result = await fn(tx);
      await conn.query("UPDATE counters SET value = ? WHERE name = 'seq'", [seq]);
      await conn.commit();
      return result;
    } catch (e) {
      await conn.rollback();
      throw e;
    } finally {
      conn.release();
    }
  }

  async changesSince(seq, { collections = null, limit = 500 } = {}) {
    const params = [seq];
    let where = 'seq > ?';
    if (collections?.length) { where += ` AND collection IN (${collections.map(() => '?').join(',')})`; params.push(...collections); }
    params.push(limit);
    const [rows] = await this.pool.query(
      `SELECT collection, doc_id, data, version, seq, deleted FROM documents WHERE ${where} ORDER BY seq ASC LIMIT ?`, params,
    );
    return rows.map((r) => ({ collection: r.collection, id: r.doc_id, ...MysqlStore.row(r) }));
  }

  /** Stores (or replaces) the file at [path]; the new random [token] is its public download address. */
  async putFile({ path, token, owner, mime, data }) {
    await this.pool.query(
      'INSERT INTO file_blobs (path, token, owner, mime, size_bytes, data, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE token = VALUES(token), owner = VALUES(owner), mime = VALUES(mime), size_bytes = VALUES(size_bytes), data = VALUES(data), updated_at = VALUES(updated_at)',
      [path, token, owner, mime, data.length, data, Date.now()],
    );
  }

  async getFileByToken(token) {
    const [rows] = await this.pool.query('SELECT mime, data FROM file_blobs WHERE token = ? LIMIT 1', [token]);
    return rows.length ? { mime: rows[0].mime, data: rows[0].data } : null;
  }

  async deleteFile(path) { await this.pool.query('DELETE FROM file_blobs WHERE path = ?', [path]); }

  // ---- sign-in accounts ----
  async getAccount(personId) {
    const [rows] = await this.pool.query('SELECT person_id, pw_hash, disabled FROM accounts WHERE person_id = ?', [personId]);
    return rows.length ? { personId: rows[0].person_id, pwHash: rows[0].pw_hash, disabled: !!rows[0].disabled } : null;
  }

  /** Creates the account; false when one already exists for [personId]. */
  async createAccount(personId, pwHash) {
    const now = Date.now();
    const [res] = await this.pool.query('INSERT IGNORE INTO accounts (person_id, pw_hash, created_at, updated_at) VALUES (?, ?, ?, ?)', [personId, pwHash, now, now]);
    return res.affectedRows === 1;
  }

  async setPassword(personId, pwHash) {
    await this.pool.query('UPDATE accounts SET pw_hash = ?, updated_at = ? WHERE person_id = ?', [pwHash, Date.now(), personId]);
  }

  async touchLogin(personId) { await this.pool.query('UPDATE accounts SET last_login = ? WHERE person_id = ?', [Date.now(), personId]); }

  async addRefreshToken({ tokenHash, personId, authTime, expiresAt }) {
    await this.pool.query('INSERT INTO refresh_tokens (token_hash, person_id, auth_time, expires_at) VALUES (?, ?, ?, ?)', [tokenHash, personId, authTime, expiresAt]);
    await this.pool.query('DELETE FROM refresh_tokens WHERE expires_at < ?', [Date.now()]);
  }

  /** Returns and removes the refresh token (each one is used once), or null when unknown / expired. */
  async takeRefreshToken(tokenHash) {
    const [rows] = await this.pool.query('SELECT person_id, auth_time, expires_at FROM refresh_tokens WHERE token_hash = ?', [tokenHash]);
    if (!rows.length) return null;
    const [res] = await this.pool.query('DELETE FROM refresh_tokens WHERE token_hash = ?', [tokenHash]);
    if (res.affectedRows !== 1 || Number(rows[0].expires_at) < Date.now()) return null;
    return { personId: rows[0].person_id, authTime: Number(rows[0].auth_time) };
  }

  async deleteRefreshTokens(personId) { await this.pool.query('DELETE FROM refresh_tokens WHERE person_id = ?', [personId]); }

  async close() { await this.pool.end(); }
}
