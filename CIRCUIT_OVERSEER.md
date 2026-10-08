# Circuit Overseer module

Circuit Codes, Circuit Overseer accounts, and the Circuit → Overseer → Congregation relationships.

## Data model (no new database — same Firestore project)

| Collection / document | Purpose |
|---|---|
| `circuitCodes/{CODE}` | One Circuit Code. **The document id is the upper-cased code**, so a duplicate code is impossible. `overseerPersonId` = the one overseer holding it. |
| `congregationCircuits/{congregationId}` | "This congregation belongs to this overseer / circuit." **Keyed by congregation id**, so a congregation can have only one overseer. Kept out of the `congregations` document on purpose: a normal congregation edit rewrites that whole document and could erase an assignment made a moment earlier. |
| `userAccessGrants/{personId}` (existing) | The overseer's access. Gains `circuitCode`; `scopeCongregationIds` always equals the congregations they hold. Fixed view-only permissions (`CircuitRules.OVERSEER_PERMISSIONS`). |
| `people`, `roleAssignments` (existing) | The overseer is an ordinary Person with an `ADMIN:CIRCUIT_OVERSEER` role. Login, session and role selection are unchanged. |

`Circuit Code (1) → (1) Overseer`, `Overseer (1) → (many) Congregations`, `Congregation (1) → (1) Overseer`.

## How the rules are enforced

* **Transactions** — every write that touches these relationships is one server transaction
  (`FirestoreCircuitAssignmentService`): it re-reads the code and congregation documents, refuses a code or congregation
  already held by someone else, and only then writes. Two admins editing at once cannot both win.
* **Security rules** (`firestore.rules`) — `circuitCodes` and `congregationCircuits` are readable by any signed-in user and
  writable by the Super-Admin only (they are also in the catch-all's exclusion list; without that the catch-all would
  have left them open to everyone). Verified in `firestore-tests/circuit.rules.test.mjs`.
* Ported to `backend/src/policy/rules.js` for the Hostinger API (covered by `backend/test/rules.test.js`).
* **Caveat** — the Super-Admin override at the top of `firestore.rules` is OR-ed with every other rule, so a Super-Admin's
  *direct* Firestore write is not constrained by these checks; the app always goes through the transactions.

## Screens (Administration section of the side panel)

* **Circuit Codes** — create / edit / activate / deactivate / delete (refused while an overseer or congregation uses it).
* **Circuit Overseer Accounts** — list with search, Circuit Code and Status filters, "Record Found", View / Edit / Delete.
  Delete is refused while congregations are still assigned. Passwords are never shown.
* **Circuit Assignment** — the migration screen: lists every congregation that still has no overseer and assigns them in bulk.
  Until the list is empty, editing an older congregation does not require an overseer; afterwards the field is required.
* **Congregations** — new congregations require *Circuit Overseer Assigned*; the Circuit Code follows the overseer.
  The overseer can be changed or cleared at any time; the congregation moves between the two overseers in one server step.
