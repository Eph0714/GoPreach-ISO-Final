package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * "Publishers App – Customizable Module Navigation Redesign" — where a
 * Publisher's dashboard module currently sits. This is the *only* thing this
 * customization feature ever changes (spec §10): never permissions, never
 * which modules exist, never their routes. A module hidden/unauthorized for
 * this Publisher's role never becomes reachable just because it moved.
 */
enum class DashboardModuleLocation { MAIN_FORM, SIDE_PANEL }

/**
 * One customizable Publisher Main Form/Side Panel entry. This id is
 * permanent and is what gets stored — the actual title/subtitle/icon/route/
 * badge for it lives entirely in PublisherHomeScreen's own tile catalog, so
 * renaming a label or swapping an icon later never touches a saved layout.
 * Every id here already exists and is already reachable in
 * PublisherHomeScreen's FeatureTileGrid today — this enum never grants new
 * reach, it only lets a Publisher choose *where on this one screen* an
 * already-authorized tile is shown (spec §10). [MY_TOTAL_HOURS] (Preaching
 * Time Record) keeps its existing Pioneer-only gating unchanged — a
 * non-Pioneer never sees it in either panel, customization or not.
 */
enum class DashboardModuleId {
    MY_TOTAL_HOURS,
    SEARCHING,
    RETURN_VISIT,
    BIBLE_STUDY,
    MONTHLY_REPORT,
    MY_SUBMITTED_REPORTS,
    FORWARDED_TO_ME,
    INCOMING_HOUSEHOLDER_ASSIGNMENTS,
    HOUSEHOLDER_VISIT_HISTORY,
    MY_BIBLE_TEXT_RECORD,
    MY_CALENDAR,
    SHARE_MY_LOCATION,
    FIND_LOCATION,
    TERRITORY_MAP,
    MEETING_CART_ASSIGNMENT,
    MY_ASSIGNMENTS,
    ANNOUNCEMENT,
    GROUP_CHAT,
    // "Make a module for publisher to see the available schedule of the
    // other publishers" — promotes the existing "View Other Publishers'
    // Schedules" link (Account Settings) into a first-class module tile,
    // same PublisherSchedulesScreen/Destinations.PUBLISHER_SCHEDULES route.
    PUBLISHER_SCHEDULES,
    // My Planner / Ministry Timer / Reporting upgrade — Day/Month/Year
    // ministry-time planner, Publisher-only, own data (spec §16/§41).
    MY_PLANNER,
}

/**
 * Default location for a module a Publisher has never explicitly moved.
 * Spec §8: a future module not yet in a Publisher's saved layout always
 * resolves through this same function, so it appears somewhere sensible
 * without silently overwriting anything the Publisher already customized.
 *
 * "Move all the icon from main form to side panel" — every module now
 * defaults to the Side Panel, full stop, including [FORWARDED_TO_ME]/
 * [INCOMING_HOUSEHOLDER_ASSIGNMENTS] (previously kept on the Main Form for
 * their live badge — an explicit, unconditional request overrides that
 * earlier call). This only changes what a *never-customized* layout
 * resolves to (see [DashboardModuleLayout]'s "deliberately NOT storing every
 * module" note) — a Publisher who already explicitly placed a module on the
 * Main Form keeps that choice untouched, since their id is already present
 * in [DashboardModuleLayout.mainFormModuleIds] and this function is never
 * consulted for it; "Reset Dashboard Layout" (spec §9) is what actually
 * clears an existing customization back to this all-Side-Panel default.
 */
fun DashboardModuleId.defaultLocation(): DashboardModuleLocation = DashboardModuleLocation.SIDE_PANEL

/**
 * "Publishers App – Customizable Module Navigation Redesign" spec §5/§6/§21
 * — one document per Publisher (personId is the Firestore document id, same
 * self-owned "personId-keyed document" convention `presence/{personId}`
 * already uses), synced via the same offline-first mirror every other
 * per-account setting in this app already uses, so the layout follows the
 * account across devices, not just the one it was set on (spec §6's own
 * "if the publisher changes devices... the customized arrangement should be
 * restored").
 *
 * Deliberately NOT storing "every module and its location" — only the
 * modules a Publisher has *explicitly* touched, each list in the order they
 * chose (spec §7). A module absent from both lists has never been moved and
 * still resolves through [defaultLocation] — this is what makes spec §8
 * ("a new module joins the default location automatically, without
 * overwriting the Publisher's existing customized layout") true by
 * construction rather than by a migration step: there is nothing to migrate.
 *
 * See [mainFormModules]/[sidePanelModules] for the actual ordered, resolved
 * list each panel renders, and [moved]/[reset] for the two ways this
 * document changes.
 */
