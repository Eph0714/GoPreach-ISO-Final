import { loadActor } from './policy/index.js';

/**
 * "Online Users" for the Hostinger backend. Who is online is short-lived by nature, so it is kept in the server's memory (not the
 * database, not the sync log): each signed-in app on screen reports in every 30 seconds, and anyone seen within the last
 * [ONLINE_WINDOW_MS] counts as online. A server restart simply empties the list until the next beats (under a minute).
 *
 * POST /v1/presence  { beat?: boolean }  ->  { online: [{ personId, congregationId, lastSeen }] }
 *   beat = true records the caller as online now (their active congregation is read from their own person record, never from the request).
 *   The list follows the old Firestore rule: the Super-Admin sees everyone, anyone else only their own active congregation.
 */
export const ONLINE_WINDOW_MS = 75_000;

export function mountPresence(app, store, auth, now = () => Date.now()) {
  const seen = new Map(); // personId -> { congregationId, lastSeen }

  app.post('/v1/presence', auth, async (req, res) => {
    try {
      const actor = await loadActor(req.personId, (c, i) => store.get(c, i));
      if (!actor.known) return res.status(403).json({ error: 'Unknown user' });
      const t = now();
      if (req.body?.beat === true) seen.set(req.personId, { congregationId: actor.congregationId, lastSeen: t });
      const online = [];
      for (const [personId, row] of seen) {
        if (t - row.lastSeen > ONLINE_WINDOW_MS) { seen.delete(personId); continue; }
        if (actor.isSuperAdmin || (actor.congregationId != null && row.congregationId === actor.congregationId)) {
          online.push({ personId, congregationId: row.congregationId, lastSeen: row.lastSeen });
        }
      }
      res.json({ online });
    } catch (e) {
      console.error('presence failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });
}
