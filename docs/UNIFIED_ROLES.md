# Unified multi-role account (Android)

One login, one interface. There is no "Select Role" / "Login As" screen any more (`SelectRoleScreen`, `Destinations.SELECT_ROLE`, `UserSession.selectRole/switchRole` and the profile-menu "Switch Role" item are removed).

## How roles are consolidated

`SessionState` (shared/.../domain/UserSession.kt) reads every ACTIVE `RoleAssignment` of the signed-in person:

| Property | Meaning |
|---|---|
| `adminAssignments` | all active Admin-track roles, most senior first (`AdminRole` enum order) |
| `primaryAdminAssignment` | the most senior one - its congregation / group scopes the admin screens and is what `Person.activeAdminRole` / `activeCongregationId` mirror for firestore.rules |
| `consolidatedAdminAssignments` / `consolidatedAdminRoles` | the primary plus every other Admin-track role **in the same congregation**. Super-Admin is not congregation-bound. A role held in another congregation is never merged in |
| `publisherAssignment` | the person's own Publisher category (drives Share My Location, My Reports, ...) |
| `activeRoleAssignment` | `primaryAdminAssignment ?: publisherAssignment` - resolves by itself, never asks |
| `activeRoleLabel` | "Coordinator Elder + Publisher" |

Nothing is hard-coded to a role combination: roles are detected from the rows, and permissions are merged by `AdminCapabilities.of(session)` (app/.../ui/components/AdminCapabilities.kt) = the OR of `forRole(...)` for each consolidated role. `forRole` is now the one place of the permission matrix that the Admin Main Form used to derive inline.

## Interface

* Anyone with a Publisher category lands on the **Publisher Main Form** (unchanged dashboard). The modules of their other roles appear in the **side panel**, in the same collapsible groups (CONTROL PANEL / INPUT / RECORD / REPORTS / MANAGEMENT / SETTINGS / TOOLS), built by `adminSideItems(caps, includeTabModules = true)`. The Admin Main Form's tab-strip modules (Publishers, Field Service Group Report, Manual Field Service Entry, Meeting Attendance, Territory Map) are listed there too because the Publisher Main Form has no tab strip.
* Admin-track-only accounts (Super-Admin, Circuit Overseer, an elder without a Publisher category) keep the Admin Main Form, now fed by the same union.
* Notifications (bell and sound) merge the Publisher and Admin lists, de-duplicated by id.
* `GoPreachNavGraph`: route gates that read `currentRole == X` / `currentRole in setOf(...)` now ask `X in heldRoles` / `heldRoles anyIn setOf(...)`, so a screen opens when ANY consolidated role allows it. Scope (congregation, group) still comes from the primary assignment.

## Duplicate-module audit

| Function | Where it existed | Result |
|---|---|---|
| Group Chat, Calendar, Weather, Share/Find Location, Voice Notes, Offline Maps | Publisher tile **and** admin side panel, same route | one entry (de-duplicated by route) |
| Meeting and Cart Assignment | read-only `PUBLISHER_MEETING_ASSIGNMENTS` and editor `MEETING_ASSIGNMENTS` | one entry; opens the editor when any role may edit, else the read-only view |
| Announcements | publisher inbox (`PUBLISHER_ANNOUNCEMENTS`, seen-badge) and `MANAGE_ANNOUNCEMENTS` | **kept both** (different workflow: reading with unseen count vs. authoring); the admin one is labelled "Manage Announcements" |
| House Holder Visit History | Ministry tab (own / congregation records) and a Super-Admin-only screen | kept (different data scope: all congregations) |
| Publishers / Groups / Field Service Group / Publisher Reports | admin-only, one route each | no duplicate found |

## Security

Hiding is not the protection. firestore.rules and the backend are **unchanged** and still decide every read and write from `Person.activeAdminRole` / `activeCongregationId` / `activeGroupId`, which `UserSession` keeps in step with the primary role. Combining roles therefore cannot reach another congregation, and server-side the account is never *more* privileged than its most senior role.

Consequences to know about:
* rules take **one** role per person, so a lower role's *extra* abilities (for example a Service Overseer's Publisher Assignment next to a Coordinator Elder) are enforced client-side only when the senior role does not already grant them. Today the senior roles are supersets for everything except the House Holder Assignment / Territory Assignment family; check those two on a device.
* a person with an Admin-track role now always has `activeAdminRole` set (before, it was `null` while they were in Publisher mode), so the account-credential rule that lets a Coordinator Elder / Service Overseer / Secretary manage only accounts with no admin role will treat such a person as an elder (Admin-only).

## Tests

`MultiRoleSessionTest`, `RecordScopeTest` (shared) and `AdminCapabilitiesTest` (app) cover: every combination in the spec, three-role accounts, another-congregation roles not merged, Super-Admin, role removal/inactivation, duplicate roles, simple-menu rule, group role under a congregation-wide role.
Not done: running the app on a device, and the Laravel website (it still has its own role-selection flow).
