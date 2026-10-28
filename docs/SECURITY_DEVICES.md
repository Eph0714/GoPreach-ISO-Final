# Two-factor sign-in, devices and remote logout

Backend: `backend/src/security.js` (+ `accounts.js`, `auth.js`, table `security_docs`). Android: `ui/screens/security/`, `SecurityService.kt`. Website: `SecurityController`, `views/security/`.

## What changed for everyone

**One account now has any number of signed-in devices** (it used to be exactly one, with an "Is this you?" approval). Each device is a *session* the account holder can see and end. By default no approval is asked. New-device approval is an opt-in setting per account: see HOSTINGER_SECURITY.md section 3.

## Sign-in

1. Password right, no 2FA -> signed in, a device session is created.
2. Password right, 2FA on -> `202 { twoFactorRequired, challengeId, challengeToken }`, nothing is issued. `POST /v1/auth/2fa/verify` with an authenticator code, a recovery code or an e-mail code creates the session. 5 wrong tries end the challenge; a code is never accepted twice.
3. A *trusted device* presents its credential and skips step 2. The credential is a random secret only that device holds (the server keeps a hash), is tied to the device id, expires (`TRUSTED_DEVICE_DAYS`, default 30) and ends on password change, on turning 2FA off or when removed. It can only be created for the device in your hand, after a verified code.
4. A passkey sign-in is its own second factor.

TOTP is RFC 6238 (SHA-1, 6 digits, 30 s) on Node's `crypto`, checked against the RFC test vectors. The secret is stored AES-GCM-encrypted with a key derived from `AUTH_SECRET`; recovery codes are stored as HMACs and die on use.

## Revocation is real

Every request is checked against the session record (`auth.js`): a revoked session gets `401 session-ended` on its very next call, and its refresh tokens are deleted. Clients already treat that as "signed out". Revoking, turning 2FA off, regenerating recovery codes, trusting a device and changing the e-mail need a password typed in the last 10 minutes.

## Who sees what

* Own account: devices, trusted devices, own log (`/v1/security/...`).
* Super Admin: every session, filters, history, revoke one / all of a user, and the whole audit log.
* Admin of a congregation: sessions of people of that congregation only (others answer 404). Nobody else has device monitoring.
* Passwords, codes, recovery codes and tokens are never returned or logged. The log is append-only (no route edits or deletes an entry).

## Needs configuring

| What | Without it |
|---|---|
| SMTP (`SMTP_HOST`, `MAIL_FROM`, ...) | e-mail codes and the recovery e-mail are not offered |
| Push when the app is closed (FCM) | new-device notices appear the next time the app or website is open |

Not done: approximate location (only a shortened network address is kept), a QR code on the setup screen (the setup key and an "open in authenticator app" link are shown).
