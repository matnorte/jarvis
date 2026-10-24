# Jarvis — Handoff

Snapshot: 2026-10-23. Written so another assistant (or a person) can continue without the original chat.

## 1. What this project is
A JARVIS-style voice assistant:
- **Backend (done, deployed):** `server/` — FastAPI WebSocket voice gateway on a VPS. Mic audio in → faster-whisper (`base`, CPU) → Hermes Agent (fast profile `gpt-5.6-luna`, escalates to deep profile `gpt-6.1-sol` on `[[DEEP]]`, both via OpenAI Codex OAuth) → ElevenLabs TTS (voice **Julian** `7p1Ofvcwsv7UBPoFNcpI`, model `eleven_turbo_v2_5`) → audio back. Live at `wss://jarvis.openclawchip.tech/ws`.
- **Android app (in progress):** `android/` — native Kotlin client. Branch **`feat/android`**.
- **Windows client:** not started (sub-project 2).

Key docs (read these first):
- Backend spec: `docs/superpowers/specs/2026-10-23-jarvis-backend-design.md`
- Android spec: `docs/superpowers/specs/2026-10-23-jarvis-android-design.md`
- Android plan (task-by-task, with tests): `docs/superpowers/plans/2026-10-23-jarvis-android.md`
- Shared orb: `ui/orb/orb.html` (vendored assets; copied into Android at build time)

