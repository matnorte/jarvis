# Jarvis Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Kotlin Android app (Android 12+) that wakes on "Jarvis", streams the question to the deployed voice gateway, plays Julian's reply, supports a 6 s follow-up and barge-in, and shows the golden orb.

**Architecture:** A foreground microphone service runs the audio pipeline (AudioRecord with AEC → Porcupine wake word, Silero VAD, a 300 ms pre-roll) and drives a pure-Kotlin `Conversation` state machine that talks to the gateway over an OkHttp WebSocket and plays audio with AudioTrack. `MainActivity` shows the bundled Three.js orb in a WebView fed through a JS bridge. Everything platform-specific sits behind small interfaces so the protocol, state machine, buffers and socket client are JVM-unit-tested.

**Tech Stack:** Kotlin, Gradle 9.5 (Kotlin DSL) with AGP 9.4.1 (built-in Kotlin), minSdk 31 / compile+target 36, kotlinx-coroutines, OkHttp 5.5.1 (+ MockWebServer), `ai.picovoice:porcupine-android:4.0.2`, `com.github.gkonovalov.android-vad:silero:2.0.10` (JitPack), JUnit 4, kotlinx-coroutines-test, Three.js 0.147.0 (vendored).

**Spec:** `docs/superpowers/specs/2026-10-23-jarvis-android-design.md`

## Global Constraints

