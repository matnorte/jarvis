"""ElevenLabs streaming TTS over WebSocket, plus pre-generated canned lines."""

import asyncio
import base64
import json
import logging
from collections.abc import AsyncIterator
from pathlib import Path
from urllib.parse import urlencode

import websockets

log = logging.getLogger(__name__)

MODEL_ID = "eleven_flash_v2_5"
OUTPUT_FORMAT = "pcm_24000"

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
    ) -> None:
        self._api_key = api_key
        self._voice_id = voice_id
        self._base_url = base_url.rstrip("/")

    def _url(self, lang: str) -> str:
        query = urlencode(
            {
                "model_id": MODEL_ID,
                "output_format": OUTPUT_FORMAT,
                "language_code": lang,
            }
        )
        return f"{self._base_url}/v1/text-to-speech/{self._voice_id}/stream-input?{query}"

    async def _send_all(self, ws, chunks: AsyncIterator[str]) -> None:
        await ws.send(json.dumps({"text": " ", "xi_api_key": self._api_key}))
        async for chunk in chunks:
            if chunk:
                await ws.send(
                    json.dumps({"text": chunk, "try_trigger_generation": True})
                )
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
        try:
            while True:
                try:
                    raw = await ws.recv()
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
            sender.cancel()
            try:
                await sender
            except BaseException:  # noqa: BLE001 - cancelled or already surfaced
                pass
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
