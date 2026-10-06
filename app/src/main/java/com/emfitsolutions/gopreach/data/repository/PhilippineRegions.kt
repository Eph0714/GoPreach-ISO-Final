package com.emfitsolutions.gopreach.data.repository

/**
 * Region -> province lookup for the Province search box. The bundled PSGC
 * data (see `PsgcDatabase`) has no Region level — Province is its top level —
 * so a publisher who types "Bicol" (or "Region V", "Calabarzon", ...) into the
 * Province field used to get "No matches" even though every Bicol province is
 * in the list. This maps the common region names/aliases to the province
 * names in that region so those searches list the provinces underneath.
 *
 * Aliases and province names are compared in normalized form (see
 * [PhilippineLocationRepository]'s `normalize`), so casing/punctuation don't
 * matter. Province names must match the bundled data's own names.
 */
internal object PhilippineRegions {
    private class Region(val aliases: List<String>, val provinces: List<String>)

    private val regions = listOf(
        Region(listOf("NCR", "NATIONAL CAPITAL REGION"), listOf("Metro Manila")),
        Region(
            listOf("CAR", "CORDILLERA", "CORDILLERA ADMINISTRATIVE REGION"),
            listOf("Abra", "Apayao", "Benguet", "Ifugao", "Kalinga", "Mountain Province"),
        ),
        Region(
            listOf("REGION I", "REGION 1", "ILOCOS", "ILOCOS REGION"),
            listOf("Ilocos Norte", "Ilocos Sur", "La Union", "Pangasinan"),
        ),
        Region(
            listOf("REGION II", "REGION 2", "CAGAYAN VALLEY"),
            listOf("Batanes", "Cagayan", "Isabela", "Nueva Vizcaya", "Quirino"),
        ),
        Region(
            listOf("REGION III", "REGION 3", "CENTRAL LUZON"),
            listOf("Aurora", "Bataan", "Bulacan", "Nueva Ecija", "Pampanga", "Tarlac", "Zambales"),
        ),
        Region(
            listOf("REGION IV A", "REGION 4A", "CALABARZON", "SOUTHERN TAGALOG"),
            listOf("Batangas", "Cavite", "Laguna", "Quezon", "Rizal"),
        ),
        Region(
            listOf("REGION IV B", "REGION 4B", "MIMAROPA"),
            listOf("Marinduque", "Occidental Mindoro", "Oriental Mindoro", "Palawan", "Romblon"),
        ),
        Region(
            listOf("REGION V", "REGION 5", "BICOL", "BICOL REGION", "BICOLANDIA"),
            listOf("Albay", "Camarines Norte", "Camarines Sur", "Catanduanes", "Masbate", "Sorsogon"),
        ),
        Region(
            listOf("REGION VI", "REGION 6", "WESTERN VISAYAS"),
            listOf("Aklan", "Antique", "Capiz", "Guimaras", "Iloilo", "Negros Occidental"),
        ),
        Region(
            listOf("REGION VII", "REGION 7", "CENTRAL VISAYAS"),
            listOf("Bohol", "Cebu", "Negros Oriental", "Siquijor"),
        ),
        Region(
            listOf("REGION VIII", "REGION 8", "EASTERN VISAYAS"),
            listOf("Biliran", "Eastern Samar", "Leyte", "Northern Samar", "Samar", "Southern Leyte"),
        ),
        Region(
            listOf("REGION IX", "REGION 9", "ZAMBOANGA PENINSULA"),
            listOf("Zamboanga del Norte", "Zamboanga del Sur", "Zamboanga Sibugay"),
        ),
        Region(
            listOf("REGION X", "REGION 10", "NORTHERN MINDANAO"),
            listOf("Bukidnon", "Camiguin", "Lanao del Norte", "Misamis Occidental", "Misamis Oriental"),
        ),
        Region(
            listOf("REGION XI", "REGION 11", "DAVAO REGION"),
            listOf("Davao de Oro", "Davao del Norte", "Davao del Sur", "Davao Occidental", "Davao Oriental"),
        ),
        Region(
            listOf("REGION XII", "REGION 12", "SOCCSKSARGEN"),
            listOf("Cotabato", "Sarangani", "South Cotabato", "Sultan Kudarat"),
        ),
        Region(
            listOf("REGION XIII", "REGION 13", "CARAGA"),
            listOf("Agusan del Norte", "Agusan del Sur", "Dinagat Islands", "Surigao del Norte", "Surigao del Sur"),
        ),
        Region(
            listOf("BARMM", "BANGSAMORO", "ARMM"),
            listOf("Basilan", "Lanao del Sur", "Maguindanao del Norte", "Maguindanao del Sur", "Sulu", "Tawi-Tawi"),
        ),
    )

    /** Province names of every region whose name/alias [normalizedQuery]
     * refers to. A "Region ..." alias must match exactly ("Region V" must not
     * also list Region VI's provinces); a plain name like "Bicol" also matches
     * as a prefix once at least three letters are typed ("bic"). Empty when the
     * query isn't a region at all — the normal province-name search then
     * stands alone. */
    fun provincesForQuery(normalizedQuery: String, normalize: (String) -> String): List<String> {
        if (normalizedQuery.isBlank()) return emptyList()
        return regions
            .filter { region ->
                region.aliases.any { alias ->
                    alias == normalizedQuery ||
                        (normalizedQuery.length >= 3 && !alias.startsWith("REGION") && alias.startsWith(normalizedQuery))
                }
            }
            .flatMap { it.provinces }
            .map(normalize)
    }
}
