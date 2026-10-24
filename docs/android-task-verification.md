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
