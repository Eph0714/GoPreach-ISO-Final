# GoPreach

Android app for congregation publisher activity tracking — basic personal
data, monthly Bible studies, preaching hours, interested-people visits, and
territory/schedule management. Built by EMF IT Solutions, Est. 2026.

## Tech stack

- **Kotlin + Jetpack Compose** (Material 3), single APK
- **Firebase**: Auth, Firestore, Storage
- **Offline-first**: a generic Room-backed cache + outbox (see
  [`data/sync/OfflineFirestoreRepository.kt`](app/src/main/java/com/emfitsolutions/gopreach/data/sync/OfflineFirestoreRepository.kt))
  that every repository writes through, flushed by a WorkManager `SyncWorker`
  whenever connectivity is available
- **Hilt** for dependency injection
- Min SDK 24, compile/target SDK 35

## Structure

One app, two logical contexts reached after login based on the signed-in
Person's roles (a person can hold both):

- **Admin context** — Super-Admin, Admin Per Congregation, Coordinator Elder,
  Regular Elder: enrollment, congregation/publisher/territory/schedule
  management, Control Panel, reports, backup/restore, user logs.
- **Ministry Report context** — Publisher: monthly reports, Bible Study
  Record, Interested People + visits, personal calendar notes.

See [`data/model/`](app/src/main/java/com/emfitsolutions/gopreach/data/model)
for the full schema and [`domain/PermissionChecker.kt`](app/src/main/java/com/emfitsolutions/gopreach/domain/PermissionChecker.kt)
for how role/scope checks work — every permission question reduces to "does
this Person have an active RoleAssignment matching X in scope Y."

## Getting started

See [SETUP.md](SETUP.md) for Firebase project setup and bootstrapping the
first Super-Admin account (there's no in-app enrollment for it — a real
system has exactly one, created once outside the app).

```
./gradlew :app:assembleDebug
```

## Build status

Built phase by phase per [BUILD_PLAN.md](BUILD_PLAN.md); every phase has
passed a `clean` build + lint sweep. Phases 0-6 are complete — the full
feature set from the original spec is implemented. Known scoped-down spots
(documented inline where they live):

- **Backup & Restore** is a JSON export/import of the offline cache, not a
  database snapshot — Firestore itself is already durable, so this is a
  portable safety net rather than disaster recovery.
- **Share Location** updates on a foreground timer while the screen is open,
  not a background/foreground service, and hands off to the device's
  installed maps app rather than embedding a Maps SDK view (no Google Maps
  API key required for this pass).
- **Calendar** is a single chronological list rather than a month/week grid.
- Congregation Master File CRUD when signed in as **Super-Admin** always
  scopes Groups/Territories to a chosen congregation elsewhere in the app;
  creating a Group/Territory *as* Super-Admin (rather than viewing) currently
  needs a congregation picker that isn't wired up yet — Admin/Coordinator
  Elder, who are always scoped to one congregation, are unaffected.
- **Account Management's Reset Password** is visible but disabled — Firebase's
  client SDK can only change the *signed-in* account's own password;
  resetting someone else's needs a Cloud Function (Firebase Admin SDK), which
  needs the Blaze plan this project isn't on yet (see BUILD_PLAN.md's Phase 55).

## Territory map drawing

Long-press the Territory Map (if your role allows drawing) to enter Drawing Mode: lasso a worked area, pick its
status (Finished green / To Continue amber / To Do red), or drop a pin with a required date and notes.
Drawings are GeoJSON in `territoryDrawings`, saved offline-first and synced like everything else; every change
is audited in `territoryDrawingAudits`. Shared pieces live in `ui/components/map/` (`MapLibreHost`,
`MapDrawingOverlay`, `CurrentLocationLayer`, `MapOrientation`) and `domain/map/` (geometry + permissions).
Rules for these collections are in `firestore.rules` (tests in `firestore-tests/`). The server checks role, FS
Group, territory membership and the territory's bounding box (`territoryBounds`, published by
Coordinator Elder / Service Overseer / Secretary / Admin / Super Admin when they open the map); the exact
polygon-inside-territory test runs in the app before saving.
