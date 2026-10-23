# JARVIS — Android App Design (sub-project 3)

Date: 2026-10-23 · Status: draft for user review
Backend spec: `docs/superpowers/specs/2026-10-23-jarvis-backend-design.md` (deployed at `wss://jarvis.openclawchip.tech/ws`).

## 1. Goal and success criteria
A native Kotlin Android app on the user's own phone (Android 12+, installed by USB/APK, not the Play Store) that lets them talk to Jarvis hands-free:
- Say **"Jarvis"** with the screen off or the app closed → hear Julian answer through the phone.
- Reply within ~6 s without saying "Jarvis" again (follow-up window).
- Interrupt Jarvis by saying "Jarvis" or tapping the orb.
- Portuguese and English both work (the server detects language; the app is language-agnostic).
- Main screen shows the approved golden hologram orb (`prototype/orb.html`) driven by live `state`/`level`.

Out of scope for v1: Play Store release signing, lock-screen UI, home-screen widget, typed chat UI (the `text` message is only used by tests), multiple users.

## 2. Architecture
```
            ┌──────────────────────── JarvisService (foreground, type=microphone) ───────────────────────┐
mic ─▶ AudioIn ─▶ frames(512 @16 kHz) ─┬─▶ WakeWord (Porcupine "JARVIS")                                 │
  (VOICE_COMMUNICATION + AEC)          ├─▶ Vad (Silero)                                                  │
                                       └─▶ PreRoll ring buffer (300 ms)                                  │
                                                    │ events                                             │
                                              Conversation (state machine) ◀──▶ GatewayClient (OkHttp WSS)│
                                                    │                              ▲ binary PCM 24 kHz    │
                                                    ▼                              │                      │
                                              AudioOut (AudioTrack) ◀──────────────┘                      │
                                                    │ UiState (StateFlow: state, mic/out level, captions) │
            └───────────────────────────────────────┼────────────────────────────────────────────────────┘
                                                    ▼
                         MainActivity: WebView (assets/orb/orb.html + three.min.js + bloom addons)
                         ← JS bridge: JarvisOrb.setState(s) / setLevel(v); native captions overlay
```

