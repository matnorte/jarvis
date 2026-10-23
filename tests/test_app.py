import json

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from server.app import Deps, create_app
from server.config import Settings
from server.router import Delta
from server.stt import Transcript

AUDIO = b"A" * 4
TOKEN = "tok-phone"


class FakeSTT:
    def __init__(self):
        self.calls = []

    async def transcribe(self, pcm16):
        self.calls.append(pcm16)
        return Transcript("hello", "en")


class FakeRouter:
    def __init__(self):
        self.calls = []

    async def ask(self, device, text, lang):
        self.calls.append((device, text, lang))
        yield Delta("Good evening, sir.")


class FakeTTS:
    async def synthesize(self, chunks, lang):
        async for _ in chunks:
            yield AUDIO


class FakeCanned:
    def __init__(self):
        self.ensured = 0

    async def ensure(self):
        self.ensured += 1

    def get(self, key, lang):
        return b"C" * 4


def make(tokens=None, **kw):
    settings = Settings(
        elevenlabs_api_key="k",
        elevenlabs_voice_id="v",
        hermes_api_key="h",
        device_tokens=tokens or {TOKEN: "phone", "tok-desk": "desk"},
    )
    deps = Deps(stt=FakeSTT(), router=FakeRouter(), tts=FakeTTS(), canned=FakeCanned())
    return TestClient(create_app(settings, deps, **kw)), deps


def hello(token=TOKEN, device="whatever"):
    return json.dumps({"type": "hello", "token": token, "device": device})


def closed_code(ws):
    with pytest.raises(WebSocketDisconnect) as exc:
        ws.receive_text()
    return exc.value.code


def test_health():
    client, _ = make()
    assert client.get("/health").json() == {"ok": True}


def test_startup_ensures_canned_lines():
    client, deps = make()
    with client:
        pass
    assert deps.canned.ensured == 1


def test_bad_token_closes_4401():
    client, _ = make()
    with client.websocket_connect("/ws") as ws:
        ws.send_text(hello("nope"))
        assert closed_code(ws) == 4401


def test_audio_before_hello_closes_4401():
    client, deps = make()
    with client.websocket_connect("/ws") as ws:
        ws.send_bytes(b"\x01\x00" * 100)
        assert closed_code(ws) == 4401
    assert deps.stt.calls == []


def test_interrupt_before_hello_closes_4401():
    client, _ = make()
    with client.websocket_connect("/ws") as ws:
        ws.send_text(json.dumps({"type": "interrupt"}))
        assert closed_code(ws) == 4401


def test_bad_json_before_hello_closes_4401():
    client, _ = make()
    with client.websocket_connect("/ws") as ws:
        ws.send_text("not json")
        assert closed_code(ws) == 4401


def test_idle_socket_before_hello_closes_4401():
    client, deps = make(auth_timeout=0.2)
    with client.websocket_connect("/ws") as ws:
        assert closed_code(ws) == 4401  # nothing sent: closed after the timeout
    assert deps.stt.calls == [] and deps.router.calls == []


def test_oversized_hello_closes_4401():
    client, deps = make()
    big = json.dumps({"type": "hello", "token": TOKEN, "device": "x" * 1100})
    assert len(big) > 1024
    with client.websocket_connect("/ws") as ws:
        ws.send_text(big)
        assert closed_code(ws) == 4401
    assert deps.router.calls == []


def test_hello_at_size_limit_is_accepted():
    client, _ = make()
    msg = hello(device="x")
    msg = hello(device="x" * (1024 - len(msg) + 1))
    assert len(msg) == 1024
    with client.websocket_connect("/ws") as ws:
        ws.send_text(msg)
        ws.send_text(json.dumps({"type": "text", "text": "hi"}))
        assert json.loads(ws.receive_text())["type"] == "state"


def test_websockets_logger_pinned_to_info():
    import logging

    logging.getLogger("websockets").setLevel(logging.DEBUG)
    make()
    assert logging.getLogger("websockets").level == logging.INFO
    assert not logging.getLogger("websockets.client").isEnabledFor(logging.DEBUG)


