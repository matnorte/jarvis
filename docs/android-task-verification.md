# Android task verification

Run commands from the repository root in **Windows Git Bash**. Start the Android emulator first.
Never enter real credentials in tests, commands, logs, or this document. Enter them only on the phone.
The numbered Task 6–9 entries document the original implementation. Picovoice-specific
setup is superseded by the offline-wake extension below.

## Task 6 review fixes

Files: `audio/AudioIn.kt`, `audio/AudioOut.kt`, `audio/PorcupineWakeWord.kt`,
`audio/SileroVad.kt`, JVM `audio/AudioInTest.kt`, instrumented `audio/AudioAdaptersSmokeTest.kt`.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest connectedDebugAndroidTest assembleDebug
```

Tests cover microphone death, non-recording start, callback failure, intentional stop,
restarting after cleanup, native playback after flushing a two-second reply at 100 ms,
and a 100 ms reply starting and draining. The stale-write regression did not reproduce
on the emulator before the fix; the short-reply regression did fail before the fix.

Phone checks after service/UI wiring: interrupt a long spoken reply; old audio should
stop immediately. Short answers should play. Take a call or use another recording app;
Jarvis should pause visibly and recover after the microphone becomes available.

## Task 7 settings and onboarding

Files: `settings/SecretBox.kt`, `settings/Settings.kt`, `ui/SettingsActivity.kt`,
`ui/Onboarding.kt`, manifest permissions/activity; JVM `settings/SettingsTest.kt`,
instrumented `settings/SecretBoxTest.kt`.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest connectedDebugAndroidTest assembleDebug
```

Phone checks (once Task 9 connects the main screen): complete setup, save credentials,
reopen Settings and verify only “••• saved” appears. Replace a secret without exposing
the old value. Reject `ws://` URLs. Grant microphone/notification permissions and
optionally allow unrestricted battery use. Saved credentials must survive app restart.

## Task 8 foreground service

Files: `service/JarvisService.kt`, `service/BootReceiver.kt`, manifest; focus callback
in `audio/AudioOut.kt`, silenced-capture detection in `audio/AudioIn.kt`, instrumented
`service/ServiceSmokeTest.kt`.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest connectedDebugAndroidTest assembleDebug
```

Phone checks: notification contains Talk and Stop; Stop releases the microphone;
tap works with an unavailable wake-word engine; missing credentials request setup.
Take a call/use another recorder and verify PAUSED and recovery in ~5 seconds after
the mic becomes available. Screen-off wake and swipe-away service survival are Task 10.

Platform adjustment: `startOnBoot` posts “Tap to start Jarvis after reboot” rather
than starting capture from the boot receiver. Android while-in-use microphone and
foreground-service restrictions require a user-visible launch. No wire/turn change.

## Task 8b client-owned VAD

Files: `server/protocol.py`, `server/session.py`, `server/app.py`; corresponding
`tests/test_protocol.py`, `tests/test_session.py`, `tests/test_app.py`; Android
`protocol/Protocol.kt`, `ProtocolTest.kt`, `GatewayClientTest.kt`.

```bash
python -m uv run --python 3.12 pytest -W error -q
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest connectedDebugAndroidTest assembleDebug
```

Wire addition authorized by HANDOFF §6: Android sends `"client_vad":true` in hello.
Omitted/false preserves the old server endpointer; true waits for explicit
`end_utterance`, retaining the 30-second server cap. Authentication forwards the
flag only into its own connection's session. No Conversation semantics change.

Phone check after backend deployment: ask a sentence with a pause near the configured
silence threshold; the phone's setting controls turn completion, with no server race.

Deployment: the backend portion of commit `4b13f8c` was applied as a checked patch to
the clean VPS `main` checkout and the gateway container rebuilt/restarted. `/health`
returned `{"ok":true}` afterward. The VPS has these six backend/test files modified
locally until the branch is merged; do not blindly pull/reset over that patch.
No environment files or credentials were read, copied or modified.

## Task 9 orb UI

Files: shared `ui/orb/orb.html` (moved from `prototype/`), vendored scripts/license
and SHA-256 sources; Gradle asset sync and ignore rules; `MainActivity.kt`,
`ui/OrbBridge.kt`; JVM `OrbBridgeTest.kt`; instrumented `ui/OrbWebViewTest.kt` and
settings-preservation fixture. `Conversation.kt`/`ConversationTest.kt` also cover the
deferred late-`thinking` drain bug: a finished turn enters follow-up once playback
drains even if a late state message changed the displayed phase back to THINKING.
This preserves the existing drain-based follow-up semantics. Captions retain the
previous transcript until a new final transcript arrives, as before.
`audio/FrameInbox.kt`/`FrameInboxTest.kt` bound mic backlog to eight recent frames
(256 ms) with capture generation preserved. The service yields between frames so
control/socket events remain responsive when capture outruns processing.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest connectedDebugAndroidTest assembleDebug lintDebug
```

