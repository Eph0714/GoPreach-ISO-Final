/**
 * One-time (re-runnable) copy of Firestore into the backend store.
 *
 *   npm i --no-save firebase-admin          (only on the machine running the import — it is NOT a server dependency)
 *   set GOOGLE_APPLICATION_CREDENTIALS=C:\path\to\service-account.json     (Firebase console → Project settings → Service accounts)
 *   npm run import:firestore -- --dry-run   (counts only)
 *   npm run import:firestore                (writes to the store configured in .env)
 *
 * Keep the service-account file OFF the server and out of git; delete it when the migration is finished.
 * Each document is written as { ...fields } keyed by its Firestore id (the id is not duplicated inside the data, exactly
 * like the app's uploads). Re-running overwrites with the latest Firestore values, so it can be repeated for a final delta.
 */
import { createStore } from '../src/store.js';

const DRY = process.argv.includes('--dry-run');
const COLLECTIONS = [
  'people', 'congregations', 'groups', 'elderTitles', 'roleAssignments', 'userAccessGrants', 'territories', 'territoryAssignments',
  'territoryAssignmentBarangays', 'publisherTerritoryAssignments', 'schedules', 'interestedPeople', 'forwardRequests',
  'publisherForwardRequests', 'houseHolderAssignments', 'monthlyReports', 'deletedRecords', 'appSettings', 'sharedLocations',
  'savedLocations', 'mapPins', 'cartAssignments', 'locationSharingSettings', 'midweekMeetingSchedules', 'publicTalkSchedules',
  'announcements', 'creditHourCategories', 'creditHourRecords', 'plannerDays', 'monthlyPlannerGoals', 'weeklyPlannerGoals',
  'yearlyPlannerGoals', 'ministryTimerSessions', 'bibleTextCategories', 'bibleTextRecords', 'preachingTimeRecords',
  'dashboardModuleLayouts', 'groupChats', 'territoryDrawings', 'territoryDrawingAudits', 'territoryBounds',
];
// auditLog (local-only by design) and presence are intentionally not migrated.

let db;
try {
  const { initializeApp, applicationDefault } = await import('firebase-admin/app');
  const { getFirestore } = await import('firebase-admin/firestore');
  initializeApp({ credential: applicationDefault() });
  db = getFirestore();
} catch (e) {
  console.error('firebase-admin is not installed or could not start. Run:  npm i --no-save firebase-admin');
  console.error(String(e.message ?? e).slice(0, 300));
  process.exit(1);
}
const store = DRY ? null : await createStore();

/** Firestore Timestamps / GeoPoints → plain JSON the app's Gson understands (epoch millis). */
function plain(value) {
  if (value === null || typeof value !== 'object') return value;
  if (typeof value.toMillis === 'function') return value.toMillis();
  if (Array.isArray(value)) return value.map(plain);
  if (typeof value.latitude === 'number' && typeof value.longitude === 'number') return { lat: value.latitude, lng: value.longitude };
  return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, plain(v)]));
}

let total = 0;
async function copy(path, ref) {
  if (DRY) {
    // Counts only, with Firestore's aggregation query (billed as a tiny fraction of reading every document).
    const { count } = (await ref.count().get()).data();
    total += count;
    console.log(`[dry] ${path}: ${count}`);
    return;
  }
  const snap = await ref.get();
  if (!DRY && snap.size) {
    for (let i = 0; i < snap.docs.length; i += 100) {
      const chunk = snap.docs.slice(i, i + 100);
      await store.transaction(async (tx) => { for (const d of chunk) await tx.put(path, d.id, plain(d.data()), 'import'); });
    }
  }
  total += snap.size;
  console.log(`${DRY ? '[dry] ' : ''}${path}: ${snap.size}`);
  // Subcollections (e.g. interestedPeople/{id}/visits) are copied under their full path.
  for (const d of snap.docs) {
    for (const sub of await d.ref.listCollections()) await copy(`${path}/${d.id}/${sub.id}`, sub);
  }
}

for (const name of COLLECTIONS) await copy(name, db.collection(name));
if (DRY) {
  // Known subcollections, counted across every parent (a dry run does not walk each document).
  for (const sub of ['visits', 'messages']) {
    const { count } = (await db.collectionGroup(sub).count().get()).data();
    total += count;
    console.log(`[dry] (all) ${sub}: ${count}`);
  }
}
console.log(`Done: ${total} documents ${DRY ? '(dry run — nothing written)' : 'written'}.`);
await store?.close();