### Components (package `com.openclawchip.jarvis`)
| Unit | Responsibility | Depends on |
|---|---|---|
| `JarvisService` | Foreground service (`foregroundServiceType="microphone"`), owns the audio pipeline, notification (state text + **Talk** / **Stop** actions), START_STICKY, optional start on boot. Exposes `UiState` as a `StateFlow`. | all below |
| `AudioIn` | `AudioRecord`, 16 kHz mono PCM16, source `VOICE_COMMUNICATION` (platform AEC/NS), delivers 512-sample frames on a dedicated thread. | — |
| `WakeWord` | Interface `process(frame: ShortArray): Boolean`; impl `PorcupineWakeWord` (built-in `JARVIS`, sensitivity from settings, default 0.6). | Picovoice Porcupine Android |
| `Vad` | Interface `isSpeech(frame): Boolean`; impl Silero (Android VAD library), 16 kHz, 512-sample frames. | Silero VAD |
| `PreRoll` | Ring buffer of the last 300 ms of frames, drained into the stream on wake. | — |
| `Protocol` | Encode client messages / decode server messages (exact formats §4). Pure Kotlin. | — |
| `GatewayClient` | OkHttp WebSocket; `hello` on open; ping 25 s; reconnect with backoff 1,2,4,…,30 s; surfaces close codes 4401 (bad token: stop retrying, show "check token") and 4409 (replaced: don't reconnect until next wake). | OkHttp |
| `AudioOut` | `AudioTrack` streaming PCM16 24 kHz mono, `USAGE_ASSISTANT`; `flush()` for barge-in; reports output RMS level; requests transient audio focus with ducking while speaking. | — |
| `Conversation` | State machine (§3). Pure Kotlin, driven by events, emits commands; fully unit-testable with fakes. | interfaces only |
| `Settings` | EncryptedSharedPreferences: `serverUrl` (default `wss://jarvis.openclawchip.tech/ws`), `deviceToken`, `picovoiceKey`, `wakeSensitivity` (0.6), `endSilenceMs` (700), `interruptBySpeech` (false), `startOnBoot` (false). Secrets never displayed after save, never logged. | AndroidX Security |
| `MainActivity` | Full-screen WebView orb + caption overlay (transcript, reply text as it streams) + connection dot + settings gear; tap orb = talk / interrupt. Pauses rendering when not visible. | WebView |
| `SettingsActivity` | Edits `Settings`. | — |
| `Onboarding` | First run: mic + notification permission rationale; opens Settings if token/key missing; asks for battery-optimisation exemption (optional). | — |

## 3. Conversation state machine
States: `WAITING`, `LISTENING`, `THINKING`, `SPEAKING`, `FOLLOW_UP`, `PAUSED`, `OFFLINE`.

| From | Event | Action → To |
|---|---|---|
| WAITING | wake word / tap / notification Talk | chime; send pre-roll + live frames → LISTENING |
| LISTENING | VAD: speech seen, then `endSilenceMs` of silence | send `end_utterance`; stop streaming → THINKING |
| LISTENING | no speech within 6 s | send `interrupt` (clears server buffer) → WAITING |
| LISTENING | 30 s total | send `end_utterance` → THINKING |
| THINKING/SPEAKING | binary audio | `AudioOut.write` → SPEAKING |
| SPEAKING/THINKING | `speaking_done` | → FOLLOW_UP |
| SPEAKING/THINKING | wake word, tap, or (if `interruptBySpeech`) ≥300 ms VAD speech | send `interrupt`; `AudioOut.flush()`; start streaming (pre-roll + live) → LISTENING |
| FOLLOW_UP | VAD speech starts | stream pre-roll + live → LISTENING |
| FOLLOW_UP | 6 s silence | → WAITING |
| any | server `state:idle` while THINKING with no audio (empty transcript) | → WAITING |
| any | `error` message | show caption; server plays the spoken error line; → FOLLOW_UP when idle arrives |
| any | phone call / mic taken by another app | stop mic; → PAUSED; resume → WAITING |
| any | socket lost | local error tone once; → OFFLINE; reconnect with backoff; wake word keeps running and a wake triggers an immediate reconnect |

Audio leaves the phone only in LISTENING (and the pre-roll on entering it). Porcupine runs in every state except PAUSED; VAD runs in LISTENING, FOLLOW_UP and (when `interruptBySpeech`) SPEAKING.

## 4. Wire protocol (v1, must match `server/protocol.py`)
Client → server (text frames, JSON): `{"type":"hello","token":T,"device":"android"}` (first message), `{"type":"end_utterance"}`, `{"type":"interrupt"}`, `{"type":"text","text":S,"lang":"pt"|"en"}`. Binary frames: PCM16 LE mono 16 kHz audio.
Server → client: `{"type":"state","state":"idle|listening|thinking|speaking|deep|error"}`, `{"type":"transcript","text","lang","final"}`, `{"type":"reply_text","delta"}`, `{"type":"level","mic","out"}`, `{"type":"speaking_done"}`, `{"type":"error","code","message"}`; binary frames: PCM16 LE mono 24 kHz.
Close codes: 4401 unauthorized, 4409 replaced by a newer connection for the same device.

## 5. Orb integration
- `prototype/orb.html` is moved to a shared `ui/orb/` folder (single source for Android now, Windows later) and packaged into `android/app/src/main/assets/orb/` by a Gradle copy task. CDN `<script>` tags are replaced by local copies of `three.min.js` 0.147.0 and its `examples/js` bloom files (EffectComposer, RenderPass, ShaderPass, CopyShader, LuminosityHighPassShader, UnrealBloomPass).
- The demo buttons are hidden when `?embedded=1`; the page exposes `window.JarvisOrb.setState/setLevel` (already present).
- Native → page via `evaluateJavascript`, throttled to ≤30 calls/s. Orb state = server/app state (`waiting`→`idle`, `follow_up`→`listening`, `offline`→`error`); level = mic level while listening, out level while speaking.
- WebView: JavaScript on, no file access beyond `file:///android_asset/`, no network access needed.

## 6. Permissions, battery, security
- Manifest: `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `WAKE_LOCK` (partial wake lock only while LISTENING/THINKING/SPEAKING).
- `usesCleartextTraffic=false`; WSS only. Token sent only in `hello`. No secrets in code or logs; the Picovoice key and token come from Settings.
- Battery: WAITING runs only Porcupine on the mic thread; WebView paused when not visible; socket idle except 25 s pings. Measured on the user's phone during testing (target: < 5 %/hour in WAITING with screen off).
- Signing: debug key for v1.

## 7. Project
- `android/` Gradle project (Kotlin DSL), single module `app`, `minSdk 31`, `targetSdk 36`, `compileSdk 36`, JDK from Android Studio (`jbr`).
- Dependencies (exact versions pinned in the plan): OkHttp, Picovoice `porcupine-android`, Android VAD (Silero), AndroidX Security Crypto, AndroidX Core/Activity/Lifecycle, kotlinx-coroutines; tests: JUnit, kotlinx-coroutines-test, OkHttp MockWebServer.

## 8. Error handling
| Situation | Behaviour |
|---|---|
| Missing token or Picovoice key | Service does not start; Main shows "Set up Jarvis" → Settings. |
| Porcupine rejects key / init fails | Notification + caption "Wake word unavailable — tap to talk"; tap-to-talk still works. |
| 4401 | Stop reconnecting; caption "Device token rejected"; orb error. |
| 4409 | Do not reconnect until next wake/tap (another device session took over). |
| Socket drop mid-turn | Stop playback, error tone, OFFLINE, reconnect. |
| Mic permission revoked | Service stops; notification explains. |
| Audio focus loss (call) | PAUSED, resume when focus/mic returns. |

## 9. Testing
- **JVM unit tests:** `Conversation` (every transition in §3, with fake clock/wake/VAD/gateway/audio-out), `Protocol` encode/decode against the exact §4 strings, `PreRoll` ring buffer, backoff schedule, close-code handling.
- **Instrumented test:** `GatewayClient` against OkHttp MockWebServer speaking protocol v1 (hello, audio round trip, 4401, reconnect).
- **On the user's phone (USB) checklist:** wake in pt and en; follow-up; interrupt by "Jarvis" and by tap; `interruptBySpeech` on loudspeaker; screen off for 10 min then wake; incoming call; airplane mode and back; app killed by swipe (service survives); 1-hour battery drain in WAITING; latency to first audio comparable to `scripts/bench.py` (~2.6 s).

## 10. Open items
- Picovoice AccessKey: user creates a free account at console.picovoice.ai and enters the key in Settings.
- Android-side latency is measured on the device; server-side budget is unchanged (~2.6 s).
