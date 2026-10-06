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

let admin;
try {
  admin = (await import('firebase-admin')).default;
} catch {
  console.error('firebase-admin is not installed. Run:  npm i --no-save firebase-admin');
  process.exit(1);
}
admin.initializeApp();
const db = admin.firestore();
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
console.log(`Done: ${total} documents ${DRY ? '(dry run — nothing written)' : 'written'}.`);
await store?.close();
