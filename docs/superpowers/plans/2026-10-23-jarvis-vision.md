# Jarvis UI, offline wake word, live vision, Hermes dashboard

User-approved extension of the Android plan (2026-10-23). Remain on `feat/android`.
The tested checkpoint is `58c5e4b`: 81 Android JVM / 11 emulator / 130 backend tests.

## Decisions

- Remove Picovoice entirely: no account/key required for wake detection.
- Use sherpa-onnx open-vocabulary KWS, bundled small quantized model, explicit JARVIS
  phonemes, one CPU inference thread. Pin/checksum official runtime and model downloads.
  Native inference and release remain synchronized. Test silence, lifecycle, and wake
  detection with a controlled speech fixture where possible; manually verify the phone.
- Redesign the entire Android flow: welcome/setup, assistant home, live vision, settings.
  Dark graphite, warm amber, restrained typography, real status labels, compact controls,
  rounded cards, accessible touch targets. Preserve the shared golden orb.
- Camera choice is **live stream**. Explicit camera-on control, foreground preview,
  front/back switch, visible streaming indicator, stop on leaving/backgrounding camera.
  Send bounded JPEG frames over the authenticated existing WebSocket, at limited FPS;
  latest frame only on server, expire stale frames, drop frames under backpressure.
  No image history, image files, base64 logging, or unsolicited continuous model calls.
- A spoken question with camera on uses a fresh streamed frame as structured image
  context in Hermes. Add an explicit vision protocol extension with tests; preserve
  voice-only behavior and Conversation's turn/barge-in/follow-up rules.
- User selected **Hermes's own web interface**. Run the installed official dashboard
  on VPS loopback port 9119, accessible through an SSH tunnel, with no public admin port.
  Do not print dashboard bootstrap tokens, environment files, credentials, or key values.

## Sequence and verification

1. Enable native Hermes dashboard, verify HTTP/UI and profile selection through SSH.
2. TDD: account-free wake adapter and settings/onboarding migration; full suites; commit.
3. TDD: server bounded latest-frame store, validated frame/control messages, structured
   image routing and history handling; old-client compatibility; full suites; commit.
4. TDD: Android camera capture/throttling and authenticated frame delivery; complete
   polished UI and permission flow; WebView/preview/rotation/lifecycle checks; commit.
5. Deploy tested backend changes without overwriting server env files or prior patch.
   Install final APK when user is ready; verify wake/voice/follow-up/barge-in, live visual
   question, camera off/background clearing, reconnect/call, screen-off wake, battery.

Run the full Android JVM + emulator suite and backend suite before each code commit.
When phone/emulator both connected, instrument the emulator explicitly using ADB.
Record Windows commands/files/phone checks in `docs/android-task-verification.md` and
manual outcomes in `docs/android-test-results.md`. Never claim pending device checks pass.

## Checkpoint

Steps 1–4 are implemented and tested: native Hermes dashboard, offline Sherpa wake,
bounded server vision, Android CameraX transport, and Assistant/Vision/Settings UI.
88 JVM / 15 emulator / 147 backend tests pass, including preview shutdown on tab
change/background and explicit Pause across activity recreation. The fixed model
speech fixture is synthetic; real phone accent/noise/battery checks remain manual.

Step 5 deployment is complete: tested backend + both profile personas installed;
both profile image probes and public WSS camera → answer → spoken PCM passed with
an artificial red square. Env files were preserved. The VPS checkout retains the
client-VAD and vision patches locally; reconcile before pulling the final branch.
Physical-phone APK upgrade, real-camera/voice checks, and battery measurement are
pending USB reconnection/user availability. See the per-task verification document.
