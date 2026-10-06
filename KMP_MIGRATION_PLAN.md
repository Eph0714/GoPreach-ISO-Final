# GoPreach → Kotlin Multiplatform (Android + iOS)

Working copy of the Android app, converted step by step to Kotlin Multiplatform + Compose Multiplatform.
The original project (`Desktop\GoPreach`) is untouched. The web app is a separate project.

## 1. What the code looks like today (measured, 390 Kotlin files)

| Area | Files | Touch Android / Firebase / DI libs | What it needs |
|---|---|---|---|
| `data/model` | 29 | 27 | Mostly the Firebase `@DocumentId` annotation → plain `@Serializable` data classes. Easy. |
| `domain` (permissions, geometry, reports) | 17 | 3 | Already almost pure Kotlin. Moves to common first. Includes the tested drawing rules. |
| `data/repository` | 58 | 56 | Hilt `@Inject` + Firestore calls. Becomes interfaces in common + a remote API client. |
| `data/sync`, `data/local` | 19 | 19 | Room cache + outbox + WorkManager. Room KMP for the cache; background sync needs an Android/iOS pair. |
| `data/location`, `export`, `print` | 12 | 11 | Platform code: GPS, CSV/PDF/print. `expect/actual`. |
| `ui/components`, `ui/screens` | 227 | 217 | Compose → Compose Multiplatform (same `androidx.compose.*` code), plus Hilt ViewModels → Koin. |
| `ui/navigation` | 3 | 2 | Navigation Compose (multiplatform). |

Library usage across files: Hilt/javax.inject 171 · androidx.lifecycle 159 · Compose 155 · `android.*` APIs 116 ·
Firebase 70 · Gson 16 · MapLibre 10 · Room 9 · WorkManager 6 · Navigation 1.

## 2. Library mapping

| Today (Android only) | Target (shared) |
|---|---|
| Hilt / `@Inject` | **Koin** (multiplatform) |
| Gson | **kotlinx.serialization** |
| Firebase Auth / Firestore / Storage | **Ktor client** → the Hostinger API (`backend/`), Firebase Auth token kept at first |
| Room 2.6 | **Room KMP** (2.7+, bundled SQLite) |
| WorkManager | `expect/actual`: WorkManager (Android), BGTaskScheduler (iOS) |
| play-services-location | `expect/actual`: Fused Location (Android), CoreLocation (iOS) |
| MapLibre Android SDK | **maplibre-compose** (MapLibre Native on Android + iOS) |
| Coil 2 | Coil 3 (multiplatform) |
| Compose BOM 2024.09 / Kotlin 2.0.20 | Compose Multiplatform 1.8+ / Kotlin 2.1+ |
| `android.widget.Toast`, `Intent`, `Uri`, `Bitmap` | small `expect/actual` services |
| CSV / PDF / print exports | `expect/actual` per platform |

## 3. Target structure

```
GoPreach to ISO/
  shared/            Kotlin Multiplatform module (commonMain, androidMain, iosMain)
     commonMain      models, domain rules, repositories, sync engine, ViewModels, ALL Compose UI
     androidMain     GPS, sensors, WorkManager, export/print, Android map glue
     iosMain         CoreLocation, CoreMotion, BGTaskScheduler, iOS map glue
  androidApp/        thin Android app (MainActivity, manifest, Firebase config)
  iosApp/            thin Xcode project that hosts the shared Compose UI
  backend/           Node.js + MySQL API (Hostinger)
```

## 4. Phases (each one leaves the Android app building and working)

0. **Backend first.** Finish the Hostinger API (sync push/pull, remaining permission rules). Without it the
   shared code can't drop Firebase. *(In progress — paused at the Hostinger 503.)*
1. **Project structure.** Add `:shared` (KMP) and `:androidApp`; move the manifest/activity; app still builds as before.
2. **Move the pure logic to common.** `data/model`, `domain`, geometry, permissions; swap Gson → kotlinx.serialization
   and drop `@DocumentId`. Unit tests move with them and run on the JVM and iOS.
3. **Data layer.** Repository interfaces in common; sync engine + Room KMP cache; `RemoteApi` (Ktor) replaces Firestore;
   Firebase kept only as an Android-side adapter until the backend is live.
4. **DI + ViewModels.** Hilt → Koin; ViewModels (`androidx.lifecycle`, KMP) move to common.
5. **UI.** Compose screens move to commonMain one module at a time (theme and components first, then screens);
   replace `android.*` calls with small platform services.
6. **Map.** Territory Map on maplibre-compose: layers, clustering, Polygon Lasso, current-location arrow, compass,
   hide/show icons. The most delicate screen; the geometry and permission code is already shared.
7. **iOS app.** Xcode host project, iOS platform services, permissions, icons, TestFlight.
8. **Release.** App Store review, then keep both platforms on one codebase.

## 5. Rough size of the work (honest estimate)

Phases 1–2 are small. Phases 3–6 are most of the effort: ~390 files to touch, mostly mechanically (imports, DI,
JSON). The map and offline-sync parts need real-device testing on an iPhone. Expect several weeks of focused
work to a first iOS TestFlight build, longer to match every screen.

## 6. Requirements outside the code

* A **Mac** for building the iOS app (a rented cloud Mac, or a macOS CI runner such as GitHub Actions / Codemagic).
* An **Apple Developer Program** account (about US$99 per year) for TestFlight and the App Store.
* The Hostinger API live (phase 0), since the iOS app has no Firebase.

