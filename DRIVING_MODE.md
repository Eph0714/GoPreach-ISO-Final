# Driving Mode (Territory Map)

A navigation aid only. It never changes territory boundaries, assignments, statuses or field service records, and it does not give turn-by-turn or voice directions.

## What it does
- **Drive** button in the Territory Map tool column (map view only). Starting it asks for foreground location permission if needed; if location services are off, the existing "turn on location" help is shown. It cannot start while Draw Mode is on, and starting Draw Mode or leaving the map ends it.
- The arrow follows the live GPS position and points in the **GPS direction of travel** (course over ground), not the compass. The arrow is drawn in true-north degrees, so it stays correct when the camera is rotated.
- The camera eases to the latest accepted position, with the arrow in the lower centre. A zoom outside street level (15.5-18.5) is eased to 17; otherwise the zoom is left as it is.
- **Heading up / North up** toggle. **Following / Recenter**: moving the map by hand pauses following; Recenter resumes at once, and following resumes by itself with the next GPS update after 8 s of leaving the map alone.
- **GPS status** button: "GPS ±Nm", "Finding", "Weak GPS" or "No GPS". Tap it for a plain sentence (weak signal, stale signal, offline). The arrow is faded until a direction of travel is known.
- Turning it off clears the padding and returns the map to north-up and normal behaviour.

## How the GPS is filtered (`domain/navigation/DrivingTracker.kt`, shared by Android and iOS)
- Ignored: invalid coordinates, fixes older than 15 s when they arrive, fixes older than one already used, accuracy worse than 60 m (the arrow neither moves nor turns), and impossible jumps (over 216 km/h). Three agreeing jumps in a row are taken as a real move.
- The course is updated only at 2 m/s (7 km/h) or faster with accuracy of 35 m or better. It uses the GPS bearing, or the direction between positions at least 7 m apart. It is smoothed (2.5° dead band, shortest-way turns, 0-360 normalised). A near-reversal at low speed needs a second agreeing reading.
- Slow or stopped: the last reliable course is kept; the arrow never spins.
- No fix for 8 s: the status is "No GPS" and the arrow is labelled as the last known position. A stale position is never shown as live.

## GPS stream
No second stream is opened. The Territory Map's existing location flow now also carries speed, GPS bearing and fix time, and asks for fixes every 1 s while Driving Mode is on (3 s otherwise). It still stops with the screen, and the screen is kept awake only while Driving Mode is on.

## Testing done
- `DrivingTrackerTest` (12 tests): **simulated routes only**, covering a turn, a derived course, stationary jitter, poor accuracy, jumps, invalid and old fixes, stale signal, U-turns, angle wrap-around and map-relative rotation.
- On a real phone (Android, stationary or moving slowly, **not** a real drive): Drive on/off, the permission-granted path, zoom and lower-centre placement, GPS status, pan pausing, Recenter, and return to normal behaviour.

## Not verified
- A real drive: course accuracy at speed, camera rotation while turning, and a real signal loss.
- Permission-denied and location-services-off paths on a device.
- iOS: the territory map screen is Android-only in this repository and there is no iOS app project or Info.plist here, so no iOS UI exists. The shared tracker is common code, and the iOS location tracker now fills in speed, course and time (uncompiled here). An iOS app needs `NSLocationWhenInUseUsageDescription`; background location is not used.

# In-app turn-by-turn guidance

Starts from a record ("Navigate here (turn-by-turn)" in the record sheet) or from a long-press on the map. It turns Driving Mode on if it is off, fetches a driving route from the current GPS position, draws it, shows the next manoeuvre and speaks each instruction. Ending it (X) or turning Driving Mode off removes the route and silences the voice. It is an aid only: it never edits territories, assignments, statuses or reports, and the panel always says "Follow road signs and traffic rules."

