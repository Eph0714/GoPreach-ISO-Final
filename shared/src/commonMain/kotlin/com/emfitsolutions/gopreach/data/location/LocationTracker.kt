package com.emfitsolutions.gopreach.data.location

import kotlinx.coroutines.flow.Flow

/**
 * A GPS fix. [speedMps], [bearingDeg] (course of travel, degrees clockwise from true north, NOT the way the phone faces) and [timeMillis] (when the fix
 * was taken, as device clock millis) are null/0 when the platform does not report them, so existing callers that only need a position are unaffected.
 */
data class LatLng(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float?,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val timeMillis: Long = 0L,
)

/**
 * The structured pieces of an on-device geocoder result used to pre-fill City / Municipality / Barangay when a publisher captures
 * coordinates. [barangay] is the least reliable of the three (varies by device and is often null even when the others resolve);
 * callers treat every field as a best-effort suggestion the publisher can still override, never an authoritative fill.
 */
data class GeocodedAddress(
    val barangay: String?,
    val cityMunicipality: String?,
    val province: String?,
)

/**
 * Device location and geocoding, used by Share Location and by GPS-coordinate capture on Publisher, Bible study and Interested
 * person forms. Android implements it on Google Play services' fused provider; iOS will use CoreLocation. It surfaces
 * `accuracyMeters` so a caller can apply an accuracy threshold.
 */
interface LocationTracker {
    fun hasLocationPermission(): Boolean

    /** A separate question from [hasLocationPermission]: the device's location services (GPS/network provider) may be switched off. */
    fun isLocationServicesEnabled(): Boolean

    /**
     * A real, precise fix for capturing coordinates: answers at once from a recent precise fix, otherwise listens until one is precise
     * enough (or time runs out and the best one heard is returned). Never invents a position; null when nothing real was heard.
     */
    suspend fun getCurrentLocation(): LatLng?
    suspend fun getLastKnownLocation(): LatLng?

    /** Called when the app comes to the front: lets the GPS settle so the first capture or map view starts from a precise fix. Does nothing by default. */
    suspend fun warmUp() {}

    fun requestLocationUpdatesFlow(intervalMillis: Long = 15_000L, minUpdateIntervalMillis: Long = 8_000L): Flow<LatLng>

    suspend fun reverseGeocode(lat: Double, lng: Double): String?
    suspend fun reverseGeocodeAddress(lat: Double, lng: Double): GeocodedAddress?
    suspend fun geocodeAddress(query: String): LatLng?
}
