# Jarvis backend

Voice gateway: WebSocket in (PCM16 16 kHz) -> faster-whisper STT -> Hermes Agent
(fast Haiku profile, escalating to a deep Opus profile on `[[DEEP]]`) -> ElevenLabs TTS
-> WebSocket out (PCM16 24 kHz). Persona in `persona/SOUL.md` (fast profile, with the
routing rule) and `persona/SOUL.deep.md` (deep profile, same persona without it).

## Test locally

    python -m uv run pytest

## Deploy (Ubuntu VPS)

1. DNS: A record `jarvis.openclawchip.tech` -> VPS IP.
2. Hermes: put `ANTHROPIC_API_KEY` and `API_SERVER_KEY` in `/root/jarvis-secrets.env`
   (root-only, mode 600), then `sudo bash deploy/install-hermes.sh`. The Anthropic key
   lives only in that file (and the Hermes profiles); it never goes in `deploy/.env`.
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

Firewall allows only 22, 80, 443. Hermes (8642/8643) and the gateway (8000) bind 127.0.0.1.

## Tools

    python -m scripts.talk --url wss://jarvis.openclawchip.tech/ws --token T in.wav out.wav
    python -m scripts.bench --url wss://jarvis.openclawchip.tech/ws --token T samples/*.wav

`talk` streams a 16 kHz mono wav and writes the reply to a 24 kHz wav. `bench` prints
end-of-upload -> transcript / first reply text / first audio per sample and exits non-zero
if the median to first audio exceeds 2.0 s.
