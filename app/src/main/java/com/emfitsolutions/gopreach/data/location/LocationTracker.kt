package com.emfitsolutions.gopreach.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.location.LocationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class LatLng(val lat: Double, val lng: Double, val accuracyMeters: Float?)

/** The structured pieces of an on-device [Geocoder] result that matter for
 * "Add a dropdown for City, Municipalities, Town Barangay... automatic if
 * the publisher captures the coordinates" — [barangay] ([android.location
 * .Address.getSubLocality]) is the least reliable of the three (varies by
 * device/geocoder backend, and is frequently null even when the other two
 * resolve fine); callers treat every field as a best-effort suggestion the
 * publisher can still override via [com.emfitsolutions.gopreach.ui.components
 * .PhilippineAddressPicker], never an authoritative fill. */
data class GeocodedAddress(
    val barangay: String?,
    val cityMunicipality: String?,
    val province: String?,
)

/**
 * Thin wrapper over Play Services' fused location provider — used by Share
 * Location (spec §6.1) and available for GPS-coordinate capture on Publisher/
 * Bible-study/Interested-person forms (spec §7 open question: capture accuracy
 * threshold + weak-signal fallback are left as a follow-up product decision;
 * this surfaces `accuracyMeters` so a caller can apply one).
 */
@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        const val TAG = "LocationTracker"
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** "GPS is currently disabled. Please enable location services." — a
     * separate question from [hasLocationPermission]: the app can be fully
     * permitted and the device's location services (GPS/network provider)
     * still switched off entirely (airplane mode's location-off toggle,
     * device settings, a work profile restriction). [getCurrentLocation]
     * returning null on its own can't distinguish "no fix yet" from "no
     * provider is even on," so callers check this first and give the
     * specific message instead of a generic failure. */
    fun isLocationServicesEnabled(): Boolean =
        LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)

    @SuppressLint("MissingPermission") // caller checks hasLocationPermission() first
    suspend fun getCurrentLocation(): LatLng? {
        if (!hasLocationPermission()) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        // PRIORITY_HIGH_ACCURACY, not BALANCED_POWER_ACCURACY — the latter
        // is free to answer from WiFi/cell towers alone (often 50-100+
        // meters of error), which combined with Share Location's own
        // accuracy-radius filter (see LocationSharingSettings, default 5m)
        // meant a fix almost never met the threshold and "Share Location"
        // silently never actually published anything. HIGH_ACCURACY asks
        // for a real GPS-chip fix (typically single-digit-to-low-tens of
        // meters outdoors), which is what that filter was written assuming.
        val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).build()
        val location = client.getCurrentLocation(request, null).await() ?: return null
        return LatLng(location.latitude, location.longitude, location.accuracy)
    }

    /** "The publisher cannot open Share Location fast, it will take time" —
     * a fresh [getCurrentLocation] fix genuinely can take many seconds
     * (sometimes tens of seconds indoors on a cold GPS chip), since
     * PRIORITY_HIGH_ACCURACY deliberately forces a real new fix rather than
     * answering from cache. Play Services keeps its *own* rolling cache of
     * the device's last fix across every app that's asked recently, usually
     * only seconds old outdoors — [LocationSharingService] tries this first
     * so sharing can visibly turn on immediately, then upgrades to a fresh
     * [getCurrentLocation] fix on its very next cycle regardless. */
    @SuppressLint("MissingPermission") // caller checks hasLocationPermission() first
    suspend fun getLastKnownLocation(): LatLng? {
        if (!hasLocationPermission()) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = client.lastLocation.await() ?: return null
        return LatLng(location.latitude, location.longitude, location.accuracy)
    }

    /** "Fix the delay in the Shared Location feature" — root cause: the old
     * approach called [getCurrentLocation] (or [getLastKnownLocation]) once
     * per fixed-interval polling cycle. Every one of those calls pays a
     * fresh GPS fix's own multi-second acquisition latency *on top of* the
     * polling interval itself — the real source of the reported delay, not
     * the interval length alone (which is why simply shortening it was never
     * going to fully fix this). [requestLocationUpdates] instead keeps one
     * continuous subscription open with Play Services and calls back
     * immediately every time a new fix is genuinely available — no polling,
     * no repeated fresh-fix latency, and Play Services itself coalesces
     * anything more frequent than [minUpdateIntervalMillis] so this can't
     * flood Firestore with near-duplicate writes either.
     *
     * [intervalMillis]/[minUpdateIntervalMillis] bound how *often* Play
     * Services is asked to produce a fix (the legitimate battery/accuracy
     * trade-off knob — spec §5's "optimized update interval to balance
     * speed, battery consumption, and accuracy"), not an artificial wait
     * layered on top of an already-slow fetch. PRIORITY_HIGH_ACCURACY for
     * the same reason [getCurrentLocation] uses it — see that function's own
     * doc comment. */
    @SuppressLint("MissingPermission") // caller checks hasLocationPermission() first
    fun requestLocationUpdatesFlow(intervalMillis: Long = 15_000L, minUpdateIntervalMillis: Long = 8_000L): Flow<LatLng> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(minUpdateIntervalMillis)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(LatLng(location.latitude, location.longitude, location.accuracy))
                }
            }
        }
        // A permission revoked *after* the [hasLocationPermission] check
        // above (rare, but possible mid-session) can still throw here —
        // closing the flow rather than letting a SecurityException escape
        // uncaught, same defensive posture the old polling loop's own
        // `runCatching` around every fetch already had.
        runCatching { client.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
            .onFailure {
                close(it)
                return@callbackFlow
            }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    /** "Shared Location Reports" spec — the human-readable "Location" line
     * next to each record's raw coordinates, via Android's own on-device
     * [Geocoder] (no API key/new dependency, same "no new dependency"
     * philosophy this app already follows elsewhere). Returns null on any
     * failure (no network, no geocoder backend on this device, nothing
     * found) — callers show the coordinates alone in that case rather than
     * blocking on an address that may never resolve. */
    suspend fun reverseGeocode(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lng, 1) { addresses ->
                        cont.resume(addresses.firstOrNull()?.getAddressLine(0))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0)
            }
        }.getOrNull()
    }

    /** Structured counterpart to [reverseGeocode] — "automatic if the
     * publisher will capture the coordinates, the system will automatically
     * fill-up the City, Municipalities, Town and barangay." Same on-device
     * [Geocoder], just reading [android.location.Address.getSubLocality]/
     * `getLocality`/`getAdminArea` instead of the one formatted address
     * line. Returns null (not a [GeocodedAddress] with all-null fields) if
     * the geocoder has nothing at all, so callers can tell "no match" apart
     * from "matched, but couldn't identify any of the three levels." */
    suspend fun reverseGeocodeAddress(lat: Double, lng: Double): GeocodedAddress? {
        val onDevice = reverseGeocodeOnDevice(lat, lng)
        Log.d(TAG, "on-device geocoder -> $onDevice")
        // Phones without Google's geocoder backend (e.g. Huawei devices
        // running without full Google Mobile Services) report no geocoder at
        // all or always come back empty, and even where it works the barangay
        // is often missing. Fall back to OpenStreetMap's public Nominatim
        // service to fill whatever the device couldn't resolve.
        val online = reverseGeocodeOnline(lat, lng)
        Log.d(TAG, "online geocoder -> $online")
        if (online == null) return onDevice
        // Online values win: OSM's levels follow the Philippine hierarchy
        // (province/municipality/barangay), whereas a phone's own geocoder can
        // report a region ("Cagayan Valley") as the province and leave the
        // municipality empty. The on-device result only fills what's missing.
        return GeocodedAddress(
            barangay = online.barangay ?: onDevice?.barangay,
            cityMunicipality = online.cityMunicipality ?: onDevice?.cityMunicipality,
            province = online.province ?: onDevice?.province,
        )
    }

    private suspend fun reverseGeocodeOnDevice(lat: Double, lng: Double): GeocodedAddress? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The listener's onError does nothing by default, so a failed
                // lookup would otherwise never resume this coroutine.
                withTimeoutOrNull(8_000L) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(lat, lng, 1) { addresses -> cont.resume(addresses.firstOrNull()) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
            }
            address?.let {
                GeocodedAddress(barangay = it.subLocality, cityMunicipality = it.locality, province = it.adminArea)
            }
        }.onFailure { Log.w(TAG, "on-device geocoder failed", it) }.getOrNull()
    }

    /** OpenStreetMap Nominatim reverse lookup (no API key; its usage policy
     * asks for an identifying User-Agent and no more than one request per
     * second, which one lookup per coordinate capture stays well within).
     * In Philippine OSM data the province is `state`, the municipality/city
     * is `city`/`town`/`municipality`, and the barangay is one of `suburb`/
     * `village`/`quarter`/`neighbourhood`. Null on any failure. */
    private suspend fun reverseGeocodeOnline(lat: Double, lng: Double): GeocodedAddress? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18&accept-language=en&lat=$lat&lon=$lng")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "GoPreach/1.0 (Android; congregation ministry app)")
            }
            try {
                if (connection.responseCode != 200) {
                    Log.w(TAG, "Nominatim HTTP ${connection.responseCode}")
                    return@runCatching null
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val address = JsonParser.parseString(body).asJsonObject.getAsJsonObject("address") ?: return@runCatching null
                fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
                    address.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                }
                GeocodedAddress(
                    barangay = field("suburb", "village", "quarter", "neighbourhood", "hamlet", "city_district"),
                    cityMunicipality = field("city", "town", "municipality"),
                    province = field("state", "province", "county"),
                )
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "online geocoder failed", it) }.getOrNull()
    }

    /** Forward geocoding — "Find Location" spec: "the textbox will search a
     * coordinates or address, not just the latitude and longitude." Same
     * on-device [Geocoder] as [reverseGeocode] (no API key/new dependency),
     * just run the other direction. Returns the first/best match, or null on
     * any failure (no network, no geocoder backend on this device, nothing
     * found for that text) — the caller falls back to its own "couldn't find
     * that" error rather than assuming this always resolves. */
    suspend fun geocodeAddress(query: String): LatLng? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(query, 1) { addresses -> cont.resume(addresses.firstOrNull()) }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 1)?.firstOrNull()
            }
            address?.let { LatLng(it.latitude, it.longitude, accuracyMeters = null) }
        }.getOrNull()
    }
}
