import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { hashPassword } from '../src/passwords.js';
import { pinProblem, patternProblem, lockDurationMs, } from '../src/quickLogin.js';
import { base32, totp } from '../src/security.js';

const SECRET = 'test-secret-test-secret-test-secret';
const KEY = 'k'.repeat(43);
const dev = { id: 'web-1', name: 'Chrome on Windows', type: 'web', os: 'Windows' };

async function world() {
  const st = new MemoryStore();
  await st.transaction(async (tx) => {
    for (const [id, u] of [['ana001', 'ana'], ['ben001', 'ben']]) await tx.put('people', id, { username: u, firstName: u, lastName: 'X', activeCongregationId: 'c1' }, 'seed');
  });
  for (const id of ['ana001', 'ben001']) await st.createAccount(id, await hashPassword('Secret123'));
  const srv = createApp(st, { authOptions: { secret: SECRET, limits: { login: { max: 1000 }, spray: { max: 1000 }, verify: { max: 1000 }, act: { max: 1000 }, quick: { max: 1000 } } } }).listen(0);
  const url = `http://127.0.0.1:${srv.address().port}`;
  const post = (path, body, token) => fetch(url + path, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body ?? {}) });
  const password = async (id) => (await post('/v1/auth/login', { email: `${id}@gopreach.app`, password: 'Secret123', device: dev })).json();
  const quick = (id, body) => post('/v1/auth/quick-login/login', { email: `${id}@gopreach.app`, deviceKey: KEY, device: dev, ...body });
  return { st, post, password, quick, close: () => srv.close() };
}

test('PIN and pattern rules match the app', () => {
  assert.equal(pinProblem('482916'), null);
  for (const bad of ['12345', '111111', '123456', '654321', '482482', 'abcdef']) assert.ok(pinProblem(bad), bad);
  assert.equal(patternProblem('0-4-8-7-3-1'), null);
  for (const bad of ['0-1-2-3-4', '0-1-2-3-4-5', '0-1-2-3-4-4', 'x']) assert.ok(patternProblem(bad), bad);
  assert.equal(lockDurationMs(4), 0);
  assert.equal(lockDurationMs(5), 30_000);
  assert.equal(lockDurationMs(10), 60_000);
});

test('enrol, availability, sign in, and the wrong-try lock', async () => {
  const w = await world();
  try {
    const s = await w.password('ana001');
    assert.equal((await w.post('/v1/auth/quick-login/enroll', { method: 'pin', secret: '123456', deviceKey: KEY }, s.accessToken)).status, 422);
    assert.equal((await w.post('/v1/auth/quick-login/enroll', { method: 'pin', secret: '482916', deviceKey: KEY }, s.accessToken)).status, 200);
    const avail = (id, key = KEY) => w.post('/v1/public/quick-login/available', { email: `${id}@gopreach.app`, deviceKey: key }).then((r) => r.json());
    assert.deepEqual(await avail('ana001'), { pin: true, pattern: false });
    assert.deepEqual(await avail('ben001'), { pin: false, pattern: false }); // another account on the same browser: nothing
    assert.deepEqual(await avail('ana001', 'z'.repeat(43)), { pin: false, pattern: false }); // another browser: nothing

    assert.equal((await w.quick('ben001', { method: 'pin', secret: '482916' })).status, 401); // never opens another account
    const ok = await w.quick('ana001', { method: 'pin', secret: '482916' });
    assert.equal(ok.status, 200);
    const t = await ok.json();
    assert.ok(t.accessToken && t.sessionId);

    for (let i = 0; i < 4; i++) assert.equal((await w.quick('ana001', { method: 'pin', secret: '000111' })).status, 401);
    assert.equal((await w.quick('ana001', { method: 'pin', secret: '000111' })).status, 429);
    assert.equal((await w.quick('ana001', { method: 'pin', secret: '482916' })).status, 429); // locked even for the right PIN
  } finally { w.close(); }
});

test('enrolling needs a recently typed password; changing the password removes PIN and pattern', async () => {
  const w = await world();
  try {
    const s = await w.password('ana001');
    await w.post('/v1/auth/quick-login/enroll', { method: 'pattern', secret: '0-4-8-7-3-1', deviceKey: KEY }, s.accessToken);
    assert.equal((await w.quick('ana001', { method: 'pattern', secret: '0-4-8-7-3-1' })).status, 200);
    const st = await (await w.post('/v1/auth/quick-login/status', { deviceKey: KEY }, s.accessToken)).json();
    assert.deepEqual(st, { pin: false, pattern: true });
    assert.equal((await w.post('/v1/auth/password', { newPassword: 'Another456' }, s.accessToken)).status, 200);
    assert.equal((await w.quick('ana001', { method: 'pattern', secret: '0-4-8-7-3-1' })).status, 401);
  } finally { w.close(); }
});

test('a PIN does not skip two-factor sign-in', async () => {
  const w = await world();
  try {
    const s = await w.password('ana001');
    await w.post('/v1/auth/quick-login/enroll', { method: 'pin', secret: '482916', deviceKey: KEY }, s.accessToken);
    const setup = await (await w.post('/v1/security/2fa/totp/setup', {}, s.accessToken)).json();
    await w.post('/v1/security/2fa/totp/confirm', { code: totp(base32.decode(setup.secret)) }, s.accessToken);
    const r = await w.quick('ana001', { method: 'pin', secret: '482916' });
    assert.equal(r.status, 202);
    assert.equal((await r.json()).twoFactorRequired, true);
  } finally { w.close(); }
});
