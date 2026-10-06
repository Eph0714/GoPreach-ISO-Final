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

* **Ported exactly (and tested):** `territoryDrawings`, `territoryDrawingAudits`, `territoryBounds` — role → congregation →
  FS Group → territory, bounding box for group-level users, status/color match, append-only audits.
* **Safe default for everything else:** writes only inside the caller's own congregation, no cross-congregation writes, no
  minting Super Admins, no assigning Super Admin/Circuit Overseer roles unless you are the Super Admin.
  Reads are limited to the caller's own congregation (Super Admin: all).

### Policy porting checklist (do before Firebase is switched off)

The default is deliberately stricter than the old "any signed-in user" rules, but it is not yet the per-collection logic:

- [ ] `roleAssignments` / `people` / `groups`: who may edit whom (Coordinator, Secretary, Service Overseer, group slots)
- [ ] `monthlyReports`: publisher edits only their own; locks after submission
- [ ] `interestedPeople` + `…/visits`: owner / assigned-publisher / forward-request rules
- [ ] `forwardRequests`, `publisherForwardRequests`, `houseHolderAssignments`: cross-congregation visibility fields
- [ ] `userAccessGrants` and Circuit Overseer scoping
- [ ] subcollections (`interestedPeople/{id}/visits`, chats): derive the congregation from the parent document
- [ ] private per-publisher collections (planner, credit hours, timers, Bible texts): only the owner

Add tests next to `test/api.test.js` for each port.

## Run locally (no database needed)

```
cd backend
npm install
npm test                              # 13 tests, in-memory store
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
