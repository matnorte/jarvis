"""FastAPI app: /health and the authenticated /ws voice endpoint."""
from __future__ import annotations

import asyncio
import logging
import secrets
from contextlib import asynccontextmanager
from dataclasses import dataclass

import httpx
from fastapi import FastAPI, WebSocket
from starlette.websockets import WebSocketDisconnect

from server import protocol
from server.config import Settings
from server.session import Session

log = logging.getLogger(__name__)

CLOSE_UNAUTHORIZED = 4401
CLOSE_REPLACED = 4409


@dataclass
class Deps:
    stt: object
    router: object
    tts: object
    canned: object


@dataclass
class _Conn:
    ws: WebSocket
    session: Session | None = None


def _device_for(tokens: dict[str, str], token: str) -> str | None:
    """Look the token up without short-circuiting on the first match."""
    supplied = token.encode()
    found = None
    for known, device in tokens.items():
        if secrets.compare_digest(known.encode(), supplied):
            found = device
    return found


def _build_real_deps(settings: Settings, http: httpx.AsyncClient) -> Deps:
    from server.hermes_client import HermesClient
    from server.router import Router
    from server.stt import Transcriber
    from server.tts import CannedLines, ElevenLabsTTS

    tts = ElevenLabsTTS(settings.elevenlabs_api_key, settings.elevenlabs_voice_id)
    return Deps(
        stt=Transcriber(settings.stt_model),
        router=Router(
            HermesClient(settings.hermes_fast_url, settings.hermes_api_key, http),
            HermesClient(settings.hermes_deep_url, settings.hermes_api_key, http),
        ),
        tts=tts,
        canned=CannedLines(settings.canned_dir, tts),
    )


def create_app(settings: Settings, deps: Deps | None = None) -> FastAPI:
    http: httpx.AsyncClient | None = None
    real = deps is None
    if deps is None:
        http = httpx.AsyncClient()
        deps = _build_real_deps(settings, http)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        try:
            if real:
                await asyncio.to_thread(deps.stt._get_model)  # warm whisper
            await deps.canned.ensure()
            yield
        finally:
            if http is not None:
                await http.aclose()

    app = FastAPI(lifespan=lifespan)
    active: dict[str, _Conn] = {}

    @app.get("/health")
    async def health() -> dict:
        return {"ok": True}

    @app.websocket("/ws")
    async def ws_endpoint(ws: WebSocket) -> None:
        await ws.accept()
        device = await _authenticate(ws)
        if device is None:
            return

        conn = _Conn(ws)
        previous = active.get(device)
        active[device] = conn
        try:
            if previous is not None:
                await _safe_close(previous.ws, CLOSE_REPLACED)
                if previous.session is not None:
                    await previous.session.close()

            async def send_text(data: str) -> None:
                try:
                    await ws.send_text(data)
                except Exception as exc:  # socket already closed
                    log.debug("send_text dropped: %s", type(exc).__name__)

            async def send_bytes(data: bytes) -> None:
                try:
                    await ws.send_bytes(data)
                except Exception as exc:
                    log.debug("send_bytes dropped: %s", type(exc).__name__)

            conn.session = Session(
                device, send_text, send_bytes,
                deps.stt, deps.router, deps.tts, deps.canned,
            )
            await _pump(ws, conn.session, send_text)
        except WebSocketDisconnect:
            pass
        finally:
            if conn.session is not None:
                await conn.session.close()
            if active.get(device) is conn:
                del active[device]

    async def _authenticate(ws: WebSocket) -> str | None:
        device = None
        try:
            msg = await ws.receive()
            text = msg.get("text")
            if msg["type"] == "websocket.receive" and text is not None:
                parsed = protocol.parse_client(text)
                if isinstance(parsed, protocol.Hello):
                    device = _device_for(settings.device_tokens, parsed.token)
        except protocol.ProtocolError:
            pass
        except WebSocketDisconnect:
            return None
        if device is None:
            await _safe_close(ws, CLOSE_UNAUTHORIZED)
        return device

    return app


async def _safe_close(ws: WebSocket, code: int) -> None:
    try:
        await ws.close(code)
    except Exception as exc:  # already closed
        log.debug("close(%s) skipped: %s", code, type(exc).__name__)


async def _pump(ws: WebSocket, session: Session, send_text) -> None:
    while True:
        msg = await ws.receive()
        if msg["type"] == "websocket.disconnect":
            return
        data = msg.get("bytes")
        if data is not None:
            if data and len(data) % 2 == 0:
                await session.on_audio(data)
            else:
                log.debug("dropped %d-byte audio frame", len(data))
            continue
        try:
            parsed = protocol.parse_client(msg.get("text") or "")
        except protocol.ProtocolError as exc:
            await send_text(protocol.error("bad_message", str(exc)))
            continue
        if isinstance(parsed, protocol.EndUtterance):
            await session.on_end_utterance()
        elif isinstance(parsed, protocol.Interrupt):
            await session.on_interrupt()
        elif isinstance(parsed, protocol.TextInput):
            await session.on_text(parsed.text)
        else:  # repeated hello: ignore
            log.debug("ignored repeated hello")
