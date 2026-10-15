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
PAGASA publishes a CAP feed (`publicalert.pagasa.dost.gov.ph/feeds/`) and RSS feeds. I found no published terms of use for them, and matching a CAP area to a coordinate needs polygon handling. **No warning feed is wired in**, so the app shows an explicit "No verified warning data is available" notice and links to the PAGASA advisory and bulletin pages. It never implies that no alert means no hazard. Adding the CAP feed later needs a terms check and a parser; the section is already separate from the forecast.

## Offline and cache
- The last forecast of up to 8 places is stored on the device, one store per signed-in person, and is dropped after 24 hours. It is shown labelled "Saved forecast from <time> ... may be out of date".
- A forecast under 10 minutes old is not re-requested. Manual refresh of the same place within 30 seconds is declined. A retry runs when the connection returns.
- Saved places and the last selected place are stored the same way. Nothing about the user's location is sent to the GoPreach server.
- The location permission is requested only when "Use my current location" is tapped. If it is denied, search and saved places still work.

## Territory weather
The list holds territories from the user's own congregation (Super Admin: all), taken from the already-synced territory drawings. Only the territory name and its centre point are used.

## Tests
`WeatherTest` (shared) covers WMO mapping, local time, advisory rules, response parsing, malformed responses, HTTP errors and the request parameters.