def test_text_turn_roundtrip_uses_token_device():
    client, deps = make()
    with client.websocket_connect("/ws") as ws:
        ws.send_text(hello(device="spoofed"))
        ws.send_text(json.dumps({"type": "text", "text": "hi"}))
        seen, audio = [], []
        while True:
            msg = ws.receive()
            if msg.get("bytes") is not None:
                audio.append(msg["bytes"])
                continue
            data = json.loads(msg["text"])
            seen.append(data["type"])
            if data["type"] == "speaking_done":
                break
    assert "state" in seen and "reply_text" in seen
    assert audio and all(a == AUDIO for a in audio)
    assert deps.router.calls == [("phone", "hi", "en")]


def test_malformed_after_hello_keeps_connection_open():
    client, _ = make()
    with client.websocket_connect("/ws") as ws:
        ws.send_text(hello())
        ws.send_text("garbage")
        assert json.loads(ws.receive_text())["code"] == "bad_message"
        ws.send_text(json.dumps({"type": "nope"}))
        assert json.loads(ws.receive_text())["code"] == "bad_message"
        ws.send_text(json.dumps({"type": "text", "text": "hi"}))
        assert json.loads(ws.receive_text())["type"] == "state"


def test_odd_and_empty_binary_frames_dropped():
    client, deps = make()
    with client.websocket_connect("/ws") as ws:
        ws.send_text(hello())
        ws.send_bytes(b"")
        ws.send_bytes(b"\x01\x00\x02")
        ws.send_text(json.dumps({"type": "end_utterance"}))
        # no audio reached the session, so end_utterance yields idle
        assert json.loads(ws.receive_text()) == {"type": "state", "state": "idle"}
    assert deps.stt.calls == []


def test_second_connection_replaces_first():
    client, _ = make()
    with client.websocket_connect("/ws") as first:
        first.send_text(hello())
        with client.websocket_connect("/ws") as second:
            second.send_text(hello())
            assert closed_code(first) == 4409
            second.send_text(json.dumps({"type": "text", "text": "hi"}))
            assert json.loads(second.receive_text())["type"] == "state"


def test_different_devices_coexist():
    client, _ = make()
    with client.websocket_connect("/ws") as a, client.websocket_connect("/ws") as b:
        a.send_text(hello(TOKEN))
        b.send_text(hello("tok-desk"))
        a.send_text(json.dumps({"type": "text", "text": "hi"}))
        assert json.loads(a.receive_text())["type"] == "state"


def test_replacement_race_keeps_single_live_session(monkeypatch):
    import threading
    import time

    import server.app as app_mod

    gate = threading.Event()
    created = []  # (name, live sessions at creation)
    live = []
    peak = [0]

    class HeldSession:
        def __init__(self, device, send_text, send_bytes, *deps):
            created.append((len(created), len(live)))
            live.append(self)
            peak[0] = max(peak[0], len(live))
            self.first = len(created) == 1
            self.send_text = send_text

        async def close(self):
            if self.first:
                while not gate.is_set():
                    await asyncio.sleep(0.005)
            if self in live:
                live.remove(self)

        async def on_text(self, text, lang="en"):
            await self.send_text(json.dumps({"type": "state", "state": "thinking"}))

    import asyncio

    monkeypatch.setattr(app_mod, "Session", HeldSession)
    client, _ = make()
    with client.websocket_connect("/ws") as a:
        a.send_text(hello())
        time.sleep(0.1)
        with client.websocket_connect("/ws") as b:
            b.send_text(hello())  # blocks on A's held close()
            time.sleep(0.1)
            with client.websocket_connect("/ws") as c:
                c.send_text(hello())
                time.sleep(0.1)
                gate.set()
                assert closed_code(a) == 4409
                assert closed_code(b) == 4409
                c.send_text(json.dumps({"type": "text", "text": "hi"}))
                assert json.loads(c.receive_text())["type"] == "state"
    assert len(created) == 2  # A and C only; B never started a Session
    assert created[1][1] == 0  # C started after A's close() completed
    assert peak[0] == 1
