# Jarvis Windows voice companion

2026-10-24. The owner chose: desktop voice companion, shared orb, offline wake,
tray and global talk hotkey. Camera and controlling Windows apps are outside this
first version. Android and the public Hermes UI are user-confirmed working.

## App

Electron on Windows 10/11 x64, with a sandboxed renderer and isolated preload.
Reuse the vendored Three.js orb. Graphite/amber assistant window with retained
transcript/reply, connection and listening status, Talk/Pause, compact mode and
Open Hermes. Close hides to tray; tray has Show, Talk, Pause/Resume and Quit.
Ctrl+Shift+Space talks/interrupts, with visible failure if Windows cannot register it.
User must start listening explicitly; Pause releases the microphone, and Quit stops
audio, inference, sockets and the shortcut. Resume/reconnect never silently undo Pause.

All preferences remain in Hermes. Pair by matching a short-lived public request ID
in Hermes's Jarvis tab. Request optionally declares `device: win`; omitted means
Android for compatibility. Approval is bound to that device type and private claim.
Use the existing separate win token, never the Android token. No credentials enter
renderer state, URLs, browser storage or logs. Encrypt the token with Electron's
Windows safeStorage (DPAPI); refuse plaintext fallback. Cache only nonsecret settings.
Shared voice settings apply to both clients, outside turns/follow-up, with existing
config acknowledgements. Show each connected device separately in Hermes.

## Audio and turn policy

Renderer Web Audio capture with echo cancellation/noise suppression, mono PCM16
16 kHz, 512-sample frames; resample actual device rate. Bounded queues with capture
epochs and source guards. A separate utility process owns sherpa-onnx 2.0.0 native
keyword spotting and Silero VAD, with pinned/checksummed local models. No account
or wake key. Model failure leaves tap/hotkey available and reports degradation.

Main process owns credentials, WSS, pairing and the serial conversation policy.
Port Android's tested semantics: 300 ms pre-roll, speech end after configured silence,
6 s no-speech/follow-up, 30 s utterance cap, ten speech frames for optional barge-in,
interrupt+flush before new audio, stale-reply suppression until thinking, and
follow-up only after server done AND local playback drain, in either order.
Playback uses PCM24k with a bounded queue and generations; flush drops scheduled
and pending audio, including stale IPC/drain callbacks. Pause emits no voice Effects.
No binary PCM or existing turn semantics change. Additive pairing/device status
metadata is documented; old Android payloads continue to work.

## Boundaries and delivery

No Node in renderer, no arbitrary IPC/shell/path/URL commands, deny navigation and
new windows, restrict media permission to the app's main frame and microphone.
External Open Hermes uses only the fixed HTTPS dashboard origin. CSP blocks remote
scripts; shared orb is local. Startup-at-login follows Hermes start_on_boot and
opens a stopped/tray reminder, not a live microphone. Sleep/lock releases capture;
resume stays paused until explicit user action. No camera permission or PC control.

TDD for protocol/turn/audio/pairing/storage/device isolation; full suites before
each small conventional commit. Build a Windows portable executable and installer,
verify actual native window/tray/hotkey and synthetic wake/playback behavior.
Manual real-voice/noise/latency/power checks require the owner; record actual results.