- Package `com.openclawchip.jarvis`; project in `android/` with one module `app`; `minSdk 31`, `compileSdk 36`, `targetSdk 36`; JDK = Android Studio's `jbr` (set `org.gradle.java.home` in the user's `~/.gradle/gradle.properties`, never in the repo).
- Mic audio: PCM16 LE mono **16 kHz**, frames of **512** samples, `AudioSource.VOICE_COMMUNICATION`. Reply audio: PCM16 LE mono **24 kHz**.
- Wire protocol exactly as spec §4 (`hello` with `"device":"android"`, `end_utterance`, `interrupt`, `text` with optional `lang`); close codes **4401** (stop retrying) and **4409** (don't reconnect until next wake/tap).
- Timings: pre-roll **300 ms**; end of speech after `endSilenceMs` (default **700**); no speech in LISTENING **6 s** → `interrupt` → WAITING; utterance max **30 s**; FOLLOW_UP **6 s**; barge-in by speech needs **300 ms** of VAD speech; WebSocket ping **25 s**; reconnect backoff **1, 2, 4, 8, 16, 30, 30… s**.
- Settings defaults: `serverUrl=wss://jarvis.openclawchip.tech/ws`, `wakeSensitivity=0.6`, `endSilenceMs=700`, `interruptBySpeech=false`, `startOnBoot=false`; `deviceToken` and `picovoiceKey` have no default.
- Secrets (device token, Picovoice key) are stored encrypted with an Android Keystore AES-256-GCM key; never logged, never displayed after saving, never in code or the repo. (Ruling: AndroidX `EncryptedSharedPreferences` is deprecated, so a small Keystore wrapper replaces it — same guarantee the spec asks for.)
- No cleartext traffic (`usesCleartextTraffic=false`); audio is sent only in LISTENING (plus the pre-roll on entering it).
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A wake word fired while a turn is THINKING/SPEAKING must send exactly one `interrupt` before any new audio frame, and flush playback — no stale reply audio after the interrupt. Test in Task 4.
2. Server audio that arrives after the client sent `interrupt` (in flight) must be dropped until the server's next `state:thinking`. Test in Task 4.
3. A socket drop mid-LISTENING must stop streaming (no buffered frames sent on reconnect) and go OFFLINE; a wake while OFFLINE triggers an immediate reconnect. Test in Tasks 4 and 5.
4. `hello` must be the first frame on every (re)connection, and the token must never appear in logs or exception messages. Test in Task 5.
5. Leaving FOLLOW_UP/LISTENING without speech must never send `end_utterance` (an empty utterance makes the server idle) — it sends `interrupt` instead. Test in Task 4.

---

## File Structure
```
ui/orb/orb.html, ui/orb/vendor/*.js          shared orb page (moved from prototype/), Three.js vendored
android/settings.gradle.kts, build.gradle.kts, gradle/wrapper/*, gradlew(.bat), gradle.properties
android/app/build.gradle.kts                  copies ../ui/orb into assets/orb at build time
android/app/src/main/AndroidManifest.xml
android/app/src/main/java/com/openclawchip/jarvis/
  protocol/Protocol.kt                        client/server message encode/decode
  audio/PreRoll.kt                            ring buffer
  audio/AudioIn.kt, AudioOut.kt               AudioRecord / AudioTrack adapters
  audio/WakeWord.kt, PorcupineWakeWord.kt     interface + Porcupine impl
  audio/Vad.kt, SileroVad.kt                  interface + Silero impl
  net/Backoff.kt, GatewayClient.kt            reconnect schedule, OkHttp WebSocket
  core/Conversation.kt                        state machine (pure Kotlin)
  core/UiState.kt
  settings/SecretBox.kt, Settings.kt          Keystore AES-GCM + prefs
  service/JarvisService.kt, BootReceiver.kt
  ui/MainActivity.kt, OrbBridge.kt, SettingsActivity.kt, Onboarding.kt
android/app/src/test/java/...                 JVM unit tests (one per unit above where pure)
android/app/src/androidTest/java/...          on-device smoke tests
```

---

### Task 1: Android project scaffold

**Files:** Create `android/` Gradle project (wrapper 9.5.0, AGP 9.4.1), `android/app/build.gradle.kts`, manifest with `MainActivity` placeholder, `android/.gitignore` (`.gradle/`, `build/`, `local.properties`, `*.keystore`), README section "Android".

**Interfaces:** Produces the build: `cd android && ./gradlew testDebugUnitTest assembleDebug` succeeds.

- [ ] **Step 1:** Generate the wrapper (`gradle wrapper --gradle-version 9.5.0` using the cached distribution, or copy from an Android Studio template) and write `settings.gradle.kts` with repositories `google()`, `mavenCentral()`, and `maven("https://jitpack.io")` for the VAD library only (content filter `com.github.gkonovalov`).
- [ ] **Step 2:** `app/build.gradle.kts`: `namespace`/`applicationId` = `com.openclawchip.jarvis`, SDK levels per Global Constraints, Java/Kotlin target 17, dependencies listed in the Tech Stack, `testImplementation` JUnit 4 + coroutines-test + `mockwebserver3`. `local.properties` (`sdk.dir`) is generated locally and git-ignored.
- [ ] **Step 3:** A trivial JVM test `SmokeTest.oneIsOne` and an empty `MainActivity`. Run `./gradlew testDebugUnitTest assembleDebug`. Expected: BUILD SUCCESSFUL, `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 4:** Commit `feat(android): project scaffold`.

---

### Task 2: Protocol

**Files:** Create `protocol/Protocol.kt`; Test `ProtocolTest.kt`.

**Interfaces — Produces:**
- `object ClientMsg { fun hello(token: String): String; fun endUtterance(): String; fun interrupt(): String; fun text(text: String, lang: String = "en"): String }` — compact JSON identical to the server's `json.dumps` field set.
- `sealed interface ServerMsg { data class State(val state: String); data class Transcript(val text: String, val lang: String, val final: Boolean); data class ReplyText(val delta: String); data class Level(val mic: Float, val out: Float); data object SpeakingDone; data class Error(val code: String, val message: String); data class Unknown(val raw: String) }` and `fun parseServer(text: String): ServerMsg` (never throws; malformed → `Unknown`).
- Use `org.json` (Android) — in JVM tests add `testImplementation("org.json:json:20250517")`.

- [ ] **Step 1: Failing tests**
```kotlin
@Test fun helloHasDeviceAndroid() = assertEquals(JSONObject(ClientMsg.hello("t")).let { it.getString("type") to it.getString("device") }, "hello" to "android")
@Test fun parsesState() = assertEquals(ServerMsg.State("speaking"), parseServer("""{"type":"state","state":"speaking"}"""))
@Test fun parsesTranscriptAndError() { /* transcript text/lang/final; error code/message */ }
@Test fun garbageIsUnknown() = assertTrue(parseServer("not json") is ServerMsg.Unknown)
@Test fun textCarriesLang() = assertEquals("pt", JSONObject(ClientMsg.text("oi", "pt")).getString("lang"))
```
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests '*ProtocolTest'` → FAIL. **Step 3:** Implement. **Step 4:** → PASS.
- [ ] **Step 5:** Commit `feat(android): wire protocol`.

---

### Task 3: PreRoll and Backoff

**Files:** Create `audio/PreRoll.kt`, `net/Backoff.kt`; Tests `PreRollTest.kt`, `BackoffTest.kt`.

**Interfaces — Produces:**
- `class PreRoll(capacityFrames: Int)` with `fun push(frame: ShortArray)` (copies), `fun drain(): List<ShortArray>` (oldest first, then empty). For 300 ms at 512-sample frames: `PreRoll.forMillis(300) ` → capacity `ceil(300*16000/1000/512) = 10`.
- `class Backoff(private val steps: List<Long> = listOf(1000, 2000, 4000, 8000, 16000, 30000))` with `fun next(): Long` (repeats last), `fun reset()`.
- `fun ShortArray.toPcm16Le(): ByteArray` helper in `audio/Pcm.kt` (little-endian).

- [ ] **Step 1: Failing tests** — `capacityFor300msIs10`, `drainReturnsOldestFirstAndEmpties`, `overflowDropsOldest` (push 12 into 10 → drain returns frames 3..12), `pushCopiesInput` (mutating the source after push doesn't change stored frame), `backoffSequence` (1000,2000,4000,8000,16000,30000,30000), `resetStartsOver`, `pcmIsLittleEndian` (shortArrayOf(0x0102) → bytes [0x02, 0x01]).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat(android): pre-roll buffer, backoff, pcm helper`.

---

### Task 4: Conversation state machine

**Files:** Create `core/Conversation.kt`, `core/UiState.kt`; Test `ConversationTest.kt`.

**Interfaces:**
- Consumes: `ClientMsg`/`ServerMsg` (Task 2), `PreRoll` (Task 3).
- Produces:
  - `enum class Phase { WAITING, LISTENING, THINKING, SPEAKING, FOLLOW_UP, PAUSED, OFFLINE }`
  - `data class UiState(val phase: Phase, val orbState: String, val micLevel: Float, val outLevel: Float, val transcript: String, val reply: String, val connected: Boolean, val notice: String?)` — `orbState` mapping: WAITING→`idle`, LISTENING/FOLLOW_UP→`listening`, THINKING→`thinking` (or `deep` when the server says `deep`), SPEAKING→`speaking`, OFFLINE→`error`, PAUSED→`idle`.
  - `interface Effects { fun sendText(json: String); fun sendAudio(pcm: ByteArray); fun playAudio(pcm24k: ByteArray); fun flushPlayback(); fun chime(); fun errorTone(); fun reconnectNow() }`
  - `class Conversation(private val fx: Effects, private val config: Config, private val now: () -> Long)` where `data class Config(val endSilenceMs: Long = 700, val noSpeechMs: Long = 6000, val maxUtteranceMs: Long = 30000, val followUpMs: Long = 6000, val bargeInSpeechMs: Long = 300, val interruptBySpeech: Boolean = false)`.
  - Inputs (all called from one thread/dispatcher): `fun onFrame(frame: ShortArray, isWake: Boolean, isSpeech: Boolean)` (every 32 ms frame; the service computes wake/speech), `fun onTap()`, `fun onServer(msg: ServerMsg)`, `fun onServerAudio(pcm: ByteArray)`, `fun onConnected()`, `fun onDisconnected()`, `fun onRejected(code: Int)` (4401 → OFFLINE with notice "Device token rejected" and wakes no longer call `reconnectNow`; 4409 → OFFLINE with notice "Another device took over", a wake calls `reconnectNow`), `fun onPause()`, `fun onResume()`; `val state: StateFlow<UiState>`.
  - Time is advanced only through `now()` checked inside `onFrame` (frames arrive every 32 ms), so tests drive time with a fake clock and frames.
- Behaviour: exactly spec §3, plus Review Focus 1, 2, 5 (drop server audio after our `interrupt` until the next `state:thinking`; never `end_utterance` without speech).

- [ ] **Step 1: Failing tests** (fake `Effects` recording calls in order; helper `frames(n, wake=false, speech=false)` advancing a fake clock 32 ms per frame):
```kotlin
@Test fun wakeSendsPreRollThenLiveFrames()          // 10 silent frames, wake → chime, then 10 pre-roll + subsequent frames sent as audio, phase LISTENING
@Test fun endOfSpeechSendsEndUtterance()           // speech 20 frames then 700 ms silence → exactly one end_utterance, no more audio, THINKING
@Test fun noSpeechFor6sSendsInterruptNotEnd()       // Review Focus 5
@Test fun utteranceCappedAt30s()                    // continuous speech → end_utterance at 30 s
@Test fun audioPlaysAndSpeakingDoneStartsFollowUp() // onServerAudio → playAudio; SpeakingDone → FOLLOW_UP
@Test fun followUpSpeechStreamsWithoutWake()        // FOLLOW_UP + speech → LISTENING, pre-roll sent
@Test fun followUpTimesOutToWaiting()               // 6 s silence → WAITING, nothing sent
@Test fun wakeWhileSpeakingInterruptsOnceAndFlushes() // Review Focus 1: order = interrupt, flushPlayback, then audio frames
@Test fun lateAudioAfterInterruptIsDropped()         // Review Focus 2: audio after interrupt ignored until State("thinking")
@Test fun bargeInBySpeechOnlyWhenEnabled()          // 300 ms speech while SPEAKING: ignored by default; interrupts when interruptBySpeech
@Test fun idleWhileThinkingWithoutAudioGoesWaiting()
@Test fun disconnectMidListeningStopsAndGoesOffline() // Review Focus 3: no audio sent after onDisconnected; errorTone once
@Test fun wakeWhileOfflineRequestsReconnect()        // reconnectNow called, chime, LISTENING once onConnected
@Test fun pauseStopsEverythingAndResumeWaits()
@Test fun deepStateShowsDeepOrb()                    // State("deep") → orbState "deep"
@Test fun rejected4401ShowsNoticeAndStopsReconnecting()
@Test fun replaced4409ReconnectsOnNextWake()
```
- [ ] **Step 2:** FAIL. **Step 3:** Implement `Conversation` (single class, `when(phase)` per input; no Android imports). **Step 4:** PASS (run 3×; no timing flakiness since time is fake).
- [ ] **Step 5:** Commit `feat(android): conversation state machine`.

---

### Task 5: GatewayClient (OkHttp WebSocket)

**Files:** Create `net/GatewayClient.kt`; Test `GatewayClientTest.kt` (JVM, `mockwebserver3`).

**Interfaces:**
- Consumes: `ClientMsg.hello`, `parseServer`, `Backoff`.
- Produces: `class GatewayClient(private val url: String, private val token: String, private val listener: Listener, private val scope: CoroutineScope, private val http: OkHttpClient = defaultClient())` with `fun connect()`, `fun reconnectNow()`, `fun sendText(json: String): Boolean`, `fun sendAudio(pcm: ByteArray): Boolean`, `fun close()`; `interface Listener { fun onConnected(); fun onDisconnected(); fun onMessage(msg: ServerMsg); fun onAudio(pcm: ByteArray); fun onRejected(code: Int) }`. `defaultClient()` sets `pingInterval(25 s)`.
- Rules: `hello` is the first frame on every open; on failure/close schedule reconnect via `Backoff` (reset on successful open); close code 4401 → `onRejected(4401)`, no retry; 4409 → `onRejected(4409)`, no retry until `reconnectNow()`; `sendAudio` returns false (drops) when not connected — never queues audio for later. Token never in logs/exception text.

- [ ] **Step 1: Failing tests** — `helloIsFirstFrame`, `textAndBinaryAreRouted` (server sends state JSON + binary → `onMessage` + `onAudio`), `reconnectsAfterDropWithHello` (server closes → second connection's first frame is hello), `code4401StopsRetrying`, `code4409WaitsForReconnectNow`, `audioWhileDisconnectedIsDropped`, `tokenNotInToString` (`client.toString()` and any thrown exception messages don't contain the token). Use a short `Backoff(listOf(10, 20))` in tests.
- [ ] **Step 2:** FAIL. **Step 3:** Implement with OkHttp `WebSocketListener`. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(android): gateway WebSocket client`.

---

### Task 6: Audio and wake-word adapters

**Files:** Create `audio/AudioIn.kt`, `audio/AudioOut.kt`, `audio/WakeWord.kt`, `audio/PorcupineWakeWord.kt`, `audio/Vad.kt`, `audio/SileroVad.kt`; Test `androidTest/AudioAdaptersSmokeTest.kt`.

**Interfaces — Produces:**
- `interface WakeWord : AutoCloseable { fun process(frame: ShortArray): Boolean }`; `class PorcupineWakeWord(context, accessKey: String, sensitivity: Float)` using built-in keyword **JARVIS** (verify the enum `Porcupine.BuiltInKeyword.JARVIS` exists in 4.0.2; if not, report BLOCKED — do not substitute another keyword). Throws `WakeWordUnavailable(reason)` with no key in the message.
- `interface Vad : AutoCloseable { fun isSpeech(frame: ShortArray): Boolean }`; `class SileroVad(context)` at 16 kHz, frame 512, mode NORMAL, speech/silence durations handled by `Conversation` (configure the library's own smoothing to the minimum).
- `class AudioIn(onFrame: (ShortArray) -> Unit)` — `start()`/`stop()`, `AudioRecord` `VOICE_COMMUNICATION`, 16 kHz, mono, 16-bit, reads 512-sample frames on its own thread; enables `AcousticEchoCanceler`/`NoiseSuppressor` when available.
- `class AudioOut` — `AudioTrack` streaming mode, 24 kHz mono 16-bit, `USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH`; `write(pcm: ByteArray)` (non-blocking queue to a writer thread), `flush()` (drops queued + `pause/flush/play`), `level(): Float` (RMS of last written block ×4, clipped 0..1), requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` on first write and abandons it on idle; `close()`.
- `object Tones { fun chime(); fun error() }` — short generated sine tones played through `AudioOut`-independent `ToneGenerator` (no assets).

- [ ] **Step 1:** Instrumented smoke test (runs on emulator/phone): `SileroVad` returns false for 20 frames of zeros and doesn't throw; `AudioOut` writes 1 s of silence and `flush()` returns; `PorcupineWakeWord` with an invalid key throws `WakeWordUnavailable` whose message doesn't contain the key.
- [ ] **Step 2:** `./gradlew connectedDebugAndroidTest` on the emulator (`Medium_Phone_API_37.0`) → FAIL before implementation, PASS after. **Step 3:** Commit `feat(android): audio, wake-word and VAD adapters`.

---

### Task 7: Settings, secrets and onboarding screens

**Files:** Create `settings/SecretBox.kt`, `settings/Settings.kt`, `ui/SettingsActivity.kt`, `ui/Onboarding.kt`; Tests `SettingsTest.kt` (JVM, with an in-memory `SecretBox` fake) and `androidTest/SecretBoxTest.kt`.

**Interfaces — Produces:**
- `interface SecretBox { fun encrypt(plain: String): String; fun decrypt(cipher: String): String }`; `class KeystoreSecretBox(alias = "jarvis-secrets")` — AES-256-GCM key in AndroidKeyStore, output `base64(iv||ciphertext)`.
- `class Settings(prefs: SharedPreferences, box: SecretBox)` with properties `serverUrl`, `deviceToken: String?`, `picovoiceKey: String?`, `wakeSensitivity`, `endSilenceMs`, `interruptBySpeech`, `startOnBoot` (defaults per Global Constraints), `val isComplete: Boolean` (token and key present), `fun hasSecret(name)`. Secrets stored only encrypted; `toString()` lists no secret values.
- `SettingsActivity`: plain Views form; secret fields show "••• saved" with a Replace button, never the value; validates `serverUrl` starts with `wss://`.
- `Onboarding` (called from `MainActivity.onCreate`): requests `RECORD_AUDIO` and `POST_NOTIFICATIONS` with a rationale dialog; opens `SettingsActivity` when `!isComplete`; offers `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` once (remembered).

- [ ] **Step 1: Failing tests** — `defaultsMatchSpec`, `secretsStoredEncrypted` (prefs raw value ≠ plain), `isCompleteNeedsBoth`, `toStringHidesSecrets`, `rejectsNonWssUrl`; instrumented `keystoreRoundTrip`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS (JVM + emulator). **Step 5:** Commit `feat(android): settings, encrypted secrets, onboarding`.

---

### Task 8: JarvisService wiring

**Files:** Create `service/JarvisService.kt`, `service/BootReceiver.kt`; Modify `AndroidManifest.xml`; Test `androidTest/ServiceSmokeTest.kt`.

**Interfaces:**
- Consumes: everything from Tasks 2–7.
- Produces: `JarvisService` (foreground, `foregroundServiceType="microphone"`), actions `ACTION_START`, `ACTION_TALK`, `ACTION_STOP`; exposes `companion object { val uiState: StateFlow<UiState> }` (process-wide, for `MainActivity`); notification channel "Jarvis" with state text and Talk/Stop actions.
- Wiring: one single-thread dispatcher runs `Conversation`; `AudioIn` frames → `PreRoll.push`, `WakeWord.process`, `Vad.isSpeech` (VAD only in the phases the spec lists) → `Conversation.onFrame`; `GatewayClient.Listener` → `Conversation.onServer/onServerAudio/onConnected/onDisconnected`; `Effects` → `GatewayClient`, `AudioOut`, `Tones`. Partial wake lock held only in LISTENING/THINKING/SPEAKING. Audio-focus loss / `AudioRecord` start failure (mic busy) → `onPause`, retry every 5 s → `onResume`. Missing settings → service refuses to start and posts a notification "Set up Jarvis".
- `BootReceiver` starts the service on `BOOT_COMPLETED` only when `startOnBoot` is on and settings are complete.
- Manifest: permissions from spec §6, `usesCleartextTraffic="false"`, service + receiver declarations.

- [ ] **Step 1:** Instrumented smoke test on the emulator: with settings filled (fake token/key), starting the service puts it in the foreground and posts the notification; with incomplete settings it stops itself. (Porcupine init with a fake key is expected to fail → notice "Wake word unavailable — tap to talk", service stays up.)
- [ ] **Step 2:** FAIL → implement → PASS. **Step 3:** Commit `feat(android): foreground service wiring`.

---

### Task 9: Orb UI

**Files:** Move `prototype/orb.html` → `ui/orb/orb.html` (git mv); Create `ui/orb/vendor/` with Three.js 0.147.0 `three.min.js` and `examples/js` CopyShader, LuminosityHighPassShader, EffectComposer, RenderPass, ShaderPass, UnrealBloomPass (downloaded once from `cdn.jsdelivr.net/npm/three@0.147.0/...`, checked into git, SHA-256 listed in `ui/orb/vendor/SOURCES.md`); Modify `android/app/build.gradle.kts` (copy task `../ui/orb` → `src/main/assets/orb` before `preBuild`, generated folder git-ignored); Create `ui/MainActivity.kt`, `ui/OrbBridge.kt`.

**Interfaces:**
- `orb.html`: script tags point to `vendor/…`; when the URL has `?embedded=1`, hide `#ui` and `#label`; keep `window.JarvisOrb.setState/setLevel`.
- `class OrbBridge(webView: WebView)` — `fun push(ui: UiState)`: calls `JarvisOrb.setState(...)` only when `orbState` changes and `JarvisOrb.setLevel(...)` at most 30×/s; level = `micLevel` in LISTENING/FOLLOW_UP else `outLevel`.
- `MainActivity`: full-screen WebView loading `file:///android_asset/orb/orb.html?embedded=1` (JS on, `allowFileAccess` only for assets, no network), caption overlay (transcript, reply), connection dot, gear → Settings; tap on the WebView → `JarvisService.ACTION_TALK`; `onPause` → `webView.onPause()`, `onResume` → `onResume()`; starts the service via Onboarding when settings are complete.

- [ ] **Step 1:** Open `ui/orb/orb.html` in the browser pane (desktop) to confirm it still renders with vendored scripts and no network (`read_console_messages` shows no errors).
- [ ] **Step 2:** JVM test `OrbBridgeTest` with a fake JS sink: `stateSentOnlyOnChange`, `levelThrottledTo30PerSecond`, `levelSourceFollowsPhase`.
- [ ] **Step 3:** FAIL → implement → PASS; `./gradlew assembleDebug`; install on the emulator, screenshot shows the orb.
- [ ] **Step 4:** Commit `feat(android): orb UI with WebView bridge`.

---

### Task 10: On-device verification (manual, with the user)

Needs: user's phone with USB debugging, the `android` device token (from `/root/jarvis-device-tokens.txt` on the VPS, entered by the user in Settings), a Picovoice AccessKey.

- [ ] **Step 1:** `adb install -r app-debug.apk`; user completes onboarding and Settings.
- [ ] **Step 2:** Run the spec §9 checklist; record results in `docs/android-test-results.md` (wake pt/en, follow-up, interrupt by "Jarvis" and tap, `interruptBySpeech` on loudspeaker, screen off 10 min, incoming call, airplane mode, swipe-kill, latency vs `bench.py`).
- [ ] **Step 3:** Battery: `adb shell dumpsys batterystats --reset`, 1 h in WAITING screen off, `dumpsys batterystats` → record drain; target < 5 %/h.
- [ ] **Step 4:** Commit `docs(android): device test results`.
