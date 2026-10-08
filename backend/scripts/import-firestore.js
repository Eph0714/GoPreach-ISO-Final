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
import { createStore, congregationOf } from '../src/store.js';
import { writeFileSync } from 'node:fs';

const DRY = process.argv.includes('--dry-run');
// --sql <file>: do not connect to MySQL; write the data as a SQL file to import with phpMyAdmin instead.
const SQL_FILE = process.argv.includes('--sql') ? process.argv[process.argv.indexOf('--sql') + 1] : null;
const COLLECTIONS = [
  'people', 'congregations', 'groups', 'elderTitles', 'roleAssignments', 'userAccessGrants', 'territories', 'territoryAssignments',
  'territoryAssignmentBarangays', 'publisherTerritoryAssignments', 'schedules', 'interestedPeople', 'forwardRequests',
  'publisherForwardRequests', 'houseHolderAssignments', 'monthlyReports', 'deletedRecords', 'appSettings', 'sharedLocations',
  'savedLocations', 'mapPins', 'cartAssignments', 'locationSharingSettings', 'midweekMeetingSchedules', 'publicTalkSchedules',
  'announcements', 'creditHourCategories', 'creditHourRecords', 'plannerDays', 'monthlyPlannerGoals', 'weeklyPlannerGoals',
  'yearlyPlannerGoals', 'ministryTimerSessions', 'bibleTextCategories', 'bibleTextRecords', 'preachingTimeRecords',
  'dashboardModuleLayouts', 'groupChats', 'territoryDrawings', 'territoryDrawingAudits', 'territoryBounds',
  // Circuit Overseer, attendance, statistics and Comparative Report workflows
  'circuitCodes', 'congregationCircuits', 'coFieldServiceMonthStatus', 'coFieldServiceReportEvents', 'meetingAttendance',
  'meetingAttendanceSettings', 'meetingAttendanceEvents', 'congregationMonthlyStatistics', 'congregationComparativeReports',
  'comparativeReportHistory', 'comparativeReportRemarks',
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

let sqlSeq = 0;
const sqlLines = [];
const hex = (s) => 'CONVERT(0x' + Buffer.from(s, 'utf8').toString('hex') + ' USING utf8mb4)';
const sqlStore = {
  async transaction(fn) {
    await fn({
      put: async (collection, id, data) => {
        sqlSeq += 1;
        const cong = congregationOf(collection, data);
        sqlLines.push(
          'INSERT INTO documents (collection, doc_id, data, version, seq, deleted, congregation_id, updated_at, updated_by) VALUES (' +
            [hex(collection), hex(id), hex(JSON.stringify(data)), 1, sqlSeq, 0, cong == null ? 'NULL' : hex(String(cong)), Date.now(), "'import'"].join(', ') +
            ') ON DUPLICATE KEY UPDATE data = VALUES(data), version = version + 1, seq = VALUES(seq), deleted = 0, congregation_id = VALUES(congregation_id), updated_at = VALUES(updated_at), updated_by = VALUES(updated_by);',
        );
      },
    });
  },
  async close() {},
};
const store = DRY ? null : SQL_FILE ? sqlStore : await createStore();

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
if (SQL_FILE) {
  sqlLines.push("UPDATE counters SET value = GREATEST(value, " + sqlSeq + ") WHERE name = 'seq';");
  writeFileSync(SQL_FILE, sqlLines.join('\n') + '\n');
  console.log('SQL file written: ' + SQL_FILE + ' (' + sqlLines.length + ' statements)');
}
