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
from server.vision import CameraFrameError

log = logging.getLogger(__name__)

CLOSE_UNAUTHORIZED = 4401
CLOSE_REPLACED = 4409
AUTH_TIMEOUT_S = 5.0  # the hello must arrive this soon after connecting
MAX_HELLO_CHARS = 1024  # a longer pre-auth text frame is rejected unparsed


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

    tts = ElevenLabsTTS(
        settings.elevenlabs_api_key, settings.elevenlabs_voice_id, model_id=settings.tts_model
    )
    return Deps(
        stt=Transcriber(settings.stt_model),
        router=Router(
            HermesClient(settings.hermes_fast_url, settings.hermes_api_key, http),
            HermesClient(settings.hermes_deep_url, settings.hermes_api_key, http),
        ),
        tts=tts,
        # Per voice+model subfolder, so changing either regenerates the lines.
        canned=CannedLines(settings.canned_dir / tts.cache_key, tts),
    )


def create_app(
    settings: Settings,
    deps: Deps | None = None,
    auth_timeout: float = AUTH_TIMEOUT_S,
) -> FastAPI:
    # websockets logs every frame at DEBUG, including the ElevenLabs
    # xi_api_key; never let a DEBUG root config print those.
    logging.getLogger("websockets").setLevel(logging.INFO)
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
    active: dict[str, _Conn] = {}  # newest registered connection per device
    live: dict[str, _Conn] = {}  # connection owning a started Session
    locks: dict[str, asyncio.Lock] = {}

    @app.get("/health")
    async def health() -> dict:
        return {"ok": True}

    @app.websocket("/ws")
    async def ws_endpoint(ws: WebSocket) -> None:
        await ws.accept()
        authenticated = await _authenticate(ws)
        if authenticated is None:
            return
        device, client_vad = authenticated

        conn = _Conn(ws)
        previous = active.get(device)
        active[device] = conn  # newest connection wins, synchronously
        try:
            if previous is not None:
                await _safe_close(previous.ws, CLOSE_REPLACED)
            async with locks.setdefault(device, asyncio.Lock()):
                # Serialized per device: replaced waiters bail out here, and
                # the newest one starts only after the live session closed.
                if active.get(device) is not conn:
                    await _safe_close(ws, CLOSE_REPLACED)
                    return
                old = live.get(device)
                if old is not None and old is not conn:
                    await _safe_close(old.ws, CLOSE_REPLACED)
                    if old.session is not None:
                        await old.session.close()
                if active.get(device) is not conn:
                    await _safe_close(ws, CLOSE_REPLACED)
                    return

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
                    client_vad=client_vad,
                )
                live[device] = conn
            await _pump(ws, conn.session, send_text)
        except WebSocketDisconnect:
            pass
        finally:
            if conn.session is not None:
                await conn.session.close()
            if active.get(device) is conn:
                del active[device]
            if live.get(device) is conn:
                del live[device]

    async def _authenticate(ws: WebSocket) -> tuple[str, bool] | None:
        device = None
        client_vad = False
        try:
            msg = await asyncio.wait_for(ws.receive(), auth_timeout)
            text = msg.get("text")
            if (
                msg["type"] == "websocket.receive"
                and text is not None
                and len(text) <= MAX_HELLO_CHARS
            ):
                parsed = protocol.parse_client(text)
                if isinstance(parsed, protocol.Hello):
                    device = _device_for(settings.device_tokens, parsed.token)
                    client_vad = parsed.client_vad
        except (protocol.ProtocolError, TimeoutError):
            pass
        except WebSocketDisconnect:
            return None
        if device is None:
            await _safe_close(ws, CLOSE_UNAUTHORIZED)
        return (device, client_vad) if device is not None else None

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
            await session.on_text(parsed.text, parsed.lang)
        elif isinstance(parsed, protocol.Camera):
            session.on_camera(parsed.enabled)
        elif isinstance(parsed, protocol.CameraFrame):
            try:
                session.on_camera_frame(parsed.jpeg)
            except CameraFrameError:
                await send_text(protocol.error("bad_camera_frame", "Invalid camera frame"))
        else:  # repeated hello: ignore
            log.debug("ignored repeated hello")
