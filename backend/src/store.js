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

/**
 * Which of [candidates] ({ id, data }) a typed username means. An exact match wins; otherwise the match ignores capital letters and surrounding spaces
 * (phone keyboards capitalize the first letter), but only when exactly one account fits, so two usernames that differ only by case never get mixed up.
 */
export function pickUsername(candidates, typed) {
  const want = String(typed ?? '').trim();
  if (!want) return null;
  const of = (c) => (typeof c.data?.username === 'string' ? c.data.username : null);
  const exact = candidates.find((c) => of(c) === want) ?? candidates.find((c) => of(c)?.trim() === want);
  if (exact) return exact;
  const loose = candidates.filter((c) => of(c)?.trim().toLowerCase() === want.toLowerCase());
  return loose.length === 1 ? loose[0] : null;
}

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
