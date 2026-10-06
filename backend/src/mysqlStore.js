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

  async close() { await this.pool.end(); }
}
