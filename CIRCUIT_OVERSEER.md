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

## Field Service Report workflow (Circuit Report snapshots)

Each service month is sent to the Circuit Overseer as its own **immutable snapshot** (`coReceivedReports/{congregationId}_{month}_{version}`,
written once and never edited, see `writeReceivedReport`). The congregation's own records stay the live data; the overseer reads only
the snapshots. A small status document per congregation and month, `coFieldServiceMonthStatus/{congregationId}_{periodMonth}`, tracks
where that month stands (with its current version), and an append-only audit trail is kept in `coFieldServiceReportEvents`.

```
DRAFT (NOT_SUBMITTED) -> SUBMITTED (Sent to CO, awaiting receipt) -> RECEIVED
SUBMITTED -> SUBMITTED   an updated snapshot while awaiting receipt (version + 1)
SUBMITTED -> DRAFT       undo, before receipt
SUBMITTED / RECEIVED -> RETURNED (reason required) -> SUBMITTED (version + 1)
SUBMITTED / RECEIVED -> CANCELLED (reason required) -> SUBMITTED (version + 1)
```

* **Sending** - Admin, Coordinator Elder, Service Overseer, Secretary of that congregation, or the Super-Admin. One send may include any
  number of months (consecutive or not, from any year up to the current month, by the **server's** date: `POST /v1/time`; the rules check
  again with the server clock). Every month is validated on its own first (eligible, already received, awaiting receipt and changed or
  not, returned, cancelled, has data) and sent as its own snapshot with its own version; a repeated tap or retry cannot duplicate a
  version (each send names the version it validated against, and the rules require exactly the next version).
* **Lock** - while a snapshot is only **awaiting receipt** the live month stays editable ("Changes Since Submission" shows when it
  differs from the sent snapshot, and an updated snapshot can be sent). **Only RECEIVED locks** the month's records for everyone but the
  Super-Admin: monthly reports, preaching time, credit hours, planner days and visits. RETURNED and DRAFT open it again. Cancelling a
  received submission does **not** silently unlock the month (`cancelledFromStatus`); only the overseer returning it for editing does.
  Enforced in the backend policy (`fsMonthLocked` / `fsDayLocked`) plus a client guard (`MonthLockGuard`).
* **Circuit Overseer** - Circuit Reports (side panel) lists every month the overseer's congregations sent, with filters (All, Awaiting
  Receipt, Received, Returned, Cancelled), search, the read-only snapshot viewer (report, details with version history, comparison with
  the previous send of the same month) and the actions *Mark as Received*, *Return for Editing* and *Cancel Submission*, each behind a
  confirmation that names the congregation, month and snapshot version; return and cancel require a reason. An action applies only to the
  version the overseer reviewed: a newer version makes it refuse (the status document must still be at that version).
* **Notifications** - the overseer's unread list gets every new version; the congregation's notification bell gets "received", "returned
  for editing" and "cancelled" (with the reason).
* **Audit** - every move records congregation, month, from/to status, version, user, role, time and the reason or remarks.

Limitations: permanent deletion of a submission is not offered (cancelling keeps the snapshot and its history). Service months use
UTC+8 (the Philippines) when a date is mapped to its month in the rules.

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

## Received Reports (navigation)

The Circuit Overseer has no standalone Field Service Report module. **Received Reports** (`Destinations.RECEIVED_REPORTS`) is a landing page with two categories:

- **Field Service Report** -> the submitted-snapshot list (`CIRCUIT_REPORTS_INBOX`, with search, status filters, sort and the Mark as Received / Return for Editing / Cancel actions); "Open Snapshot" opens the frozen copy (`RECEIVED_SNAPSHOTS`).
- **Circuit Attendance Report** -> the consolidated circuit table (`CIRCUIT_REPORT`, formerly labelled "Circuit Report"; only the user-facing labels changed).

Route ids, stored data and server rules are unchanged. `CIRCUIT_FS_REPORTS` still exists (legacy months sent before snapshots existed open the live report) but has no menu entry.

## Circuit Attendance Report (Meeting Attendance snapshots)

The Meeting Attendance of a service month goes to the Circuit Overseer by exactly the same process as the Field Service Report:

