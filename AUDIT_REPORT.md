# GoPreach audit report

Stack note: the project is Kotlin/Compose Multiplatform plus a Node backend, so Gradle and `node --test` replace the Flutter commands.

## Fixed and verified (by automated tests)
| # | Defect | Severity | Fix |
|---|--------|----------|-----|
| 1 | Undo failed with "snapshot must follow the report's status" (statistics op ahead of status op) | High | Status op always first in a push |
| 2 | Release build crashed after login (Koin could not build `WorkerParameters`) | Critical | `AppWorkerFactory`; verified on phone, released as v1.139.1 |
| 3 | Read Report narrator was disposed when the panel scrolled off screen | Medium | Narrator hoisted to screen level, paused on stop |
| 4 | Comparative tables, warnings and exports still showed "Snapshot", "accepted", "No snapshot sent" to the CO | Low | Wording follows sent/received (`noun`); regression test added |
| 5 | Attendance monthly table hard-coded "received" in admin mode | Low | Uses `noun` |
| 6 | Comparative exporter was untestable | Low | `buildHtml` extracted; 4 tests |
| 7 | Dead code (`PeriodSummaryCard`, `TrendChart`) | Trivial | Removed |
| 8 | No backend rules or tests for the attendance workflow, or for cross-congregation access | Medium | Explicit rules; tests added |

## Test results (actually run)
- `:shared:testDebugUnitTest` and `:app:testDebugUnitTest`: pass (shared 180 tests, app 50 including 4 new)
- Backend `node --test`: 91 pass, 0 fail
- `:app:installDebug`: installed on the connected phone

## Not verified / unresolved risks
- **Android lint**: cannot run (pre-existing androidTest dependency conflict).
- **iOS**: cannot compile on Windows. The CI `ios-shared` job on macOS must confirm the new shared code.
- **Backend default write rule**: unlisted collections fall through to a catch-all allow. This is a design risk and I did not change it.
- **Timezone**: the server uses UTC+8 for `monthStartOf` and clients use device time, so a month boundary can differ.
- **Attendance comparison** with real data, the Open deep links, and admin send flows were not exercised on a real session.
- **Deployment**: the backend must be deployed for the attendance rules to apply.

## DB migrations
None. New collections only (`coAttendance*`).

## Not done
Nothing is committed, pushed or released. Version 1.139.2 (versionCode 202) is ready when you say so.
