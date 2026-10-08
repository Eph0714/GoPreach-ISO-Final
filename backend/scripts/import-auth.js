/**
 * One-time copy of the Firebase Authentication accounts (with their password hashes) into GoPreach's own `accounts` table, so
 * nobody has to reset a password. It writes a SQL file to import with phpMyAdmin (the database only accepts local connections).
 *
 *   npm i --no-save firebase-admin
 *   set GOOGLE_APPLICATION_CREDENTIALS=C:\path\to\service-account.json
 *   npm run import:auth -- --sql C:\path\to\gopreach-accounts.sql
 *
 * The file contains password HASHES (not passwords). Treat it like the service-account key: import it, then delete it.
 * Only accounts whose email ends in @gopreach.internal are copied (the address the app derives from a person id).
 * The server also needs Firebase's hash settings (FIREBASE_HASH_SIGNER_KEY, FIREBASE_HASH_SALT_SEPARATOR, and FIREBASE_HASH_ROUNDS /
 * FIREBASE_HASH_MEM_COST if they are not 8 and 14) until every imported account has signed in once; see HOSTINGER_MIGRATION.md.
 */
import { writeFileSync } from 'node:fs';

const OUT = process.argv.includes('--sql') ? process.argv[process.argv.indexOf('--sql') + 1] : null;
if (!OUT) { console.error('Usage: npm run import:auth -- --sql <output file>'); process.exit(1); }
const DOMAIN = '@gopreach.internal';
const B64 = /^[A-Za-z0-9+/_=-]+$/;

let auth;
try {
  const { initializeApp, applicationDefault } = await import('firebase-admin/app');
  const { getAuth } = await import('firebase-admin/auth');
  initializeApp({ credential: applicationDefault() });
  auth = getAuth();
} catch (e) {
  console.error('Could not start firebase-admin. Run "npm i --no-save firebase-admin" and set GOOGLE_APPLICATION_CREDENTIALS.\n', e.message);
  process.exit(1);
}

const rows = [];
let skipped = 0;
let noPassword = 0;
let token;
do {
  const page = await auth.listUsers(1000, token);
  for (const u of page.users) {
    const email = u.email ?? '';
    if (!email.endsWith(DOMAIN)) { skipped++; continue; }
    const personId = email.slice(0, -DOMAIN.length);
    if (!u.passwordHash || !u.passwordSalt || !B64.test(u.passwordHash) || !B64.test(u.passwordSalt) || !/^[A-Za-z0-9_-]{6,64}$/.test(personId)) { noPassword++; continue; }
    rows.push({ personId, hash: `fb$${u.passwordHash}$${u.passwordSalt}`, disabled: u.disabled ? 1 : 0, created: Date.parse(u.metadata.creationTime) || Date.now() });
  }
  token = page.pageToken;
} while (token);

const lines = [
  'CREATE TABLE IF NOT EXISTS accounts (person_id VARCHAR(190) NOT NULL PRIMARY KEY, pw_hash VARCHAR(400) NOT NULL, disabled TINYINT(1) NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, last_login BIGINT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;',
  'CREATE TABLE IF NOT EXISTS refresh_tokens (token_hash CHAR(64) NOT NULL PRIMARY KEY, person_id VARCHAR(190) NOT NULL, auth_time BIGINT NOT NULL, expires_at BIGINT NOT NULL, KEY idx_person (person_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;',
  ...rows.map((r) => `INSERT IGNORE INTO accounts (person_id, pw_hash, disabled, created_at, updated_at) VALUES ('${r.personId}', '${r.hash}', ${r.disabled}, ${r.created}, ${Date.now()});`),
];
writeFileSync(OUT, lines.join('\n') + '\n');
console.log(`${rows.length} accounts written to ${OUT} (${noPassword} without a usable password hash, ${skipped} other accounts skipped).`);
