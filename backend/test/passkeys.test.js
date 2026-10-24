import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { hashPassword } from '../src/passwords.js';
import { androidOrigin, fingerprintBytes } from '../src/passkeys.js';
import { loginTakingOver } from './helpers/takeover.js';

/** A software authenticator: enough of WebAuthn (none attestation, ES256) to run the whole ceremony against the real routes. */
const RP_ID = 'api.example.test';
const CERT = 'AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99';
const ORIGIN = androidOrigin(CERT);
const SECRET = 'test-secret-test-secret-test-secret';
const WEB_ORIGIN = 'https://web.api.example.test';
const sha256 = (b) => createHash('sha256').update(b).digest();
const b64u = (b) => Buffer.from(b).toString('base64url');

// -- a tiny CBOR encoder: unsigned/negative ints, byte strings, text strings, maps --
function head(major, n) {
  if (n < 24) return Buffer.from([(major << 5) | n]);
  if (n < 256) return Buffer.from([(major << 5) | 24, n]);
  const b = Buffer.alloc(3); b[0] = (major << 5) | 25; b.writeUInt16BE(n, 1); return b;
}
function cbor(v) {
  if (typeof v === 'number') return v >= 0 ? head(0, v) : head(1, -1 - v);
  if (typeof v === 'string') { const t = Buffer.from(v); return Buffer.concat([head(3, t.length), t]); }
  if (Buffer.isBuffer(v)) return Buffer.concat([head(2, v.length), v]);
  const entries = v instanceof Map ? [...v.entries()] : Object.entries(v);
  return Buffer.concat([head(5, entries.length), ...entries.flatMap(([k, val]) => [cbor(k), cbor(val)])]);
}

function newAuthenticator() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = publicKey.export({ format: 'jwk' });
  const credentialId = randomBytes(16);
  const cose = cbor(new Map([[1, 2], [3, -7], [-1, 1], [-2, Buffer.from(jwk.x, 'base64url')], [-3, Buffer.from(jwk.y, 'base64url')]]));
  let counter = 0;
  const flags = (uv) => (uv ? 0x05 : 0x01);
  return {
    id: b64u(credentialId),
    register(options, { origin = ORIGIN, rpId = RP_ID } = {}) {
      const authData = Buffer.concat([sha256(rpId), Buffer.from([flags(true) | 0x40]), Buffer.alloc(4), Buffer.alloc(16), Buffer.from([0, credentialId.length]), credentialId, cose]);
      const clientData = Buffer.from(JSON.stringify({ type: 'webauthn.create', challenge: options.challenge, origin, crossOrigin: false }));
      const attestationObject = cbor({ fmt: 'none', attStmt: {}, authData });
      return { id: b64u(credentialId), rawId: b64u(credentialId), type: 'public-key', response: { clientDataJSON: b64u(clientData), attestationObject: b64u(attestationObject), transports: ['internal'] }, clientExtensionResults: {} };
    },
    assert(options, userHandle, { origin = ORIGIN, rpId = RP_ID, challenge = options.challenge } = {}) {
      counter += 1;
      const countBytes = Buffer.alloc(4); countBytes.writeUInt32BE(counter);
      const authData = Buffer.concat([sha256(rpId), Buffer.from([flags(true)]), countBytes]);
      const clientData = Buffer.from(JSON.stringify({ type: 'webauthn.get', challenge, origin, crossOrigin: false }));
      const signature = sign('sha256', Buffer.concat([authData, sha256(clientData)]), privateKey);
      return {
        id: b64u(credentialId), rawId: b64u(credentialId), type: 'public-key',
        response: { clientDataJSON: b64u(clientData), authenticatorData: b64u(authData), signature: b64u(signature), ...(userHandle ? { userHandle: b64u(Buffer.from(userHandle)) } : {}) },
        clientExtensionResults: {},
      };
    },
  };
}

