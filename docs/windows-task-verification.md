# Windows tasks, commands and manual checks

Scope chosen by the owner: voice companion, orb, offline wake, tray and hotkey.
Branch: `codex/windows`. Android and the public Hermes UI were confirmed working by
the owner; that does not imply a measured battery/noise/call result.

## 1. Separate Windows pairing and managed status

Files: `server/{control_store,control,control_client,pairing,managed_runtime,app}.py`,
corresponding control/runtime/app tests, native Hermes plugin JS/CSS and the additive
contract in `managed-config-protocol.md`. Commit `9250806`. Already deployed to VPS,
preserving prior local patches, env keys and both existing device credentials.

```powershell
python -m uv run --python 3.12 pytest -W error -q
node --check integrations/hermes-jarvis/dashboard/dist/index.js
```

200 backend tests pass. Legacy requests still mean Android. Windows requests carry
`device: win`, approval is bound to type and claim, and only that device's credential
is returned. Dashboard displays Android/Windows connection/revision independently.
VPS health and public OAuth preflight pass after deployment. A synthetic public
Windows probe was skipped when a real Windows connection became active, preserving
the owner's session. Owner confirmed **Paired / Connected**.

Manual: start Android and Windows together; confirm distinct connected cards. Pair
Windows again and confirm Android remains connected. Reject/expire requests, match
the displayed ID/type, and verify no editable token/key exists in the app.

## 2. Client policies, protected storage and native audio

Files: `windows/package{,-lock}.json`, `src/core/{conversation,protocol,audio,managed}.mjs`,
`controller.mjs`, `gateway.mjs`, `pairing.mjs`, `storage.mjs`, native wake/utility
adapters, renderer audio client/worklets and tests. Pinned assets are prepared by
`scripts/prepare-assets.mjs`; hashes/sources in `windows/ASSETS.md`.

```powershell
cd windows
npm.cmd ci
node node_modules/electron/install.js
npm.cmd run prepare:assets
npm.cmd test
npm.cmd run smoke
npm.cmd run smoke:long
```

45 Windows tests pass, including actual native silence rejection and synthetic
Jarvis detection. Native development smoke and the two-minute burst smoke pass.
They use fake microphone input and an isolated profile, never the owner's mic or
token. Test coverage includes pre-roll, no-speech timeout, exact barge-in order,
stale audio, drain in both orders, caption retention, safe preference sync,
cancelled claims, encrypted storage, output ownership and bounded inference delivery.

Manual: Start listening, ask in Portuguese and English, try a follow-up, interrupt
with wake/hotkey and optionally configured speech barge-in. Confirm Pause releases
the microphone; resume intentionally after locking/sleeping. Change a Hermes
preference during a reply and verify it waits until the turn/follow-up ends.

## 3. Desktop shell and packaging

Files: `src/main.mjs`, `preload.cjs`, `renderer/{index.html,ui.css,renderer.mjs}`,
build/icon scripts, shared `ui/orb/orb.html` (additive animation-pause API), docs and
`docs/images/windows-assistant.png`.

```powershell
cd windows
npm.cmd run build
& ./dist/win-unpacked/Jarvis.exe --smoke-test
& ./dist/win-unpacked/Jarvis.exe --smoke-test --smoke-long-reply
& ./dist/Jarvis-0.1.1-x64-portable.exe --smoke-test --smoke-long-reply
if ($LASTEXITCODE -ne 0) { throw 'Portable smoke failed' }
```

v0.1.1 portable/installer built, unpacked and portable smoke passed, and the actual
native window was visually reviewed. Pairing/connection was confirmed by the owner.
Window close hides to tray; Compact is above other windows; Ctrl+Shift+Space talks
or interrupts. Hidden/minimized windows pause orb animation while native voice
capture can continue. Permissions are limited to the local main frame's active
microphone; no camera permission, Node renderer or arbitrary URL/IPC commands.

Manual: toggle Compact, use hotkey from another app, close/reopen through tray,
Quit fully, check startup remains microphone-off, and verify pause/resume after sleep.
The owner stopped computer control with Escape; do not resume desktop automation
without a fresh user request. No unobserved manual result is marked passed.

## 4. Long-answer regression, v0.1.1

Owner report: wake works, but long answers/deep questions produce the microphone
permission message. Reproduced by a two-minute burst overflowing the original
30-second playback queue. First failing tests precede the fix.

The bounded queue now allows ten minutes/28.8 MB. Playback errors have a distinct,
generation-checked IPC path. An oversized reply is cancelled while healthy capture
continues; stale PCM/errors are ignored until the next turn/flush. Output failure
permits a clean retry, and an old output context cannot close its replacement.
Regression tests also simulate 64 seconds of deep thinking and final audible drain.

The Windows suite and native long-burst smoke pass on source, unpacked and portable
builds. v0.1.1 is installed successfully; encrypted pairing was retained. Android
and Windows were both observed connected and synchronized at revision 7 afterward.
Portable startup also targets the persistent launcher, with two regression tests.
The owner's real long-answer retest remains pending.
Do not claim the real-device regression is fixed until that retest is observed.

## Shared Android regression (Git Bash)

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
./gradlew --stop
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.openclawchip.jarvis android.permission.RECORD_AUDIO
adb -s emulator-5554 shell pm grant com.openclawchip.jarvis android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell pm grant com.openclawchip.jarvis android.permission.CAMERA
adb -s emulator-5554 shell am instrument -w -r com.openclawchip.jarvis.test/androidx.test.runner.AndroidJUnitRunner
```

Explicitly target the emulator, never the owner's personal phone. A cleared/fresh
emulator needs these grants; adopted test-shell identity alone does not reliably
complete the application's runtime permission flow. Last checkpoint before the
optional orb animation API: 103 JVM, 16 emulator, APK builds and lint pass. The new
JVM/APK/lint checks pass. Initial native reruns encountered host memory pressure
and missing emulator runtime grants; the full run with grants passes all 16 tests.
The emulator was stopped afterward to release host memory.
