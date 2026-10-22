import { congregationOf } from './store.js';
import { MemoryCoData, attachCo } from './coStore.js';

/** In-memory store with the same contract as MysqlStore — for tests and for running the API with no database. */
export class MemoryStore {
  constructor() {
    this.docs = new Map(); // "collection/id" -> { collection, id, data, version, seq, deleted, congregationId, updatedAt, updatedBy }
    this.seq = 0;
    this.queue = Promise.resolve();
    this.co = new MemoryCoData(); // the Circuit Overseer's File Manager and Previous Manual Record (see coStore.js)
  }

  key(collection, id) { return `${collection}/${id}`; }

  async get(collection, id) {
    const d = this.docs.get(this.key(collection, id));
    return d ? { data: structuredClone(d.data), version: d.version, seq: d.seq, deleted: d.deleted } : null;
  }

  /** Live (not deleted) documents of one collection, optionally only one congregation's. */
  async list(collection, congregationId) {
    return [...this.docs.values()]
      .filter((d) => d.collection === collection && !d.deleted && (congregationId === undefined || d.congregationId === congregationId))
      .map((d) => ({ id: d.id, data: structuredClone(d.data), version: d.version, seq: d.seq }));
  }

  /** The people document whose username is exactly [username] (case-sensitive, like the Firestore query it replaces). */
  async findPersonByUsername(username) {
    for (const d of this.docs.values()) {
      if (d.collection === 'people' && !d.deleted && d.data.username === username) return { id: d.id, data: structuredClone(d.data) };
    }
    return null;
  }

  /** Writers run one at a time (like the MySQL counter row lock). */
  transaction(fn) {
    const run = this.queue.then(async () => {
      const staged = new Map();
      const tx = {
        get: async (collection, id) => {
          const k = this.key(collection, id);
          if (staged.has(k)) {
            const s = staged.get(k);
            return s ? { data: structuredClone(s.data), version: s.version, seq: s.seq, deleted: s.deleted } : null;
          }
          return this.get(collection, id);
        },
        list: async (collection, congregationId) => {
          const rows = new Map((await this.list(collection, congregationId)).map((r) => [r.id, r]));
          for (const [k, s] of staged) {
            if (!k.startsWith(collection + '/') || k.slice(collection.length + 1).includes('/')) continue;
            const id = k.slice(collection.length + 1);
            if (!s || s.deleted || (congregationId !== undefined && s.congregationId !== congregationId)) rows.delete(id);
            else rows.set(id, { id, data: structuredClone(s.data), version: s.version, seq: s.seq });
          }
          return [...rows.values()];
        },
        put: async (collection, id, data, actor) => this.stage(staged, collection, id, data, false, actor),
        remove: async (collection, id, actor) => {
          const cur = await tx.get(collection, id);
          return this.stage(staged, collection, id, cur?.data ?? {}, true, actor);
        },
      };
      const result = await fn(tx);
      for (const [k, v] of staged) this.docs.set(k, v);
      return result;
    });
    this.queue = run.catch(() => {});
    return run;
  }

  stage(staged, collection, id, data, deleted, actor) {
    const k = this.key(collection, id);
    const prev = staged.get(k) ?? this.docs.get(k);
    const next = {
      collection, id, data: structuredClone(data), deleted,
      version: (prev?.version ?? 0) + 1,
      seq: ++this.seq,
      congregationId: congregationOf(collection, data),
      updatedAt: Date.now(),
      updatedBy: actor ?? null,
    };
    staged.set(k, next);
    return { version: next.version, seq: next.seq };
  }

  async changesSince(seq, { collections = null, congregations = null, limit = 500 } = {}) {
    return [...this.docs.values()]
      .filter((d) => d.seq > seq)
      .filter((d) => !collections || collections.includes(d.collection))
      .sort((a, b) => a.seq - b.seq)
      .slice(0, limit)
      .map((d) => ({ collection: d.collection, id: d.id, data: structuredClone(d.data), version: d.version, seq: d.seq, deleted: d.deleted }));
  }

  async putFile({ path, token, owner, mime, data }) { (this.files ??= new Map()).set(path, { token, owner, mime, data }); }

  async getFileByToken(token) {
    for (const f of (this.files ?? new Map()).values()) if (f.token === token) return { mime: f.mime, data: f.data };
    return null;
  }

  async getFileByPath(path) { const f = this.files?.get(path); return f ? { mime: f.mime, data: f.data } : null; }
  async getFileOwner(path) { return this.files?.get(path)?.owner ?? null; }

  async deleteFile(path) { this.files?.delete(path); }

  // ---- sign-in accounts ----
  async getAccount(personId) { const a = (this.accounts ??= new Map()).get(personId); return a ? { personId, pwHash: a.pwHash, disabled: !!a.disabled } : null; }

  async createAccount(personId, pwHash) {
    const m = (this.accounts ??= new Map());
    if (m.has(personId)) return false;
    m.set(personId, { pwHash, disabled: false });
    return true;
  }

  async setPassword(personId, pwHash) { const a = (this.accounts ??= new Map()).get(personId); if (a) a.pwHash = pwHash; }

  async touchLogin() {}

  // ---- passkeys (WebAuthn public keys; the private key never leaves the phone) ----
  async addPasskey(p) { (this.passkeys ??= new Map()).set(p.credentialId, { ...p, lastUsedAt: null }); }
  async getPasskey(credentialId) { const p = (this.passkeys ??= new Map()).get(credentialId); return p ? structuredClone(p) : null; }
  async listPasskeys(personId) { return [...(this.passkeys ??= new Map()).values()].filter((p) => p.personId === personId).map((p) => structuredClone(p)); }
  async updatePasskeyCounter(credentialId, counter) { const p = (this.passkeys ??= new Map()).get(credentialId); if (p) { p.counter = counter; p.lastUsedAt = Date.now(); } }
  async deletePasskey(personId, credentialId) {
    const m = (this.passkeys ??= new Map());
    const p = m.get(credentialId);
    if (!p || p.personId !== personId) return false;
    return m.delete(credentialId);
  }

  async addRefreshToken({ tokenHash, personId, authTime, expiresAt }) { (this.refresh ??= new Map()).set(tokenHash, { personId, authTime, expiresAt }); }

  async takeRefreshToken(tokenHash) {
    const m = (this.refresh ??= new Map());
    const r = m.get(tokenHash);
    m.delete(tokenHash);
    return r && r.expiresAt >= Date.now() ? { personId: r.personId, authTime: r.authTime } : null;
  }

  async deleteRefreshTokens(personId) { for (const [k, v] of (this.refresh ??= new Map())) if (v.personId === personId) this.refresh.delete(k); }

  async close() {}
}

attachCo(MemoryStore);
