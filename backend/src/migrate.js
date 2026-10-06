import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import mysql from 'mysql2/promise';
import { config } from './config.js';

/** Creates the tables (safe to re-run). Usage: `npm run migrate` with the DB_* values set in .env. */
const sql = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'schema.sql'), 'utf8');
const conn = await mysql.createConnection({ ...config.db, multipleStatements: true, charset: 'utf8mb4' });
await conn.query(sql);
console.log('Schema is up to date.');
await conn.end();