## 2. Repo and branches
- GitHub: `matnorte/jarvis` (public). `main` = deployed backend (PRs #1–#4 merged).
- `feat/android` = Android work, **not pushed / not merged yet** unless pushed after this handoff. Commits so far:
  `9623e68` spec · `e5541c0` plan · `bc61e34` T1 scaffold · `9e59e40` T2 protocol · `8ff2a74` T3 pre-roll/backoff/pcm · `eef60a0` + `2d2a572` T4 conversation · `7e964bc` T5 gateway client · `0023256` T6 audio adapters.
- Commit style: conventional (`feat(android): …`), small commits, tests first (TDD).

## 3. Backend operations (VPS)
- Host `187.127.57.170` (Hostinger, Campinas BR), Ubuntu 26.04, 2 vCPU / 8 GB. SSH as root **with an SSH key** (password login should not be used; the root password was exposed in a chat and **must be changed with `passwd`**).
- `/opt/jarvis` = clone of `main`. Gateway: `docker compose -f deploy/docker-compose.yml up -d --build` (services `gateway` on 127.0.0.1:8000 and `caddy` on 80/443, host networking).
- Hermes: one host gateway `systemctl status hermes-gateway` (127.0.0.1:8642, profiles at `/p/fast` and `/p/deep`). Fast profile has heavy toolsets disabled on the `api_server` platform (latency).
- Secrets (never commit, never paste in chats): `/root/jarvis-secrets.env` (API_SERVER_KEY), `/opt/jarvis/deploy/.env` (ElevenLabs key, voice id, API_SERVER_KEY, DEVICE_TOKENS), `/root/jarvis-device-tokens.txt` (`win:` and `android:` device tokens, root-only).
- Health: `curl https://jarvis.openclawchip.tech/health` → `{"ok":true}`. Per-turn timing log: `docker compose -f deploy/docker-compose.yml logs gateway | grep "turn ok"`.
- Tools: `python -m uv run python -m scripts.talk|bench|tokens --help`. Latency now ≈ 2.6 s to first reply audio (user accepted keeping whisper `base`).
- Backend tests: `python -m uv run pytest -W error -q` (125 passing). `uv` is not on PATH on the dev PC; use `python -m uv`.

## 4. Android dev environment (Windows PC)
- JDK: Android Studio's `jbr`. Every Gradle command: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` (Git Bash), run from `android/`.
- Gradle 9.6.0, AGP 9.4.1 (built-in Kotlin), compileSdk 37, targetSdk 36, minSdk 31. Deps: OkHttp 5.5.1, Porcupine 4.0.2 (built-in `JARVIS` keyword confirmed), Silero VAD 2.0.10 (JitPack), coroutines 1.10.2.
- JVM tests: `./gradlew testDebugUnitTest`. Instrumented: start emulator `Medium_Phone_API_37.0` headless (`.../Android/Sdk/emulator/emulator.exe -avd Medium_Phone_API_37.0 -no-window -no-audio -no-boot-anim`), then `./gradlew connectedDebugAndroidTest`.
- The user's phone (Android 12+) will be connected by USB for Task 10.

## 5. Android status (plan tasks)
| Task | Status |
|---|---|
| 1 Scaffold | ✅ reviewed |
| 2 Protocol | ✅ reviewed |
| 3 PreRoll / Backoff / PCM | ✅ reviewed |
| 4 Conversation state machine | ✅ reviewed (36 tests) |
| 5 GatewayClient | ✅ reviewed (12 tests) |
| 6 Audio adapters | ✅ Important findings fixed (`17831e5`) |
| 7 Settings, encrypted secrets, onboarding | ✅ (`a4b7d9b`) — Picovoice setup now superseded by new request |
| 8 JarvisService wiring | ✅ (`ff35a59`), bounded capture inbox added during UI integration |
| 8b Server `client_vad` hello flag | ✅ (`4b13f8c`), backend patch deployed to VPS |
| 9 Orb UI (WebView) | ✅ built and verified on emulator and user phone |
| 10 On-device testing with the user | In progress: installed on SM-S942B / Android 17; orb confirmed; voice/battery pending new scope |

### Task 6 review findings (resolved)
1. **`AudioOut.flush()` can still play stale reply audio** (breaks Review Focus 1). The writer does a blocking `track.write` outside the lock; after `flush()` frees the buffer, the blocked write refills it with old audio (≥200 ms). Also the generation is read after `queue.poll` in a separate step, so a chunk can be tagged with the new generation. **Fix:** stamp each chunk with the generation at `write()` time (or read generation atomically with dequeue); write in small slices (10–20 ms) or `WRITE_NON_BLOCKING`, re-checking generation before each slice. **Test (instrumented):** write 2 s, flush after 100 ms → `onDrained` fires within ~one buffer period.
2. **`AudioIn` fails silently when the mic dies** (e.g. `read` returns `ERROR_DEAD_OBJECT`, or another app holds the mic and `recordingState != RECORDING` after `startRecording`). No signal reaches the service; native objects leak on the next `start()`. **Fix:** add an `onError` callback (or throw from `start()` when not recording); run cleanup on read failure; guard `start()` on `record != null`.
Worth fixing at the same time (minor): track `playbackHeadPosition` as unsigned (`toLong() and 0xFFFFFFFFL`); make `PorcupineWakeWord.process`/`SileroVad.isSpeech` safe against concurrent `close()` (or document that AudioIn must be stopped first); wrap `onFrame` in AudioIn's thread so an exception doesn't kill it; check a ~100 ms reply actually starts playing (AudioTrack start threshold).

Resolution: enqueue-time generations + locked nonblocking 10 ms writes, unsigned head,
10 ms start threshold; mic errors release resources before reporting and starts verify
recording state. Synchronized native inference/close. Tests include dead mic, failed
start, callback failure, flush at 100 ms of a two-second reply and short reply drain.
The old stale-write bug did not reproduce on the emulator; the short-reply test did fail.
Current checkpoint: 81 JVM tests, 11 emulator tests, 130 backend tests; lint passes
with warnings. Per-task files/Windows commands/phone checks: `docs/android-task-verification.md`.
Manual results: `docs/android-test-results.md`.

## 6. Binding decisions made during the build (respect these)
- Gradle wrapper 9.6.0 and compileSdk 37 (tooling forced); no edits to `~/.gradle/gradle.properties`.
- Conversation (`core/Conversation.kt`, nested `Conversation.Config`) owns its PreRoll; the client phase machine is authoritative for when audio is sent; barge-in by speech = ≥10 consecutive VAD frames; LISTENING no-speech 6 s from entry; no Effects while PAUSED; OFFLINE wake → `reconnectNow` + chime, LISTENING only on `onConnected`; after 4401 wakes do nothing.
- **Follow-up starts only after local playback drains:** `Conversation.onPlaybackDone()` + server `speaking_done` (either order). AudioOut exposes `setOnDrained(cb)` and `isDrained()`; the service must re-check `isDrained()` on the conversation thread before calling `onPlaybackDone()`, and call it on every drain (including between chunks).
- GatewayClient: `onConnected` only after `hello` was sent; `onDisconnected` on every failed attempt/drop (not after `close()`, not for 4401/4409 → `onRejected`); callbacks arrive on OkHttp threads — the service must hop them onto one single-thread dispatcher and ignore callbacks from a closed/replaced client instance.
- Secrets: Android Keystore AES-256-GCM wrapper instead of deprecated EncryptedSharedPreferences.
- OrbBridge takes a `(String) -> Unit` JS sink and a clock, so it is JVM-testable.
- Clock for Conversation: `SystemClock.elapsedRealtime`.
- **Task 8b (new):** the server's fallback energy endpointer (700 ms) races the phone's VAD. Add optional `"client_vad": true` to `hello`; the server skips its endpointer for that connection (`server/protocol.py` Hello + `server/session.py`), with tests; the Android client sends it. Deploy the backend change to the VPS before device testing.
- Boot microphone start is restricted by modern Android: `startOnBoot` posts a tap-to-start notification. No public admin port is opened.
- UI retains the previous transcript until a new final transcript arrives. Late `thinking` after `speaking_done` no longer prevents drain-based follow-up.
- Capture backlog is bounded to eight frames (256 ms), keeps generation stamps, and yields to control/socket callbacks.
- Backend-only patch from `4b13f8c` is applied locally on VPS `main` in six backend/test files. Container rebuilt; health and running `client_vad` support verified. Preserve/reconcile this patch before pulling/merging. Secrets files untouched.

## 7. Known deferred minors (fix when convenient)
- Protocol: lenient JSON coercion; missing escape/non-object tests; `ClientMsg.text` doesn't validate `lang`.
- Conversation late-thinking drain issue is fixed; previous-transcript retention was deliberately preserved.
- GatewayClient: rare onDisconnected-before-onConnected ordering; one OkHttpClient per instance.
- Backend: see `git log` / PRs for the earlier deferred list; root password change outstanding.

## 8. How the work was run
Each plan task: implement with TDD → independent review (spec compliance + quality) → fix Important findings → re-review → next task; final whole-branch review before merging `feat/android` into `main` via PR. Never commit secrets; never put API keys in code, logs or chats.

## 9. New user scope (2026-10-23; in progress)

The user explicitly requested replacing Picovoice, a clean complete Android UI/UX,
live camera vision, and **Hermes's own web interface** for configuration. SSH access
to the same VPS is explicitly authorized. This supersedes the Porcupine-specific
requirements in the old design/plan. Keep turn semantics unless a documented camera
protocol extension requires otherwise. Camera choice: **live stream**, with a visible
on/off control; bounded latest-frame memory and no image history/logging by default.

Phone is connected/authorized; initial Task 9 APK installed and orb confirmed. User
asked how to set up keys, then chose to replace Picovoice rather than create an account.
Do not overwrite the app while the user is entering credentials without coordination.
No voice checks or battery measurements have been claimed as passing.

VPS inspected: Hermes v0.22.5, installed as user `hermes` under
`/home/hermes/.hermes/hermes-agent`; `hermes dashboard` command exists. Gateway is
active on loopback 8642. The dashboard should remain loopback-only (9119) and be
reached through SSH forwarding. Do not dump `.env`, auth files, or dashboard bootstrap
tokens. Native dashboard is now enabled as `hermes-dashboard.service`, loopback
9119 only, and verified in-browser through a temporary local SSH tunnel. See
`docs/hermes-dashboard.md` and the new extension plan
`docs/superpowers/plans/2026-10-23-jarvis-vision.md`.
Sherpa-onnx offline KWS is implemented: pinned 2.0.0 static runtime, ~5.5 MB quantized
chunk-8 model, explicit JARVIS phonemes, no account/key. Legacy encrypted Picovoice
setting removed on open; device token alone completes setup. 83 JVM / 11 emulator /
130 backend tests pass, including silence and synthetic JARVIS detection. See
`android/WAKE_ASSETS.md`. Only arm64-v8a and x86_64 are packaged.
Official Hermes API supports structured image_url input. Live camera/backend vision
and full UI redesign are next; phone manual voice/battery checks remain pending.

Live-vision backend is now implemented and locally tested (147 backend tests).
Optional authenticated JSON `camera`/`camera_frame` controls leave binary PCM and
Conversation semantics intact; bounded current JPEG only, four-second TTL, no
images in Jarvis router history. See `docs/vision-protocol.md` for the exact contract.
Pillow 13 is added to the locked runtime. Backend vision and both updated personas
are deployed on the VPS, preserving the earlier client-VAD patch and all env files.
The server checkout now contains both deployed patches as uncommitted changes;
reconcile them before pulling/merging. Container rebuilt; public health passes.
Both native Hermes profiles recognized a synthetic red JPEG, and a public WSS test
passed authenticated camera frame → Hermes answer → spoken PCM. No real camera
image or credentials were printed. Dashboard/API/gateway remain loopback-only.

Android live camera and full native UI are implemented: Assistant/Vision/Settings,
graphite/amber surfaces, shared offline orb, clear connection/phase labels, encrypted
token replacement, and account-free setup. CameraX preview is explicit, supports
front/back selection, and stops/clears context on leaving Vision or backgrounding.
Upright JPEGs have a 640 px long edge, 128 KiB limit, at most 2 FPS; one-slot frame
inbox drops older frames, socket backpressure drops rather than queues, and source
epochs reject old preview frames/stop events. Reconnect reannounces camera mode.
Conversation inputs and PCM protocol remain unchanged. Explicit Pause now survives
activity recreation/resume; Talk/Start resumes intentionally.

Verification: 88 JVM tests, 15 emulator tests, 147 backend tests; APK/AndroidTest
assemblies and lint pass (existing warnings, no errors). Tests cover real emulator
preview, tab/background clearing, rotation/JPEG bounds, dropped/stale frames,
reconnect, secret field protection, pause recreation, and packaged WebGL rendering.
Design screenshots are under `docs/images/android-{assistant,vision,settings}-emulator.png`.
2026-10-24: user reconnected the phone; latest APK installed and MainActivity opened.
User entered setup and reported “Still connecting.” DNS/reachability and VPS health
pass; debug inspection of connection flags confirms a terminal 4401 (saved token
rejected), without reading/displaying its value. The misleading Connecting header
now shows “Token rejected · Open Settings,” with JVM regression coverage.
Full verification passes: 90 JVM / 15 emulator / 147 backend, APK builds and lint.
Server-side comparisons confirm the saved Android token, server env, and running
gateway agree; only match booleans were printed, and no server token was changed.
User has a token to enter directly on the phone; use only the Android token value,
not the `android:` label/quotes. No server token rotation has been performed.
Physical voice/vision/noise/call/screen-off/battery checks remain pending.
Do not mark Task 10 done.

Additional user request: **all Jarvis configuration inside Hermes Agent's own UI**,
including phone pairing and voice/camera preferences. Implement as a native Hermes
dashboard plugin (upstream supports tabs/API extensions); do not patch Hermes core.
Android permissions remain OS prompts and phone credentials remain Keystore-encrypted.
This configuration work is now active; preserve the original pending phone tests.

Control/plugin foundation committed as `b25a7cf`: native Hermes Jarvis tab, private
UDS configuration API, masked ElevenLabs-key replacement, bounded approved pairing,
deployment/socket-directory setup. Full checkpoint: 168 backend / 90 JVM / 15
emulator tests. Gateway synchronization now implemented locally: managed opt-in
hello + config/idle acknowledgements, safe Session dependency replacement, old-client
compatibility, public bounded pairing relays, retryable control outages and fixed
apply errors. Full backend suite now passes 179 tests. See
`docs/managed-config-protocol.md` and `docs/hermes-jarvis-control.md`.
Central control and gateway synchronization (`b25a7cf`, `63db919`) are now deployed.
The VPS retains these patches alongside the earlier client-VAD/vision changes as
local modifications; reconcile before pulling/merging. Existing secret files and
the Android device token were preserved. The control service has no network and
listens only on a root/Hermes Unix socket; public internal-runtime access returns
404. Native Jarvis tabs are enabled for default/fast/deep through the native Hermes
config CLI, preserving other plugins. No Hermes core file is modified.

Android migration is implemented: Settings contains only pairing/status, no token
or preference editors. Pairing uses a phone-private capability held in memory,
explicit matching-ID approval in Hermes, and direct encrypted credential storage.
Lifecycle cancellation rejects stale claims. Managed settings cache offline and
apply only in WAITING/PAUSED; configuration preserves captions and turn semantics.
Camera disable/endpoint changes clear the old stream; no remote setting starts it.
All Jarvis settings now live in Hermes's Jarvis tab; agent models/providers/tools
remain on native Hermes Config pages. Permissions are still Android OS prompts.

Latest full verification: **180 backend / 103 JVM / 16 emulator**, APK assemblies
and lint pass (existing warnings, no errors). Native UI save/persistence checked for
boot reminder and camera preference, then restored original values. Key inputs are
blank with configured badges. A VPS-local synthetic device completed native UI
approval → one-use claim → existing token → managed config/idle ack → artificial
red image answer and spoken PCM; no credential or private capability was printed
or left in a file. This is not a physical-phone test.

The new Android APK is ready but not installed on the physical phone: latest ADB
lists only the emulator. A USB reconnection question is pending. After installation,
use Settings → Pair with Hermes, approve the actual phone's matching request ID in
the web tab, verify Connected, and continue original Task 10/manual vision checks.
Do not resume manual token entry. Do not mark Task 10 complete.

## 10. Remote phone dashboard request (2026-10-24; pending account sign-in)

The owner now explicitly requests Hermes access from anywhere, primarily on the
phone. This supersedes the earlier SSH-only browser decision, while backend ports
and the control Unix socket remain private. Prepare public HTTPS at the existing
`jarvis.openclawchip.tech` origin with native Nous OAuth and Caddy; preserve voice
routes. The VPS has no Nous login/provider configured. The Portal Local Dashboards
sign-in page is open for the owner's personal sign-in/Terms acceptance; no account
or credential was invented. A Tailscale alternative was offered.

`deploy/Caddyfile.hermes` and the tested `check-hermes-public.py` are prepared.
The active Caddyfile is unchanged: dashboard is not public yet. Run the guard on
loopback before publication and HTTPS afterward; it refuses disabled/non-OAuth
gates, exposed owner APIs or missing login redirects. Native public_url engages
the gate while keeping 9119 loopback. Exact setup/checks are in
`docs/hermes-remote-access.md`. Do not expose the old loopback SPA bootstrap token
as remote authentication. Continue once the owner completes Nous setup or chooses
Tailscale. Physical phone/Task 10 checks remain pending.

Pre-publication checkpoint: 192 backend / 103 JVM / 16 emulator tests pass, APK
assemblies and lint pass. Candidate Caddyfile validates on the VPS. Running the
guard against the actual dashboard correctly refuses its disabled OAuth gate.
Public `/jarvis` remains 404, voice health passes, SSH dashboard stays active.
