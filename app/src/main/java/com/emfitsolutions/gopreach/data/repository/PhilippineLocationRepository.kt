package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.local.psgc.BarangayEntity
import com.emfitsolutions.gopreach.data.local.psgc.MuncityEntity
import com.emfitsolutions.gopreach.data.local.psgc.PsgcDao
import com.emfitsolutions.gopreach.data.local.psgc.ProvinceEntity
import com.emfitsolutions.gopreach.data.location.GeocodedAddress
import javax.inject.Inject
import javax.inject.Singleton

/** One resolved level of [PhilippineAddressSelection] — [id] is the PSGC
 * row's own id (used to query the next level down), [name] what's shown. */
data class PsgcOption(val id: Int, val name: String)

/** What [PhilippineAddressPicker][com.emfitsolutions.gopreach.ui.components
 * .PhilippineAddressPicker] holds and what a caller ultimately saves onto
 * [com.emfitsolutions.gopreach.data.model.InterestedPerson]/[com
 * .emfitsolutions.gopreach.data.model.Person] — plain names, not PSGC ids,
 * matching every other free-text location field this app already stores
 * (e.g. [com.emfitsolutions.gopreach.data.model.InterestedPerson.address]);
 * the ids only ever matter transiently, for cascading the next dropdown. */
data class PhilippineAddressSelection(
    val province: String? = null,
    val cityMunicipality: String? = null,
    val barangay: String? = null,
)

