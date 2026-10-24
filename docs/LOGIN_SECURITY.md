# Login protection for the first signed-in device

One account has one session. When someone signs in with the right password while another device holds the session, the new device does **not** replace it.

1. The server opens a *login request* (5 minutes) and answers `409 session-active` with `requestId`, `pollToken` and the methods on offer.
2. The signed-in device (Android app or website, polling every 5 s while open) shows **"Someone is trying to access your GoPreach account from another device. Is this you?"** with **Yes, It's Me** / **No, Deny Access**.
3. The new device polls `POST /v1/auth/login-requests/status`. Only an **approved** request lets it repeat the password sign-in with `approval: { requestId, pollToken }`; the server spends the request once and then (and only then) ends the old session.
4. Silence never approves: an unanswered request expires and is refused. A denied or expired request changes nothing.

Alternatives when the first device is unavailable:
- **E-mail code**: 6 digits, to the address on the person record *at the moment the request was made*, masked in the UI (`j******@example.com`). Valid 10 minutes, one use, 5 tries, at most 3 sends, 60 s between sends. Offered only when the server can send mail.
- **Passkey**: a verified passkey sign-in is its own proof and may take the session over after the person confirms on the new device.

`endOtherSession` on the password login is ignored by the server.

## Configuration still needed

| What | Where | Without it |
|---|---|---|
| SMTP for the e-mail code: `SMTP_HOST`, `SMTP_PORT` (465), `SMTP_SECURE`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM` (Hostinger mailbox: `smtp.hostinger.com`). Run `npm install` in `backend` (adds `nodemailer`). | `backend/.env` | The e-mail method is not offered; approval from the first device still works. |
| Database table `auth_challenges` | created automatically at start (MySQL) | n/a |
| Push notification to the first phone **when the app is closed** | needs Firebase Cloud Messaging (not configured in this app) | The "Is this you?" box appears the next time the app or website is open; the request expires after 5 minutes. |
| Passkeys | `PASSKEY_RP_ID`, `ANDROID_CERT_SHA256`, `PASSKEY_WEB_ORIGINS` (see `backend/src/config.js`) | Passkeys answer 503. |

## Find Location (coordinates from a picture)

- Android reads the picture **on the phone** with Google ML Kit text recognition (bundled, no credentials, nothing uploaded). The website reads it in the browser with Tesseract.js, loaded from jsDelivr only when used (needs internet the first time; English data).
- Both use the same parsing rules and test cases (`CoordinateParser.kt`, `public/js/coordinate-parser.js`). The photo's own GPS tags are listed separately from text read in the picture. A picture without usable coordinates says so and invents nothing.
- Different OCR engines can read the same blurry picture differently; the parsing and validation are identical.