* **Circuit Overseer login** — Circuit Dashboard (circuit, name, congregation count, Quick Access tiles) and **Field Service Report**
  (the submitted copies of the overseer's own congregations, see below).

## Deploying

1. `firebase deploy --only firestore:rules` — **required**. Without the new rules the new collections fall under the
   catch-all and any signed-in account could write them.
2. Install the app, sign in as the Super-Admin, add Circuit Codes, add the Circuit Overseer accounts (code + congregations),
   then use **Circuit Assignment** for the congregations that already existed.

## Limits worth knowing

* Assignments need a connection (they are server transactions, not queued writes).
* Firestore only answers list queries whose rules it can prove for every result, so a Circuit Overseer's device syncs
  `congregations`, `groups`, `roleAssignments` and `monthlyReports` *filtered to their congregations*
  (`RestrictedSessionSync`) instead of listening to the whole collections. Beyond 30 congregations the filter is chunked.
* The sign-in service cannot set another account's password from the app, so **Change Password** on the edit screen explains
  that the overseer changes it themselves (Account Settings). The password chosen at creation goes only to Firebase Auth
  (salted hash) — never to Firestore.
* Tests: `npm run test:circuit` in `firestore-tests` (needs the Firebase emulator), `CircuitRulesTest` in `shared`,
  backend `NODE_ENV=test npm test`.

## Accounts and first login

The Super-Admin never types a password. Saving a Circuit Overseer generates a temporary username and password (shown once,
with a share link); the account shows **TEMPORARY ACCOUNT** until the overseer signs in, is forced to choose their own
username and password, and signs in again — then it is an ordinary Active account and the temporary pair no longer works.

## Territory Map and Circuit Overseer Management

* **Territory Map (Circuit Overseer)** — the existing Territory Map, opened view-only (`readOnly`): no drawing, no pins, and none
  of the publishers' personal Searching / Return Visit / Bible Study markers. The congregation picker lists only the overseer's
  congregations (one is chosen automatically). "View Territory Map" on a Congregation Overview opens it on that congregation.
* **Server-side** — `territoryAssignments`, `territoryAssignmentBarangays`, `territoryBounds`, `territoryDrawings` and `mapPins`
  are readable by a grant-based account only for the congregations on its grant; every other account is unchanged. A Circuit
  Overseer can't write any of them. Their device syncs these collections filtered by congregation (`RestrictedSessionSync`).
  Rules tests: `firestore-tests/circuit.rules.test.mjs`; backend port: `rules.js` / `index.js`.
* **Circuit Overseer Management (Super-Admin)** — overview numbers, a circuit picker ("All Circuits" or one overseer — a circuit
  has exactly one overseer), CO Quick Access with a congregation filter, and buttons into the same Circuit screens (people,
  territory, reports, account), pointed at the chosen circuit (`CircuitScopeStore`). The Super-Admin's Approve /
  Return is recorded under their own name in the audit trail.

## Field Service Report workflow

The congregation's own Field Service Report (the publishers' monthly reports) is the **single source of truth** — nothing is
copied for the Circuit Overseer. What the workflow adds is one small status document per congregation and service month,
`coFieldServiceMonthStatus/{congregationId}_{periodMonth}` (no report figures), plus an append-only audit trail in
`coFieldServiceReportEvents`.

```
NOT SUBMITTED → SUBMITTED → RECEIVED          SUBMITTED → NOT SUBMITTED (undo, before receipt)
RECEIVED / SUBMITTED → RETURNED → SUBMITTED   (only the Circuit Overseer returns a received month)
```

* **Submitting** — Admin, Coordinator Elder, Service Overseer, Secretary of that congregation, or the Super-Admin. A service month
  that has not started cannot be submitted (rules `request.time`, and the client for every role). One status document per month
  makes a duplicate submission impossible.
* **Lock** — while a month is SUBMITTED or RECEIVED nobody but the Super-Admin adds, edits or deletes its records: monthly
  reports (publisher submission and the Manual Field Service Record), preaching time (hours/minutes), credit hours, planner days
  and visits (searching, return visits, Bible studies). RETURNED and NOT SUBMITTED open it again. Only that month locks.
  Enforced in `firestore.rules` (`fsMonthLocked` / `fsDayLocked`) and the backend policy, plus a client guard
  (`MonthLockGuard`) so an offline device does not queue a change that could never sync. Screens show
  "Status: Submitted to Circuit Overseer" and the lock message.
* **Circuit Overseer** — sees only submitted months (SUBMITTED / RECEIVED / RETURNED). They open the congregation's actual Field
  Service Report for such a month (its records are mirrored on demand), see a review panel and can *Mark Received*, edit the
  **CO Remarks** of that month, or *Return* it (remarks required). Remarks belong to the month and are kept in the history.
* **Undo** — the congregation can undo a SUBMITTED month; the active CO remarks are cleared but kept in the audit history.
  After RECEIVED only the Circuit Overseer can return it.
* **Audit** — every move records congregation, month, from/to status, user, role, time and remarks (and the previous remarks).

Limitations: the Super-Admin override in `firestore.rules` is not bound by the future-month or lock rules (the app enforces the
future-month rule for them too). Service months use UTC+8 (the Philippines) when a date is mapped to its month in the rules.

