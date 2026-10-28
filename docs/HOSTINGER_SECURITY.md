# Hostinger-only security system: assessment, API, deployment

Everything below runs on the Hostinger Node/MySQL backend (`backend/`). No Firebase service is used for sign-in, 2FA, device approval, notices or the audit log. Companion files: [SECURITY_DEVICES.md](SECURITY_DEVICES.md) (sign-in and revocation detail), [LOGIN_SECURITY.md](LOGIN_SECURITY.md).

## 1. Architecture assessment

| Piece | Where | State |
|---|---|---|
| Password sign-in, sessions, refresh, logout | `accounts.js`, `auth.js` | Done. Many devices per account; each is a revocable session. |
| TOTP 2FA + recovery codes | `security.js` | Done. Secret AES-GCM encrypted (key from `AUTH_SECRET`); recovery codes stored as HMACs, single use. |
| New-device approval | `loginApproval.js`, `security.js` | Done, **opt-in per account, off by default** (see section 3). |
| Trusted devices | `security.js` (kind `trusted`) | Done. Random secret held by the device, server keeps a hash, tied to device id, expires after `TRUSTED_DEVICE_DAYS` (30). |
| Notifications | e-mail (SMTP) + in-app notices (`/v1/security/notices`) | Done; no push service. A notice shows when the app or site is next open. |
| Login history / audit log | `/v1/security/events` | Done, append-only. Filters in the UI are not built yet. |
| Server-enforced revocation | `auth.js` | Done. A revoked session gets `401 session-ended` on its next request and its refresh tokens are deleted. |
| Role / congregation scope | `security.js`, `policy/` | Own account; Super Admin all; congregation Admin only that congregation (others get 404). |
| Account recovery request flow | | **Not built.** |

Why the lockout seen earlier cannot return: approval used to be forced on everyone by a one-session rule. It is now a setting each account turns on from a device it already holds, and turning it off needs only the password.

## 2. Data model

Stored through the generic security store (`security_docs` in MySQL, memory store in tests):

* `a2fa` (one per person): encrypted TOTP secret, recovery-code HMACs, `deviceApproval {enabled, at}`.
* `dsession`: one per signed-in device (name, type, OS, app version, shortened IP, created/last seen, revoked flag).
* `trusted`: hash of the device credential, device id, expiry, revoked flag.
* `secevent`: audit entries (type, actor, device, result, time). No passwords, tokens, TOTP secrets or full recovery codes are ever written.
* Login requests are short-lived challenges (kind `loginreq`).

## 3. New-device approval

1. Owner turns it on (`POST /v1/security/device-approval {enabled:true}`, password typed in the last 10 minutes). The current device becomes trusted and its credential is returned once.
2. A device that is not trusted sends the correct password. `POST /v1/auth/login` answers `409 {error:"session-active", requestId, pollToken, expiresAt, ...}`. **No tokens are issued.**
3. The device polls `POST /v1/auth/login-requests/status`. It can only read its own request, using the poll token.
4. The owner sees the request (`GET /v1/auth/login-requests`: device, OS, time, shortened IP) and calls `.../:id/approve` or `.../:id/deny`. Only the owning account can decide (others get 404). A request is decided once.
5. After approval the device repeats the login with `approval {requestId, pollToken}`; the session is issued once and the approval is spent.
6. Expiry is 5 minutes (`LOGIN_REQUEST_MS`). More than 5 requests in 15 minutes for one account returns `429`. If the account has an e-mail, the owner is told by e-mail (the mail contains no secret). With no device at hand: `login-requests/email-code` then `email-verify` with a 6-digit code.

## 4. API reference (all JSON, `Authorization: Bearer` unless noted)

**Auth (public or session)**
`POST /v1/auth/login`, `/refresh`, `/logout`, `/reauth`, `/password`, `/2fa/verify`, `/2fa/email-code`, `/passkey/...`, `/login-requests/status`, `/login-requests/email-code`, `/login-requests/email-verify` (the last three use the request's poll token, not a session).
**Login requests (session):** `GET /v1/auth/login-requests`, `POST /v1/auth/login-requests/:id/approve|deny`.
**Security (session):**
`GET /v1/security/status | devices | events | notices`;
`POST /v1/security/2fa/totp/setup | totp/confirm | disable | recovery-codes`;
`POST /v1/security/devices/:sessionId/rename | revoke`, `devices/revoke-others | revoke-all`;
`POST /v1/security/trusted/current`, `trusted/:id/remove`;
`POST /v1/security/device-approval`, `recovery-email`, `recovery-email/verify`, `notices/seen`.

Actions that change security (revoke, 2FA off, recovery codes, trust, device approval, e-mail change) need a password typed in the last 10 minutes.

## 5. Deployment

Backend (Hostinger Business, Node app):
1. Push `backend/` to GitHub `main`; Hostinger pulls it. Run `npm install` if dependencies changed (SMTP needs `nodemailer`).
2. Environment (never committed): `AUTH_SECRET` (long random; changing it invalidates encrypted TOTP secrets, so keep it), `DB_*`, `STORE=mysql`, `NODE_ENV=production`, `AUTH_MODE` not `dev`, `TRUSTED_DEVICE_DAYS`.
3. SMTP for e-mail codes and notices: `SMTP_HOST=smtp.hostinger.com`, `SMTP_PORT=465`, `SMTP_SECURE=true`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM`. Without them e-mail methods are simply not offered.
4. Restart the Node app and check `/health`.

Website: push the Laravel repo to `main`; GitHub Actions deploys to Hostinger. It needs the backend deployed first for the new card.

Backups: daily MySQL backup (hPanel Backups or `mysqldump` to a folder outside `public_html`). `security_docs` is in the same database. Take a manual dump before any release that changes the schema.

Rollback: revert the commit on `main` and redeploy. Approval is off for every account unless its owner enabled it, so rolling back only removes the option. No migration is involved in this feature.

## 6. Firebase audit

* No Firebase SDK in `gradle`, the app, or the backend; no `google-services`.
* Removed from the project root and archived in `docs/legacy-firebase/`: `firebase.json`, `storage.rules`, `firestore.rules`, `firestore-tests/`. `firestore.rules` is kept as the written spec of the backend policy port.
* Remaining on purpose: `backend/scripts/import-firestore.js`, `import-auth.js` and `FIREBASE_HASH_*` settings, which only let accounts migrated from the old project keep their password; the word "firestore" in code comments.

## 7. Known limitations

* Approval is off by default; accounts must opt in.
* No push when the app is closed; notices appear on next open.
* Approximate location is not stored (shortened IP only).
* Expired login requests are purged hourly by the server (`purgeChallenges`). The account-recovery request flow is not built yet. Login-history filters (result, kind, time range, device) and a notification centre are on the Security page of web and Android; the API takes `result`, `type`, `q`, `from`, `to` on `/v1/security/events` and `all=1` on `/v1/security/notices`.
* A Super Admin cannot bypass 2FA or approval, by design.
* The 2FA and approval flows are covered by backend and web tests but have not been exercised on a physical device yet.

## 8. Super Admin: new-device request limits

`GET/POST /v1/admin/security/approval-limits` (Super Admin only; saving needs a password typed in the last 10 minutes). Fields: `maxRequests` (1-20, default 5), `windowMinutes` (1-120, default 15), `expiryMinutes` (1-30, default 5). Stored as kind `secsettings` in `security_docs`, read on every request so a change applies at once with no restart. Out-of-range values are refused with 400, so the protection can be tuned but never switched off. Each change is written to the audit log (`APPROVAL_LIMITS_CHANGED`, with the old and new values). Screen: Security Audit Logs on web and Android.
