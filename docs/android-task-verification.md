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
