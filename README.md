# RadioPoint 2 · field-test build

An Android app for exchanging GPS positions through the voice radios your crew already carries. Hold the radio's PTT, speak the normal call, then tap **CHIRP LOCATION** with the phone speaker near the radio microphone. An armed phone near the receiving radio decodes the tones and places the sender on its map.

**Experimental: no real-radio validation has been completed for this build.** It is a position-reporting aid, not a traffic-clearance, collision-avoidance, or passing authorization system. An empty map does not establish that a road is clear.

## Install and try it

Install `RadioPoint-2.0-fieldtest.apk` on both phones. This test app has a separate package ID (`com.radiopoint.app.fieldtest`) and can coexist with the original RadioPoint APK.

1. Assign different unit numbers (1–255) using the upper-right **UNIT** button.
2. Tap **ARM** and grant precise location, microphone, and notification permissions. Wait for a fresh GPS fix. Sending requires a fix no older than 30 seconds and reported accuracy within 100 m.
3. For a phone-to-phone bench check, arm the receiving phone and tap CHIRP on the sending phone. Use normal media volume, then adjust as needed. This checks acoustic operation without a radio.
4. For a radio test, use an authorized channel and check the radios' programming with the radio administrator first. Hold PTT, make the normal voice call, then play the chirp into the microphone. Keep PTT pressed until the chirp ends (about 0.85 seconds).
5. Check the receiving phone's crew list: unit number, coordinates, accuracy, and age. The sender's “sent” message confirms audio playback, not reception. Confirm receipt by voice.

See [FIELD-TEST.md](docs/FIELD-TEST.md) for a practical test record.

## Find crew and the truck on foot

Tap **TRUCK MODE · SWITCH TO ON FOOT**. At the truck, tap **SAVE TRUCK** and
confirm its parking location with a fresh GPS fix. **FIND TRUCK** selects that
saved location; **FIND CREW** (or CREW) selects a received unit. The target panel
updates distance and true-north bearing once per second and shows report age
and GPS accuracy. Tap the panel to frame you and the target on the map.
No road import, cell service, or matching basemap is needed for these calculations.
Download an offline basemap in advance if you want map detail without connectivity.

Each phone must save its own truck marker before walking away. The marker
persists across restarts, records its save time, and does not follow or broadcast
the truck. If someone remains with a moving truck, select that person's unit
and ask them to send a new chirp. Crew targets update only on received reports;
after 30 seconds they are labelled stale, and after 30 minutes guidance stops.
Distance/bearing are to the last reported coordinates, not live tracking.

Bearings are degrees from true north, not an arrow relative to the phone or a
magnetic compass. Guidance is a straight line, not a traversable walking route.
Bearing is withheld when the distance is within combined reported GPS accuracy;
this is not proof of arrival. Your own stale/inaccurate GPS suppresses guidance.

On-foot mode suppresses local vehicle encounter estimates and sends existing
v2 PARKED/unknown direction with road code 0, preserving coordinate reception
by older v2 phones. The existing vehicle-status field is retained; older phones
do not show an ON FOOT label. Returning to truck mode resets direction to
PARKED/unknown: select UP or DOWN explicitly. No audio/protocol framing changes.
Radio PTT is still manual. Real Icom radio-link testing remains pending.

## Controls

- **ARM / DISARM:** Opens or closes the microphone. The default session lasts five minutes and extends after each received position. Enable continuous mode in crew setup for a session up to 12 hours; stop it with DISARM or the notification action.
- **UNIT:** Persistent crew identity, listening mode, and optional spoken encounter estimates while the app is open.
- **MAPS:** Import a raster `.mbtiles`, `.sqlite`, or supported tile `.zip` archive, import a road track, or switch back to online/cached OpenStreetMap. No offline basemap is bundled.
- **Road name:** Select an imported road. Install the same named, ordered track geometry on both phones. Route km measures distance from its first point; it is not automatically calibrated to posted road kilometer signs.
- **Status:** Empty, loaded, pickup, or hazard.
- **Direction:** Explicitly select UP toward the end of the imported track, DOWN toward its start, or PARKED/unknown. Road changes reset direction to PARKED.
- **+ PULLOUT:** Records your current location against the selected real track, within a 150 m corridor. Points are saved on the device. They are recorded locations, not verified passing areas.
- **CREW:** Shows received coordinates, age, and accuracy, including reports on other routes. Export historical crew positions as KML for Avenza or GIS tools.

