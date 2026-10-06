package com.emfitsolutions.gopreach.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Shared "#RRGGBB" color palette for a Congregation Group — the single
 * source every screen that shows a Group's color reads from, so the
 * Territory Map (markers/barrier), [com.emfitsolutions.gopreach.ui.screens
 * .groups.ManageGroupsScreen] (the color picker and list swatch), and any
 * future screen never drift onto two different formulas for "what color is
 * this Group."
 *
 * The actual assigned color per Group lives on [com.emfitsolutions.gopreach
 * .data.model.Group.color] (set once at creation from [nextAvailableColor],
 * editable by an admin afterward) — everything here is just the shared
 * palette/fallback machinery that field is built from.
 */
object GroupColorPalette {

    /** Neutral gray for a record whose assigned Publisher has no Congregation
     * Group at all — deliberately never a "real" group's own assigned/
     * generated color, so an unassigned record can never be mistaken for
     * belonging to one. */
    const val UNASSIGNED_COLOR = "#78909C"

    /** "Group 1 -> Yellow, Group 2 -> Orange, Group 3 -> Blue, Group 4 ->
     * Green, Group 5 -> Violet" — spec's own exact worked example, extended
     * to 12 hand-picked, mutually-distinct, professional colors (never a
     * formula that *might* produce two similar-looking ones — see
     * [colorForGroupId]'s own doc comment for why a purely computed palette
     * isn't good enough on its own for a small handful of Groups). A new
     * Group is auto-assigned the first entry not already in use by another
     * active Group in the same congregation (see [nextAvailableColor]); a
     * 13th+ Group in one congregation falls through to [colorForGroupId]'s
     * generated color instead.
     */
    val CURATED = listOf(
        "#FBC02D", // Yellow
        "#FB8C00", // Orange
        "#1E88E5", // Blue
        "#43A047", // Green
        "#8E24AA", // Violet
        "#E53935", // Red
        "#00ACC1", // Cyan
        "#D81B60", // Pink
        "#6D4C41", // Brown
        "#3949AB", // Indigo
        "#00897B", // Teal
        "#F4511E", // Deep Orange
    )

    /** The color a *new* Group should be assigned by default (an admin can
     * still change it before or after saving): the first [CURATED] entry not
     * already used by another active Group in [usedColors], so "Different
     * Group = Different Color" holds for every congregation up to 12 Groups
     * without an admin having to think about it. Falls through to
     * [colorForGroupId] once every curated color is already taken. */
    fun nextAvailableColor(usedColors: Collection<String>, seedId: String): String =
        CURATED.firstOrNull { it !in usedColors } ?: colorForGroupId(seedId)

    /** Overflow generator, used only once a congregation has more Groups than
     * [CURATED] has colors for (spec's own "do not limit the system to five
     * colors/groups... automatically generate/assign additional visually
     * distinct colors"). Golden-ratio-conjugate hue stepping, seeded by the
     * Group's own stable id hash, is a standard trick for spreading
     * arbitrary-but-deterministic values well around the color wheel — a
     * reasonable, dependency-free approximation for the rare case a
     * congregation actually runs past 12 Groups.
     */
    fun colorForGroupId(groupId: String): String {
        val goldenRatioConjugate = 0.6180339887498949
        val hash = groupId.hashCode().toLong() and 0xFFFFFFFFL
        val hue = ((hash * goldenRatioConjugate) % 1.0 * 360.0).toFloat()
        return hsvToHex(hue, saturation = 0.68f, value = 0.80f)
    }

    /** Plain HSV -> "#RRGGBB" conversion with no Compose/Android dependency,
     * so the exact same color can be embedded straight into the map's own JS
     * (markers, group boundary lines/fills) and parsed back into a Compose
     * [Color] for dropdown/legend/report swatches — one formula, never two
     * color systems that could drift apart. */
    private fun hsvToHex(hueDegrees: Float, saturation: Float, value: Float): String {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs((hueDegrees / 60f) % 2 - 1))
        val m = value - c
        val (r1, g1, b1) = when {
            hueDegrees < 60f -> Triple(c, x, 0f)
            hueDegrees < 120f -> Triple(x, c, 0f)
            hueDegrees < 180f -> Triple(0f, c, x)
            hueDegrees < 240f -> Triple(0f, x, c)
            hueDegrees < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(channel(r1), channel(g1), channel(b1))
    }

    /** Parses a "#RRGGBB" string (as produced by [colorForGroupId]/
     * [UNASSIGNED_COLOR]/[CURATED]) into a Compose [Color] for dropdown/
     * legend/report/picker swatches — `android.graphics.Color.parseColor`
     * would also work, but pulling in the platform's own class for a format
     * this object already generates itself is one dependency this doesn't
     * need. */
    fun parseHex(hex: String): Color {
        val clean = hex.removePrefix("#")
        val r = clean.substring(0, 2).toInt(16)
        val g = clean.substring(2, 4).toInt(16)
        val b = clean.substring(4, 6).toInt(16)
        return Color(r, g, b)
    }
}
