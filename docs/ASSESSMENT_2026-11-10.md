# GoPreach assessment - 2026-11-10

Scope actually covered: backend login/lookup rules, the Android sync engine (push, pull, cursor, account switching), a read-only integrity scan of the live data
(Super Admin pull of every collection: 3,000+ rows), the web login form, and live checks of the login routes. NOT covered (see "Not done"): a module-by-module
CRUD test on a real phone and browser, network-failure simulation, performance work, Room schema changes.

## Problems found, root causes, status

| # | Severity | Problem | Root cause | Status |
|---|---|---|---|---|
| 1 | High | Correct credentials rejected on the web (and sometimes Android) | Phone keyboards add a trailing space (web password box sent 15 chars, last = space) and a leading capital; usernames matched case-sensitively and never trimmed on the server | Fixed, pushed, live (backend `accounts.js`, `store.js`, `mysqlStore.js`, `memoryStore.js`). Android fields no longer auto-capitalize (released as 1.169.50) |
| 2 | High | Super Admin sees 0 publishers for Central Congregation Solano (server has 10) | The download cursor only moves forward. Another account (that could not read those rows) had synced on the same phone; the Super Admin has no active congregation so the "re-download once" catch-up was skipped | Fixed in code, NOT pushed/released: `SyncEngine.repullOnceFor` also runs for accounts with no congregation |
| 3 | High | Previous account's data stays on the phone for the next account; its unsent changes upload under the next account's identity | No cache/cursor handling when a different person signs in. This is what produced the 4 "deletedRecords - DELETE denied" sync errors | Fixed in code, NOT pushed/released: `SyncEngine.claimCacheFor` (called from `AuthRepository.finishSignIn`): removes the previous account's synced copy, restarts the download, keeps the old account's unsent changes flagged ("other-account") so they are never sent as the new person. 3 new tests |
| 4 | Medium | Android writes are last-write-wins | The Android sync never sends `baseVersion` (the server supports it; the website sends it on edit forms). A stale Android edit can overwrite a newer web edit | OPEN. Needs the server version stored per cached document (Room schema change + migration + on-device test). Not attempted blind |
| 5 | Low | 2 role assignments point to people that no longer exist (`KLvxpG0o...` Coordinator Elder, Solano Tagalog; `KpKb1zCD...` Inactive Publisher, Central Solano) | Person records were removed without removing the assignment | OPEN, data left untouched (production). The app skips them silently. Repair = soft-delete those 2 assignments; needs your OK |
| 6 | Low | 2 people with no role assignment (`TEST TESTLOGIN`, `ISRAEL D. CANALES`); same full name used by several people (Ephraim Fernandez x3, Israel Canales x2, Relito Martin x2, Richard Martin x2) | Test/duplicate enrolments | OPEN, report only. Usernames do not collide (also case-insensitively), so sign-in is unaffected |
| 7 | Info | Sync "100%" honesty / per-record status | The engine already keeps rejected edits (flagged, shown under Sync Error with Retry/Discard) and never reports synced while the queue holds items | No change needed |

## What was checked and is sound
- Record ids are stable and shared (server document ids, client-generated ids, idempotent `set`/`delete`): repeating a push cannot create a duplicate.
- Pull: an unsent local edit wins over another device's edit; a record deleted elsewhere is removed and the stale edit is kept flagged, never resurrected.
- Server push: one transaction per operation, permission checked per operation on the server (not only in the app), deleted records are not brought back.
- Live data integrity: no assignment with a missing congregation or a group of another congregation, no person with two active publisher assignments in one congregation, no interested person without an owner or congregation, no visit without its parent, no monthly report without a publisher, no person whose active congregation lacks an assignment, no territory without a congregation.

## Tests run (all executed here)
- Backend: 326 pass, 0 fail (`NODE_ENV=test npm test`; includes new username-lookup and trailing-space tests).
- Android + shared unit tests: 439 pass, 0 fail (`:app:testDebugUnitTest :shared:testDebugUnitTest`; includes the 3 new account-switch tests).
- Live: Super Admin signs in on the real server with `Ephraim`, `ephraim`, and the password with a trailing space (API and website); a wrong password is still refused. Android 1.169.50 signed in on the connected phone. The four rejected sync changes were retried as Super Admin and cleared.

## Not done / limits (be aware)
- No on-device test of problems 2 and 3 yet (the fix is not installed). After it ships, sign in as the Super Admin on a phone another account used: Central Solano should list its publishers.
- No Room schema change, no `baseVersion` on Android (problem 4), no offline-sign-in account-switch handling (an offline sign-in cannot switch accounts).
- No module-by-module CRUD matrix on web vs Android, no slow/offline/timeout simulation, no performance profiling, no new logging beyond what exists (the backend already records failed logins, device sessions and a security log without passwords or tokens).
- Nothing in this file's "NOT pushed" rows is deployed. Deploying = commit + push (backend deploys on push) + `node scripts/release.mjs --notes=...`.

## Deployment status
- Backend: username/password fixes live. Account-switch/Super Admin sync fixes are client-side only.
- Web: no change needed (the backend fixes cover it).
- Android: 1.169.50 released and installed on the test phone. The sync fixes (problems 2 and 3) are in the working tree, committed locally, not released.
- Database: no migration run, no data changed.

## Overall status
Operational with known limitations. Login is repaired and live. The "missing records after switching accounts" problem is fixed in code and tested in unit tests but not shipped. Android last-write-wins (problem 4) is the main open data-integrity risk.
