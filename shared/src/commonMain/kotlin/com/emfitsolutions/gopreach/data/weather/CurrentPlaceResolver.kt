package com.emfitsolutions.gopreach.data.weather

import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.domain.weather.PlaceKind
import com.emfitsolutions.gopreach.domain.weather.WeatherPlace

/** Turns the device's current position into a named [WeatherPlace]. Used for the first place and for "Use my current location". */
class CurrentPlaceResolver(private val locationTracker: LocationTracker) {
    fun hasPermission() = locationTracker.hasLocationPermission()

    sealed interface Result {
        data class Found(val place: WeatherPlace) : Result
        data object ServicesOff : Result
        data object NotFound : Result
    }

    suspend fun resolve(): Result {
        if (!locationTracker.isLocationServicesEnabled()) return Result.ServicesOff
        val fix = runCatching { locationTracker.getCurrentLocation() ?: locationTracker.getLastKnownLocation() }.getOrNull() ?: return Result.NotFound
        val address = runCatching { locationTracker.reverseGeocodeAddress(fix.lat, fix.lng) }.getOrNull()
        val name = address?.barangay?.takeIf { it.isNotBlank() } ?: address?.cityMunicipality?.takeIf { it.isNotBlank() } ?: "My current location"
        val detail = listOfNotNull(address?.cityMunicipality?.takeIf { it != name }, address?.province).filter { it.isNotBlank() }.joinToString(", ")
        return Result.Found(WeatherPlace(id = "current_location", name = name, detail = detail, lat = fix.lat, lng = fix.lng, kind = PlaceKind.CURRENT_LOCATION))
    }
}
