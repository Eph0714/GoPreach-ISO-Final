package com.emfitsolutions.gopreach.data.local.psgc

import androidx.room.Dao
import androidx.room.Query

/**
 * Read-only lookups over the bundled PSGC reference data (see
 * [PsgcDatabase]'s doc comment). Every search is a `LIKE` against
 * [ProvinceEntity.nameNormalized]/etc. rather than an exact match — the
 * picker (see [com.emfitsolutions.gopreach.ui.components.PhilippineAddressPicker])
 * filters as the publisher types, and the same normalized-contains match is
 * what the reverse-geocode auto-fill (see [com.emfitsolutions.gopreach.data
 * .repository.PhilippineLocationRepository]) uses to resolve Android's
 * Geocoder output back to one of these rows.
 *
 * Bug fix ("Publisher Enrollment — Fix Incomplete Province Dropdown"): every
 * one of these queries used to cap results at `LIMIT 50` — fine for
 * [searchMuncitiesNationwide] (a genuinely unscoped, 1,634-row fallback
 * search, only ever used before a Province is picked), but wrong for the
 * three queries below it, each of which is already scoped to a single,
 * small, *complete* set the Publisher is entitled to see in full: there are
 * only 83 provinces total (so `LIMIT 50` silently hid every province from
 * "Negros Oriental" onward, alphabetically — exactly this bug report), Cebu
 * alone has 53 cities/municipalities (3 always missing), and 107
 * municipalities/cities nationwide (Manila alone has 897) have more than 50
 * barangays. The underlying bundled data was never incomplete — see this
 * fix's own audit — only these three queries were truncating it. Every
 * level here is still indexed, so returning the full scoped set instead of
 * an artificial top-50 stays cheap even for Manila's 897 barangays.
 */
@Dao
interface PsgcDao {
    @Query("SELECT * FROM province WHERE nameNormalized LIKE '%' || :query || '%' ORDER BY name")
    suspend fun searchProvinces(query: String): List<ProvinceEntity>

    @Query("SELECT * FROM province WHERE id = :id")
    suspend fun provinceById(id: Int): ProvinceEntity?

    @Query("SELECT * FROM muncity WHERE provinceId = :provinceId AND nameNormalized LIKE '%' || :query || '%' ORDER BY name")
    suspend fun searchMuncitiesInProvince(provinceId: Int, query: String): List<MuncityEntity>

    /** Used only when no province has been picked yet — a nationwide search
     * across every city/municipality, still cheap thanks to the name index.
     * Kept capped at 50: unlike the queries above, this one is genuinely
     * unscoped (up to 1,634 rows), so the cap only ever trims an already-
     * optional, type-ahead-driven convenience search — a Publisher who knows
     * their Province gets the complete, uncapped per-province list above
     * instead. */
    @Query("SELECT * FROM muncity WHERE nameNormalized LIKE '%' || :query || '%' ORDER BY name LIMIT 50")
    suspend fun searchMuncitiesNationwide(query: String): List<MuncityEntity>

    @Query("SELECT * FROM muncity WHERE id = :id")
    suspend fun muncityById(id: Int): MuncityEntity?

    @Query("SELECT * FROM barangay WHERE muncityId = :muncityId AND nameNormalized LIKE '%' || :query || '%' ORDER BY name")
    suspend fun searchBarangaysInMuncity(muncityId: Int, query: String): List<BarangayEntity>

    /** Territory Map's "Municipality: All Municipalities" case — every
     * barangay across every municipality/city in one province, joined
     * through [MuncityEntity] since [BarangayEntity] itself only stores its
     * own `muncityId`, not a province id. */
    @Query(
        "SELECT barangay.* FROM barangay INNER JOIN muncity ON barangay.muncityId = muncity.id " +
            "WHERE muncity.provinceId = :provinceId AND barangay.nameNormalized LIKE '%' || :query || '%' ORDER BY barangay.name",
    )
    suspend fun searchBarangaysInProvince(provinceId: Int, query: String): List<BarangayEntity>

    @Query("SELECT * FROM barangay WHERE id = :id")
    suspend fun barangayById(id: Int): BarangayEntity?

    /** Exact-normalized-name match, used by the reverse-geocode auto-fill to
     * resolve Android's Geocoder output to a canonical row before falling
     * back to a looser `LIKE` search. */
    @Query("SELECT * FROM province WHERE nameNormalized = :normalized LIMIT 1")
    suspend fun provinceByExactName(normalized: String): ProvinceEntity?

    @Query("SELECT * FROM muncity WHERE nameNormalized = :normalized AND (:provinceId IS NULL OR provinceId = :provinceId) LIMIT 1")
    suspend fun muncityByExactName(normalized: String, provinceId: Int?): MuncityEntity?

    @Query("SELECT * FROM barangay WHERE nameNormalized = :normalized AND muncityId = :muncityId LIMIT 1")
    suspend fun barangayByExactName(normalized: String, muncityId: Int): BarangayEntity?
}