## 7. Decisions to confirm before phase 1

1. DI: **Koin** (recommended) instead of Hilt.
2. JSON: **kotlinx.serialization** instead of Gson.
3. Maps: **maplibre-compose** (keeps MapLibre as the only map engine, as required).
4. Keep Firebase Auth for logins at first (the API verifies its tokens).

## Progress log

* **Phase 1 started (done):** `:shared` Kotlin Multiplatform module added (Android + iosX64/iosArm64/iosSimulatorArm64; iOS
  targets compile on a Mac/CI only). `DrawingGeometry` (+14 tests) moved to `shared/commonMain`/`commonTest`, with Gson replaced
  by kotlinx.serialization. The app depends on `:shared` and still builds; all existing app tests pass.
* **Phase 2a (done):** all 29 data models and the pure domain rules (`DrawingPermissions`, `GroupAccessScope`, `PermissionChecker`,
  `NameOrder`, `PersonDuplicateDetection`, `CredentialGenerator`, Bible reference data) now live in `shared/commonMain`.
  `@DocumentId` / `@PropertyName` are `expect`/`actual` (Firebase on Android, no-op on iOS); `platform/Time.kt` holds date helpers.
  App builds; 28 shared tests + app tests pass.
* **Phase 2b (done):** Room cache + outbox (`CachedDocumentEntity`, `PendingSyncOperationEntity`, `CacheDao`, `SyncQueueDao`, `AppDatabase`)
  moved to `shared/commonMain` on Room KMP 2.7 (same tables, same schema v2, migration kept, so installed apps upgrade in place).
* **Phase 2c (done):** every model is `@Serializable`; `DocJson` (kotlinx.serialization, Gson-compatible settings) + round-trip tests.
* **Phase 3 (done):** Hilt replaced by Koin (`di/AppModule.kt`: 86 repositories/services, 86 ViewModels, workers via `KoinWorkerFactory`).
  `KoinGraphTest` verifies every constructor dependency is provided. Verified on the phone: launches, stays signed in, existing cached data intact, sync works.
* **Phase 4a (done):** `SyncApi` + Ktor `HttpSyncApi` (Hostinger push/pull contract) and the platform-independent `SyncEngine`
  (upload outbox, download by cursor; keeps unsent local edits, keeps rejected edits as permanent failures, keeps the queue on network errors)
  in `shared/commonMain` with 9 tests (fake DAOs + Ktor MockEngine). OkHttp engine on Android, Darwin on iOS. Android still uses Firestore.
* **Phase 5a (done):** Android switch. `gopreach.backendUrl=...` in local.properties makes `SyncWorker` run the shared `SyncEngine` (Hostinger) instead of Firestore and
  turns off the Firestore listeners; empty (default) = Firestore as before. Per-collection id field handled (`idFieldByCollection`). Not yet tested against a live backend.
* **Phase 6a (done):** `OfflineFirestoreRepository` (the cache read/write path every repository uses) moved to `shared/commonMain` on `DocJson` instead of Gson;
  platform bits split out (`WriteQueuedListener`, Android-only `saveNow`). Malformed cached rows are skipped, not fatal.
* **Fix (found on device):** models must use `@field:DocumentId` / `@field:PropertyName` — plain `@DocumentId` on a common-code constructor property attaches to the *parameter*, so Firestore left ids blank (login built `@gopreach.internal`). Startup also purges blank-id cache rows and queued writes.
* **Phase 7a (done):** `RemoteCollections` interface (new ids, live mirror, one-shot pull; Firestore impl on Android). 21 pure repositories moved to `shared/commonMain`
  (Schedule, Congregation, InterestedPerson, Announcement-free set, PlannerGoals, Meeting, ... see `shared/.../data/repository`), plus `NaturalOrder`. Regression test for the document-id annotation added.
  Still in the app (Android-only deps): Person/RoleAssignment/MonthlyReport/SharedLocation (`saveNow`), Announcement/AppSettings/GroupChat (Storage + Uri), TerritoryDrawing (sync status), PlannerDay (needs MonthlyReport), Auth, Visit, MapPin, CreditHour, Backup, RecycleBin, TerritoryAssignment.
* **Phase 8 (done):** `saveNow` (`RemoteCollections.pushNow`), `NetworkStatus`, `RemoteFiles` (Storage; Uri -> String) abstractions; `TimeBounds` (Day/Week/Month/Year) rewritten on kotlinx-datetime with tests.
  Moved to shared: Person, RoleAssignment, MonthlyReport, SharedLocation, PlannerDay, TerritoryDrawing, Announcement, AppSettings (27 repositories total in `shared`).
  Still in the app: Auth, Visit, MapPin, CreditHour, GroupChat, Backup, RecycleBin, TerritoryAssignment (direct Firestore calls / transactions / Gson / logging).
* **Phase 9 (done):** Visit (collection-group mirror), MapPin, CreditHour (x2), Backup and RecycleBin moved to `shared` (Gson -> kotlinx). `RemoteCollections` gained `deleteNow`, `hasAny`, `countWhere`, `mirrorGroup`. 32 repositories now shared.
  Deliberately left in the app, each needs a server-side design rather than a mechanical move: **TerritoryAssignment** (atomic barangay-claim transactions -> needs a backend "claim" endpoint), **GroupChat** (realtime chat, batched writes, atomic counters), **Auth** (Firebase Auth), plus Android-only stores/preferences.
* **Next:** get the Hostinger API live, test the switch on the phone; then ViewModels and UI to common.
