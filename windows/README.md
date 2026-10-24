# Jarvis for Windows

Windows 10/11 x64 voice companion: offline Jarvis wake word, shared golden orb,
system tray, compact always-on-top window and Ctrl+Shift+Space to talk/interrupt.
The first version intentionally has no webcam or Windows app-control features.

## Use

Run the portable executable, or install the per-user setup executable from `dist/`.
Open **Pair with Hermes**, then approve the matching **Windows PC** request in
[Hermes → Jarvis](https://jarvis.openclawchip.tech/jarvis?profile=fast). Windows uses
its own credential and does not replace the Android phone. Configuration remains
inside Hermes. Click Start listening, then say “Jarvis” or press Ctrl+Shift+Space.

Pause releases the microphone and inference process. Close/Hide keeps Jarvis in
the tray; Quit in the tray stops it completely. Compact stays above other windows.
Sleep/lock pauses capture; resume explicitly. The startup preference in Hermes
starts a stopped tray app at login, so it never silently turns on the microphone.
For portable builds, keep the launcher at its chosen location so startup can find it.

Pairing is encrypted using Windows DPAPI in
`%APPDATA%/jarvis-windows/pairing.json`. Updates retain this file. No provider keys,
device token or pairing proof is bundled in the app or sent to the renderer. The
preview is unsigned; no signing certificate has been configured.

## Develop and verify (Windows PowerShell)

Node 24+ and Windows `tar.exe` are required. No Visual Studio compiler is required
for the pinned prebuilt x64 sherpa-onnx runtime. From the repository root:

```powershell
cd windows
npm.cmd ci
# If npm's script policy skipped Electron's official runtime download:
node node_modules/electron/install.js
npm.cmd run prepare:assets
npm.cmd test
npm.cmd run smoke
npm.cmd run smoke:long
npm.cmd start
```

Build portable and per-user NSIS installer:

```powershell
npm.cmd run build
& ./dist/win-unpacked/Jarvis.exe --smoke-test --smoke-long-reply
& ./dist/Jarvis-0.1.1-x64-portable.exe --smoke-test --smoke-long-reply
if ($LASTEXITCODE -ne 0) { throw 'Portable smoke failed' }
```

Smoke mode uses an isolated temporary profile, a fake gateway and Chromium's
synthetic microphone. It never captures real microphone audio or reads the owner's
pairing. It exercises real Electron isolation, Windows encryption, the native voice
utility, Web Audio capture, pause cleanup and actual playback drain. The long mode
queues two minutes of audio ahead of playback, verifies capture stays alive, then
flushes and verifies a short reply drains. Native wake tests also detect a synthetic
Jarvis fixture and reject silence.

Assets are downloaded from official sherpa releases and verified against pinned
SHA-256 values. The KWS archive hash matches Android's. Only four model files are
extracted, and the VAD hash matches the GitHub release digest. Shared orb assets
come from `ui/orb`; node_modules/models/build outputs stay outside Git.
See [asset attribution](ASSETS.md) and [verification](../docs/windows-task-verification.md).

## v0.1.1 long-reply correction

TTS can deliver speech faster than it plays. The original 30-second queue overflowed
on long answers and incorrectly raised a microphone-permission error. The queue now
holds up to ten minutes (28.8 MB PCM16), retains generation checks, and never silently
drops words. Overflow is a playback-specific error which cancels that reply while
keeping the healthy microphone/wake engine available. Failed output initialization
releases its own context and permits retry without closing a replacement context.

Follow-up still requires both server completion and the final local audible drain.
No voice wire format, Android turn semantics or server model selection changes.
