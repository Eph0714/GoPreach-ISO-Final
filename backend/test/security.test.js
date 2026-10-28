import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MemoryStore } from '../src/memoryStore.js';
import { createApp } from '../src/app.js';
import { hashPassword } from '../src/passwords.js';
import { base32, hotp, totp, matchTotp, seal, unseal } from '../src/security.js';

const SECRET = 'test-secret-test-secret-test-secret';
const person = (extra = {}) => ({ isSuperAdmin: false, activeAdminRole: null, activeCongregationId: 'congA', firstName: 'Ana', lastName: 'Cruz', ...extra });

async function world() {
  const st = new MemoryStore();
  const sent = [];
  const mailer = { send: async (to, subject, text) => { sent.push({ to, subject, text }); } };
  await st.transaction(async (tx) => {
    await tx.put('people', 'ana001', person({ username: 'ana' }), 'seed');
    await tx.put('people', 'ben001', person({ username: 'ben', activeCongregationId: 'congB', firstName: 'Ben' }), 'seed');
    await tx.put('people', 'adm001', person({ username: 'admA', activeAdminRole: 'ADMIN_PER_CONGREGATION', firstName: 'Ada' }), 'seed');
    await tx.put('people', 'sup001', person({ username: 'super', isSuperAdmin: true, activeAdminRole: 'SUPER_ADMIN', activeCongregationId: null, firstName: 'Sue' }), 'seed');
    await tx.put('people', 'ele001', person({ username: 'elder', activeAdminRole: 'COORDINATOR_ELDER', firstName: 'Eli' }), 'seed');
    await tx.put('congregations', 'congA', { name: 'Congregation A' }, 'seed');
    await tx.put('congregations', 'congB', { name: 'Congregation B' }, 'seed');
    await tx.put('roleAssignments', 'r1', { personId: 'ana001', congregationId: 'congA', roleType: 'PUBLISHER:REGULAR_PIONEER', status: 'ACTIVE' }, 'seed');
  });
  for (const id of ['ana001', 'ben001', 'adm001', 'sup001', 'ele001']) await st.createAccount(id, await hashPassword('Secret123'));
  const srv = createApp(st, { authOptions: { secret: SECRET, mailer, limits: { login: { max: 1000 }, spray: { max: 1000 }, verify: { max: 1000 }, act: { max: 1000 } } } }).listen(0);
  const url = `http://127.0.0.1:${srv.address().port}`;
  const call = (method, path, body, token) => fetch(url + path, { method, headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const post = (path, body, token) => call('POST', path, body ?? {}, token);
  const get = (path, token) => call('GET', path, null, token);
  const login = (id, extra = {}, password = 'Secret123') => post('/v1/auth/login', { email: `${id}@gopreach.app`, password, ...extra });
  const signIn = async (id, device = { id: `dev-${id}`, name: `${id} phone`, type: 'phone', os: 'Android', osVersion: '14', appVersion: '1.165.0' }) => (await login(id, { device })).json();
  const enable2fa = async (id, session) => {
    const setup = await (await post('/v1/security/2fa/totp/setup', {}, session.accessToken)).json();
    const bytes = base32.decode(setup.secret);
    const confirm = await post('/v1/security/2fa/totp/confirm', { code: totp(bytes) }, session.accessToken);
    return { setup, bytes, confirm, ...(await confirm.json()) };
  };
  return { st, srv, sent, post, get, call, login, signIn, enable2fa, close: () => srv.close() };
}

// ---------------------------------------------------------------------------------------------------------------------------------------------------------------------

test('TOTP is RFC 6238: the published SHA-1 test vectors', () => {
  const secret = Buffer.from('12345678901234567890');
  for (const [t, code] of [[59, '94287082'], [1111111109, '07081804'], [1111111111, '14050471'], [1234567890, '89005924'], [2000000000, '69279037'], [20000000000, '65353130']]) {
    assert.equal(totp(secret, t * 1000, 8), code);
  }
  assert.equal(hotp(secret, 0), '755224', 'RFC 4226 counter 0');
  assert.equal(base32.encode(Buffer.from('foobar')), 'MZXW6YTBOI');
  assert.deepEqual(base32.decode('MZXW6YTBOI'), Buffer.from('foobar'));
  const now = Date.now();
  assert.ok(matchTotp(secret, totp(secret, now), now) !== null);
  assert.equal(matchTotp(secret, totp(secret, now - 5 * 60_000), now), null, 'an old code is not accepted');
  assert.equal(matchTotp(secret, '12345', now), null);
});

test('the TOTP secret is sealed with AES-GCM and cannot be opened with another key or altered', () => {
  const sealed = seal('JBSWY3DPEHPK3PXP', SECRET);
  assert.ok(!sealed.includes('JBSWY3DPEHPK3PXP'));
  assert.equal(unseal(sealed, SECRET), 'JBSWY3DPEHPK3PXP');
  assert.throws(() => unseal(sealed, 'another-secret-another-secret-another'));
  const bad = Buffer.from(sealed, 'base64url'); bad[bad.length - 1] ^= 1;
  assert.throws(() => unseal(bad.toString('base64url'), SECRET));
});

test('login without 2FA: no prompt, and any number of devices may be signed in at once', async () => {
  const w = await world();
  try {
    const a = await w.signIn('ana001', { id: 'dev-1', name: 'Phone' });
    const b = await w.signIn('ana001', { id: 'dev-2', name: 'Tablet' });
    assert.ok(a.accessToken && b.accessToken && a.sessionId !== b.sessionId);
    const list = await (await w.get('/v1/security/devices', a.accessToken)).json();
    assert.equal(list.devices.length, 2);
    assert.equal(list.devices.filter((d) => d.current).length, 1);
    assert.equal(list.devices.find((d) => d.current).deviceId, 'dev-1');
    assert.ok(list.devices.every((d) => d.status === 'active' && !('refreshToken' in d) && !('accessToken' in d)), 'no token is ever listed');
  } finally { w.close(); }
});

test('enabling 2FA needs a recently typed password and a code from the authenticator app; recovery codes are shown once', async () => {
  const w = await world();
  try {
    const s = await w.signIn('ana001');
    assert.equal((await (await w.get('/v1/security/status', s.accessToken)).json()).twoFactor.enabled, false);
    const wrong = await w.post('/v1/security/2fa/totp/setup', {}, s.accessToken);
    assert.equal(wrong.status, 200);
    const setup = await wrong.json();
    assert.match(setup.otpauthUri, /^otpauth:\/\/totp\/GoPreach:ana\?secret=[A-Z2-7]+&issuer=GoPreach/);
    assert.equal((await w.post('/v1/security/2fa/totp/confirm', { code: '000000' }, s.accessToken)).status, 401, 'a wrong code does not turn it on');
    assert.equal((await (await w.get('/v1/security/status', s.accessToken)).json()).twoFactor.enabled, false);
    const ok = await w.post('/v1/security/2fa/totp/confirm', { code: totp(base32.decode(setup.secret)) }, s.accessToken);
    assert.equal(ok.status, 200);
    const { recoveryCodes } = await ok.json();
    assert.equal(recoveryCodes.length, 10);
    assert.match(recoveryCodes[0], /^[A-Z2-9]{5}-[A-Z2-9]{5}$/);
    const status = await (await w.get('/v1/security/status', s.accessToken)).json();
    assert.deepEqual([status.twoFactor.enabled, status.twoFactor.recoveryCodesLeft], [true, 10]);
    assert.ok(!JSON.stringify([...w.st.sec.values()]).includes(setup.secret), 'the secret is not stored in clear');
    assert.ok(!JSON.stringify([...w.st.sec.values()]).includes(recoveryCodes[0]), 'recovery codes are not stored in clear');
  } finally { w.close(); }
});

test('sign-in with 2FA: password alone gives nothing; a right code gives the session; a wrong one and a replayed one do not', async () => {
  const w = await world();
  try {
    const s = await w.signIn('ana001');
    const e = await w.enable2fa('ana001', s);
    const first = await w.login('ana001', { device: { id: 'dev-new', name: 'New phone', type: 'phone' } });
    assert.equal(first.status, 202);
    const c = await first.json();
    assert.equal(c.twoFactorRequired, true);
    assert.ok(!c.accessToken && !c.refreshToken, 'nothing is issued before the second factor');
    assert.equal((await w.post('/v1/auth/2fa/verify', { challengeId: c.challengeId, challengeToken: 'nope' })).status, 401, 'the challenge secret is needed');
    const bad = await w.post('/v1/auth/2fa/verify', { challengeId: c.challengeId, challengeToken: c.challengeToken, code: '123456' });
    assert.equal(bad.status, 401);
    assert.equal((await bad.json()).triesLeft, 4);
    const code = totp(e.bytes);
    // the setup consumed this time step: a code is never accepted twice
    assert.equal((await w.post('/v1/auth/2fa/verify', { challengeId: c.challengeId, challengeToken: c.challengeToken, code })).status, 401, 'the code used to set up cannot be replayed');
    const next = matchTotp(e.bytes, totp(e.bytes, Date.now() + 30_000), Date.now() + 30_000);
    const good = await w.post('/v1/auth/2fa/verify', { challengeId: c.challengeId, challengeToken: c.challengeToken, code: totp(e.bytes, Date.now() + 30_000) });
    assert.equal(good.status, 200);
    const t = await good.json();
    assert.ok(t.accessToken && t.sessionId && next);
    assert.equal((await w.post('/v1/auth/2fa/verify', { challengeId: c.challengeId, challengeToken: c.challengeToken, code: totp(e.bytes, Date.now() + 60_000) })).status, 401, 'a challenge works once');
    const list = await (await w.get('/v1/security/devices', t.accessToken)).json();
    assert.equal(list.devices.find((d) => d.current).authMethod, 'password+totp');
  } finally { w.close(); }
});

test('five wrong codes end the challenge', async () => {
  const w = await world();
  try {
    const s = await w.signIn('ana001');
    await w.enable2fa('ana001', s);
    const c = await (await w.login('ana001', {})).json();
    for (let i = 0; i < 5; i++) assert.equal((await w.post('/v1/auth/2fa/verify', { challengeId: c.challengeId, challengeToken: c.challengeToken, code: '000000' })).status, 401);
    assert.equal((await w.post('/v1/auth/2fa/verify', { challengeId: c.challengeId, challengeToken: c.challengeToken, code: '000000' })).status, 429);
    assert.equal((await w.post('/v1/auth/2fa/verify', { challengeId: c.challengeId, challengeToken: c.challengeToken, code: totp(base32.decode(unseal((await w.st.secGet('a2fa', 'ana001')).data.totp.secret, SECRET)), Date.now() + 30_000) })).status, 401, 'the challenge is gone');
  } finally { w.close(); }
});

test('a recovery code signs in once and then never again; regenerating replaces them all', async () => {
  const w = await world();
  try {
    const s = await w.signIn('ana001');
    const e = await w.enable2fa('ana001', s);
    const [one, two] = e.recoveryCodes;
    const challenge = async () => (await w.login('ana001', {})).json();
    let c = await challenge();
    assert.equal((await w.post('/v1/auth/2fa/verify', { ...pick(c), recoveryCode: one })).status, 200);
    c = await challenge();
    assert.equal((await w.post('/v1/auth/2fa/verify', { ...pick(c), recoveryCode: one })).status, 401, 'used');
    assert.equal((await w.post('/v1/auth/2fa/verify', { ...pick(c), recoveryCode: two.toLowerCase().replace('-', ' ') })).status, 200, 'case and separators do not matter');
    const left = (await (await w.get('/v1/security/status', s.accessToken)).json()).twoFactor.recoveryCodesLeft;
    assert.equal(left, 8);
    const fresh = await (await w.post('/v1/security/2fa/recovery-codes', { recoveryCode: e.recoveryCodes[2] }, s.accessToken)).json();
    assert.equal(fresh.recoveryCodes.length, 10);
    c = await challenge();
    assert.equal((await w.post('/v1/auth/2fa/verify', { ...pick(c), recoveryCode: e.recoveryCodes[3] })).status, 401, 'the old codes are invalid');
    assert.equal((await w.post('/v1/auth/2fa/verify', { ...pick(c), recoveryCode: fresh.recoveryCodes[0] })).status, 200);
  } finally { w.close(); }
});
const pick = (c) => ({ challengeId: c.challengeId, challengeToken: c.challengeToken });

test('disabling 2FA needs a recent password and a current code; everything is then back to password only', async () => {
  const w = await world();
  try {
    const s = await w.signIn('ana001');
    const e = await w.enable2fa('ana001', s);
    assert.equal((await w.post('/v1/security/2fa/disable', { code: '000000' }, s.accessToken)).status, 401);
    const off = await w.post('/v1/security/2fa/disable', { code: totp(e.bytes, Date.now() + 30_000) }, s.accessToken);
    assert.equal(off.status, 200);
    assert.equal((await w.login('ana001', {})).status, 200, 'no prompt any more');
    assert.equal((await (await w.get('/v1/security/status', s.accessToken)).json()).twoFactor.enabled, false);
  } finally { w.close(); }
});

test('sensitive settings need a recently typed password', async () => {
  const w = await world();
  try {
    const s = await w.signIn('ana001');
    const e = await w.enable2fa('ana001', s);
    // make the session look old: the access token carries the time the password was typed
    const { signAccessToken } = await import('../src/auth.js');
    const old = await signAccessToken('ana001', Date.now() - 20 * 60_000, SECRET, s.sessionId);
    for (const [path, body] of [['/v1/security/2fa/totp/setup', {}], ['/v1/security/2fa/disable', { code: totp(e.bytes, Date.now() + 30_000) }], ['/v1/security/devices/revoke-others', {}], ['/v1/security/devices/revoke-all', {}], ['/v1/security/2fa/recovery-codes', { recoveryCode: e.recoveryCodes[0] }]]) {
      const r = await w.post(path, body, old);
      assert.equal(r.status, 403, path);
      assert.equal((await r.json()).error, 'requires-recent-login');
    }
  } finally { w.close(); }
});

test('revoking a device ends it on the server at once: its token, its renewal and the others are untouched', async () => {
  const w = await world();
  try {
    const phone = await w.signIn('ana001', { id: 'd1', name: 'Phone' });
    const tablet = await w.signIn('ana001', { id: 'd2', name: 'Tablet' });
    const other = await w.signIn('ana001', { id: 'd3', name: 'Web' });
    assert.equal((await w.post('/v1/presence', {}, tablet.accessToken)).status, 200);
    const r = await w.post(`/v1/security/devices/${tablet.sessionId}/revoke`, {}, phone.accessToken);
    assert.equal(r.status, 200);
    const refused = await w.post('/v1/presence', {}, tablet.accessToken);
    assert.equal(refused.status, 401);
    assert.equal((await refused.json()).error, 'session-ended', 'the existing clients already treat this as "signed out"');
    assert.equal((await w.post('/v1/auth/refresh', { refreshToken: tablet.refreshToken })).status, 401, 'it cannot renew');
    assert.equal((await w.post('/v1/presence', {}, phone.accessToken)).status, 200);
    assert.equal((await w.post('/v1/presence', {}, other.accessToken)).status, 200, 'other sessions were not touched');
    const list = await (await w.get('/v1/security/devices', phone.accessToken)).json();
    const row = list.devices.find((d) => d.sessionId === tablet.sessionId);
    assert.deepEqual([row.status, row.revokedBy], ['revoked', 'self']);
  } finally { w.close(); }
});

test('Log Out Other Devices keeps this one; Log Out All Devices ends every one, this included', async () => {
  const w = await world();
  try {
    const a = await w.signIn('ana001', { id: 'a' });
    const b = await w.signIn('ana001', { id: 'b' });
    const c = await w.signIn('ana001', { id: 'c' });
    const others = await (await w.post('/v1/security/devices/revoke-others', {}, a.accessToken)).json();
    assert.equal(others.revoked, 2);
    assert.equal((await w.post('/v1/presence', {}, b.accessToken)).status, 401);
    assert.equal((await w.post('/v1/presence', {}, c.accessToken)).status, 401);
    assert.equal((await w.post('/v1/presence', {}, a.accessToken)).status, 200, 'this device is preserved');
    const all = await (await w.post('/v1/security/devices/revoke-all', {}, a.accessToken)).json();
    assert.equal(all.revoked, 1);
    assert.equal((await w.post('/v1/presence', {}, a.accessToken)).status, 401, 'this device too');
    assert.equal((await w.post('/v1/auth/refresh', { refreshToken: a.refreshToken })).status, 401);
    const again = await w.signIn('ana001', { id: 'a' });
    assert.ok(again.accessToken, 'a new sign-in works');
  } finally { w.close(); }
});

test('nobody can see or end another person\'s device', async () => {
  const w = await world();
  try {
    const ana = await w.signIn('ana001');
    const ben = await w.signIn('ben001');
    assert.equal((await w.post(`/v1/security/devices/${ben.sessionId}/revoke`, {}, ana.accessToken)).status, 404);
    assert.equal((await w.post('/v1/presence', {}, ben.accessToken)).status, 200);
    const list = await (await w.get('/v1/security/devices', ana.accessToken)).json();
    assert.ok(list.devices.every((d) => d.sessionId !== ben.sessionId));
    assert.equal((await w.get('/v1/admin/security/sessions', ana.accessToken)).status, 403, 'an ordinary account has no device monitoring');
    assert.equal((await w.post(`/v1/admin/security/sessions/${ben.sessionId}/revoke`, {}, ana.accessToken)).status, 403);
  } finally { w.close(); }
});

test('a trusted device skips the code; it is bound to a secret credential and to the device; removing or a password change ends the trust', async () => {
  const w = await world();
  try {
    const s = await w.signIn('ana001', { id: 'my-phone', name: 'My phone' });
    const e = await w.enable2fa('ana001', s);
    assert.equal((await w.post('/v1/security/trusted/current', { code: '000000' }, s.accessToken)).status, 401, 'trust needs a verified code');
    const trust = await (await w.post('/v1/security/trusted/current', { code: totp(e.bytes, Date.now() + 30_000) }, s.accessToken)).json();
    assert.ok(trust.trustedToken.length > 30 && trust.trustedUntil > Date.now());
    assert.ok(!JSON.stringify([...w.st.sec.values()]).includes(trust.trustedToken), 'only a hash of the credential is kept');
    const phone = { id: 'my-phone', name: 'My phone' };
    const skip = await w.login('ana001', { device: phone, trustedToken: trust.trustedToken });
    assert.equal(skip.status, 200, 'trusted: no code');
    assert.equal((await w.login('ana001', { device: phone, trustedToken: 'x'.repeat(40) })).status, 202, 'a wrong credential is not trust');
    assert.equal((await w.login('ana001', { device: { id: 'another', name: 'My phone' }, trustedToken: trust.trustedToken })).status, 202, 'the same credential on another device is not trusted');
    assert.equal((await w.login('ana001', { device: { name: 'My phone' } })).status, 202, 'a device name alone is never trust');
    const list = await (await w.get('/v1/security/devices', s.accessToken)).json();
    assert.equal(list.trusted.length, 1);
    assert.equal(list.devices.find((d) => d.deviceId === 'my-phone').trusted, true);
    await w.post('/v1/security/devices/revoke-others', {}, s.accessToken);
    // a password change ends the trust
    assert.equal((await w.post('/v1/auth/password', { newPassword: 'NewSecret456' }, s.accessToken)).status, 200);
    assert.equal((await w.login('ana001', { device: phone, trustedToken: trust.trustedToken }, 'NewSecret456')).status, 202, 'trust ended with the password change');
    // removing trust
    const t2 = await (await w.post('/v1/security/trusted/current', { recoveryCode: e.recoveryCodes[0] }, (await (await w.post('/v1/auth/reauth', { password: 'NewSecret456' }, s.accessToken)).json()).accessToken)).json();
    const [tr] = (await (await w.get('/v1/security/devices', s.accessToken)).json()).trusted;
    const fresh = await (await w.post('/v1/auth/reauth', { password: 'NewSecret456' }, s.accessToken)).json();
    assert.equal((await w.post(`/v1/security/trusted/${tr.id}/remove`, {}, fresh.accessToken)).status, 200);
    assert.equal((await w.login('ana001', { device: phone, trustedToken: t2.trustedToken }, 'NewSecret456')).status, 202, 'a removed device is no longer trusted');
  } finally { w.close(); }
});

test('a password change ends every other session at once', async () => {
  const w = await world();
  try {
    const a = await w.signIn('ana001', { id: 'a' });
    const b = await w.signIn('ana001', { id: 'b' });
    assert.equal((await w.post('/v1/auth/password', { newPassword: 'NewSecret456' }, a.accessToken)).status, 200);
    assert.equal((await w.post('/v1/presence', {}, b.accessToken)).status, 401, 'the other device is out immediately, not when its token expires');
    assert.equal((await w.post('/v1/presence', {}, a.accessToken)).status, 200);
  } finally { w.close(); }
});

test('e-mail code: only to a confirmed recovery address, single use, limited', async () => {
  const w = await world();
  try {
    const s = await w.signIn('ana001');
    await w.enable2fa('ana001', s);
    const set = await w.post('/v1/security/recovery-email', { email: 'ana@example.com' }, s.accessToken);
    assert.equal(set.status, 200);
    assert.equal((await set.json()).masked, 'a**@example.com');
    let c = await (await w.login('ana001', {})).json();
    assert.equal(c.methods.email, null, 'not offered until confirmed');
    const code = /(\d{6})/.exec(w.sent.at(-1).text)[1];
    assert.equal((await w.post('/v1/security/recovery-email/verify', { code: '000000' }, s.accessToken)).status, 401);
    assert.equal((await w.post('/v1/security/recovery-email/verify', { code }, s.accessToken)).status, 200);
    c = await (await w.login('ana001', {})).json();
    assert.equal(c.methods.email.masked, 'a**@example.com');
    assert.equal((await w.post('/v1/auth/2fa/email-code', pick(c))).status, 200);
    const sentCode = /(\d{6})/.exec(w.sent.at(-1).text)[1];
    assert.equal((await w.post('/v1/auth/2fa/email-code', pick(c))).status, 429, 'one a minute');
    assert.equal((await w.post('/v1/auth/2fa/verify', { ...pick(c), emailCode: '111111' })).status, 401);
    assert.equal((await w.post('/v1/auth/2fa/verify', { ...pick(c), emailCode: sentCode })).status, 200);
    assert.equal((await w.post('/v1/auth/2fa/verify', { ...pick(c), emailCode: sentCode })).status, 401, 'once');
  } finally { w.close(); }
});

test('the security log records the events, names no secret, and cannot be edited', async () => {
  const w = await world();
  try {
    await w.login('ana001', {}, 'wrong-password');
    const s = await w.signIn('ana001');
    const e = await w.enable2fa('ana001', s);
    const c = await (await w.login('ana001', {})).json();
    await w.post('/v1/auth/2fa/verify', { ...pick(c), code: '000000' });
    const t = await (await w.post('/v1/auth/2fa/verify', { ...pick(c), code: totp(e.bytes, Date.now() + 30_000) })).json();
    await w.post(`/v1/security/devices/${t.sessionId}/rename`, { name: 'Ana tablet' }, s.accessToken);
    await w.post(`/v1/security/devices/${t.sessionId}/revoke`, {}, s.accessToken);
    await w.post('/v1/security/devices/revoke-others', {}, s.accessToken);
    await w.post('/v1/security/devices/revoke-all', {}, s.accessToken);
    const sup = await w.signIn('sup001');
    const log = (await (await w.get('/v1/admin/security/events?personId=ana001&limit=300', sup.accessToken)).json()).events;
    const types = new Set(log.map((x) => x.type));
    for (const need of ['LOGIN_FAILED', 'LOGIN_SUCCESS', 'NEW_DEVICE_LOGIN', 'TWO_FACTOR_ENABLED', 'TWO_FACTOR_FAILED', 'TWO_FACTOR_SUCCESS', 'DEVICE_LOGOUT', 'LOG_OUT_OTHER_DEVICES', 'LOG_OUT_ALL_DEVICES']) assert.ok(types.has(need), need);
    const text = JSON.stringify([...w.st.sec.values()].filter((r) => r.kind === 'secevent'));
    for (const secret of ['Secret123', 'wrong-password', e.setup.secret, e.recoveryCodes[0], c.challengeToken, t.accessToken, t.refreshToken]) assert.ok(!text.includes(secret), `the log never holds ${secret.slice(0, 6)}...`);
    const failed = log.find((x) => x.type === 'LOGIN_FAILED');
    assert.equal(failed.result, 'failure');
    // there is no route that changes or deletes an entry
    for (const method of ['PUT', 'PATCH', 'DELETE', 'POST']) assert.equal((await w.call(method, '/v1/admin/security/events', {}, sup.accessToken)).status, 404, method);
    assert.equal((await w.get('/v1/admin/security/events', (await w.signIn('ele001')).accessToken)).status, 403, 'only the Super Admin reads the whole log');
  } finally { w.close(); }
});

test('Super Admin: sees every device with filters, never a secret, and can revoke one or all of a user', async () => {
  const w = await world();
  try {
    const ana = await w.signIn('ana001', { id: 'a1', name: 'Ana phone', type: 'phone', os: 'Android', osVersion: '14', appVersion: '1.165.0' });
    const ana2 = await w.signIn('ana001', { id: 'a2', name: 'Ana laptop', type: 'web', os: 'Windows' });
    const ben = await w.signIn('ben001', { id: 'b1', name: 'Ben phone', type: 'phone' });
    const sup = await w.signIn('sup001');
    const all = (await (await w.get('/v1/admin/security/sessions', sup.accessToken)).json()).sessions;
    assert.equal(all.length, 4);
    const row = all.find((r) => r.sessionId === ana.sessionId);
    assert.deepEqual([row.username, row.name, row.congregation, row.deviceName, row.type, row.os, row.osVersion, row.appVersion, row.status], ['ana', 'Ana Cruz', 'Congregation A', 'Ana phone', 'phone', 'Android', '14', '1.165.0', 'active']);
    assert.ok(row.roles.includes('REGULAR_PIONEER'));
    assert.ok(!JSON.stringify(all).match(/"(accessToken|refreshToken|trustedToken|token|secret|pwHash|recoveryCodes?)"/), 'no secret in the Super Admin view');
    const q = async (qs) => (await (await w.get(`/v1/admin/security/sessions?${qs}`, sup.accessToken)).json()).sessions.map((r) => r.deviceName).sort();
    assert.deepEqual(await q('q=ana'), ['Ana laptop', 'Ana phone']);
    assert.deepEqual(await q('congregation=congB'), ['Ben phone']);
    assert.deepEqual(await q('type=web'), ['Ana laptop']);
    assert.deepEqual(await q('role=REGULAR_PIONEER&status=active'), ['Ana laptop', 'Ana phone']);
    assert.deepEqual(await q('q=Congregation%20B'), ['Ben phone']);
    assert.equal((await w.post(`/v1/admin/security/sessions/${ana2.sessionId}/revoke`, {}, sup.accessToken)).status, 200);
    assert.equal((await w.post('/v1/presence', {}, ana2.accessToken)).status, 401, 'revoked on the server');
    assert.equal((await w.post('/v1/presence', {}, ana.accessToken)).status, 200);
    assert.equal((await w.post('/v1/admin/security/users/ana001/revoke-all', {}, sup.accessToken)).status, 200);
    assert.equal((await w.post('/v1/presence', {}, ana.accessToken)).status, 401);
    assert.equal((await w.post('/v1/presence', {}, ben.accessToken)).status, 200);
    const mine = (await (await w.get('/v1/security/notices', (await w.signIn('ana001')).accessToken)).json()).notices;
    assert.ok(mine.some((n) => n.type === 'ADMIN_SESSION_REVOKED'), 'the person is told');
    const history = (await (await w.get('/v1/admin/security/users/ana001/history', sup.accessToken)).json()).events;
    assert.ok(history.some((h) => h.type === 'ADMIN_SESSION_REVOKED' && h.by === 'sup001'));
    const supLog = (await (await w.get('/v1/admin/security/events?personId=sup001', sup.accessToken)).json()).events;
    assert.ok(supLog.some((h) => h.type === 'ADMIN_REVOKED_A_SESSION'), 'the administrator\'s own action is logged too');
  } finally { w.close(); }
});

test('a congregation Admin sees and ends devices of their own congregation only; other roles nothing', async () => {
  const w = await world();
  try {
    const ana = await w.signIn('ana001');
    const ben = await w.signIn('ben001');
    const adm = await w.signIn('adm001');
    const rows = (await (await w.get('/v1/admin/security/sessions', adm.accessToken)).json()).sessions;
    assert.deepEqual(new Set(rows.map((r) => r.username)), new Set(['ana', 'admA']), 'congregation B is not visible');
    assert.equal((await w.post(`/v1/admin/security/sessions/${ben.sessionId}/revoke`, {}, adm.accessToken)).status, 404, 'another congregation: as if it did not exist');
    assert.equal((await w.post('/v1/admin/security/users/ben001/revoke-all', {}, adm.accessToken)).status, 404);
    assert.equal((await w.post('/v1/presence', {}, ben.accessToken)).status, 200);
    assert.equal((await w.post(`/v1/admin/security/sessions/${ana.sessionId}/revoke`, {}, adm.accessToken)).status, 200);
    assert.equal((await w.post('/v1/presence', {}, ana.accessToken)).status, 401);
    const elder = await w.signIn('ele001');
    assert.equal((await w.get('/v1/admin/security/sessions', elder.accessToken)).status, 403);
    assert.equal((await w.get('/v1/admin/security/events', adm.accessToken)).status, 403, 'the whole log is the Super Admin\'s');
  } finally { w.close(); }
});

test('a multi-role account signs in the same way; its roles do not change the session', async () => {
  const w = await world();
  try {
    await w.st.transaction((tx) => tx.put('roleAssignments', 'r2', { personId: 'ele001', congregationId: 'congA', roleType: 'PUBLISHER:REGULAR_PIONEER', status: 'ACTIVE' }, 'seed'));
    const s = await w.signIn('ele001');
    assert.ok(s.accessToken);
    assert.equal((await w.post('/v1/presence', {}, s.accessToken)).status, 200);
    assert.equal((await w.get('/v1/security/devices', s.accessToken)).status, 200);
  } finally { w.close(); }
});

test('new devices are announced to the account; older apps that send only a label still work', async () => {
  const w = await world();
  try {
    const first = await (await w.login('ana001', { device: 'GoPreach Web (Chrome on Windows)' })).json();
    const second = await (await w.login('ana001', { device: { id: 'new-one', name: 'Pixel 8', type: 'phone' } })).json();
    const notices = (await (await w.get('/v1/security/notices', first.accessToken)).json()).notices;
    assert.ok(notices.filter((n) => n.type === 'NEW_DEVICE_LOGIN').length >= 1);
    assert.ok(notices.some((n) => n.meta.device === 'Pixel 8'));
    await w.post('/v1/security/notices/seen', {}, first.accessToken);
    assert.equal((await (await w.get('/v1/security/notices', first.accessToken)).json()).notices.length, 0);
    const list = await (await w.get('/v1/security/devices', second.accessToken)).json();
    assert.ok(list.devices.some((d) => d.name === 'GoPreach Web (Chrome on Windows)'));
  } finally { w.close(); }
});

// ---- new-device approval (opt-in) ---------------------------------------------------------------------------------------------------------------------------------

const dev = (id, name = `${id} device`) => ({ id, name, type: 'phone', os: 'Android', osVersion: '14', appVersion: '1.165.0' });

test('device approval: a new device waits for an approval from one of the account own devices; the password alone opens nothing', async () => {
  const w = await world();
  try {
    const first = await w.signIn('ana001', dev('dev-1', 'Ana phone'));
    const on = await (await w.post('/v1/security/device-approval', { enabled: true }, first.accessToken)).json();
    assert.equal(on.enabled, true);
    assert.ok(on.trustedToken, 'this device became the trusted one');
    assert.equal((await (await w.get('/v1/security/status', first.accessToken)).json()).deviceApproval, true);

    // the trusted device signs in again without being asked
    const again = await (await w.login('ana001', { device: dev('dev-1', 'Ana phone'), trustedToken: on.trustedToken })).json();
    assert.ok(again.accessToken, 'a trusted device signs in at once');

    // a new device: the right password gets a request and NO tokens
    const attempt = await w.login('ana001', { device: dev('dev-2', 'New tablet') });
    assert.equal(attempt.status, 409);
    const req = await attempt.json();
    assert.equal(req.error, 'session-active');
    assert.ok(req.requestId && req.pollToken && req.expiresAt > Date.now());
    assert.equal(req.accessToken, undefined);
    assert.equal(req.refreshToken, undefined);
    assert.equal((await (await w.post('/v1/auth/login-requests/status', { requestId: req.requestId, pollToken: req.pollToken })).json()).status, 'pending');
    // still pending: repeating the sign-in with the unapproved request opens nothing (it just asks again: the newest request is the one that counts)
    const retry = await w.login('ana001', { device: dev('dev-2'), approval: { requestId: req.requestId, pollToken: req.pollToken } });
    assert.equal(retry.status, 409);
    const req0 = { ...req };
    Object.assign(req, await retry.json());
    assert.notEqual(req.requestId, req0.requestId);
    assert.equal((await (await w.post('/v1/auth/login-requests/status', { requestId: req0.requestId, pollToken: req0.pollToken })).json()).status, 'expired', 'the older request is superseded');

    // somebody else's account cannot decide it; a wrong poll token reads nothing
    const ben = await w.signIn('ben001');
    assert.equal((await w.post(`/v1/auth/login-requests/${req.requestId}/approve`, {}, ben.accessToken)).status, 404, 'another account cannot approve');
    assert.equal((await (await w.post('/v1/auth/login-requests/status', { requestId: req.requestId, pollToken: 'x'.repeat(30) })).json()).status, 'expired');

    // the account own device sees it and approves exactly that request
    const pending = await (await w.get('/v1/auth/login-requests', first.accessToken)).json();
    assert.equal(pending.requests.length, 1);
    assert.equal(pending.requests[0].id, req.requestId);
    assert.equal((await w.post(`/v1/auth/login-requests/${req.requestId}/approve`, {}, first.accessToken)).status, 200);
    assert.equal((await w.post(`/v1/auth/login-requests/${req.requestId}/approve`, {}, first.accessToken)).status, 409, 'decided only once');

    const done = await w.login('ana001', { device: dev('dev-2', 'New tablet'), approval: { requestId: req.requestId, pollToken: req.pollToken } });
    assert.equal(done.status, 200);
    assert.ok((await done.json()).accessToken, 'approved: the session is issued');
    // the approval is spent: replaying it asks again
    assert.equal((await w.login('ana001', { device: dev('dev-2', 'New tablet'), approval: { requestId: req.requestId, pollToken: req.pollToken } })).status, 409);

    const types = (await (await w.get('/v1/security/events', first.accessToken)).json()).events.map((e) => e.type);
    for (const t of ['DEVICE_APPROVAL_ON', 'LOGIN_APPROVAL_REQUESTED', 'LOGIN_APPROVAL_APPROVED', 'LOGIN_APPROVAL_USED']) assert.ok(types.includes(t), t);
  } finally { w.close(); }
});

test('device approval: a rejected or expired request opens nothing; a revoked trusted device needs approval again; turning it off lifts the gate', async () => {
  const w = await world();
  try {
    const first = await w.signIn('ana001', dev('dev-1', 'Ana phone'));
    const on = await (await w.post('/v1/security/device-approval', { enabled: true }, first.accessToken)).json();

    const r1 = await (await w.login('ana001', { device: dev('dev-3') })).json();
    assert.equal((await w.post(`/v1/auth/login-requests/${r1.requestId}/deny`, {}, first.accessToken)).status, 200);
    assert.equal((await (await w.post('/v1/auth/login-requests/status', { requestId: r1.requestId, pollToken: r1.pollToken })).json()).status, 'denied');
    assert.equal((await w.login('ana001', { device: dev('dev-3'), approval: { requestId: r1.requestId, pollToken: r1.pollToken } })).status, 409, 'rejected: no session');

    const r2 = await (await w.login('ana001', { device: dev('dev-4') })).json();
    const stored = await w.st.getChallenge(r2.requestId);
    await w.st.putChallenge(r2.requestId, { ...stored, expiresAt: Date.now() - 1000 });
    assert.equal((await (await w.post('/v1/auth/login-requests/status', { requestId: r2.requestId, pollToken: r2.pollToken })).json()).status, 'expired');
    assert.equal((await w.login('ana001', { device: dev('dev-4'), approval: { requestId: r2.requestId, pollToken: r2.pollToken } })).status, 409, 'expired: no session');

    // the trusted device is removed: it is new again
    const list = await w.st.secList('trusted', 'ana001');
    assert.ok(list.length >= 1);
    await w.post(`/v1/security/trusted/${list[0].id}/remove`, {}, first.accessToken);
    assert.equal((await w.login('ana001', { device: dev('dev-1', 'Ana phone'), trustedToken: on.trustedToken })).status, 409, 'a revoked device needs approval again');

    // off: devices sign in without asking again
    assert.equal((await (await w.post('/v1/security/device-approval', { enabled: false }, first.accessToken)).json()).enabled, false);
    assert.equal((await w.login('ana001', { device: dev('dev-9') })).status, 200);
  } finally { w.close(); }
});

test('device approval: the e-mail code settles a request when no device is at hand, and a stranger flooding requests is stopped', async () => {
  const w = await world();
  try {
    await w.st.transaction((tx) => tx.put('people', 'ana001', person({ username: 'ana', email: 'ana@example.org' }), 'seed'));
    const first = await w.signIn('ana001', dev('dev-1'));
    await w.post('/v1/security/device-approval', { enabled: true }, first.accessToken);
    const r = await (await w.login('ana001', { device: dev('dev-5', 'Borrowed laptop') })).json();
    assert.ok(w.sent.some((m) => m.to === 'ana@example.org' && /new device/i.test(m.subject)), 'the owner is told by e-mail');
    assert.ok(!w.sent.some((m) => m.text.includes(r.pollToken)), 'no secret is e-mailed');
    assert.equal((await w.post('/v1/auth/login-requests/email-code', { requestId: r.requestId, pollToken: r.pollToken })).status, 200);
    const code = /\b(\d{6})\b/.exec(w.sent.at(-1).text)[1];
    assert.equal((await w.post('/v1/auth/login-requests/email-verify', { requestId: r.requestId, pollToken: r.pollToken, code })).status, 200);
    assert.equal((await w.login('ana001', { device: dev('dev-5'), approval: { requestId: r.requestId, pollToken: r.pollToken } })).status, 200);
    // flooding from many devices: the account as a whole allows four times the per-device limit (20 in 15 minutes)
    let last = 0;
    for (let i = 0; i < 25; i += 1) last = (await w.login('ana001', { device: dev(`flood-${i}`) })).status;
    assert.equal(last, 429);
  } finally { w.close(); }
});

test('purgeChallenges drops long-expired login requests and keeps live and recently expired ones', async () => {
  const st = new MemoryStore();
  const now = Date.now();
  await st.putChallenge('old', { personId: 'p', kind: 'loginreq', data: {}, expiresAt: now - 2 * 3_600_000 });
  await st.putChallenge('recent', { personId: 'p', kind: 'loginreq', data: {}, expiresAt: now - 1000 });
  await st.putChallenge('live', { personId: 'p', kind: 'loginreq', data: {}, expiresAt: now + 60_000 });
  assert.equal(await st.purgeChallenges(), 1);
  assert.equal((await st.getChallenge('live'))?.id, 'live');
  assert.equal(await st.purgeChallenges(), 0);
});

test('login history: filters by result, type, device text and date; the notice list can include seen notices', async () => {
  const w = await world();
  try {
    const d1 = { id: 'dev-1', name: 'Ana phone', type: 'phone', os: 'Android', osVersion: '14', appVersion: '1' };
    const first = await w.signIn('ana001', d1);
    await w.login('ana001', { device: { ...d1, id: 'dev-2', name: 'Kitchen tablet' }, password: 'wrong-password' });
    const get = async (q) => (await (await w.get(`/v1/security/events${q}`, first.accessToken)).json()).events;
    const everything = await get('');
    assert.ok(everything.length >= 2);
    assert.ok((await get('?result=failure')).every((e) => e.result === 'failure'));
    assert.ok((await get('?result=success')).every((e) => e.result === 'success'));
    const failed = (await get('?result=failure'))[0];
    assert.ok(failed, 'the wrong password was logged');
    assert.deepEqual((await get(`?type=${failed.type}&result=failure`)).map((e) => e.type), (await get('?result=failure')).filter((e) => e.type === failed.type).map((e) => e.type));
    assert.equal((await get('?q=zzz-no-such-device')).length, 0);
    assert.equal((await get(`?from=${Date.now() + 60_000}`)).length, 0);
    assert.equal((await get(`?to=${Date.now() + 60_000}`)).length, everything.length);

    await w.post('/v1/security/device-approval', { enabled: true }, first.accessToken);
    await w.login('ana001', { device: { ...d1, id: 'dev-9', name: 'New tablet' } });
    const unseen = await (await w.get('/v1/security/notices', first.accessToken)).json();
    assert.ok(unseen.notices.length >= 1 && unseen.unseen === unseen.notices.length);
    await w.post('/v1/security/notices/seen', {}, first.accessToken);
    assert.equal((await (await w.get('/v1/security/notices', first.accessToken)).json()).notices.length, 0);
    const all = await (await w.get('/v1/security/notices?all=1', first.accessToken)).json();
    assert.ok(all.notices.length >= 1 && all.notices.every((n) => n.seen === true) && all.unseen === 0);
  } finally { w.close(); }
});

test('approval limits: only the Super Admin manages them, within safe bounds; they change the request limit and expiry at once and are logged', async () => {
  const w = await world();
  try {
    const sup = await w.signIn('sup001');
    const adm = await w.signIn('adm001');
    const ana = await w.signIn('ana001', dev('dev-1', 'Ana phone'));
    // not for an ordinary account or a congregation Admin
    assert.equal((await w.get('/v1/admin/security/approval-limits', ana.accessToken)).status, 403);
    assert.equal((await w.get('/v1/admin/security/approval-limits', adm.accessToken)).status, 403);
    assert.equal((await w.post('/v1/admin/security/approval-limits', { maxRequests: 2, windowMinutes: 10, expiryMinutes: 3 }, adm.accessToken)).status, 403);

    const first = await (await w.get('/v1/admin/security/approval-limits', sup.accessToken)).json();
    assert.deepEqual({ ...first.settings }, { maxRequests: 5, windowMinutes: 15, expiryMinutes: 5, custom: false });

    // bounds: the protection can be tuned, never switched off
    for (const bad of [{ maxRequests: 0 }, { maxRequests: 21 }, { windowMinutes: 0 }, { windowMinutes: 121 }, { expiryMinutes: 0 }, { expiryMinutes: 31 }, { maxRequests: 'x' }]) {
      assert.equal((await w.post('/v1/admin/security/approval-limits', { maxRequests: 2, windowMinutes: 10, expiryMinutes: 3, ...bad }, sup.accessToken)).status, 400, JSON.stringify(bad));
    }
    const saved = await (await w.post('/v1/admin/security/approval-limits', { maxRequests: 2, windowMinutes: 10, expiryMinutes: 3 }, sup.accessToken)).json();
    assert.deepEqual(saved.settings, { maxRequests: 2, windowMinutes: 10, expiryMinutes: 3, custom: true });

    // in force straight away: the account turns approval on; the third request in the window is refused; a request lives 3 minutes
    await w.post('/v1/security/device-approval', { enabled: true }, ana.accessToken);
    const r1 = await (await w.login('ana001', { device: dev('x1') })).json();
    assert.ok(Math.abs(r1.expiresAt - Date.now() - 3 * 60_000) < 5000, 'expires after the configured minutes');
    await w.post(`/v1/auth/login-requests/${r1.requestId}/deny`, {}, ana.accessToken);
    const r2 = await (await w.login('ana001', { device: dev('x1') })).json();
    await w.post(`/v1/auth/login-requests/${r2.requestId}/deny`, {}, ana.accessToken);
    const blocked = await w.login('ana001', { device: dev('x1') });
    assert.equal(blocked.status, 429, 'the third request of this device in the window is refused');
    assert.ok((await blocked.json()).retryAfter > 0, 'and it says how long to wait');
    assert.equal((await w.login('ana001', { device: dev('x2') })).status, 409, 'another device has its own allowance');

    const log = (await (await w.get('/v1/admin/security/events?personId=sup001&limit=50', sup.accessToken)).json()).events;
    const change = log.find((e) => e.type === 'APPROVAL_LIMITS_CHANGED');
    assert.ok(change, 'the change is in the audit log');
    assert.deepEqual(change.meta.after, { maxRequests: 2, windowMinutes: 10, expiryMinutes: 3 });
    assert.equal(change.meta.before.maxRequests, 5);
  } finally { w.close(); }
});

test('deleting a trusted device logs out its other sessions, keeps the caller and other devices signed in, and the trust is gone', async () => {
  const w = await world();
  try {
    const other = await w.signIn('ana001', dev('dev-3', 'Old tablet'));
    const first = await w.signIn('ana001', dev('dev-1', 'Ana phone'));
    const on = await (await w.post('/v1/security/device-approval', { enabled: true }, first.accessToken)).json();
    // the same device signs in again by its trusted credential: a second session of that device
    const again = await (await w.login('ana001', { device: dev('dev-1', 'Ana phone'), trustedToken: on.trustedToken })).json();
    assert.ok(again.accessToken);
    const list = await w.st.secList('trusted', 'ana001');
    const removed = await (await w.post(`/v1/security/trusted/${list[0].id}/remove`, {}, first.accessToken)).json();
    assert.equal(removed.ok, true);
    assert.equal(removed.loggedOut, 1, 'the device\'s other session is logged out');
    assert.equal((await w.get('/v1/security/status', again.accessToken)).status, 401, 'the deleted device is signed out on its very next call');
    assert.equal((await w.get('/v1/security/status', first.accessToken)).status, 200, 'the caller stays signed in');
    assert.equal((await w.get('/v1/security/status', other.accessToken)).status, 200, 'another device is untouched');
    assert.equal((await w.login('ana001', { device: dev('dev-1', 'Ana phone'), trustedToken: on.trustedToken })).status, 409, 'the trust is gone: it needs approval again');
    assert.equal((await w.post(`/v1/security/trusted/${list[0].id}/remove`, {}, first.accessToken)).status, 404, 'deleted once');
  } finally { w.close(); }
});

test('approval limit is per device: tries from the website never use the phone\'s allowance, and a retry while waiting is not counted', async () => {
  const w = await world();
  try {
    const first = await w.signIn('ana001', dev('dev-1', 'Ana phone'));
    await w.post('/v1/security/device-approval', { enabled: true }, first.accessToken);
    // a browser tries many times in a row: each retry replaces its own waiting request and is not counted again
    for (let i = 0; i < 12; i += 1) assert.equal((await w.login('ana001', { device: dev('web-1', 'GoPreach Web') })).status, 409);
    // it also exhausts its own allowance once its requests are decided (5 per 15 minutes)
    for (let i = 0; i < 5; i += 1) {
      const r = await (await w.login('ana001', { device: dev('web-2', 'Other browser') })).json();
      await w.post(`/v1/auth/login-requests/${r.requestId}/deny`, {}, first.accessToken);
    }
    assert.equal((await w.login('ana001', { device: dev('web-2', 'Other browser') })).status, 429);
    // the phone has not tried yet and is not blocked
    const phone = await w.login('ana001', { device: dev('phone-9', 'New phone') });
    assert.equal(phone.status, 409, 'a new device still gets its request');
    assert.ok((await phone.json()).requestId);
  } finally { w.close(); }
});

test('a device that was logged out finds out by itself: the cheap session check answers 401 session-ended at once', async () => {
  const w = await world();
  try {
    const phone = await w.signIn('ana001', dev('dev-1', 'Ana phone'));
    const web = await w.signIn('ana001', dev('web-1', 'GoPreach Web'));
    assert.equal((await w.get('/v1/auth/session', web.accessToken)).status, 200);
    assert.equal((await w.get('/v1/auth/session')).status, 401, 'no token, no session');
    // the phone logs every other device out
    assert.equal((await w.post('/v1/security/devices/revoke-others', {}, phone.accessToken)).status, 200);
    const gone = await w.get('/v1/auth/session', web.accessToken);
    assert.equal(gone.status, 401);
    assert.equal((await gone.json()).error, 'session-ended');
    assert.equal((await w.get('/v1/auth/session', phone.accessToken)).status, 200, 'the phone that asked stays in');
    // and the other way round: the browser logs everything out, the phone is told on its next check
    const web2 = await w.signIn('ana001', dev('web-2', 'GoPreach Web'));
    assert.equal((await w.post('/v1/security/devices/revoke-all', {}, web2.accessToken)).status, 200);
    assert.equal((await w.get('/v1/auth/session', phone.accessToken)).status, 401);
  } finally { w.close(); }
});

// ---- account recovery: lost password AND authenticator ------------------------------------------------------------------------------------------------------------------

async function withRecoveryEmail(w, id = 'ana001') {
  const s = await w.signIn(id, dev('dev-1', 'Ana phone'));
  await w.enable2fa(id, s);
  await w.post('/v1/security/recovery-email', { email: 'ana@example.org' }, s.accessToken);
  const code = /(\d{6})/.exec(w.sent.at(-1).text)[1];
  assert.equal((await w.post('/v1/security/recovery-email/verify', { code }, s.accessToken)).status, 200);
  return s;
}
const readyNow = async (w, requestId) => { const c = await w.st.getChallenge(requestId); await w.st.putChallenge(requestId, { ...c, data: { ...c.data, readyAt: Date.now() - 1000 } }); };

test('recovery: e-mail code, then a 24-hour wait, then a new password; every session, trusted device and two-factor are cleared', async () => {
  const w = await world();
  try {
    const owner = await withRecoveryEmail(w);
    const sentBefore = w.sent.length;
    // the answer is the same for an unknown username and nothing is sent
    const ghost = await (await w.post('/v1/public/recovery/request', { username: 'nobody-here' })).json();
    assert.ok(ghost.requestId && ghost.pollToken);
    assert.equal(w.sent.length, sentBefore, 'no e-mail for an unknown account');
    // an account without a confirmed recovery e-mail gets the same answer and no e-mail
    await w.post('/v1/public/recovery/request', { username: 'ben' });
    assert.equal(w.sent.length, sentBefore);

    const req = await (await w.post('/v1/public/recovery/request', { username: 'ana' })).json();
    assert.equal(w.sent.length, sentBefore + 1);
    assert.equal(w.sent.at(-1).to, 'ana@example.org');
    assert.ok(!w.sent.at(-1).text.includes(req.pollToken), 'no secret is e-mailed');
    const code = /(\d{6})/.exec(w.sent.at(-1).text)[1];
    const at = { requestId: req.requestId, pollToken: req.pollToken };
    assert.equal((await w.post('/v1/public/recovery/status', { ...at, pollToken: 'x'.repeat(30) })).status, 404, 'a wrong poll token reads nothing');
    assert.equal((await w.post('/v1/public/recovery/verify', { ...at, code: '000000' })).status, 401);
    assert.equal((await w.post('/v1/public/recovery/complete', { ...at, newPassword: 'Brand-new-1' })).status, 409, 'no code yet: nothing to complete');

    const started = await (await w.post('/v1/public/recovery/verify', { ...at, code })).json();
    assert.equal(started.status, 'waiting');
    assert.ok(Math.abs(started.readyAt - Date.now() - 24 * 3_600_000) < 10_000, '24 hours');
    assert.equal((await w.post('/v1/public/recovery/verify', { ...at, code })).status, 409, 'a code works once');
    assert.ok(w.sent.at(-1).subject.includes('recovery started'), 'the owner is told by e-mail');
    const notices = await (await w.get('/v1/security/notices', owner.accessToken)).json();
    assert.ok(notices.notices.some((n) => n.type === 'RECOVERY_STARTED'), 'and in the app');
    const pending = await (await w.get('/v1/security/recovery', owner.accessToken)).json();
    assert.equal(pending.pending.status, 'waiting');

    // too early
    const early = await w.post('/v1/public/recovery/complete', { ...at, newPassword: 'Brand-new-1' });
    assert.equal(early.status, 409);
    assert.equal((await early.json()).error, 'not-yet');
    assert.equal((await w.login('ana001', {}, 'Brand-new-1')).status, 401, 'the old password still rules until the wait is over');

    await readyNow(w, req.requestId);
    assert.equal((await (await w.post('/v1/public/recovery/status', at)).json()).status, 'ready');
    assert.equal((await w.post('/v1/public/recovery/complete', { ...at, newPassword: 'abc' })).status, 400, 'a weak password is refused');
    assert.equal((await w.post('/v1/public/recovery/complete', { ...at, newPassword: 'Brand-new-1' })).status, 200);
    assert.equal((await w.post('/v1/public/recovery/complete', { ...at, newPassword: 'Another-1' })).status, 409, 'once');

    assert.equal((await w.get('/v1/security/status', owner.accessToken)).status, 401, 'every old session is ended');
    assert.equal((await w.login('ana001')).status, 401, 'the old password is gone');
    const fresh = await w.login('ana001', {}, 'Brand-new-1');
    assert.equal(fresh.status, 200, 'the new password signs in with no second step: two-factor starts again from nothing');
    const status = await (await w.get('/v1/security/status', (await fresh.json()).accessToken)).json();
    assert.equal(status.twoFactor.enabled, false);
    const types = (await w.st.secList('secevent', 'ana001', { limit: 100 })).map((e) => e.data.type);
    for (const t of ['RECOVERY_CODE_SENT', 'RECOVERY_STARTED', 'RECOVERY_COMPLETED']) assert.ok(types.includes(t), t);
  } finally { w.close(); }
});

test('recovery: the owner cancels with "This was not me", and five wrong codes end the request', async () => {
  const w = await world();
  try {
    const owner = await withRecoveryEmail(w);
    const start = async () => {
      const req = await (await w.post('/v1/public/recovery/request', { username: 'ana' })).json();
      const code = /(\d{6})/.exec(w.sent.at(-1).text)[1];
      const at = { requestId: req.requestId, pollToken: req.pollToken };
      return { at, code, verify: () => w.post('/v1/public/recovery/verify', { ...at, code }) };
    };
    // "This was not me"
    const a = await start();
    assert.equal((await a.verify()).status, 200);
    assert.equal((await (await w.post('/v1/security/recovery/cancel', {}, owner.accessToken)).json()).cancelled, 1);
    await readyNow(w, a.at.requestId);
    assert.equal((await w.post('/v1/public/recovery/complete', { ...a.at, newPassword: 'Brand-new-1' })).status, 409, 'a cancelled recovery cannot be completed');
    assert.equal((await (await w.get('/v1/security/recovery', owner.accessToken)).json()).pending, null);

    // a password alone (the second step is still asked) cancels nothing; an unrelated account signing in cancels nothing
    const b = await start();
    assert.equal((await b.verify()).status, 200);
    assert.ok((await w.signIn('ben001')).accessToken);
    const stage = await (await w.login('ana001', { device: dev('dev-1', 'Ana phone') })).json();
    assert.equal(stage.twoFactorRequired, true);
    assert.equal((await (await w.post('/v1/public/recovery/status', b.at)).json()).status, 'waiting');
    await w.post('/v1/security/recovery/cancel', {}, owner.accessToken);

    // five wrong codes end the request
    const c = await start();
    for (let i = 0; i < 5; i += 1) assert.equal((await w.post('/v1/public/recovery/verify', { ...c.at, code: '111111' })).status, 401);
    assert.equal((await w.post('/v1/public/recovery/verify', { ...c.at, code: c.code })).status, 429, 'the right code is too late');
    assert.equal((await w.post('/v1/public/recovery/status', c.at)).status, 404);
  } finally { w.close(); }
});

test('recovery: the owner signing in cancels a recovery started in their name', async () => {
  const w = await world();
  try {
    const ben = await w.signIn('ben001');
    await w.post('/v1/security/recovery-email', { email: 'ben@example.org' }, ben.accessToken);
    await w.post('/v1/security/recovery-email/verify', { code: /(\d{6})/.exec(w.sent.at(-1).text)[1] }, ben.accessToken);
    const req = await (await w.post('/v1/public/recovery/request', { username: 'ben' })).json();
    const at = { requestId: req.requestId, pollToken: req.pollToken };
    assert.equal((await w.post('/v1/public/recovery/verify', { ...at, code: /(\d{6})/.exec(w.sent.at(-1).text)[1] })).status, 200);
    assert.equal((await (await w.post('/v1/public/recovery/status', at)).json()).status, 'waiting');
    assert.ok((await w.signIn('ben001', dev('dev-7', 'Ben tablet'))).accessToken, 'the owner remembers the password and signs in');
    assert.equal((await (await w.post('/v1/public/recovery/status', at)).json()).status, 'cancelled');
    const types = (await w.st.secList('secevent', 'ben001', { limit: 100 })).map((e) => e.data.type);
    assert.ok(types.includes('RECOVERY_CANCELLED'));
  } finally { w.close(); }
});

test('one device: rename it everywhere, or remove it (its sessions are logged out and its trust ends); the device in hand is only renamed', async () => {
  const w = await world();
  try {
    const tablet = await w.signIn('ana001', dev('dev-3', 'Old tablet'));
    const laptop = await w.signIn('ana001', dev('dev-5', 'Laptop'));
    const first = await w.signIn('ana001', dev('dev-1', 'Ana phone'));
    const on = await (await w.post('/v1/security/device-approval', { enabled: true }, first.accessToken)).json();
    // the phone signs in again with its trusted credential: a second session of the same device
    const again = await (await w.login('ana001', { device: dev('dev-1', 'Ana phone'), trustedToken: on.trustedToken })).json();
    assert.ok(again.accessToken);

    // rename: every session of the device carries the new name
    assert.equal((await w.post('/v1/security/device/rename', { deviceId: 'dev-3', name: 'Kitchen tablet' }, first.accessToken)).status, 200);
    const list = await (await w.get('/v1/security/devices', first.accessToken)).json();
    assert.ok(list.devices.filter((d) => d.deviceId === 'dev-3').every((d) => d.name === 'Kitchen tablet'));
    assert.equal((await w.post('/v1/security/device/rename', { deviceId: 'dev-3', name: ' ' }, first.accessToken)).status, 400);
    assert.equal((await w.post('/v1/security/device/rename', { deviceId: 'nope', name: 'X' }, first.accessToken)).status, 404);

    // the device in hand cannot be removed from itself
    assert.equal((await w.post('/v1/security/device/remove', { deviceId: 'dev-1' }, first.accessToken)).status, 409);
    // another device is removed: signed out at once
    const removed = await (await w.post('/v1/security/device/remove', { deviceId: 'dev-3' }, first.accessToken)).json();
    assert.equal(removed.loggedOut, 1);
    assert.equal((await w.get('/v1/security/status', tablet.accessToken)).status, 401);
    assert.equal((await w.post('/v1/security/device/remove', { deviceId: 'dev-9' }, first.accessToken)).status, 404);

    // the trusted phone, removed from the laptop: both its sessions end and its trust is gone
    const phone = await (await w.post('/v1/security/device/remove', { deviceId: 'dev-1' }, laptop.accessToken)).json();
    assert.equal(phone.loggedOut, 2);
    assert.equal((await w.get('/v1/security/status', first.accessToken)).status, 401);
    assert.equal((await w.login('ana001', { device: dev('dev-1', 'Ana phone'), trustedToken: on.trustedToken })).status, 409, 'its trust ended: it needs approval again');
    const types = (await w.st.secList('secevent', 'ana001', { limit: 100 })).map((e) => e.data.type);
    assert.ok(types.includes('DEVICE_REMOVED'));
  } finally { w.close(); }
});

test('notices: marking them seen clears every notice that was listed (not only the newest 100), so a closed notice never comes back', async () => {
  const w = await world();
  try {
    const s = await w.signIn('ana001', dev('dev-1', 'Ana phone'));
    for (let i = 0; i < 150; i += 1) {
      await w.st.secPut('secevent', `n-${String(i).padStart(4, '0')}`, 'ana001', { at: Date.now() - 1000 + i, personId: 'ana001', type: 'NEW_DEVICE_LOGIN', result: 'success', meta: { device: `Device ${i}` }, notify: true, seenAt: null });
    }
    const before = await (await w.get('/v1/security/notices', s.accessToken)).json();
    assert.ok(before.notices.length > 100, 'more than a hundred are listed');
    assert.equal((await w.post('/v1/security/notices/seen', {}, s.accessToken)).status, 200);
    const after = await (await w.get('/v1/security/notices', s.accessToken)).json();
    assert.equal(after.notices.length, 0, 'none comes back');
    assert.equal(after.unseen, 0);
  } finally { w.close(); }
});
