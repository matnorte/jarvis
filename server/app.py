"""FastAPI app: /health and the authenticated /ws voice endpoint."""
from __future__ import annotations

import asyncio
import logging
import secrets
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass

import httpx
from fastapi import FastAPI, WebSocket
from starlette.websockets import WebSocketDisconnect

from server import protocol
from server.config import Settings, _parse_device_tokens
from server.control_client import ControlUnavailable
from server.managed_runtime import ManagedRuntime
from server.pairing import mount_pairing
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
    control=None,
    control_interval: float = 2.0,
) -> FastAPI:
    # websockets logs every frame at DEBUG, including the ElevenLabs
    # xi_api_key; never let a DEBUG root config print those.
    logging.getLogger("websockets").setLevel(logging.INFO)
    http: httpx.AsyncClient | None = None
    real = deps is None
    if deps is None:
        http = httpx.AsyncClient()
        deps = _build_real_deps(settings, http)

    async def prepare(new_settings, previous_settings, previous_deps):
        if not real:
            return previous_deps
        updated = _build_real_deps(new_settings, http)
        if new_settings.stt_model == previous_settings.stt_model:
            updated.stt = previous_deps.stt
        else:
            await asyncio.to_thread(updated.stt._get_model)
        if (new_settings.hermes_fast_url, new_settings.hermes_deep_url, new_settings.hermes_api_key) == (
                previous_settings.hermes_fast_url, previous_settings.hermes_deep_url, previous_settings.hermes_api_key):
            updated.router = previous_deps.router
        if (new_settings.elevenlabs_api_key, new_settings.elevenlabs_voice_id, new_settings.tts_model) == (
                previous_settings.elevenlabs_api_key, previous_settings.elevenlabs_voice_id, previous_settings.tts_model):
            updated.tts, updated.canned = previous_deps.tts, previous_deps.canned
        else:
            await updated.canned.ensure()
        return updated

    runtime = ManagedRuntime(control, settings, deps, prepare) if control is not None else None

    async def poll_control():
        while True:
            await asyncio.sleep(control_interval)
            try:
                await runtime.tick()
            except ControlUnavailable:
                continue  # Keep the last working voice pipeline during a control-service outage.

    async def report_control():
        # Model preparation can take minutes; keep status alive while the old voice pipeline works.
        while True:
            await asyncio.sleep(control_interval)
            try:
                await runtime.report()
            except ControlUnavailable:
                continue

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        pollers = []
        try:
            if runtime is not None:
                for attempt in range(30):
                    try:
                        await runtime.tick()
                        break
                    except ControlUnavailable:
                        if attempt == 29:
                            raise
                        await asyncio.sleep(1)
                pollers = [asyncio.create_task(poll_control()), asyncio.create_task(report_control())]
            current = runtime.deps if runtime is not None else deps
            if real:
                await asyncio.to_thread(current.stt._get_model)  # warm whisper
            await current.canned.ensure()
            yield
        finally:
            for poller in pollers:
                poller.cancel()
            for poller in pollers:
                with suppress(asyncio.CancelledError):
                    await poller
            if control is not None:
                await control.close()
            if http is not None:
                await http.aclose()

    app = FastAPI(lifespan=lifespan)
    if control is not None:
        mount_pairing(app, control)
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
        device, client_vad, client_config = authenticated

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

                current = runtime.deps if runtime is not None else deps
                session_options = {'client_vad': client_vad}
                if runtime is not None:
                    session_options['vision_enabled'] = runtime.phone.get('vision_enabled', True)
                conn.session = Session(
                    device, send_text, send_bytes,
                    current.stt, current.router, current.tts, current.canned,
                    **session_options,
                )
                live[device] = conn
            if runtime is not None and client_config:
                await runtime.attach(ws, conn.session, send_text, device=device)
            await _pump(ws, conn.session, send_text, runtime)
        except WebSocketDisconnect:
            pass
        finally:
            if runtime is not None:
                runtime.detach(ws)
            if conn.session is not None:
                await conn.session.close()
            if active.get(device) is conn:
                del active[device]
            if live.get(device) is conn:
                del live[device]

    async def _authenticate(ws: WebSocket) -> tuple[str, bool, bool] | None:
        device = None
        client_vad = False
        client_config = False
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
                    tokens = settings.device_tokens
                    if control is not None:
                        try:
                            snapshot = await control.runtime()
                            tokens = _parse_device_tokens(snapshot['env'].get('DEVICE_TOKENS', ''))
                        except ControlUnavailable:
                            await _safe_close(ws, 1013)
                            return None
                    device = _device_for(tokens, parsed.token)
                    client_vad = parsed.client_vad
                    client_config = parsed.client_config
        except (protocol.ProtocolError, TimeoutError):
            pass
        except WebSocketDisconnect:
            return None
        if device is None:
            await _safe_close(ws, CLOSE_UNAUTHORIZED)
        return (device, client_vad, client_config) if device is not None else None

    return app


async def _safe_close(ws: WebSocket, code: int) -> None:
    try:
        await ws.close(code)
    except Exception as exc:  # already closed
        log.debug("close(%s) skipped: %s", code, type(exc).__name__)


async def _pump(ws: WebSocket, session: Session, send_text, runtime=None) -> None:
    while True:
        msg = await ws.receive()
        if msg["type"] == "websocket.disconnect":
            return
        data = msg.get("bytes")
        if data is not None:
            if data and len(data) % 2 == 0:
                if runtime is not None:
                    runtime.active(ws)
                await session.on_audio(data)
            else:
                log.debug("dropped %d-byte audio frame", len(data))
            continue
        try:
            parsed = protocol.parse_client(msg.get("text") or "")
        except protocol.ProtocolError as exc:
            await send_text(protocol.error("bad_message", str(exc)))
            continue
        if isinstance(parsed, protocol.ConfigAck):
            if runtime is not None:
                await runtime.ack(ws, parsed.revision, parsed.idle)
        elif isinstance(parsed, protocol.EndUtterance):
            await session.on_end_utterance()
        elif isinstance(parsed, protocol.Interrupt):
            if runtime is not None:
                runtime.active(ws)
            await session.on_interrupt()
        elif isinstance(parsed, protocol.TextInput):
            if runtime is not None:
                runtime.active(ws)
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
