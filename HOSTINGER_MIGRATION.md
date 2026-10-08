# Moving GoPreach's data from Firebase Firestore to Hostinger

Why: Firestore's free plan stops every read/write with `RESOURCE_EXHAUSTED: Quota exceeded` once the daily allowance is used.
Hostinger (Node.js + MySQL) has no per-read limit. **Logins (Firebase Auth) and images (Firebase Storage) stay on Firebase** for now;
neither is affected by the Firestore quota.

## What is ready (in this repository)
- `backend/` - the Node.js + MySQL API (`/v1/sync/push`, `/v1/sync/pull`, territory and public-lookup endpoints) and its port of every rule
  in `firestore.rules`, including the Circuit Overseer, attendance, statistics and Comparative Report collections (48 tests pass).
- The app already syncs through it when `gopreach.backendUrl` is set (offline-first queue, push, pull every 15-30 s).
- The report workflows that used Firestore transactions (Field Service Report submit / undo / receive / return / remarks and the
  Comparative Report submit / receive / return / remarks) now have a backend version that pushes the changed documents in one request;
  the server re-checks every move with its rules. Used automatically when `gopreach.backendUrl` is set.
- `GoPreach-backend.zip` (on the Desktop) is the server upload without `node_modules` and without secrets.

## What only you can do (needs your Hostinger account)
1. **hPanel -> Databases -> Management:** create a MySQL database + user; note the name, user, password.
2. **hPanel -> Websites -> Node.js:** create a Node.js web app (Node 22), entry file `src/server.js`, upload `GoPreach-backend.zip`, run *NPM install*.
3. Add the environment values from `.env.example` in hPanel (`STORE=mysql`, the DB values, `FIREBASE_PROJECT_ID=gopreach-957a6`). Never commit them.
4. Run the `migrate` script once (creates the tables). Point a subdomain, e.g. `api.yourdomain.com`, at the app (HTTPS is provided).
5. Check `https://api.yourdomain.com/v1/health`.
6. **Import the data** from your PC with `backend/scripts/import-firestore.js` (needs a Firebase service-account key; start with `--dry-run`).
   The import reads Firestore, so run it on a day the quota is not used up.
7. Add a daily MySQL backup (hPanel -> Backups).

## Then, on this PC
Put `gopreach.backendUrl=https://api.yourdomain.com` in `local.properties`, build, install, sign in, and test. Nothing changes for anyone
until a build with that line is released.

## Still open
- Image/file upload endpoints (images stay on Firebase Storage).
- Circuit-assignment and territory-claim transactions still use Firestore in the app unless their backend endpoints are wired (territory ones exist on the server).
- Live updates: the backend path polls, so changes appear within about 30 seconds instead of instantly.
- MySQL has not yet been tested under real load.