## Meeting Attendance and the Congregation Comparative Report

* **Meeting Attendance** (Reports → Meeting Attendance; Admin, Coordinator Elder, Service Overseer, Secretary and the Super-Admin manage, the
  Circuit Overseer reads their circuit's congregations). One record per congregation + meeting type + date
  (`meetingAttendance/{congregationId}_{MIDWEEK|WEEKEND}_{date}`), holding the original counts (Midweek: Treasures / Apply Yourself /
  Living as Christians; Weekend: Public Meeting / Watchtower Study), the **calculated average**, the **official attendance** and the
  rounding mode used. The mean is always taken from the original counts first; rounding (nearest whole number, halves up) comes after,
  per the congregation's setting (`meetingAttendanceSettings`, default *Round to Nearest Whole Number*, or *Keep Exact Average*). Changing
  the setting applies to new records only unless the user also chooses to recalculate history (confirmed, audited, never touches frozen months).
* **Server rules** (`firestore.rules`, mirrored in `backend/src/policy/rules.js`) check the roles, the id, the whole-number counts for the
  right meeting type only, that the average and official figure really are the correct calculation, that the service month matches the
  date, and that the month is not frozen. Records are never hard-deleted (delete = `deleted: true`), every change appends an audit line
  (`meetingAttendanceEvents`: before / after / who / when / reason).
* **Missing meetings** are never zeros: a month shows "N recorded / M expected" (expected = the month's Monday–Sunday weeks, assigned to the
  month holding the week's Thursday) and the average only uses recorded meetings.
* **Historical monthly statistics** (`congregationMonthlyStatistics/{congregationId}_{serviceMonth}`): written in the same transaction as the
  month's report submission (publishers, pioneers, unbaptized, elders, ministerial servants, report count, attendance averages and
  recorded/missing counts, source report id, status). Refreshed when a returned report is sent again; **frozen when the Circuit Overseer
  receives the report** (rules refuse any change, and the month's attendance too). The comparative report reads only these snapshots —
  never today's publisher categories.
* **Congregation Comparative Report** (Congregation Comparative / Comparative Report): Period A vs Period B (reports are totals;
  headcounts are the ending count with start and monthly average shown; attendance is the average of the weekly figures, never a sum),
  difference and % change, plus a monthly view with a sticky first column. Months without a snapshot show — and count as missing.
  Print / PDF (through the zoomable print preview) and Excel, landscape, with the GoPreach App header.

## Comparative Reports (formal submission to the Circuit Overseer)

A congregation's **Comparative Report** compares two month ranges (Period A / Period B) using only the congregation's saved monthly
historical statistics (never today's publisher data). Months without a snapshot show "—" and are listed as missing, never counted as zero.

**Who:** Admin, Coordinator Elder, Service Overseer, Secretary (active role) and the Super-Admin prepare and submit; the Circuit Overseer
(read/review only) sees the reports of congregations in their circuit, **never drafts**. Group roles and publishers have no access.

**Status flow:** `DRAFT → SUBMITTED → RECEIVED`, or `SUBMITTED → RETURNED → SUBMITTED` (version +1 on each resubmission).

| Status | Congregation can | Circuit Overseer can |
|---|---|---|
| Draft | Open, Edit period, Regenerate, Delete, Submit | (cannot see it) |
| Submitted | View, Print/PDF/Excel only | Add remarks, Receive, Return (reason required) |
| Returned | Open, Regenerate, Resubmit | View, add remarks |
| Received | View, Print/PDF/Excel | View, Print/PDF/Excel |

**Data:** `congregationComparativeReports/{congregationId_aStart_aEnd_bStart_bEnd}` — the id is derived from the four period months, so
an identical report cannot exist twice ("A Comparative Report using these same periods already exists for this congregation." offers to
open the existing one). `reportNumber` is `CR-{year}-{seq}` per congregation. The report holds a **saved snapshot** (JSON of the prepared
content); Print / PDF / Excel print that snapshot, never the live data. `comparativeReportHistory` is the append-only audit trail (created,
edited, regenerated, submitted, resubmitted, returned, received, remarks, exported, deleted draft) and `comparativeReportRemarks` keeps
every CO remark (never overwritten).

**Locking:** firestore.rules (and the backend port in `backend/src/policy/rules.js`) allow the congregation to change only a DRAFT or
RETURNED report, never its periods or creator; a SUBMITTED report is read-only for it; a RECEIVED report cannot be edited, deleted,
resubmitted, returned or remarked by anyone except the Super-Admin override. The app shows "This Comparative Report has already been
received by the Circuit Overseer and can no longer be modified." Submit / Receive / Return / Remark are online-only server transactions
that re-read the report first; drafts are offline-first, but a queued edit of a report that has since been received fails to sync.

**Limits:** Edit Period applies to drafts (a returned report is corrected with Regenerate and resubmitted, since its periods identify
it). The Super-Admin bypasses rule-level locks (the app still enforces them). Status messages are shown in the report screens (no bell notifications).

## Universal Report standard

Every report ends with a **Summary** computed from exactly the records shown (after congregation, search and filters), in the screen,
Print/PDF and Excel/CSV. Building blocks:

- `ui/components/UniversalReport.kt` — the reusable report: header, Records Found, search, filter chips, sort, List/Table view, records,
  end Summary, Print/PDF/Excel. A screen supplies items, columns, a card, search text, filters, sorts and a summary function
  (first user: My Submitted Reports).
- `ui/components/EndSummary.kt` — the Summary card for screens that keep their own layout.
- `data/export/TableReportExporter.kt` — header + table + Summary print/Excel from one object.
- Shared print/CSV (`ReportTable`) always closes with a Summary (record count + the report's totals).
- Report Submission (`ReportSubmissionScreen`) keeps the user's month range per user + role + congregation; Field Service Report, Meeting
  Attendance and the Comparative Report editor start from it.

## Circuit Overseer dashboard (command center)

The CO's Main Form is `CircuitHomeDashboard`: Circuit Overview header (reporting month, online / last synchronized), summary cards,
Report Status bars (+ Report Submission shortcut), Action Required, My Congregations (search + sort, status badge per card), Quick Actions,
Circuit Trend (3 / 6 / 12 months) and Recent Activity, with skeleton loading and a bottom navigation (Home | Congregations | Reports | More).
Opening a congregation shows `CongregationOverviewDashboard` (statistic cards, Field Service Report card, Meeting Attendance card, quick actions).

The numbers come from `domain/CircuitOverview.kt`: the **current month** uses today's records; a **past month** uses only the saved monthly
snapshots; a month/congregation with neither shows "—" / "Data unavailable" (never 0). No new server rules: access is still limited by
`isCircuitOverseerFor` and the CO's scoped sync. Tablet sidebar, a dedicated error screen ("Try again / View cached data") and notification
deep-links are not part of this version (the existing notification bell and drawer remain).

## Circuit Overseer design system (universal UI)

One set of components (`ui/components/co/CoDesignSystem.kt`) now carries every CO screen:

* **Congregation first, never a dropdown.** `SelectCongregationPrompt` shows the congregations assigned to the account as selectable cards (name, Publishers · Elders · Groups, chevron; a search box above five). It is used by Publishers, Elders & Servants, Field Service Report, Meeting Attendance, Report Submission, Comparative Reports and Territory. The list is built only from congregations the device already holds, which the security rules limit to the account's current assignment; the cards are not a security mechanism.
* **Header.** After choosing, `SelectedCongregationBar` shows MODULE TITLE, the congregation prominently, and `Change Congregation`.
* **Buttons.** `CoButton(kind = Primary | Secondary | Success | Warning | Danger | Neutral)`: one shape, one height, an icon from the Material "Rounded" family. Mark Received = Success, Return = Warning, Print / PDF / Excel / Change Congregation / Sort = Secondary.
* **Colors.** `coPalette()`: the app's primary accent plus fixed success / warning / danger, with lighter variants on dark surfaces.
* **Full screen.** `CoFullScreenButton` + `CoFullScreenEffect` in the top bar of Publishers, Elders, Field Service Report, Meeting Attendance, Report Submission, both Comparative screens and the Circuit Report; the Field Service Report also has a "table only" mode.