* **Sending** (admin app -> Meeting Attendance -> menu -> *Send to Circuit Overseer*; Admin, Coordinator Elder, Service Overseer, Secretary, Super-Admin):
  pick one month or several, see where each stands, send. Every month is validated on its own (not a future month by the server's date, not already
  received, has recorded meetings, changed or not) and saved as its own immutable snapshot with its own version
  (`coAttendanceReports/{congregationId}_{month}_{version}`: every weekly meeting with its original counts, average, official figure and rounding).
  The status of each month is `coAttendanceMonthStatus/{congregationId}_{month}` (a `CoMonthStatus`), its audit trail `coAttendanceReportEvents`.
* **Status flow** (same as the Field Service Report): `DRAFT -> SUBMITTED -> RECEIVED`, `SUBMITTED -> SUBMITTED` (updated snapshot, version + 1),
  `SUBMITTED -> DRAFT` (undo, before receipt), `SUBMITTED / RECEIVED -> RETURNED | CANCELLED` (reason required) -> `SUBMITTED` (version + 1).
  A month taken back (undo) disappears from the Circuit Overseer's list; its old copy stays as history.
* **Lock**: while a snapshot only awaits receipt the live attendance stays editable ("Changed" shows when it differs from what was sent).
  **Only RECEIVED locks** the month's attendance (add / edit / delete / rounding recalculation) for everyone but the Super-Admin, in the app
  (`MeetingAttendanceRepository`) and on the server (`monthFrozen` in `backend/src/policy/rules.js`). Cancelling a received month keeps the lock;
  returning it unlocks it. This lock is independent of the Field Service Report: receiving that no longer freezes attendance.
* **Circuit Overseer**: Received Reports -> *Circuit Attendance Report* -> congregation -> month: the frozen sheet (a Midweek and a Weekend block,
  the original counts, average, official figure and the monthly average), version chips, Print / PDF / Excel, and *Mark as Received*,
  *Return to Congregation* and *Cancel Submission* (each behind a confirmation naming the congregation, month and version; return and cancel need a
  reason; an action applies only to the version the overseer reviewed). *Circuit summary* opens the previous consolidated circuit table.
* **Report Submission** shows each attendance month's real status (Submitted, Correction Required, Received) for the admin and the Circuit Overseer.
* Server: new collections are covered in `backend/src/policy/rules.js` and `backend/test/rules.test.js`; `firestore.rules` (the old Firebase rules)
  was not extended because the app talks to the backend.

## Comparative Report for a congregation's own admins

The Circuit Overseer's Comparative Report screen (`AcceptedComparativeScreen`) is also the admin app's **Comparative Report** (side panel > Reports, and the home quick-access tile), with `adminMode = true`:

* **Who**: Admin, Coordinator Elder, Service Overseer, Secretary (their own congregation only, locked) and the Super-Admin (chooses a congregation).
* **Data**: the frozen snapshots the congregation SENT to its Circuit Overseer (Submitted or Received, the version that counts), Field Service and Circuit Attendance - so the admin compares exactly what the overseer was given. Wording says "sent" instead of "received"; nothing else differs (report type cards, two real-named periods, Graph / List / Details, automatic summary, Read Report, Print / PDF / Excel).
* **Server**: no new rules - senders already read `coReceivedReports`, `coAttendanceReports` and the status documents of their own congregation.
* The older admin tool that compares Searching / Return Visit / Bible Study counts is now called **Searching & Study Comparison** in the side panel (same screen, same route).

## Deleting a sent snapshot (admin app)

For BOTH the Field Service Report and Meeting Attendance, a sender (Admin, Coordinator Elder, Service Overseer, Secretary, Super-Admin) can **Delete submission**
in the Send panel while the Circuit Overseer has not received the month: *Submitted (awaiting receipt)*, *Returned*, or *Cancelled while only sent*.

* The month goes back to **Draft**, every frozen snapshot of that month (all versions) is deleted, and an audit line ("... submission deleted (N snapshots, version V)")
  is appended. The live records are untouched. The Circuit Overseer stops seeing the month; a later send continues the version numbers.
* **Never deletable**: a *Received* month, or one cancelled from Received. The server enforces this: a snapshot may be deleted only when its month's status is already
  Draft, and a Received month (or one cancelled from Received) cannot reach Draft (`backend/src/policy/rules.js`, tests in `backend/test/rules.test.js`).
  The backend must be redeployed for the delete to work; an older server still refuses it ("never edited or deleted").
