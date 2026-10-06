package com.emfitsolutions.gopreach.ui.components.map

import com.emfitsolutions.gopreach.data.repository.LandmarkGroup

/** "Add a feature for the user to select what he wants to see specifically
 * ... make it checkbox so that the user can select a specific feature he
 * wants" — a multi-select filter the Leaflet boundary map offers as its own
 * checkbox picker. The boundary tint and street lines themselves are never
 * gated by this — only which landmark pins, street name labels, and building
 * footprints draw.
 * There is no `ALL` entry here: "All" is just every entry checked, handled
 * by the picker UI as a master checkbox rather than a member of this set. */
enum class MapLayer(val label: String) {
    LANDMARKS("Landmarks"),
    CHURCHES("Churches"),
    GASOLINE("Gasoline"),
    KINGDOM_HALL("Kingdom Hall"),
    STREET_NAMES("Street Names"),
    BUILDINGS("Buildings"),
}

/** Which checkbox a landmark's own [LandmarkGroup] is governed by. */
fun LandmarkGroup.toMapLayer(): MapLayer = when (this) {
    LandmarkGroup.KINGDOM_HALL -> MapLayer.KINGDOM_HALL
    LandmarkGroup.CHURCH -> MapLayer.CHURCHES
    LandmarkGroup.GASOLINE -> MapLayer.GASOLINE
    LandmarkGroup.LANDMARK -> MapLayer.LANDMARKS
}
