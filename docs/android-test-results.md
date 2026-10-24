# Android device verification — 2026-10-23

## Automated verification

- Backend: 130 tests pass (`python -m uv run --python 3.12 pytest -W error -q`).
- Android JVM suite: 81 tests pass. Debug/test APK builds pass.
- Emulator instrumentation: all 11 tests pass (explicit ADB target, including WebView).
- Android lint: no errors; warnings include deliberately pinned dependencies/target,
  optional battery-exemption request, JavaScript for local orb assets, localization,
  application icon, and explicit Android 12+ backup rules (follow-up hardening).
- Emulator: `Medium_Phone_API_37.0`, API 37. The packaged-orb WebView check passed
  after a fresh start with 2 GB guest RAM and stopping idle Gradle daemons.
- Shared orb: browser demo and embedded mode rendered with no console warnings/errors.
- Backend Task 8b deployed as the backend-only patch from `4b13f8c`; gateway container
  rebuilt, `/health` returned `{"ok":true}`, and the running image exposes the
  `Hello.client_vad` field / Session argument.
- Emulator screenshot: [orb](images/android-orb-emulator.png). The offline notice is
  expected: instrumented tests use invalid credentials and a loopback endpoint.

## Physical phone (Task 10 in progress)

Device reports model `SM-S942B`, Android 17. USB debugging authorized. Debug APK
installed successfully and MainActivity opened. No real credentials were read,
printed, transferred through commands, or captured in screenshots.

| Check | Result |
|---|---|
| APK install and launch | PASS |
| Golden orb visible | PASS — user confirmed |
| Device token / Picovoice key setup | Superseded: user requested replacing Picovoice |
| Microphone / notification permissions | Pending confirmation |
| Wake word with Portuguese question | Not yet tested |
| Wake word with English question | Not yet tested |
| Follow-up without another wake | Not yet tested |
| Interrupt by wake word | Not yet tested |
| Interrupt by orb tap | Not yet tested |
| Speech interruption on loudspeaker | Not yet tested |
| Screen off for 10 minutes, then wake | Not yet tested |
| Incoming call pauses and resumes | Not yet tested |
| Airplane mode and reconnect | Not yet tested |
| Swipe away activity; service survives | Not yet tested |
| First-audio latency versus ~2.6 s backend baseline | Not yet measured |
| WAITING, screen off, one-hour battery drain (<5%/h target) | Not yet measured |

Do not mark Task 10 complete until the manual checks and battery measurement are
recorded. Install the final APK after setup is saved; avoid replacing it while the
user is entering secrets, since installation can restart the app.

Scope update: the user requested an account-free wake engine, a complete Android UI
redesign, live camera vision, and Hermes's own web configuration interface. Full voice
and battery verification will run against that updated build rather than continuing
Picovoice setup.

Offline wake extension: 83 JVM tests and all 11 emulator tests pass, including bundled
model silence rejection and detection of “Jarvis” in a locally generated speech fixture.
No real-person wake/noise or battery result is implied. Backend remains 130 passing.

Live-vision/UI extension checkpoint (2026-10-23 local): 88 JVM tests, 15 emulator
tests, 147 backend tests pass; both APKs build and lint has no errors. Emulator
checks include CameraX preview, tab/background camera clearing, upright bounded
JPEG encoding, pause across recreation, protected token form, and packaged orb.
Screenshots were visually reviewed under `docs/images/`.

VPS deployed backend/personas: health passes; fast and deep Hermes both identified
a synthetic red JPEG. A separate public WSS probe authenticated with a server-side
credential (never printed), enabled camera, sent the artificial frame, asked its
color, received a correct answer and spoken PCM, then disabled camera and closed.
Native Hermes dashboard remains active on loopback 9119 through an SSH tunnel.

2026-10-24: updated APK installed on the reconnected physical phone; MainActivity
opened, user confirmed Settings/setup and entered a device token. User then reported
Connecting. Phone DNS/reachability and public gateway health pass. Inspection of
boolean connection flags confirms a terminal authentication rejection (4401),
without reading/displaying the token. The header incorrectly presented that as
Connecting; a regression fix distinguishes rejection from an active retry. User
will replace the token directly on the phone. No server token was rotated.
The status-label fix passes 90 JVM / 15 emulator / 147 backend tests, APK builds
and lint. Server-side comparisons show the saved Android token, deploy env and
running gateway agree (match booleans only; credentials never printed).

These are automated/emulator/server and installation results.
Live real-scene answers, accent/noise, camera switching/orientation on the phone,
voice latency, calls, screen-off wake, and battery drain remain pending.

## Windows commands

See [per-task commands, files, and phone checks](android-task-verification.md).
With both devices connected, use the explicit emulator ADB commands in that document
for instrumentation. Do not run the automated suite over an active personal-phone
setup session.

Install on the physical phone (Git Bash, from repo root; choose its serial from
`adb devices`):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest assembleDebug
adb -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PHONE_SERIAL shell am start -n com.openclawchip.jarvis/.MainActivity
```

For the battery check, after setup and voice checks pass: record the initial battery
percentage, put Jarvis in WAITING, reset its measurement window with
`adb -s PHONE_SERIAL shell dumpsys batterystats --reset`, turn the screen off for one
hour, then inspect `adb -s PHONE_SERIAL shell dumpsys batterystats` and the final
battery percentage. Record actual elapsed time and drain; do not estimate results.
