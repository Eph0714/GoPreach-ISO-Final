import 'node:process';

/** Reads a minimal .env (KEY=VALUE lines) without a dependency; real environment variables win. */
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const envFile = join(here, '..', '.env');
if (existsSync(envFile)) {
  for (const line of readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
    if (m && !line.trim().startsWith('#') && process.env[m[1]] === undefined) {
      process.env[m[1]] = m[2].replace(/^"(.*)"$/, '$1');
    }
  }
}

export const config = {
  nodeEnv: process.env.NODE_ENV ?? 'development',
  port: Number(process.env.PORT ?? 3000),
  store: process.env.STORE ?? 'mysql',
  /** Signs the app's sign-in tokens. Required: without it the sign-in endpoints answer 503 and no request is accepted. */
  authSecret: process.env.AUTH_SECRET ?? '',
  /** Firebase's password-hash settings (console → Authentication → Users → ⋮ → Password hash parameters), only for imported accounts. */
  firebaseHash: {
    signerKey: process.env.FIREBASE_HASH_SIGNER_KEY ?? '',
    saltSeparator: process.env.FIREBASE_HASH_SALT_SEPARATOR ?? '',
    rounds: Number(process.env.FIREBASE_HASH_ROUNDS ?? 8),
    memCost: Number(process.env.FIREBASE_HASH_MEM_COST ?? 14),
  },
  db: {
    host: process.env.DB_HOST ?? 'localhost',
    port: Number(process.env.DB_PORT ?? 3306),
    database: process.env.DB_NAME ?? '',
    user: process.env.DB_USER ?? '',
    password: process.env.DB_PASSWORD ?? '',
  },
};
