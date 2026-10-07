package com.emfitsolutions.gopreach.data.location

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLPlacemark
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSError
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { block() }

@OptIn(ExperimentalForeignApi::class)
private fun CLLocation.toLatLng(): LatLng {
    val (lat, lng) = coordinate.useContents { latitude to longitude }
    return LatLng(lat, lng, horizontalAccuracy.takeIf { it >= 0 }?.toFloat())
}

private fun isAuthorized(status: CLAuthorizationStatus) =
    status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways

/** Location and geocoding on CoreLocation. Needs NSLocationWhenInUseUsageDescription in the app's Info.plist. */
class IosLocationTracker : LocationTracker {
    private val manager = CLLocationManager()
    private val geocoder = CLGeocoder()

    override fun hasLocationPermission(): Boolean = isAuthorized(manager.authorizationStatus)

    override fun isLocationServicesEnabled(): Boolean = CLLocationManager.locationServicesEnabled()

    override suspend fun getLastKnownLocation(): LatLng? = manager.location?.toLatLng()

    override suspend fun getCurrentLocation(): LatLng? = suspendCancellableCoroutine { continuation ->
        onMain {
            val oneShot = OneShotDelegate { continuation.resume(it) }
            val requester = CLLocationManager()
            requester.delegate = oneShot
            // The delegate is only weakly held by the manager: keep both alive until the callback fires.
            oneShot.owner = requester
            requester.requestLocation()
        }
    }

    override fun requestLocationUpdatesFlow(intervalMillis: Long, minUpdateIntervalMillis: Long): Flow<LatLng> = callbackFlow {
        val updates = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                (didUpdateLocations.lastOrNull() as? CLLocation)?.let { trySend(it.toLatLng()) }
            }

            override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = Unit
        }
        val streaming = CLLocationManager()
        onMain {
            streaming.delegate = updates
            streaming.startUpdatingLocation()
        }
        awaitClose {
            onMain {
                streaming.stopUpdatingLocation()
                streaming.delegate = null
            }
        }
    }

    override suspend fun reverseGeocode(lat: Double, lng: Double): String? =
        placemarkAt(lat, lng)?.let { p ->
            listOfNotNull(p.subLocality, p.locality, p.administrativeArea).joinToString(", ").ifBlank { p.name }
        }

    override suspend fun reverseGeocodeAddress(lat: Double, lng: Double): GeocodedAddress? =
        placemarkAt(lat, lng)?.let { GeocodedAddress(barangay = it.subLocality, cityMunicipality = it.locality, province = it.administrativeArea) }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun geocodeAddress(query: String): LatLng? = suspendCancellableCoroutine { continuation ->
        geocoder.geocodeAddressString(query) { placemarks, _ ->
            val location = (placemarks?.firstOrNull() as? CLPlacemark)?.location
            continuation.resume(location?.toLatLng())
        }
    }

    private suspend fun placemarkAt(lat: Double, lng: Double): CLPlacemark? = suspendCancellableCoroutine { continuation ->
        geocoder.reverseGeocodeLocation(CLLocation(latitude = lat, longitude = lng)) { placemarks, _ ->
            continuation.resume(placemarks?.firstOrNull() as? CLPlacemark)
        }
    }
}

private class OneShotDelegate(private val onResult: (LatLng?) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
    var owner: CLLocationManager? = null
    private var done = false

    private fun finish(result: LatLng?) {
        if (done) return
        done = true
        owner = null
        onResult(result)
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) =
        finish((didUpdateLocations.lastOrNull() as? CLLocation)?.toLatLng())

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = finish(null)
}