private fun normalize(s: String): String =
    s.replace(Regex("\\(.*?\\)"), " ")
        .replace(Regex("[^A-Za-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .uppercase()

/** [normalize]d [s] without the "City of"/"Municipality of" prefix or trailing
 * "City" — the PSGC names cities both ways ("City of Batac", "Legazpi City"),
 * so searching on the core name finds either. Left alone when stripping would
 * leave nothing. */
private fun cityKey(s: String): String {
    val n = normalize(s)
    val core = n.removePrefix("CITY OF ").removePrefix("MUNICIPALITY OF ").removeSuffix(" CITY").trim()
    return core.ifBlank { n }
}

/**
 * "Add a dropdown for City, Municipalities, Town Barangay... The publisher
 * will browse manually, however it can be automatic if the publisher will
 * capture the coordinates" — the manual-browse half wraps [PsgcDao]'s
 * type-ahead search; the automatic half ([resolveFromGeocode]) takes
 * [GeocodedAddress] (Android's on-device Geocoder output, already best-
 * effort per its own doc comment) and matches each level against the same
 * bundled PSGC table, falling back level by level (exact match, then a
 * loose "contains" search) so a slightly different capitalization/wording
 * from the geocoder still resolves to a real, canonical PSGC entry rather
 * than silently matching nothing.
 */
@Singleton
class PhilippineLocationRepository @Inject constructor(
    private val dao: PsgcDao,
) {
    /** Province-name search, plus — because the data has no Region level — any
     * province in a region the query names ("Bicol", "Region V", "Calabarzon",
     * ...); see [PhilippineRegions]. */
    suspend fun searchProvinces(query: String): List<PsgcOption> {
        val normalized = normalize(query)
        val byName = dao.searchProvinces(normalized)
        val regionProvinceNames = PhilippineRegions.provincesForQuery(normalized, ::normalize).toSet()
        if (regionProvinceNames.isEmpty()) return byName.map { it.toOption() }
        val byRegion = dao.searchProvinces("").filter { it.nameNormalized in regionProvinceNames }
        return (byName + byRegion).distinctBy { it.id }.sortedBy { it.name }.map { it.toOption() }
    }

    suspend fun searchCitiesMunicipalities(provinceId: Int?, query: String): List<PsgcOption> {
        val key = cityKey(query)
        return (if (provinceId != null) dao.searchMuncitiesInProvince(provinceId, key) else dao.searchMuncitiesNationwide(key))
            .map { it.toOption() }
    }

    /** Exact match on a place the publisher typed by hand, or — for a
     * municipality/city inside a known province — the one unambiguous match
     * ("Legazpi" -> "Legazpi City"). Null means "not a place in the data", so
     * the caller keeps the typed text as-is and assigns no id. */
    suspend fun canonicalProvince(rawName: String): PsgcOption? =
        normalize(rawName).takeIf { it.isNotBlank() }?.let { dao.provinceByExactName(it)?.toOption() }

    suspend fun canonicalMuncity(rawName: String, provinceId: Int?): PsgcOption? {
        val n = normalize(rawName)
        if (n.isBlank() || provinceId == null) return null
        dao.muncityByExactName(n, provinceId)?.let { return it.toOption() }
        return dao.searchMuncitiesInProvince(provinceId, cityKey(rawName)).singleOrNull()?.toOption()
    }

    suspend fun canonicalBarangay(rawName: String, muncityId: Int?): PsgcOption? {
        val n = normalize(rawName)
        if (n.isBlank() || muncityId == null) return null
        return dao.barangayByExactName(n, muncityId)?.toOption()
    }

    suspend fun provinceOfMuncity(muncityId: Int): PsgcOption? =
        dao.muncityById(muncityId)?.let { dao.provinceById(it.provinceId) }?.toOption()

    suspend fun searchBarangays(muncityId: Int, query: String): List<PsgcOption> =
        dao.searchBarangaysInMuncity(muncityId, normalize(query)).map { it.toOption() }

    /** Territory Map's "Municipality: All Municipalities" case — every
     * barangay across the whole province at once. */
    suspend fun searchBarangaysInProvince(provinceId: Int, query: String): List<PsgcOption> =
        dao.searchBarangaysInProvince(provinceId, normalize(query)).map { it.toOption() }

    /** Best-effort match of a Geocoder result onto real PSGC rows — returns
     * both the resolved [PhilippineAddressSelection] (names, for display/
     * saving) and the matched ids (so the picker can keep cascading from
     * here, e.g. if the publisher wants to correct just the barangay). Any
     * level the geocoder didn't return, or that couldn't be matched, is
     * simply left null rather than guessed — see [PhilippineAddressPicker]
     * for how an unmatched level still falls back to manual browsing. */
    suspend fun resolveFromGeocode(geocoded: GeocodedAddress): ResolvedAddress {
        var province = geocoded.province?.let { findProvince(it) }
        val muncity = geocoded.cityMunicipality?.let { findMuncity(it, province?.id) }
        // A geocoder that returned a region ("Cagayan Valley") or nothing for
        // the province still usually gets the municipality right — take the
        // province from that municipality's own record rather than leaving it
        // blank (or, worse, wrong).
        if (muncity != null && province?.id != muncity.provinceId) {
            province = dao.provinceById(muncity.provinceId) ?: province
        }
        val barangay = if (muncity != null) geocoded.barangay?.let { findBarangay(it, muncity.id) } else null
        return ResolvedAddress(
            provinceId = province?.id,
            provinceName = province?.name,
            muncityId = muncity?.id,
            muncityName = muncity?.name,
            barangayId = barangay?.id,
            barangayName = barangay?.name,
        )
    }

    /** Public re-hydration lookups — used by [com.emfitsolutions.gopreach.ui
     * .components.PhilippineAddressPicker] to recover a row's id from a
     * plain name it was only ever given as text (a loaded record, or a name
     * set by [resolveFromGeocode] without its id persisted anywhere), so it
     * knows which province/muncity to scope the next dropdown's search to. */
    suspend fun findProvinceByName(rawName: String): PsgcOption? = findProvince(rawName)?.toOption()
    suspend fun findMuncityByName(rawName: String, provinceId: Int?): PsgcOption? = findMuncity(rawName, provinceId)?.toOption()

    private suspend fun findProvince(rawName: String): ProvinceEntity? {
        val n = normalize(rawName)
        return dao.provinceByExactName(n) ?: dao.searchProvinces(n).firstOrNull()
    }

    private suspend fun findMuncity(rawName: String, provinceId: Int?): MuncityEntity? {
        val n = normalize(rawName)
        dao.muncityByExactName(n, provinceId)?.let { return it }
        val candidates = if (provinceId != null) dao.searchMuncitiesInProvince(provinceId, n) else dao.searchMuncitiesNationwide(n)
        candidates.firstOrNull()?.let { return it }
        // The PSGC writes some cities "City of Batac" and others "Legazpi
        // City", while a geocoder may return either form for either — retry
        // on just the core name so "Batac City" still finds "City of Batac".
        val core = cityKey(rawName)
        if (core == n) return null
        return (if (provinceId != null) dao.searchMuncitiesInProvince(provinceId, core) else dao.searchMuncitiesNationwide(core)).firstOrNull()
    }

    private suspend fun findBarangay(rawName: String, muncityId: Int): BarangayEntity? {
        val n = normalize(rawName)
        return dao.barangayByExactName(n, muncityId) ?: dao.searchBarangaysInMuncity(muncityId, n).firstOrNull()
    }

    private fun ProvinceEntity.toOption() = PsgcOption(id, name)
    private fun MuncityEntity.toOption() = PsgcOption(id, name)
    private fun BarangayEntity.toOption() = PsgcOption(id, name)
}

/** Result of [PhilippineLocationRepository.resolveFromGeocode] — ids
 * included alongside names so the caller's picker state can keep cascading
 * (e.g. re-querying barangays for [muncityId]) without a second lookup. */
data class ResolvedAddress(
    val provinceId: Int?,
    val provinceName: String?,
    val muncityId: Int?,
    val muncityName: String?,
    val barangayId: Int?,
    val barangayName: String?,
) {
    fun toSelection() = PhilippineAddressSelection(province = provinceName, cityMunicipality = muncityName, barangay = barangayName)
}