The desktop browser rendered the vendored orb with no console warnings/errors;
embedded mode hid the demo label/buttons. Responsive camera distance keeps the
whole sphere visible on narrow displays. WebView uses bundled assets with network
loads and general file/content access disabled. The bridge batches updates to
one evaluation every 34 ms. Instrumented tests preserve pre-existing encrypted
settings; prefer running the automated suite on the emulator.

Phone checks: golden orb renders offline; captions/status update during a turn;
orb tap and notification Talk start/interrupt; gear opens Settings; saving settings
reloads the service; Stop releases capture and Start restarts it. Switch away/back
and confirm rendering pauses/resumes while the foreground service keeps running.

When both phone and emulator are connected, target instrumentation explicitly instead
of running it against both devices:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r com.openclawchip.jarvis.test/androidx.test.runner.AndroidJUnitRunner
```

If Windows is low on RAM, use `--max-workers=2`,
`'-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8'` and
`-Pkotlin.compiler.execution.strategy=in-process`, then `./gradlew --stop` before
instrumentation. A fresh headless AVD with 2 GB RAM resolved a stalled WebView test;
the orb then passed its JavaScript/console checks and a screenshot was captured in
`docs/images/android-orb-emulator.png`.

## Extension 1: account-free offline wake

Files: `audio/SherpaWakeWord.kt` replaces `PorcupineWakeWord.kt`; `WakeWord.kt`,
`settings/Settings.kt`, `SettingsActivity.kt`, `JarvisService.kt`; Gradle verified
model/runtime download and ABI packaging; `assets/wake/keywords.txt`; JVM settings
migration and adapter lifecycle tests; native silence/synthetic-JARVIS test;
service/WebView smoke fixtures; generated test speech and `android/WAKE_ASSETS.md`.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

Then run the explicitly targeted emulator instrumentation commands above. The first
build downloads pinned public runtime/model artifacts with SHA-256 checks. Only
the device token is required in settings; an old encrypted Picovoice value is removed.

Phone checks: setup has no Picovoice field/account link; token survives upgrading;
say “Jarvis” with a Portuguese and English question, adjust wake sensitivity if
needed, test false triggers during reply playback and wake while screen off. The
real accent/noise/battery checks remain manual even when the synthetic wake test passes.

## Extension 2: live-vision backend

Files: `server/vision.py`, `protocol.py`, `session.py`, `router.py`, `app.py`, Pillow
dependency/lock, both personas; frame-validation, routing/history, protocol,
authenticated WebSocket, Hermes payload, and spoken-turn tests. Wire details and
rationale are in `docs/vision-protocol.md`.

```bash
python -m uv run --python 3.12 pytest -W error -q
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

Run the emulator instrumentation commands above as well. Phone checks become
available after the Android camera UI is installed: start camera, point at text or
an object, ask about the current view, switch cameras, stop camera and verify later
questions carry no image; background/reconnect must not reuse an old frame.

## Extension 3: live Android camera and complete UI

