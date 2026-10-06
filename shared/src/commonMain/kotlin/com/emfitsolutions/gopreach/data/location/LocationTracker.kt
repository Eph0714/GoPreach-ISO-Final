package com.emfitsolutions.gopreach.data.location

import kotlinx.coroutines.flow.Flow

data class LatLng(val lat: Double, val lng: Double, val accuracyMeters: Float?)

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

    suspend fun getCurrentLocation(): LatLng?
    suspend fun getLastKnownLocation(): LatLng?

    fun requestLocationUpdatesFlow(intervalMillis: Long = 15_000L, minUpdateIntervalMillis: Long = 8_000L): Flow<LatLng>

    suspend fun reverseGeocode(lat: Double, lng: Double): String?
    suspend fun reverseGeocodeAddress(lat: Double, lng: Double): GeocodedAddress?
    suspend fun geocodeAddress(query: String): LatLng?
}
