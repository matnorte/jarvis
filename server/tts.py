"""ElevenLabs streaming TTS over WebSocket, plus pre-generated canned lines.

API notes (docs: elevenlabs.io/docs/api-reference/text-to-speech/
v-1-text-to-speech-voice-id-stream-input and the realtime-tts guide, fetched
2026-10-23):
- Query `auto_mode` (bool, default false) "focuses on reducing the latency by
  disabling the chunk schedule and all buffers"; recommended when sending full
  sentences. The ClauseChunker sends whole clauses, so with auto_mode each one
  is generated as soon as it arrives instead of waiting for the default
  chunk_length_schedule ([120, 160, 250, 290] chars).
- Each text message "should always end with a single space".
- try_trigger_generation is an advanced knob the docs advise leaving false; it
  is redundant with auto_mode and no longer sent.
"""

import asyncio
import base64
import json
import logging
from collections.abc import AsyncIterator
from pathlib import Path
from urllib.parse import urlencode

import websockets

log = logging.getLogger(__name__)

# Turbo v2.5: more natural prosody than Flash, still on the stream-input WebSocket.
MODEL_ID = "eleven_turbo_v2_5"
OUTPUT_FORMAT = "pcm_24000"
# Lower stability and some style give a livelier, less monotone delivery.
VOICE_SETTINGS = {
    "stability": 0.35,
    "similarity_boost": 0.8,
    "style": 0.35,
    "use_speaker_boost": True,
}

CANNED_TEXT: dict[str, dict[str, str]] = {
    "ack_deep": {
        "en": "Let me think about that properly, sir.",
        "pt": "Deixe-me pensar nisso com calma, senhor.",
    },
    "one_moment": {
        "en": "One moment, sir.",
        "pt": "Um momento, senhor.",
    },
    "error": {
        "en": "I'm having trouble reaching my systems, sir.",
        "pt": "Estou com dificuldade para acessar meus sistemas, senhor.",
    },
}


class TTSError(Exception):
    pass


class ElevenLabsTTS:
    def __init__(
        self,
        api_key: str,
        voice_id: str,
        base_url: str = "wss://api.elevenlabs.io",
        recv_timeout: float = 10.0,
        model_id: str = MODEL_ID,
        voice_settings: dict | None = None,
    ) -> None:
        self._recv_timeout = recv_timeout
        self._api_key = api_key
        self._voice_id = voice_id
        self._base_url = base_url.rstrip("/")
        self._model_id = model_id
        self._voice_settings = dict(VOICE_SETTINGS if voice_settings is None else voice_settings)

    @property
    def cache_key(self) -> str:
        """Identifies the sound of this voice; canned lines are cached per key."""
        return f"{self._voice_id}-{self._model_id}"

    def _url(self, lang: str) -> str:
        query = urlencode(
            {
                "model_id": self._model_id,
                "output_format": OUTPUT_FORMAT,
                "language_code": lang,
                "auto_mode": "true",
            }
        )
        return f"{self._base_url}/v1/text-to-speech/{self._voice_id}/stream-input?{query}"

    async def _send_all(self, ws, chunks: AsyncIterator[str]) -> None:
        await ws.send(
            json.dumps(
                {
                    "text": " ",
                    "voice_settings": self._voice_settings,
                    "xi_api_key": self._api_key,
                }
            )
        )
        async for chunk in chunks:
            if chunk:
                text = chunk if chunk.endswith(" ") else chunk + " "
                await ws.send(json.dumps({"text": text}))
        await ws.send(json.dumps({"text": ""}))

    async def synthesize(
        self, chunks: AsyncIterator[str], lang: str
    ) -> AsyncIterator[bytes]:
        try:
            ws = await websockets.connect(self._url(lang))
        except (OSError, websockets.WebSocketException) as exc:
            raise TTSError(
                f"ElevenLabs connection failed: {type(exc).__name__}"
            ) from exc
        sender = asyncio.create_task(self._send_all(ws, chunks))
        recv_task: asyncio.Task | None = None
        try:
            while True:
                if recv_task is None:
                    recv_task = asyncio.create_task(ws.recv())
                sender_was_done = sender.done()
                waiting = {recv_task} if sender.done() else {recv_task, sender}
                await asyncio.wait(
                    waiting,
                    timeout=self._recv_timeout,
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if (
                    sender.done()
                    and not sender.cancelled()
                    and sender.exception() is not None
                ):
                    raise TTSError("ElevenLabs send failed") from sender.exception()
                if not recv_task.done():
                    if sender_was_done or not sender.done():
                        raise TTSError("ElevenLabs receive timed out")
                    continue  # sender just finished cleanly; keep waiting on recv
                try:
                    raw = recv_task.result()
                    recv_task = None
                except websockets.ConnectionClosed as exc:
                    raise TTSError("ElevenLabs closed before isFinal") from exc
                try:
                    msg = json.loads(raw)
                except (TypeError, ValueError) as exc:
                    raise TTSError("ElevenLabs sent an invalid message") from exc
                if not isinstance(msg, dict):
                    raise TTSError("ElevenLabs sent an invalid message")
                if msg.get("error"):
                    detail = msg.get("message") or msg["error"]
                    raise TTSError(f"ElevenLabs error: {detail}")
                audio = msg.get("audio")
                if audio:
                    yield base64.b64decode(audio)
                if msg.get("isFinal"):
                    return
        finally:
            pending = [t for t in (recv_task, sender) if t is not None]
            for t in pending:
                t.cancel()
            # return_exceptions retrieves the tasks' own errors/cancellation;
            # an outer cancellation of this coroutine still propagates.
            await asyncio.gather(*pending, return_exceptions=True)
            await ws.close()


class CannedLines:
    def __init__(self, dir: Path, tts) -> None:
        self._dir = Path(dir)
        self._tts = tts

    def _path(self, key: str, lang: str) -> Path:
        return self._dir / f"{key}_{lang}.pcm"

    async def ensure(self) -> None:
        """Synthesize any missing canned line. Never raises on TTS failure."""
        self._dir.mkdir(parents=True, exist_ok=True)
        for key, by_lang in CANNED_TEXT.items():
            for lang, text in by_lang.items():
                path = self._path(key, lang)
                if path.exists():
                    continue

                async def one(t: str = text) -> AsyncIterator[str]:
                    yield t

                try:
                    audio = b"".join(
                        [c async for c in self._tts.synthesize(one(), lang)]
                    )
                except Exception as exc:  # noqa: BLE001
                    log.warning("canned line %s_%s not generated: %s", key, lang, exc)
                    continue
                if audio:
                    path.write_bytes(audio)

    def get(self, key: str, lang: str) -> bytes:
        try:
            return self._path(key, lang).read_bytes()
        except FileNotFoundError:
            return b""