Files changed: `MainActivity.kt`, `ui/SettingsActivity.kt`, new `ui/UiKit.kt`,
`service/JarvisService.kt`, `net/GatewayClient.kt`, `protocol/Protocol.kt`,
new `vision/{CameraStream,CameraFrameEncoder,CameraFramePolicy,LiveVision}.kt`,
manifest camera permission/optional hardware, stable view IDs, CameraX/Activity
dependencies, embedded `ui/orb/orb.html` palette, and JVM/instrumented tests.
The native UI uses Assistant/Vision/Settings tabs; setup requires only the existing
Jarvis device token. No Picovoice account or wake-engine key is needed.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
./gradlew --stop
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r com.openclawchip.jarvis.test/androidx.test.runner.AndroidJUnitRunner
```

Expected current results: 88 JVM tests, **OK (15 tests)** from instrumentation,
and no lint errors. ADB can return exit code zero on an instrumented process crash;
check the final OK/test count. Backend regression from repo root:

```bash
python -m uv run --python 3.12 pytest -W error -q
```

Phone checks:

1. Settings shows the saved token as a protected placeholder, no Picovoice field.
   Save & connect, check Connected, and ask “Jarvis, que horas são?”.
2. Pause, reopen/rotate the app: it stays paused. Start or Talk resumes it.
3. Open Vision: camera is off until Start camera. Allow the permission; point at
   a book/object, wait for the LIVE indicator, then ask what Jarvis sees.
4. Flip and verify front/back orientation, readable text, and correct answers for
   a changed scene. Live frames do not continuously invoke the model; questions do.
5. Stop camera, leave Vision, lock/background the app, or open Settings: camera
   access and server image context stop. Reopening requires Start camera again.
6. Disconnect/reconnect networking with preview open: show waiting status; after
   reconnect only new frames should become live. Never answer from an expired frame.
7. Continue all Task 10 wake/follow-up/barge-in/call/screen-off/battery checks.

Design QA captures: `docs/images/android-assistant-emulator.png`,
`android-vision-emulator.png`, `android-settings-emulator.png`. Emulator screenshots
use fake local credentials and a virtual camera; Connecting is expected there.
Settings QA draws the synthetic fixture's native layout; production retains
`FLAG_SECURE` and never pre-fills a saved token.

## Extension 4: deployed vision and native Hermes configuration

Files: `deploy/hermes-dashboard.service`, `docs/hermes-dashboard.md`, updated
`persona/SOUL.md` / `SOUL.deep.md`, tested backend/dependency patch above.
Hermes's own dashboard is running, reachable through the documented SSH tunnel.
Both profile image probes and an authenticated public-WSS frame → answer → PCM
probe passed using an artificial solid-color image. No env files were overwritten.
The VPS checkout retains both deployed patches; reconcile them before an update.

Phone/web checks: open the dashboard through the tunnel, select fast/deep, edit and
save a nonsecret preference, then start a new conversation to verify it. If the
native dashboard requires a gateway restart, use its restart control and confirm
the phone reconnects. Put provider keys only in Hermes's protected configuration
or server env files; put the device token only in the phone's encrypted settings.

## Phone follow-up: terminal connection status (2026-10-24)

Files: `ui/ConnectionLabel.kt`, `MainActivity.kt`, `ConnectionLabelTest.kt` and
verification/handoff records. A rejected token (4401) now shows **Token rejected ·
Open Settings**, and replacement by another connection (4409) shows **Connected
elsewhere**. Neither claims an ongoing retry. Wire/auth/Conversation behavior is
unchanged. Run the Extension 3 Windows commands above (90 JVM / 15 emulator tests).

Phone check: a rejected token gives the actionable header. Replace it in Settings
with only the Android token value, without a device label or quotes. Save & connect
reloads the client, then confirm Connected and try a spoken question. Never paste
the token into chat or logs. Do not rotate server tokens merely to debug this UI.

## Central Hermes configuration: service and gateway checkpoints

Files: `server/control{,_store,_client}.py`, `managed_runtime.py`, `pairing.py`,
`app.py`, `main.py`, `protocol.py`, `session.py`, new control/runtime/pairing tests,
`integrations/hermes-jarvis/`, Compose/Caddy/private-socket deployment files,
dependency lock and managed-configuration docs. Detailed contract:
`docs/managed-config-protocol.md`. The native plugin reuses Hermes's existing
dashboard/session gate and SDK; it does not patch Hermes core.

Run the Windows commands in `docs/hermes-jarvis-control.md`. Current full suite:
180 backend / 103 JVM / **OK (16 tests)** on the emulator, APK builds and lint pass.
Web/control/gateway are deployed, preserving VPS env files and prior patches.

Phone/web checks after that migration: pair by matching the public phone-request
identifier in Hermes, approve it there, verify Connected without token copying,
save a listening preference in the native Jarvis tab and verify it applies after
the turn/follow-up. Reject a request, let one expire, try an incorrect claim, and
confirm no credential is returned. Verify stored provider keys never reappear.
All remaining original Task 10 voice/vision/call/screen-off/battery checks still apply.

## Central Hermes configuration: Android migration

Files: new `settings/{ManagedPreferences,ManagedConfigSync,PairingFlow}.kt`,
`net/PairingClient.kt`, `ui/PairingViewModel.kt`; updated `Settings.kt`,
`SettingsActivity.kt`, `JarvisService.kt`, `MainActivity.kt`, `Conversation.kt`,
`Protocol.kt`, stable view IDs and corresponding JVM/native tests. Deployment adds
`enable-hermes-plugin.py` through `install-hermes-plugin.sh`; gateway URL validation
also rejects whitespace. These replace the earlier token-editor setup instructions.

Use the full Windows commands in `hermes-jarvis-control.md` (including explicit
emulator serial for instrumentation). Install the debug APK on the physical phone
only after USB reconnection, as shown in `android-test-results.md`.

Phone checks: open Settings, tap Pair with Hermes, match the displayed public ID
in Hermes → Jarvis and approve. Confirm Connected without token copying. Change
listening preferences during a reply/follow-up: they should apply only when idle
and retain the current caption. Disable vision in Hermes: capture stops; enabling
it again must not start the camera. Camera/endpoint changes clear old frames.
Rotate/background during pairing, retry expired/declined requests and confirm no
stale request installs credentials. Continue the original Task 10 phone checks.

Actual deployment checks: native form save/restore and secret masking pass; a
VPS-local synthetic device passed native approval → claim → config → image answer
and spoken PCM. Physical-phone migration checks remain pending. Screenshots:
`docs/images/hermes-jarvis-{dashboard,settings}.png`, updated pairing-only
`docs/images/android-settings-emulator.png`.
