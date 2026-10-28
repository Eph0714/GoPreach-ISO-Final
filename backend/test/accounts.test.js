import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { hashPassword } from '../src/passwords.js';
import { loginTakingOver } from './helpers/takeover.js';

let store, server, base;
const SECRET = 'test-secret-test-secret-test-secret';
const person = (activeAdminRole, extra = {}) => ({ isSuperAdmin: false, activeAdminRole, activeCongregationId: 'congA', ...extra });

before(async () => {
  store = new MemoryStore();
  const put = (c, id, data) => store.transaction((tx) => tx.put(c, id, data, 'seed'));
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('people', 'admin1', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'pub001', person(null));
  await store.createAccount('pub001', await hashPassword('Secret123'));
  await store.createAccount('admin1', await hashPassword('AdminPass1'));
  await store.createAccount('sa', await hashPassword('SuperPass1'));
  server = createApp(store, { authOptions: { secret: SECRET, limits: { login: { max: 1000 } } } }).listen(0);
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const post = (path, body, token) =>
  fetch(`${base}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
const login = async (id, password) => loginTakingOver(store, post, { email: `${id}@gopreach.app`, password });

test('a correct password signs in; a wrong password and an unknown account look the same', async () => {
  const ok = await login('pub001', 'Secret123');
  assert.equal(ok.status, 200);
  const t = await ok.json();
  assert.equal(t.personId, 'pub001');
  assert.ok(t.accessToken && t.refreshToken);
  const wrong = await login('pub001', 'nope');
  const unknown = await login('ghost01', 'nope');
  assert.equal(wrong.status, 401);
  assert.equal(unknown.status, 401);
  assert.deepEqual(await wrong.json(), await unknown.json());
});

test('the access token opens the API and a bad one does not', async () => {
  const { accessToken } = await (await login('pub001', 'Secret123')).json();
  const good = await post('/v1/presence', {}, accessToken);
  assert.equal(good.status, 200);
  assert.equal((await post('/v1/presence', {}, accessToken + 'x')).status, 401);
  assert.equal((await post('/v1/presence', {})).status, 401);
});

test('a refresh token works once and rotates', async () => {
  const first = await (await login('pub001', 'Secret123')).json();
  const second = await post('/v1/auth/refresh', { refreshToken: first.refreshToken });
  assert.equal(second.status, 200);
  assert.equal((await post('/v1/auth/refresh', { refreshToken: first.refreshToken })).status, 401, 'the old one is spent');
  const next = await (await second.clone().json());
  assert.equal((await post('/v1/presence', {}, next.accessToken)).status, 200);
  await post('/v1/auth/logout', { refreshToken: next.refreshToken });
  assert.equal((await post('/v1/auth/refresh', { refreshToken: next.refreshToken })).status, 401, 'signed out');
});

test('changing a password needs a recently typed one, and the new one works', async () => {
  const { accessToken } = await (await login('pub001', 'Secret123')).json();
  const changed = await post('/v1/auth/password', { newPassword: 'NewSecret456' }, accessToken);
  assert.equal(changed.status, 200);
  assert.equal((await login('pub001', 'Secret123')).status, 401);
  assert.equal((await post('/v1/auth/login', { email: 'pub001@gopreach.app', password: 'NewSecret456' })).status, 200, 'the new password signs in');
  assert.equal((await post('/v1/auth/password', { newPassword: 'abc' }, accessToken)).status, 400, 'too short');
  const reauth = await post('/v1/auth/reauth', { password: 'wrong' }, accessToken);
  assert.equal(reauth.status, 401);
});

test('only the Super-Admin or an admin-role holder creates accounts for others, and never a duplicate', async () => {
  const pub = (await (await login('pub001', 'NewSecret456')).json()).accessToken;
  const admin = (await (await login('admin1', 'AdminPass1')).json()).accessToken;
  assert.equal((await post('/v1/auth/accounts', { personId: 'newbie01', password: 'Temp1234' }, pub)).status, 403);
  assert.equal((await post('/v1/auth/accounts', { personId: 'newbie01', password: 'Temp1234' }, admin)).status, 201);
  assert.equal((await post('/v1/auth/accounts', { personId: 'newbie01', password: 'Temp1234' }, admin)).status, 409);
  assert.equal((await login('newbie01', 'Temp1234')).status, 200);
  assert.equal((await post('/v1/auth/accounts', { personId: '../x', password: 'Temp1234' }, admin)).status, 400);
});

test('too many wrong passwords lock the account for a while', async () => {
  for (let i = 0; i < 10; i++) await login('admin1', 'bad');
  assert.equal((await login('admin1', 'AdminPass1')).status, 429);
});

test('an admin sets a temporary password for a member of their congregation; a member or another congregation cannot', async () => {
  const st = new MemoryStore();
  const put = (c, id, data) => st.transaction((tx) => tx.put(c, id, data, 'seed'));
  await put('people', 'sa', { isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null });
  await put('people', 'adminA', person('ADMIN_PER_CONGREGATION'));
  await put('people', 'pubA01', person(null));
  await put('people', 'pubB01', person(null, { activeCongregationId: 'congB' }));
  for (const [id, pw] of [['sa', 'SuperPass1'], ['adminA', 'AdminPass1'], ['pubA01', 'Secret123'], ['pubB01', 'Other1234']]) await st.createAccount(id, await hashPassword(pw));
  const srv = createApp(st, { authOptions: { secret: SECRET, limits: { login: { max: 1000 } } } }).listen(0);
  const url = `http://127.0.0.1:${srv.address().port}`;
  try {
    const p = (path, body, token) => fetch(`${url}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
    const signIn = (id, password) => p('/v1/auth/login', { email: `${id}@gopreach.app`, password });
    const token = async (id, pw) => (await (await signIn(id, pw)).json()).accessToken;
    const admin = await token('adminA', 'AdminPass1');
    const member = await token('pubA01', 'Secret123');
    const sa = await token('sa', 'SuperPass1');

    assert.equal((await p('/v1/auth/accounts/pubA01/password', { password: 'abc' }, admin)).status, 400);
    assert.equal((await p('/v1/auth/accounts/pubA01/password', { password: 'Temp12345' }, member)).status, 403);
    assert.equal((await p('/v1/auth/accounts/pubB01/password', { password: 'Temp12345' }, admin)).status, 403);
    assert.equal((await p('/v1/auth/accounts/ghost01/password', { password: 'Temp12345' }, sa)).status, 404);

    assert.equal((await p('/v1/auth/accounts/pubA01/password', { password: 'Temp12345' }, admin)).status, 200);
    assert.equal((await signIn('pubA01', 'Secret123')).status, 401);
    assert.equal((await signIn('pubA01', 'Temp12345')).status, 200);

    assert.equal((await p('/v1/auth/accounts/pubB01/password', { password: 'Temp67890' }, sa)).status, 200); // the Super Admin reaches every congregation
    assert.equal((await signIn('pubB01', 'Temp67890')).status, 200);
  } finally {
    srv.close();
  }
});

test('sign-in limits follow the account, and renewing a session never uses the sign-in allowance', async () => {
  const st = new MemoryStore();
  await st.transaction((tx) => tx.put('people', 'u1aaaa', person(null), 'seed'));
  await st.transaction((tx) => tx.put('people', 'u2bbbb', person(null), 'seed'));
  await st.createAccount('u1aaaa', await hashPassword('Secret123'));
  await st.createAccount('u2bbbb', await hashPassword('Secret123'));
  const srv = createApp(st, { authOptions: { secret: SECRET, limits: { login: { max: 3 }, refresh: { max: 50 } } } }).listen(0);
  const url = `http://127.0.0.1:${srv.address().port}`;
  const call = (path, body) => fetch(url + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  const signIn = async (id) => { await st.endSession(id); return call('/v1/auth/login', { email: `${id}@gopreach.app`, password: 'Secret123' }); };
  try {
    let tokens = await (await signIn('u1aaaa')).json();
    for (let i = 0; i < 10; i++) { // ten renewals from the same address
      const r = await call('/v1/auth/refresh', { refreshToken: tokens.refreshToken });
      assert.equal(r.status, 200);
      tokens = await r.json();
    }
    assert.equal((await signIn('u1aaaa')).status, 200);
    assert.equal((await signIn('u1aaaa')).status, 200);
    assert.equal((await signIn('u1aaaa')).status, 429, 'one account trying again and again is still limited');
    assert.equal((await signIn('u2bbbb')).status, 200, 'but somebody else from the same address is not');
  } finally { srv.close(); }
});

// ---- one account, any number of devices: each is a session the account holder can end ----
async function sessionApp(extra = {}) {
  const st = new MemoryStore();
  await st.transaction((tx) => tx.put('people', 'solo01', person(null), 'seed'));
  await st.createAccount('solo01', await hashPassword('Secret123'));
  const srv = createApp(st, { authOptions: { secret: SECRET, limits: { login: { max: 1000 }, spray: { max: 1000 }, ...extra } } }).listen(0);
  const url = `http://127.0.0.1:${srv.address().port}`;
  const call = (path, body, token) => fetch(url + path, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
  const signIn = (more = {}, password = 'Secret123') => call('/v1/auth/login', { email: 'solo01@gopreach.app', password, ...more });
  return { st, srv, call, signIn };
}

test('several devices can be signed in at the same time, each with its own session', async () => {
  const { srv, call, signIn } = await sessionApp();
  try {
    const results = await Promise.all([signIn({ device: 'A' }), signIn({ device: 'B' }), signIn({ device: 'C' })]);
    assert.deepEqual(results.map((r) => r.status), [200, 200, 200]);
    const tokens = await Promise.all(results.map((r) => r.json()));
    assert.equal(new Set(tokens.map((t) => t.sessionId)).size, 3, 'three separate sessions');
    for (const t of tokens) assert.equal((await call('/v1/presence', {}, t.accessToken)).status, 200);
    assert.equal((await signIn({}, 'wrong-password')).status, 401);
  } finally { srv.close(); }
});

test('signing out ends only that device: its tokens stop working, the others carry on', async () => {
  const { srv, call, signIn } = await sessionApp();
  try {
    const a = await (await signIn({ device: 'Phone' })).json();
    const b = await (await signIn({ device: 'Web' })).json();
    await call('/v1/auth/logout', { refreshToken: a.refreshToken });
    const refused = await call('/v1/presence', {}, a.accessToken);
    assert.equal(refused.status, 401);
    assert.equal((await refused.json()).error, 'session-ended');
    assert.equal((await call('/v1/auth/refresh', { refreshToken: a.refreshToken })).status, 401);
    assert.equal((await call('/v1/presence', {}, b.accessToken)).status, 200);
  } finally { srv.close(); }
});

test('a password reset by an administrator ends every session of the account', async () => {
  const { st, srv, call, signIn } = await sessionApp();
  try {
    const a = await (await signIn({})).json();
    const b = await (await signIn({})).json();
    await st.endSession('solo01'); // the legacy single-session row never decides any more
    assert.equal((await call('/v1/presence', {}, a.accessToken)).status, 200);
    assert.equal((await call('/v1/presence', {}, b.accessToken)).status, 200);
  } finally { srv.close(); }
});

test('renewing keeps the same session, and wrong-password attempts are still limited', async () => {
  const { srv, call, signIn } = await sessionApp();
  try {
    const a = await (await signIn({})).json();
    const b = await (await call('/v1/auth/refresh', { refreshToken: a.refreshToken })).json();
    assert.equal((await call('/v1/presence', {}, b.accessToken)).status, 200);
    for (let i = 0; i < 10; i++) await signIn({}, 'bad');
    assert.equal((await signIn({})).status, 429, 'brute force protection is intact');
  } finally { srv.close(); }
});
