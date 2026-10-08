import { scrypt as scryptCb, randomBytes, timingSafeEqual, createCipheriv } from 'node:crypto';
import { promisify } from 'node:util';

const scrypt = promisify(scryptCb);

/**
 * Password storage for GoPreach's own sign-in.
 *
 * New and changed passwords are stored as   scrypt$<N>$<r>$<p>$<salt b64>$<hash b64>   (Node's built-in scrypt, 16-byte random salt).
 *
 * Accounts brought over from Firebase keep Firebase's own hash until their first successful sign-in, when it is replaced by the
 * native one above (so nobody has to reset their password). Firebase's hash is its modified scrypt:
 *   key  = scrypt(password, base64(salt) + base64(saltSeparator), N = 2^memCost, r = rounds, p = 1, 64 bytes)
 *   hash = AES-256-CTR(key[0..32), zero IV) applied to base64(signerKey)
 * stored as   fb$<hash b64>$<salt b64>   — the project-wide parameters (signerKey, saltSeparator, rounds, memCost) are server settings.
 */
const N = 16384, R = 8, P = 1, KEYLEN = 64;

export async function hashPassword(password) {
  const salt = randomBytes(16);
  const key = await scrypt(password, salt, KEYLEN, { N, r: R, p: P, maxmem: 128 * N * R * 2 });
  return `scrypt$${N}$${R}$${P}$${salt.toString('base64')}$${key.toString('base64')}`;
}

async function verifyNative(password, stored) {
  const [, n, r, p, salt, hash] = stored.split('$');
  const expected = Buffer.from(hash, 'base64');
  const key = await scrypt(password, Buffer.from(salt, 'base64'), expected.length, { N: Number(n), r: Number(r), p: Number(p), maxmem: 256 * Number(n) * Number(r) });
  return key.length === expected.length && timingSafeEqual(key, expected);
}

/** Firebase's modified scrypt. [params] = { signerKey, saltSeparator, rounds, memCost } from the Firebase console. */
export async function verifyFirebaseHash(password, hashB64, saltB64, params) {
  if (!params?.signerKey || !params?.saltSeparator) return false;
  const salt = Buffer.concat([Buffer.from(saltB64, 'base64'), Buffer.from(params.saltSeparator, 'base64')]);
  const n = 2 ** Number(params.memCost);
  const r = Number(params.rounds);
  const key = await scrypt(password, salt, KEYLEN, { N: n, r, p: 1, maxmem: 256 * n * r });
  const cipher = createCipheriv('aes-256-ctr', key.subarray(0, 32), Buffer.alloc(16));
  const out = Buffer.concat([cipher.update(Buffer.from(params.signerKey, 'base64')), cipher.final()]);
  const expected = Buffer.from(hashB64, 'base64');
  return out.length === expected.length && timingSafeEqual(out, expected);
}

/** Checks [password] against a stored value. Returns { ok, upgrade } — upgrade is true when the stored value is a Firebase hash. */
export async function verifyPassword(password, stored, firebaseParams) {
  if (typeof stored !== 'string') return { ok: false, upgrade: false };
  if (stored.startsWith('scrypt$')) return { ok: await verifyNative(password, stored), upgrade: false };
  if (stored.startsWith('fb$')) {
    const [, hash, salt] = stored.split('$');
    return { ok: await verifyFirebaseHash(password, hash, salt, firebaseParams), upgrade: true };
  }
  return { ok: false, upgrade: false };
}
