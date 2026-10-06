/**
 * The storage contract the API uses. Two implementations: MySQL (production, Hostinger) and an in-memory one (tests,
 * local development without a database). Every write goes through `transaction(fn)`, which serializes writers so the
 * change sequence ("seq") is issued in commit order.
 *
 *   store.get(collection, id)                     -> { data, version, seq, deleted } | null  (tombstones included)
 *   store.transaction(async (tx) => ...)          -> tx.get / tx.put / tx.remove, all atomic
 *   store.changesSince(seq, { collections, congregations, limit }) -> rows with seq > given, oldest first
 */
import { config } from './config.js';

/** The congregation a record belongs to, for read filtering. Mirrors how the Firestore rules read each collection. */
export function congregationOf(collection, data) {
  if (!data) return null;
  if (collection === 'people') return data.activeCongregationId ?? null;
  if (collection === 'congregations') return data.id ?? null;
  return data.congregationId ?? null;
}

export async function createStore() {
  if (config.store === 'memory') {
    const { MemoryStore } = await import('./memoryStore.js');
    return new MemoryStore();
  }
  const { MysqlStore } = await import('./mysqlStore.js');
  return MysqlStore.connect(config.db);
}
