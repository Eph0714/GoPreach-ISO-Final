# GoPreach backend (Hostinger Business Web Hosting)

A small Node.js + MySQL sync API that can replace Firestore as GoPreach's primary database.

**Status: step 1 of the migration — server, schema, sync endpoints, permissions for the drawing collections, and the
Firestore import script. The Android app still talks to Firebase; nothing is switched over yet.**

## How it works

The app already keeps every record as "collection + id + JSON" in its offline cache and uploads queued writes in an
outbox. The server mirrors that: a versioned document store with a global change sequence.

| Call | Purpose |
|---|---|
| `GET /v1/health` | liveness |
| `POST /v1/sync/push` | `{ ops: [{ collection, id, op: "set" \| "delete", data }] }` → a result per op (`ok` / `denied` / `invalid`). Each op is authorized on its own; one bad op never blocks the rest. |
| `GET /v1/sync/pull?since=<seq>&collections=a,b&limit=500` | everything changed after `seq` that the caller may read, deletes as tombstones, plus the next `cursor` and `hasMore`. The app polls this every 15–30 s while open (Business hosting has no reliable WebSockets). |

**Logins stay on Firebase Auth for now.** The app sends its Firebase ID token; the server only verifies it against Google's
public keys (`FIREBASE_PROJECT_ID`), so no secret is needed. The person id is the email's part before `@`, like
`firestore.rules`. Replacing Firebase Auth is a later, separate step.

## Authorization

`src/policy/` is the server-side port of `firestore.rules`.

* **Drawings** (`territoryDrawings`, `territoryDrawingAudits`, `territoryBounds`): `drawings.js`, role → congregation → FS Group → territory, bounding box for group-level users, append-only audits.
* **Every other collection**: `rules.js`, a faithful port of each `allow` in `firestore.rules` (people, userAccessGrants, congregations, groups,
  roleAssignments, monthlyReports, owner-only planner / Bible text / credit hours / timer records, groupChats and messages,
  interestedPeople and visits, presence, dashboardModuleLayouts, deletedRecords, mapPins, territory assignments,
  creditHourCategories, appSettings, preachingTimeRecords, and the plain signed-in collections). `test/rules.test.js` has a test per rule.
* **Reads** follow the rules, then ONE addition: sensitive collections that carry a congregation (`CONGREGATION_SCOPED_READS` in
  `rules.js`) are filtered to the caller's own congregation (or a grant's scope). Firestore could not do this (it rejects a whole
  query instead of filtering rows); this server can. Remove a name from that set to loosen it.

### Endpoints beyond sync

| Call | Login | Purpose |
|---|---|---|
| `POST /v1/public/lookup-username` `{ username }` | none (30 per 10 min per IP) | `{ person }` for the sign-in screen, replacing Firestore's public read of `people` |
| `POST /v1/public/password-reset-request` `{ username }` | none (5 per hour per IP) | server builds the `passwordResetRequests` row; always 202 |
| `POST /v1/territory/save` | token | claim barangays for a group in one province; 409 `{ barangayName, takenByGroupName }` if taken |
| `POST /v1/territory/remove-group` `{ congregationId, groupId, provinceId }` | token | release everything a group holds in a province |
| `POST /v1/territory/remove` `{ assignmentId }` | token | delete one assignment and its claims |

Territory calls are the server version of the app's three Firestore transactions; every write transaction locks the sequence counter, so concurrent claims are serialized (tested).

### Not ported / still open

- [ ] Image / file upload and download endpoints (`files` table exists).
- [ ] Group chat unread counters were atomic increments in Firestore; here they are plain document updates.
- [ ] Hardening the rules file itself documents as deliberately coarse (e.g. any signed-in user can edit `schedules`, `territories`, `forwardRequests`).
- [ ] Testing the MySQL store against a real MySQL under load (tests use the in-memory store with the same contract).

## Run locally (no database needed)

```
cd backend
npm install
npm test                              # in-memory store
set STORE=memory&& set AUTH_MODE=dev&& npm start
```

## Deploy to Hostinger Business Web Hosting

1. **hPanel → Databases → Management:** create a MySQL database and user. Note the host (usually `localhost`), name, user.
2. **hPanel → Websites → your site → Node.js:** create a Node.js Web App, **Node 22**, entry file `src/server.js`,
   and upload this folder (ZIP of `backend/` without `node_modules` and without `.env`). Run *NPM install* there.
3. In the app's environment settings (or a `.env` file on the server — never in git) set the values from `.env.example`.
   `STORE` must be `mysql`; leave `AUTH_MODE` unset.
4. Create the tables once: *Run NPM script* → `migrate` (or run `npm run migrate` over SSH).
5. Point a subdomain at the app (e.g. `api.yourdomain.com`; Hostinger provides the HTTPS certificate) and check
   `https://api.yourdomain.com/v1/health`.
6. Import the data (from your PC, not the server): see `scripts/import-firestore.js`. Start with `--dry-run`.
7. Add a daily MySQL backup (hPanel → Backups, or a cron job running `mysqldump` to a folder outside `public_html`).

## Not done yet

- The **Android data layer** (a switch between Firebase and this API, repositories/outbox/mirror calling `/v1/sync/*`).
- **Image upload** endpoints (`files` table exists; profile/record images still use Firebase Storage).
- **Policy porting** (checklist above) and testing the MySQL store against a real MySQL (tests use the in-memory store
  with the same contract).
- Replacing Firebase Auth.
