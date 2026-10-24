import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { hashPassword } from '../src/passwords.js';
import { maskEmail } from '../src/loginApproval.js';

const SECRET = 'test-secret-test-secret-test-secret';

async function setup({ mail = true, requestMs } = {}) {
  const st = new MemoryStore();
  await st.transaction((tx) => tx.put('people', 'solo01', { isSuperAdmin: false, activeAdminRole: null, activeCongregationId: 'congA', email: 'juan.delacruz@example.com' }, 'seed'));
  await st.createAccount('solo01', await hashPassword('Secret123'));
  const sent = [];
  const mailer = mail ? { send: async (to, subject, text) => { sent.push({ to, subject, text }); } } : null;
  const srv = createApp(st, { authOptions: { secret: SECRET, mailer, loginRequestMs: requestMs, limits: { login: { max: 1000 }, spray: { max: 1000 } } } }).listen(0);
  const url = `http://127.0.0.1:${srv.address().port}`;
  const call = (path, body, token, method = 'POST') => fetch(url + path, { method, headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: method === 'GET' ? undefined : JSON.stringify(body) });
  const signIn = (device, password = 'Secret123') => call('/v1/auth/login', { email: 'solo01@gopreach.app', password, device });
  return { st, srv, call, signIn, sent };
}

test('a second device with the right password does NOT replace the first session: it waits for approval', async () => {
  const { srv, call, signIn } = await setup();
  try {
    const first = await (await signIn('Phone A')).json();
    const second = await signIn('Phone B');
    assert.equal(second.status, 409);
    const info = await second.json();
    assert.equal(info.error, 'session-active');
    assert.ok(info.requestId && info.pollToken);
    assert.equal(info.methods.device, true);
    assert.equal(info.methods.email.masked, 'j******@example.com');
    assert.equal((await call('/v1/presence', {}, first.accessToken)).status, 200, 'the first session is untouched');
    assert.equal((await signIn('Phone C', 'wrong')).status, 401, 'a wrong password creates no request and reveals nothing');
    // endOtherSession no longer lets a password take the session over
    const sneaky = await call('/v1/auth/login', { email: 'solo01@gopreach.app', password: 'Secret123', endOtherSession: true });
    assert.equal(sneaky.status, 409);
    assert.equal((await call('/v1/presence', {}, first.accessToken)).status, 200);
  } finally { srv.close(); }
});

test('the first device sees the request and Yes, It is Me lets the second in, once', async () => {
  const { srv, call, signIn } = await setup();
  try {
    const first = await (await signIn('Phone A')).json();
    const info = await (await signIn('Phone B')).json();
    const poll = () => call('/v1/auth/login-requests/status', { requestId: info.requestId, pollToken: info.pollToken });
    assert.equal((await (await poll()).json()).status, 'pending');
    const list = await (await call('/v1/auth/login-requests', undefined, first.accessToken, 'GET')).json();
    assert.equal(list.requests.length, 1);
    assert.equal(list.requests[0].device, 'Phone B');
    assert.equal(list.requests[0].id, info.requestId);
    assert.equal((await call(`/v1/auth/login-requests/${info.requestId}/approve`, {}, first.accessToken)).status, 200);
    assert.equal((await (await poll()).json()).status, 'approved');
    const approval = { requestId: info.requestId, pollToken: info.pollToken };
    assert.equal((await call('/v1/presence', {}, first.accessToken)).status, 200, 'approval alone changes nothing: the new device must still sign in');
    const wrongPw = await call('/v1/auth/login', { email: 'solo01@gopreach.app', password: 'nope', device: 'Phone B', approval });
    assert.equal(wrongPw.status, 401, 'an approval never replaces the password');
    const done = await (await call('/v1/auth/login', { email: 'solo01@gopreach.app', password: 'Secret123', device: 'Phone B', approval })).json();
    assert.ok(done.accessToken);
    assert.equal((await call('/v1/presence', {}, done.accessToken)).status, 200);
    assert.equal((await call('/v1/presence', {}, first.accessToken)).status, 401, 'approving hands the account to the new device');
    const again = await call('/v1/auth/login', { email: 'solo01@gopreach.app', password: 'Secret123', device: 'Phone C', approval });
    assert.equal(again.status, 409, 'a request is redeemed only once');
  } finally { srv.close(); }
});

test('No, Deny Access keeps the first session and the request can never be used', async () => {
  const { srv, call, signIn } = await setup();
  try {
    const first = await (await signIn('Phone A')).json();
    const info = await (await signIn('Phone B')).json();
    assert.equal((await call(`/v1/auth/login-requests/${info.requestId}/deny`, {}, first.accessToken)).status, 200);
    const r = await (await call('/v1/auth/login-requests/status', { requestId: info.requestId, pollToken: info.pollToken })).json();
    assert.equal(r.status, 'denied');
    assert.ok(!r.accessToken);
    assert.equal((await call(`/v1/auth/login-requests/${info.requestId}/approve`, {}, first.accessToken)).status, 409, 'a denied request cannot be approved later');
    assert.equal((await call('/v1/presence', {}, first.accessToken)).status, 200);
  } finally { srv.close(); }
});