The microphone runs in a foreground service when armed, including with the screen off. A notification and bounded wake lock keep its operation visible. Audio is processed in memory, never recorded to a file. GPS runs while the screen is open with location permission, or while the service is armed. Disarming and leaving the app stops these updates.

## What changed from the recovered prototype

- Replaced 12-bit unsigned coordinate offsets that wrapped every 4,096 m with full signed latitude/longitude at one-millionth-degree resolution.
- Introduced a versioned payload, CRC-16, route fingerprint, GPS accuracy, fix age, and sequence byte.
- Replaced the decoder's buffer-local tone windows with streaming correlators and eight symbol phases.
- Prevented local transmission from being decoded back into the receiving pipeline; prefer and check the phone's built-in speaker.
- Added explicit unit setup, background listening, persistent received positions, and age indicators.
- Removed fabricated real-road geometry and channel assignments. The bundled corridor is explicitly a sample; import a real track for road calculations.
- Reworked encounter estimates: only opposing moving traffic, fresh reports, compatible road geometry, and nearby GPS points qualify. No invented minimum speed for stopped vehicles. A nearby recorded pullout is shown separately and does not relocate the computed encounter point.
- GPX/KML imports now reject disconnected/multiple lines, retain their identity across restart, and leave the radio channel unverified.
- Added real waveform tests; the old DSP test fed decoded bits directly and did not test the acoustic path.

## Protocol v2

Incompatible with both the original 1200-baud prototype and the separate compact v0.1 design in Drive. All participating phones need this build.

600 baud continuous-phase FSK: 1400 Hz mark, 2300 Hz space, 48 kHz signed 16-bit PCM. MSB-first bits. Each frame is:

| Part | Length |
|---|---:|
| Alternating `AA AA AA AA` preamble | 32 bits |
| `2D D4` synchronization | 16 bits |
| Version 2 payload | 168 bits |
| CRC-16/CCITT-FALSE over payload | 16 bits |

232 bits per frame, 386.7 ms, followed by 40 ms silence, repeated twice: 853.3 ms total.

Payload (network byte order): version u8, unit u8, status/direction nibbles u8, route fingerprint u32, latitude microdegrees i32, longitude microdegrees i32, speed km/h u8, heading turns/256 u8, accuracy meters u16, fix age seconds u8, sequence u8. CRC initial value FFFF, polynomial 1021, no reflection, no final XOR. CRC provides error detection, not authentication or encryption.

## Build

JDK 17, Android SDK 34 / build-tools 34.0.0, Gradle 8.6, AGP 8.3.2, Kotlin 1.9.23. Android 8+ (API 26); target API 34. Dependency versions are pinned in the Gradle files.

Open this folder in Android Studio, set the SDK path in an untracked `local.properties`, and run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`.

Output: `app/build/outputs/apk/debug/app-debug.apk`. Debug builds use the separate field-test application ID. The APK uses a development signing key, not a production/distribution key; a build from another machine may require reinstalling the test app.

## Practical limits

- Real-radio reliability, full-shift battery use, manufacturer background restrictions, and performance under canopy are unmeasured.
- Road distance and encounter calculations currently support BC's UTM zone 10 longitude range (-126° to -120°). Full latitude/longitude reports themselves do not depend on a shared UTM zone.
- Import one continuous GPX track segment/route or one KML LineString at a time, up to 10 MB / 100,000 points. Names containing “pullout”, “turnout”, or “passing” are eligible waypoint candidates within 150 m of the route.
- Offline map import supports raster archives recognized by osmdroid; vector MBTiles are not rendered. Archive limit is 1 GB.
- Received markers fade after 30 seconds and disappear from the map after 30 minutes. Positions are snapshots, with no dead reckoning. Encounter estimates assume unchanged speed and direction and can be inaccurate even with recent data.
- CRC and route fingerprints do not establish identity or authority. Unit conflicts can be flagged but unit IDs are not globally unique or authenticated.
- Online basemaps make tile requests to OpenStreetMap. No app analytics, account, or location-sharing backend is included.

Android foreground-service implementation follows the [Android service-type documentation](https://developer.android.com/develop/background-work/services/fgs/service-types); speaker routing uses [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack). Those platform requirements do not establish radio-link performance.
