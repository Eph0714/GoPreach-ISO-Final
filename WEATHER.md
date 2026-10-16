# Weather module

Open to every signed-in role (publisher, admin roles, Circuit Overseer). It reads only public weather data. It never reads or writes congregation, publisher or report records, and it does not touch the sync queue.

## Where it is
- Side menu: Weather (Tools group for admin roles; publishers get the module tile).
- Dashboard: a Quick Access card for admin roles; for publishers the Weather tile starts on the Main Form (it can be moved like any other tile).
- Route `Destinations.WEATHER`; code in `ui/screens/weather/WeatherScreen.kt` (app) and `WeatherViewModel.kt` (shared).

## Data source and terms (checked 2026-10-15)
- **Open-Meteo** forecast API and geocoding API, with no API key. The free tier is for **non-commercial** use only (600 calls/min, 5,000/hour, 10,000/day). Data is CC BY 4.0, so the screen shows "Weather data by Open-Meteo.com (CC BY 4.0)".
- If GoPreach is ever run commercially (ads or subscriptions), a paid Open-Meteo plan is required. Set the new base URLs in `OpenMeteoWeatherService` and pass the key; none is in the source.
- Times are requested as unix time, and the place's own UTC offset is used for display. Units are °C, km/h and mm.

## Official warnings (PAGASA)
Warnings come from PAGASA's public CAP feed (`publicalert.pagasa.dost.gov.ph/feeds/`, an Atom feed of CAP documents). The feed states "Licensed under CC BY 4.0", so the screen shows "Official warnings by PAGASA-DOST (CC BY 4.0)". Checked 2026-10-16.
- `PagasaAlertService` downloads the feed and every entry updated in the last 48 hours (6 at a time), keeps the result for 5 minutes (30 seconds minimum between forced refreshes), and drops expired, cancelled and non-"Actual" alerts.
- A place gets an alert only when it lies inside one of the alert's area polygons (`CapParser.inside`, matched on the device; no coordinates are sent). Most severe first.
- Warnings load separately from the forecast and are never cached. If they cannot be loaded (offline, error), the screen says so and links to PAGASA; it never implies that no warning means no hazard. Outside the Philippines it says PAGASA covers the Philippines only.
- Today the feed mostly carries General Flood Advisories per region; Tropical Cyclone Wind Signals and rainfall warnings appear only if PAGASA publishes them to this feed. The PAGASA advisory and bulletin links stay on screen.

## Offline and cache
- The last forecast of up to 8 places is stored on the device, one store per signed-in person, and is dropped after 24 hours. It is shown labelled "Saved forecast from <time> ... may be out of date".
- A forecast under 10 minutes old is not re-requested. Manual refresh of the same place within 30 seconds is declined. A retry runs when the connection returns.
- Saved places and the last selected place are stored the same way. Nothing about the user's location is sent to the GoPreach server.
- The location permission is requested only when "Use my current location" is tapped. If it is denied, search and saved places still work.

## Territory weather
The list holds territories from the user's own congregation (Super Admin: all), taken from the already-synced territory drawings. Only the territory name and its centre point are used.

## Tests
`WeatherTest` and `OfficialAlertTest` (shared) cover CAP parsing, polygon matching, feed parsing, WMO mapping, local time, advisory rules, response parsing, malformed responses, HTTP errors and the request parameters.
