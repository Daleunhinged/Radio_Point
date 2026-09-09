# RadioPoint development handoff

Updated 2026-09-09. User: Dale. Goal: build a usable Android radio-position app from the RadioPoint project in Google Drive, and publish durable GitHub checkpoints so other agents can continue without the conversation.

## Start here

The canonical repository is `Daleunhinged/Radio_Point`, branch `main`. This repository root is the Android project: open it directly in Android Studio or run `./gradlew` here. Continue development and publish checkpoints here.

Migrated from `Daleunhinged/dale` branch `radiopoint-checkpoint`, source commit `126d5195b09c7db6bfc85ff4756418d00a1a0b51`. The old branch remains historical provenance; do not use it for ongoing development.

## Verified checkpoint

GitHub Actions run [34311106552](https://github.com/Daleunhinged/dale/actions/runs/34311106552) succeeded against commit `d319f0ffe4986189502580df1e50b8e84702c794` on 2026-09-09. This includes the map/marker caching and 12-hour service session cap.

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` succeeded on JDK 17, Android SDK 34.
- All 22 JVM tests passed, zero skipped/failures/errors: packet 4, codec 7, predictor 6, importer 5.
- Lint completed with zero errors; warnings remain for dependency/target updates, resources, styling, and localization. Consult the CI artifact's lint report.
- Exact CI APK SHA-256: `1850eea1547e7886c0368580e4781719fa22ee2b91a002fc5e8b61e6c2af6b5a`.
- CI artifact `radiopoint-d319f0ffe4986189502580df1e50b8e84702c794` includes APK, hash, test XML, and lint reports; expires after 30 days.
- PCM tests cover every 0–79 sample start offset, varied chunks including one sample, random packets, low-pass/attenuated/noisy audio, and noise-only rejection.

## Remaining work, in order

1. Run native Android smoke tests on an emulator or device. Local emulator lacked KVM and never produced a verified UI. A preliminary, **uncompiled and unrun** test draft is preserved in `docs/validation/drafts/RadioSmokeTest.kt`; to adopt it, move into `app/src/androidTest/java/com/radiopoint/app/`, add `androidTestImplementation("androidx.test:rules:1.5.0")`, and run connectedDebugAndroidTest on API 34. It is outside the source set to preserve the tested build.
2. Cover first-run unit entry; denied/approximate permissions; GPS; ARM/DISARM and notification stop; screen-off reception; transmit cooldown/speaker routing; import/restart persistence; font scaling/landscape. Test offline maps with actual archives.
3. Review stale position age across restarts/device clock changes: received age currently uses wall clock and clamps negative differences to zero. Check service lifetime/error paths and marker-cache changes on device.
4. Use two phones for acoustic loopback, then two authorized radios. Record measured decode rates, GPS performance and battery use in docs/FIELD-TEST.md. Real-radio operation is not validated; this is an experimental field-test build.
5. Checkpoint each completed milestone to this repository and update this handoff so another agent can resume.

## Source and design

Recovered from Google Drive's “Truck radio app” project. A later “radiopoint-android” folder contained only build files/README; its claimed `core` module was absent. The original full implementation used 1200-baud Bell 202 with unsafe truncated coordinates and synthetic road data. This implementation is protocol v2 with 600 baud, 1400/2300 Hz, full signed microdegree coordinates, 32-bit route fingerprint, GPS age/accuracy, and CRC-16. Both phones need v2.

Key files:
- data/TelemetryPacket.kt: 21-byte payload, validation, route hashing.
- dsp/RadioCodec.kt: frame/CRC and continuous-phase audio synthesis.
- dsp/AcousticAfskDemodulator.kt: eight-phase streaming correlator decoder.
- service/RadioService.kt: mic/GPS foreground service, session expiration, received positions.
- ui/MainActivity.kt: settings, imports, map, crew, KML export, encounter display.
- map/TrackImporter.kt: single-segment GPX/KML, geometry identity, BC zone-10 validation.
- gis/RoadPredictorEngine.kt: opposing-motion estimates with separate nearby-pullout suggestions.

## Build environment notes from prior session

JDK 17, AGP 8.3.2, Kotlin 1.9.23, Gradle 8.6, SDK/build-tools 34. Full JDK was required: the initial runtime had Java but no javac/jlink. The Maven AAPT2 binary produced an invalid compiled resource table in that environment; a clean rebuild using `-Pandroid.aapt2FromMavenOverride=<SDK>/build-tools/34.0.0/aapt2` succeeded. Only use that override if needed. A complete JDK's trust store needed the runtime's existing trusted CA store; certificate checks were not disabled. Environment proxies were per execution and must not be hard-coded or committed.

Temporary SDK/JDK downloads, Gradle caches, emulator images, and /tmp logs disappeared after the usage interruption. Workspace source, APK, and test XML survived. GitHub checkpoints are now the intended continuation source.

## GitHub checkpoint

Initial source checkpoint: `60ae95cd646f92b31d31c7d59e2439ce63a1b286` on `Daleunhinged/dale`, branch `radiopoint-checkpoint`. The RadioPoint-specific GitHub Actions workflow tests, builds, lints, and uploads an APK/report artifact on changes to this folder. Workflow results must be checked before claiming success. Artifacts expire after 30 days; source and handoff remain in Git.