- **Routing:** `ValhallaRoutingService` calls a Valhalla server (`POST /route`, car, English, kilometres) and reads the route line, each manoeuvre and Valhalla's own spoken texts. The default is the public FOSSGIS instance `https://valhalla1.openstreetmap.de`: no key and no cost, but a shared, fair-use community service with **no availability guarantee, and I did not find or verify published usage terms**. For production, run your own Valhalla or use a hosted provider and pass its URL to `ValhallaRoutingService(baseUrl = ...)` in `AppModule.kt`. Each route request sends the start and destination coordinates to that server; nothing is stored by GoPreach.
- **Following (`NavigationEngine`, shared):** it matches each filtered fix to the route (progress never goes backwards), works out the step, the distance to the next turn, the distance and time left, and arrival (within 25 m). It leaves the route only after 3 fixes in a row more than max(35 m, 1.5 x accuracy) away, or one fix over 150 m away.
- **Voice (`VoiceGuide`):** Android text-to-speech, tagged as navigation guidance, other audio is lowered while it speaks, a newer instruction replaces an unspoken one, an offline voice is preferred, and there is a mute button. Each turn is announced ahead of time ("In 250 meters, bear left..."), again close to the turn, and the distance after it on long stretches; the distance thresholds grow with speed. If the phone has no speech voice, the panel says so and the instructions stay on screen.
- **Rerouting:** when off the route and online, a new route is requested (at most every 10 s) and "Rerouting." is spoken. Offline, the driver is told a new route needs a connection and the old route stays on screen. A route needs an internet connection to be found; once found, voice and following continue without it.

## Testing
- `NavigationTest` (8 tests) parses a **real** Valhalla answer (Solano, 1.9 km, 7 steps) and **simulates driving it**: steps advance in order, turns are announced ahead and near, arrival is detected, remaining distance never grows, one stray fix does not reroute but three do, a poor GPS accuracy widens the tolerance, and routing HTTP errors become specific messages.
- On the phone: the long-press and record entry points, a live route request, a 6.4 km / 20 min route drawn with "100 m - Turn right to stay on Ballesteros Street" on the banner, and ending guidance. The phone was not driven along the route, so following, voice at speed, off-route detection and rerouting were **not** tested live, and I could not listen to the speech in this session.
- Not done: iOS (no iOS app project in this repository; the route and engine code is shared and platform-neutral, but the voice, banner and map layer are Android-only), lane guidance, speed limits, avoiding tolls, multiple stops.

# Voice switch and Street View

- **Silent / Voice:** while Driving Mode is on, a Voice button in the tool column switches spoken guidance on or silent (instructions still show on screen). It is the same switch as the speaker button on the guidance panel, it silences anything being spoken at once, and the choice is remembered.
- **Street View:** the Street View button opens a full-screen Street View inside the app and nothing else. It first checks for street photos within about 60 m; if there are none it says "No Street View here" and shows no map. Closing it (arrow or Back) returns straight to the record sheet or the long-press choices. It uses Mapillary's free street photos and needs a free **Client Token** from mapillary.com/dashboard/developers, put in `local.properties` as `mapillaryToken=MLY|...` (the app already reads that line); coverage depends on what Mapillary contributors have photographed, which is thinner than Google's. **Without a token** the button falls back to opening Google Maps Street View in the Google Maps app, where Back goes through the Maps app first. I could not test the in-app viewer because no token is configured here.

## Street View sources (updated)
Order: **Google** (only when `googleMapsApiKey=...` is in `local.properties`; Street View Static API + Maps Embed API enabled in Google Cloud) - checked first with Google's free availability check (nearest outdoor photo within 60 m), then **Mapillary** (`mapillaryToken=...`, search within about 150 m). If neither has a photo the screen says "No Street View here" and never shows a map. With neither configured the button falls back to the Google Maps app. Tested here with a Mapillary token only: for the record at 16.5227, 121.1864 (Solano) Mapillary has no photos within 150 m (nearest about 1 km away, from 2016), and the Google Maps app, given the same point, showed a plain map rather than street imagery. The Google in-app path is built but untested (no Google key here).
