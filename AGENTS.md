# RadioPoint agent instructions

Read README.md, HANDOFF.md, and docs/FIELD-TEST.md before changing this project.

## Scope and continuity
- Canonical repository: `Daleunhinged/Radio_Point`, branch `main`. This root is the Android project. Use this repository for future development; the old `dale` checkpoint branch is historical only.
- The user explicitly wants durable GitHub checkpoints so work can pass between agents when a usage limit interrupts development. Commit and push a coherent checkpoint after each completed milestone; update HANDOFF.md in the same commit. Do not wait until the entire project is finished.
- Record exact build/test results and unresolved limitations. A successful earlier APK does not prove that later source edits compile.
- Never claim hardware radio validation from a simulator or unit test.
- Preserve the field-test application ID so the user's original app is not replaced.

## Build and checks
- JDK 17 (full JDK including javac and jlink), Android SDK 34, build-tools 34.0.0.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
- Use a local untracked local.properties for sdk.dir. Do not commit machine SDK paths, proxy configuration, signing secrets, caches, or build outputs.
- Protocol changes need end-to-end PCM tests, not only bit injection. Maintain coordinate sign/range tests, CRC rejection, chunk-boundary tests, route-identity tests, and approaching/diverging/stopped prediction cases.
- Run hardware/emulator smoke checks for permissions, startup, ARM/DISARM, microphone failure, background/lock-screen behavior, and stale GPS before describing a build as ready for field use.

## Product invariants
- No invented roads, channel assignments, GPS fixes, or traffic-clearance claims.
- No sign-losing or wrapping coordinates. Protocol v2 is intentionally incompatible with earlier prototypes.
- Sending audio is not an acknowledgement of reception.
- Encounters are estimates from snapshots; nearby pullouts are separate, unverified suggestions. Do not snap an encounter onto a turnout and present it as a passing instruction.
- Audio stays in memory; no recording or location backend.
- Keep age and GPS accuracy visible. Directions are explicitly relative to the imported track.
