/**
 * LOCAL TEST SERVER with demo data - never used in production (it refuses NODE_ENV=production).
 *
 *   node scripts/dev-server.js            -> http://127.0.0.1:3100   (in-memory, everything is lost when it stops)
 *
 * It runs the REAL API (real sign-in, real rules, real sync), so a client - the Laravel website in particular - can be developed and tested
 * against it without touching live data. Demo sign-in:  username "demo.publisher"  password "demo1234".
 */
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { hashPassword } from '../src/passwords.js';
import { config } from '../src/config.js';

if (config.nodeEnv === 'production') throw new Error('The demo server is not for production.');
process.env.AUTH_SECRET ??= 'local-dev-secret-change-me-0123456789abcdef';
config.authSecret = process.env.AUTH_SECRET;

const store = new MemoryStore();
const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
const now = Date.now();

await put('congregations', 'congDemo', { id: 'congDemo', name: 'Demo Congregation', status: 'ACTIVE' });
await put('people', 'pubDemo', {
  username: 'demo.publisher', firstName: 'Demo', lastName: 'Publisher', isSuperAdmin: false, activeAdminRole: null, activeCongregationId: 'congDemo',
  status: 'ACTIVE', category: 'AUXILIARY_PIONEER',
});
await put('people', 'pubOther', { username: 'other.publisher', firstName: 'Other', lastName: 'Person', isSuperAdmin: false, activeAdminRole: null, activeCongregationId: 'congDemo', status: 'ACTIVE' });
await put('roleAssignments', 'ra1', { congregationId: 'congDemo', roleType: 'PUBLISHER:AUXILIARY_PIONEER', groupId: 'grp1', personId: 'pubDemo', status: 'ACTIVE' });
await put('roleAssignments', 'ra5', { congregationId: 'congDemo', roleType: 'PUBLISHER:REGULAR_PUBLISHER', groupId: 'grp1', personId: 'pubOther', status: 'ACTIVE' });
await put('monthlyReports', 'mr1', { publisherPersonId: 'pubDemo', congregationId: 'congDemo', category: 'AUXILIARY_PIONEER', periodMonth: 1790784000000, bibleStudiesCount: 2, returnVisitsCount: 3, hoursRendered: 35, status: 'SUBMITTED', submittedAt: now, remarks: 'Auxiliary pioneer', source: 'PUBLISHER' });
await put('monthlyReports', 'mr2', { publisherPersonId: 'pubOther', congregationId: 'congDemo', category: 'REGULAR_PUBLISHER', periodMonth: 1790784000000, bibleStudiesCount: 1, returnVisitsCount: 0, hoursRendered: null, status: 'SUBMITTED', submittedAt: now, source: 'PUBLISHER' });
await store.createAccount('pubDemo', await hashPassword('demo1234'));
await store.createAccount('pubOther', await hashPassword('other1234'));
await put('people', 'adminDemo', { username: 'demo.admin', firstName: 'Demo', lastName: 'Admin', isSuperAdmin: false, activeAdminRole: 'SECRETARY', activeCongregationId: 'congDemo', status: 'ACTIVE' });
await put('roleAssignments', 'ra2', { congregationId: 'congDemo', roleType: 'ADMIN:SECRETARY', personId: 'adminDemo', status: 'ACTIVE' });
await store.createAccount('adminDemo', await hashPassword('admin1234'));
await put('people', 'saDemo', { username: 'demo.super', firstName: 'Demo', lastName: 'Super', isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null, status: 'ACTIVE', accountStatus: 'ACTIVE' });
await put('roleAssignments', 'ra3', { congregationId: null, roleType: 'ADMIN:SUPER_ADMIN', personId: 'saDemo', status: 'ACTIVE' });
await store.createAccount('saDemo', await hashPassword('super1234'));
await put('people', 'coDemo', { username: 'demo.co', firstName: 'Carlos', lastName: 'Overseer', isSuperAdmin: false, activeAdminRole: 'CIRCUIT_OVERSEER', activeCongregationId: null, status: 'ACTIVE', accountStatus: 'ACTIVE' });
await put('roleAssignments', 'ra4', { congregationId: null, roleType: 'ADMIN:CIRCUIT_OVERSEER', personId: 'coDemo', status: 'ACTIVE' });
await put('circuitCodes', 'CO-1', { id: 'CO-1', code: 'CO-1', description: 'Demo circuit', status: 'ACTIVE', overseerPersonId: 'coDemo', createdAt: now });
await put('userAccessGrants', 'coDemo', { personId: 'coDemo', permissions: ['VIEW_CONGREGATIONS', 'VIEW_GROUPS', 'VIEW_ELDERS', 'VIEW_PUBLISHERS', 'PRINT_REPORTS', 'EXPORT_REPORTS'], scopeType: 'SELECTED_CONGREGATIONS', scopeCongregationIds: ['congDemo'], scopeGroupIds: [], circuitCode: 'CO-1' });
await store.createAccount('coDemo', await hashPassword('co1234'));
await put('congregationCircuits', 'congDemo', { id: 'congDemo', congregationId: 'congDemo', circuitOverseerPersonId: 'coDemo', circuitCode: 'CO-1', assignedAt: now, assignedByPersonId: 'saDemo' });
await put('groups', 'grp1', { id: 'grp1', congregationId: 'congDemo', name: 'Group 1', overseerPersonId: 'adminDemo', status: 'ACTIVE', createdAt: now });

// A Bible Text event with two texts, and a private note of someone else (which the demo publisher must never see).
await put('bibleTextCategories', 'ev1', {
  id: 'ev1', publisherPersonId: 'pubDemo', name: 'Circuit Assembly Talk', event: 'Circuit Assembly', speaker: 'Brother Cruz', subtopics: [], videos: [], createdAt: now, updatedAt: now,
});
await put('bibleTextRecords', 'bt1', {
  id: 'bt1', publisherPersonId: 'pubDemo', categoryId: 'ev1', bibleVersionId: 'nwt', languageId: 'en', bibleBookId: 'matthew', chapter: 6, verses: '33',
  verseText: '33 "Keep on, then, seeking first the Kingdom and his righteousness, and all these other things will be added to you."', remarks: 'Use at the door.', kind: '', order: 0, videos: [], createdAt: now, updatedAt: now,
});
await put('bibleTextRecords', 'bt2', {
  id: 'bt2', publisherPersonId: 'pubDemo', categoryId: 'ev1', kind: 'NOTE', verseText: 'Ask about their family first.', remarks: '', order: 0, videos: [], createdAt: now, updatedAt: now,
});
await put('bibleTextRecords', 'btOther', { id: 'btOther', publisherPersonId: 'pubOther', categoryId: 'evOther', verseText: 'SECRET of the other publisher', kind: 'NOTE', createdAt: now, updatedAt: now });

const port = Number(process.env.PORT ?? 3100);
createApp(store, { devAuth: false, health: () => ({ db: 'demo' }) }).listen(port, '127.0.0.1', () => console.log(`GoPreach DEMO API on http://127.0.0.1:${port}  (demo.publisher / demo1234, demo.admin / admin1234, demo.super / super1234, demo.co / co1234)`));
