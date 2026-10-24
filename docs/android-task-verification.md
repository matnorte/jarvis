# Android task verification

Run commands from the repository root in **Windows Git Bash**. Start the Android emulator first.
Never enter real credentials in tests, commands, logs, or this document. Enter them only on the phone.

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