data class DashboardModuleLayout(
    @DocumentId val personId: String = "",
    val mainFormModuleIds: List<String> = emptyList(),
    val sidePanelModuleIds: List<String> = emptyList(),
    /** My Planner sections this Publisher has switched off (see
     * [PlannerSection]). Display preference only — hiding a section never
     * touches the records behind it; switching it back on shows them again. */
    val hiddenPlannerSections: List<String> = emptyList(),
    /** Admin dashboard "Quick Access" card order (see QuickAccessItem ids); empty means the default order. */
    val quickAccessOrder: List<String> = emptyList(),
    /** True once the user has added, removed or reordered a Quick Access card: [quickAccessOrder] is then exactly what they chose (even empty). */
    val quickAccessConfigured: Boolean = false,
    val updatedAt: Long = 0L,
)

/** Every module currently on the Main Form for this layout, in display
 * order: this Publisher's own explicit order first, then every
 * never-customized module whose [defaultLocation] is Main Form, in the
 * catalog's own declared order (spec §7/§8). */
fun DashboardModuleLayout.mainFormModules(): List<DashboardModuleId> {
    val touched = (mainFormModuleIds + sidePanelModuleIds).toSet()
    val explicit = mainFormModuleIds.mapNotNull { id -> runCatching { DashboardModuleId.valueOf(id) }.getOrNull() }
    val defaulted = DashboardModuleId.entries.filter { it.name !in touched && it.defaultLocation() == DashboardModuleLocation.MAIN_FORM }
    return explicit + defaulted
}

/** Every module currently on the Side Panel for this layout — same
 * resolution rule as [mainFormModules], mirrored for the other panel. */
fun DashboardModuleLayout.sidePanelModules(): List<DashboardModuleId> {
    val touched = (mainFormModuleIds + sidePanelModuleIds).toSet()
    val explicit = sidePanelModuleIds.mapNotNull { id -> runCatching { DashboardModuleId.valueOf(id) }.getOrNull() }
    val defaulted = DashboardModuleId.entries.filter { it.name !in touched && it.defaultLocation() == DashboardModuleLocation.SIDE_PANEL }
    return explicit + defaulted
}

/** Moves [moduleId] to [to], appended at the end of its new panel's order —
 * "the remaining Main Form modules retain their existing order" (spec §7)
 * is automatic here since nothing else in either list is touched. */
fun DashboardModuleLayout.moved(moduleId: DashboardModuleId, to: DashboardModuleLocation): DashboardModuleLayout {
    val newMain = mainFormModuleIds.filterNot { it == moduleId.name }.toMutableList()
    val newSide = sidePanelModuleIds.filterNot { it == moduleId.name }.toMutableList()
    when (to) {
        DashboardModuleLocation.MAIN_FORM -> newMain += moduleId.name
        DashboardModuleLocation.SIDE_PANEL -> newSide += moduleId.name
    }
    return copy(mainFormModuleIds = newMain, sidePanelModuleIds = newSide, updatedAt = com.emfitsolutions.gopreach.platform.nowMillis())
}

/** "Reset Dashboard Layout" (spec §9) — clears every explicit placement, so
 * both panels fall straight back through [defaultLocation] for every
 * module. */
fun DashboardModuleLayout.reset(): DashboardModuleLayout =
    copy(mainFormModuleIds = emptyList(), sidePanelModuleIds = emptyList(), updatedAt = com.emfitsolutions.gopreach.platform.nowMillis())

/** The My Planner sections a Publisher can show or hide for themselves. */
enum class PlannerSection(val label: String) {
    HOURS("Hours / Minutes"),
    CREDIT_HOURS("Credit Hours"),
    RETURN_VISITS("Return Visits"),
    BIBLE_STUDIES("Bible Studies"),
}

fun DashboardModuleLayout.isPlannerSectionVisible(section: PlannerSection): Boolean = section.name !in hiddenPlannerSections

fun DashboardModuleLayout.withPlannerSectionVisible(section: PlannerSection, visible: Boolean): DashboardModuleLayout {
    val hidden = hiddenPlannerSections.filterNot { it == section.name }.let { if (visible) it else it + section.name }
    return copy(hiddenPlannerSections = hidden, updatedAt = com.emfitsolutions.gopreach.platform.nowMillis())
}
