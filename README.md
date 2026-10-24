# Jarvis backend

Voice gateway: WebSocket in (PCM16 16 kHz) -> faster-whisper STT -> Hermes Agent
(fast gpt-5.6-luna profile, escalating to a deep gpt-6.1-sol profile on `[[DEEP]]`, both via
OpenAI Codex OAuth) -> ElevenLabs TTS
-> WebSocket out (PCM16 24 kHz). Persona in `persona/SOUL.md` (fast profile, with the
routing rule) and `persona/SOUL.deep.md` (deep profile, same persona without it).

## Test locally

    python -m uv run pytest

## Deploy (Ubuntu VPS)

1. DNS: A record `jarvis.openclawchip.tech` -> VPS IP.
2. Hermes: put `API_SERVER_KEY` in `/root/jarvis-secrets.env` (root-only, mode 600),
   then `sudo bash deploy/install-hermes.sh`. Log in to OpenAI Codex once (device code,
   shared by both profiles):
   `sudo -u hermes -H /home/hermes/.local/bin/hermes auth add openai-codex`, then
   `systemctl restart hermes-gateway` (one Hermes gateway serves both profiles at
   `127.0.0.1:8642/p/fast` and `/p/deep`). Models can be overridden with
   `FAST_MODEL=... DEEP_MODEL=...` when running the script.
   Read the `# VERIFY:` notes in the script header; they list steps not confirmed
   against the Hermes docs.
3. Config: `cp deploy/.env.example deploy/.env && chmod 600 deploy/.env`, fill in
   `ELEVENLABS_API_KEY`, `ELEVENLABS_VOICE_ID` and `API_SERVER_KEY` (must be identical
   to the one in `/root/jarvis-secrets.env`).
4. Start: `docker compose -f deploy/docker-compose.yml up -d --build`
5. Device tokens: `python -m scripts.tokens add win --env deploy/.env` (prints the
   token; `revoke <device>` removes it), then
   `docker compose -f deploy/docker-compose.yml up -d` to reload the env file
   (`docker compose restart` does not, so a revoked token would keep working).

Firewall allows only 22, 80, 443. Hermes (8642) and the gateway (8000) bind 127.0.0.1.

## Tools

    python -m scripts.talk --url wss://jarvis.openclawchip.tech/ws --token T in.wav out.wav
    python -m scripts.bench --url wss://jarvis.openclawchip.tech/ws --token T samples/*.wav

`talk` streams a 16 kHz mono wav and writes the reply to a 24 kHz wav. `bench` prints
end-of-upload -> transcript / first reply text / first audio per sample and exits non-zero
if the median to first audio exceeds 2.0 s.

## Android

Native Kotlin client in `android/` (package `com.openclawchip.jarvis`, minSdk 31).
Build and run the JVM tests with Android Studio's JDK and an SDK path in
`android/local.properties` (`sdk.dir=...`, git-ignored):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd android
./gradlew testDebugUnitTest assembleDebug
```

The debug APK lands in `android/app/build/outputs/apk/debug/app-debug.apk`.

Install with `adb -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`
from `android/`, then open Jarvis. In Settings, tap **Pair with Hermes**. Open
Hermes → Jarvis through the [dashboard](docs/hermes-dashboard.md), match the
public request identifier on your phone, and approve it there. The credential goes
directly into encrypted phone settings; no token copying is needed. All voice,
listening, camera and connection preferences are edited in that native Hermes tab.
Wake detection is offline with sherpa-onnx; no Picovoice account or key is required.
After pairing, allow microphone and
notification permissions, and optionally allow unrestricted battery use.

Say “Jarvis” or tap the orb to talk. Reply during the six-second follow-up window
without another wake. Wake or tap during a reply to interrupt. The foreground
notification has Talk and Stop actions. Optional startup after reboot is a tap-to-start
notification to respect Android's microphone restrictions.

The shared offline orb lives in `ui/orb/`; Gradle syncs it into generated app assets.
See [task verification](docs/android-task-verification.md) for Windows commands and
phone checks, and [device results](docs/android-test-results.md) for the remaining
manual verification.

The official Hermes configuration UI is available on your phone at
[Hermes → Jarvis](https://jarvis.openclawchip.tech/jarvis?profile=fast) with native
Nous sign-in; see
[Hermes dashboard](docs/hermes-dashboard.md). Offline wake runtime/model download
versions and hashes are documented in [wake assets](android/WAKE_ASSETS.md).