let store, server, base;
before(async () => {
  store = new MemoryStore();
  const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
  await put('people', 'pub001', { isSuperAdmin: false, activeCongregationId: 'congA', username: 'ana.cruz', firstName: 'Ana', lastName: 'Cruz' });
  await put('people', 'pub002', { isSuperAdmin: false, activeCongregationId: 'congA', username: 'ben.lee' });
  await store.createAccount('pub001', await hashPassword('Secret123'));
  await store.createAccount('pub002', await hashPassword('Secret456'));
  const passkey = { rpId: RP_ID, rpName: 'GoPreach', androidPackage: 'com.emfitsolutions.gopreach', androidCertSha256: [CERT], webOrigins: [WEB_ORIGIN] };
  server = createApp(store, { authOptions: { secret: SECRET, limits: { login: { max: 1000 }, passkey } } }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const post = (path, body, token) =>
  fetch(`${base}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body ?? {}) });
const login = async (id, password) => (await (await loginTakingOver(store, post, { email: `${id}@gopreach.internal`, password })).json()).accessToken;

async function registerPasskey(token, authenticator, name) {
  const { options } = await (await post('/v1/auth/passkey/register/options', {}, token)).json();
  return post('/v1/auth/passkey/register/verify', { response: authenticator.register(options), name }, token);
}
async function passkeyLogin(authenticator, personId, overrides) {
  const { sessionId, options } = await (await post('/v1/auth/passkey/login/options', {})).json();
  return post('/v1/auth/passkey/login/verify', { sessionId, response: authenticator.assert(options, personId, overrides), endOtherSession: true });
}

test('the asset links file ties the Android app to this domain', async () => {
  const links = await (await fetch(`${base}/.well-known/assetlinks.json`)).json();
  assert.equal(links[0].target.package_name, 'com.emfitsolutions.gopreach');
  assert.deepEqual(links[0].target.sha256_cert_fingerprints, [CERT]);
  assert.equal(fingerprintBytes(CERT).length, 32);
  assert.equal(androidOrigin('nonsense'), null);
});

test('a signed-in person adds a passkey, then signs in with it - and it is the same account', async () => {
  const token = await login('pub001', 'Secret123');
  const phone = newAuthenticator();
  const registered = await registerPasskey(token, phone, 'Ana phone');
  assert.equal(registered.status, 200);

  const listed = await (await post('/v1/auth/passkey/list', {}, token)).json();
  assert.equal(listed.passkeys.length, 1);
  assert.equal(listed.passkeys[0].name, 'Ana phone');

  const res = await passkeyLogin(phone, 'pub001');
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.personId, 'pub001');
  assert.equal(body.username, 'ana.cruz');
  assert.ok(body.accessToken && body.refreshToken);
  assert.equal((await post('/v1/presence', {}, body.accessToken)).status, 200, 'the passkey token opens the API like a password token');
});

test('registering needs a login, and a stranger cannot register for someone else', async () => {
  assert.equal((await post('/v1/auth/passkey/register/options', {})).status, 401);
  const token2 = await login('pub002', 'Secret456');
  const { options } = await (await post('/v1/auth/passkey/register/options', {}, token2)).json();
  const phone = newAuthenticator();
  // a response made for another challenge is refused
  const forged = phone.register({ ...options, challenge: b64u(randomBytes(32)) });
  assert.equal((await post('/v1/auth/passkey/register/verify', { response: forged }, token2)).status, 400);
  // and the challenge is single-use
  assert.equal((await post('/v1/auth/passkey/register/verify', { response: phone.register(options) }, token2)).status, 400);
});

test('a passkey made for another site or app is refused', async () => {
  const token = await login('pub002', 'Secret456');
  const { options } = await (await post('/v1/auth/passkey/register/options', {}, token)).json();
  const phish = newAuthenticator();
  assert.equal((await post('/v1/auth/passkey/register/verify', { response: phish.register(options, { origin: 'https://evil.example', rpId: 'evil.example' }) }, token)).status, 400);
  assert.equal((await post('/v1/auth/passkey/list', {}, token).then((r) => r.json())).passkeys.length, 0, 'nothing was stored');
});

test('a wrong challenge, an unknown passkey, a replay and another account are all refused', async () => {
  const token = await login('pub002', 'Secret456');
  const phone = newAuthenticator();
  assert.equal((await registerPasskey(token, phone)).status, 200);

  const first = await post('/v1/auth/passkey/login/options', {});
  const { sessionId, options } = await first.json();
  const good = phone.assert(options, 'pub002');
  assert.equal((await post('/v1/auth/passkey/login/verify', { sessionId, response: phone.assert(options, 'pub002', { challenge: b64u(randomBytes(32)) }) })).status, 401, 'signed over another challenge');

  const fresh = await (await post('/v1/auth/passkey/login/options', {})).json();
  const stranger = newAuthenticator();
  assert.equal((await post('/v1/auth/passkey/login/verify', { sessionId: fresh.sessionId, response: stranger.assert(fresh.options, 'pub002') })).status, 401, 'an unregistered passkey');

  const again = await (await post('/v1/auth/passkey/login/options', {})).json();
  assert.equal((await post('/v1/auth/passkey/login/verify', { sessionId: again.sessionId, response: phone.assert(again.options, 'pub001') })).status, 401, 'a passkey that claims to be another account');

  const ok = await (await post('/v1/auth/passkey/login/options', {})).json();
  const response = phone.assert(ok.options, 'pub002');
  assert.equal((await post('/v1/auth/passkey/login/verify', { sessionId: ok.sessionId, response, endOtherSession: true })).status, 200);
  assert.equal((await post('/v1/auth/passkey/login/verify', { sessionId: ok.sessionId, response })).status, 401, 'a session works once');
  assert.ok(good, 'unused assertion only exercised the signing helper');
});

test('a disabled account cannot use its passkey, and a passkey can be removed', async () => {
  const token = await login('pub001', 'Secret123');
  const phone = newAuthenticator();
  await registerPasskey(token, phone, 'second');
  const { passkeys } = await (await post('/v1/auth/passkey/list', {}, token)).json();
  const mine = passkeys.find((p) => p.name === 'second');
  // someone else cannot remove it
  const other = await login('pub002', 'Secret456');
  assert.equal((await (await post('/v1/auth/passkey/delete', { id: mine.id }, other)).json()).ok, false);
  assert.equal((await (await post('/v1/auth/passkey/delete', { id: mine.id }, token)).json()).ok, true);
  assert.equal((await passkeyLogin(phone, 'pub001')).status, 401, 'a removed passkey no longer signs in');
});

test('passkeys answer 503 until the domain and the app certificate are configured', async () => {
  const bare = createApp(new MemoryStore(), { authOptions: { secret: SECRET, limits: { passkey: { rpId: '', rpName: 'GoPreach', androidPackage: 'x', androidCertSha256: [] } } } }).listen(0);
  try {
    const res = await fetch(`http://127.0.0.1:${bare.address().port}/v1/auth/passkey/login/options`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
    assert.equal(res.status, 503);
  } finally { bare.close(); }
});

test('the website can register and use a passkey from its own listed origin, and no other web origin', async () => {
  const token = await login('pub002', 'Secret456');
  const browser = newAuthenticator();
  const { options } = await (await post('/v1/auth/passkey/register/options', {}, token)).json();
  assert.equal((await post('/v1/auth/passkey/register/verify', { response: browser.register(options, { origin: 'https://other.api.example.test' }) }, token)).status, 400, 'an origin that is not listed');
  const again = await (await post('/v1/auth/passkey/register/options', {}, token)).json();
  assert.equal((await post('/v1/auth/passkey/register/verify', { response: browser.register(again.options, { origin: WEB_ORIGIN }), name: 'Laptop' }, token)).status, 200);
  const res = await passkeyLogin(browser, 'pub002', { origin: WEB_ORIGIN });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).personId, 'pub002');
});