test('an unanswered request expires and is refused: silence never approves', async () => {
  const { srv, call, signIn } = await setup({ requestMs: 150 });
  try {
    const first = await (await signIn('Phone A')).json();
    const info = await (await signIn('Phone B')).json();
    await new Promise((r) => setTimeout(r, 250));
    const r = await (await call('/v1/auth/login-requests/status', { requestId: info.requestId, pollToken: info.pollToken })).json();
    assert.equal(r.status, 'expired');
    assert.equal((await call(`/v1/auth/login-requests/${info.requestId}/approve`, {}, first.accessToken)).status, 404, 'too late to approve');
    assert.equal((await call('/v1/presence', {}, first.accessToken)).status, 200);
  } finally { srv.close(); }
});

test('only the right poll token, and only the account own device, can read or decide a request', async () => {
  const { st, srv, call, signIn } = await setup();
  try {
    const first = await (await signIn('Phone A')).json();
    const info = await (await signIn('Phone B')).json();
    const bad = await (await call('/v1/auth/login-requests/status', { requestId: info.requestId, pollToken: 'x'.repeat(32) })).json();
    assert.equal(bad.status, 'expired');
    await st.transaction((tx) => tx.put('people', 'other01', { isSuperAdmin: false, activeAdminRole: null, activeCongregationId: 'congA' }, 'seed'));
    await st.createAccount('other01', await hashPassword('Other1234'));
    const other = await (await call('/v1/auth/login', { email: 'other01@gopreach.app', password: 'Other1234' })).json();
    assert.equal((await call(`/v1/auth/login-requests/${info.requestId}/approve`, {}, other.accessToken)).status, 404, 'another account cannot approve');
    assert.equal((await call(`/v1/auth/login-requests/${info.requestId}/approve`, {})).status, 401, 'nor can anyone without a session');
    assert.equal((await call(`/v1/auth/login-requests/${info.requestId}/approve`, {}, first.accessToken)).status, 200);
  } finally { srv.close(); }
});

test('e-mail code: sent to the registered address, single use, limited tries, then it signs the device in', async () => {
  const { srv, call, signIn, sent } = await setup();
  try {
    await signIn('Phone A');
    const info = await (await signIn('Phone B')).json();
    const body = { requestId: info.requestId, pollToken: info.pollToken };
    const code1 = await (await call('/v1/auth/login-requests/email-code', body)).json();
    assert.equal(code1.masked, 'j******@example.com');
    assert.equal(sent.length, 1);
    assert.equal(sent[0].to, 'juan.delacruz@example.com');
    assert.equal((await call('/v1/auth/login-requests/email-code', body)).status, 429, 'a new code must wait');
    const code = /(\d{6})/.exec(sent[0].text)[1];
    const wrong = String((Number(code) + 1) % 1_000_000).padStart(6, '0');
    const w = await call('/v1/auth/login-requests/email-verify', { ...body, code: wrong });
    assert.equal(w.status, 401);
    assert.equal((await w.json()).triesLeft, 4);
    const ok = await call('/v1/auth/login-requests/email-verify', { ...body, code });
    assert.equal(ok.status, 200);
    assert.equal((await ok.json()).status, 'approved');
    const done = await (await call('/v1/auth/login', { email: 'solo01@gopreach.app', password: 'Secret123', device: 'Phone B', approval: body })).json();
    assert.ok(done.accessToken);
    assert.equal((await call('/v1/presence', {}, done.accessToken)).status, 200);
    assert.notEqual((await call('/v1/auth/login-requests/email-verify', { ...body, code })).status, 200, 'the code works once');
  } finally { srv.close(); }
});

test('five wrong e-mail codes end the request', async () => {
  const { srv, call, signIn, sent } = await setup();
  try {
    await signIn('Phone A');
    const info = await (await signIn('Phone B')).json();
    const body = { requestId: info.requestId, pollToken: info.pollToken };
    await call('/v1/auth/login-requests/email-code', body);
    const code = /(\d{6})/.exec(sent[0].text)[1];
    const wrong = String((Number(code) + 1) % 1_000_000).padStart(6, '0');
    for (let i = 0; i < 5; i++) await call('/v1/auth/login-requests/email-verify', { ...body, code: wrong });
    assert.notEqual((await call('/v1/auth/login-requests/email-verify', { ...body, code })).status, 200, 'even the right code is refused now');
    assert.equal((await (await call('/v1/auth/login-requests/status', body)).json()).status, 'denied');
  } finally { srv.close(); }
});

test('without mail set up, the e-mail method is not offered and cannot be used', async () => {
  const { srv, call, signIn } = await setup({ mail: false });
  try {
    await signIn('Phone A');
    const info = await (await signIn('Phone B')).json();
    assert.equal(info.methods.email, null);
    assert.equal((await call('/v1/auth/login-requests/email-code', { requestId: info.requestId, pollToken: info.pollToken })).status, 409);
  } finally { srv.close(); }
});

test('maskEmail hides most of the name', () => {
  assert.equal(maskEmail('a@x.com'), 'a**@x.com');
  assert.equal(maskEmail('not-an-email'), null);
});
