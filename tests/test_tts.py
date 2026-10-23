import asyncio
import base64
import json
import logging

import pytest
import websockets

from server.tts import CannedLines, ElevenLabsTTS, TTSError


async def chunks(*items):
    for i in items:
        yield i


async def collect(agen):
    return [b async for b in agen]


def serve(handler):
    return websockets.serve(handler, "127.0.0.1", 0)


def base_url(server):
    port = server.sockets[0].getsockname()[1]
    return f"ws://127.0.0.1:{port}"


async def test_synthesize_streams_audio():
    seen = {}

    async def handler(ws):
        seen["path"] = ws.request.path
        seen["msgs"] = []
        while True:
            m = json.loads(await ws.recv())
            seen["msgs"].append(m)
            if m["text"] == "":
                break
        await ws.send(json.dumps({"audio": base64.b64encode(b"ab").decode()}))
        await ws.send(json.dumps({"audio": base64.b64encode(b"cd").decode()}))
        await ws.send(json.dumps({"isFinal": True}))

    async with serve(handler) as server:
        tts = ElevenLabsTTS("secret-key", "voice1", base_url=base_url(server))
        out = await collect(tts.synthesize(chunks("Hello ", "there."), "en"))
    assert b"".join(out) == b"abcd"
    assert seen["msgs"][0]["xi_api_key"] == "secret-key"
    assert seen["msgs"][0]["text"] == " "
    assert seen["msgs"][1] == {"text": "Hello ", "try_trigger_generation": True}
    assert seen["msgs"][2]["text"] == "there."
    assert seen["msgs"][-1] == {"text": ""}
    p = seen["path"]
    assert p.startswith("/v1/text-to-speech/voice1/stream-input?")
    assert "model_id=eleven_flash_v2_5" in p
    assert "output_format=pcm_24000" in p
    assert "language_code=en" in p


async def test_server_error_message_raises():
    async def handler(ws):
        await ws.recv()
        await ws.send(json.dumps({"error": "bad", "message": "nope"}))
        await ws.wait_closed()

    async with serve(handler) as server:
        tts = ElevenLabsTTS("k", "v", base_url=base_url(server))
        with pytest.raises(TTSError):
            await collect(tts.synthesize(chunks("hi"), "pt"))


async def test_close_before_final_raises():
    async def handler(ws):
        await ws.recv()
        await ws.send(json.dumps({"audio": base64.b64encode(b"x").decode()}))
        await ws.close()

    async with serve(handler) as server:
        tts = ElevenLabsTTS("k", "v", base_url=base_url(server))
        with pytest.raises(TTSError):
            await collect(tts.synthesize(chunks("hi"), "pt"))


async def test_connection_failure_raises(caplog):
    tts = ElevenLabsTTS("super-secret", "v", base_url="ws://127.0.0.1:1")
    with caplog.at_level(logging.DEBUG):
        with pytest.raises(TTSError):
            await collect(tts.synthesize(chunks("hi"), "pt"))
    assert "super-secret" not in caplog.text


class FakeTTS:
    def __init__(self, fail=False):
        self.fail = fail
        self.calls = []

    async def synthesize(self, chunks, lang):
        texts = [c async for c in chunks]
        self.calls.append((texts, lang))
        if self.fail:
            raise TTSError("down")
        yield f"{lang}:{''.join(texts)}".encode()


async def test_canned_ensure_writes_files(tmp_path):
    tts = FakeTTS()
    canned = CannedLines(tmp_path, tts)
    await canned.ensure()
    f = tmp_path / "one_moment_pt.pcm"
    assert f.read_bytes() == b"pt:Um momento, senhor."
    assert canned.get("error", "en") == b"en:I'm having trouble reaching my systems, sir."
    assert len(list(tmp_path.glob("*.pcm"))) == 6
    n = len(tts.calls)
    await canned.ensure()
    assert len(tts.calls) == n  # existing files are not regenerated


async def test_canned_ensure_survives_tts_failure(tmp_path, caplog):
    canned = CannedLines(tmp_path, FakeTTS(fail=True))
    with caplog.at_level(logging.WARNING):
        await canned.ensure()
    assert canned.get("one_moment", "pt") == b""
    assert list(tmp_path.glob("*.pcm")) == []
    assert "warning" in caplog.text.lower() or caplog.records


async def test_chunks_failure_raises_promptly():
    async def bad_chunks():
        yield "one"
        raise RuntimeError("hermes died")

    async def handler(ws):
        await ws.wait_closed()  # never sends isFinal

    async with serve(handler) as server:
        tts = ElevenLabsTTS("k", "v", base_url=base_url(server), recv_timeout=5.0)
        loop = asyncio.get_running_loop()
        start = loop.time()
        with pytest.raises(TTSError) as ei:
            await collect(tts.synthesize(bad_chunks(), "en"))
        assert loop.time() - start < 2.0
    assert isinstance(ei.value.__cause__, RuntimeError)


async def test_silent_server_times_out():
    async def handler(ws):
        await ws.wait_closed()

    async with serve(handler) as server:
        tts = ElevenLabsTTS("k", "v", base_url=base_url(server), recv_timeout=0.2)
        with pytest.raises(TTSError, match="timed out"):
            await collect(tts.synthesize(chunks("hi"), "en"))


async def test_cancellation_propagates_and_closes_socket():
    closed = asyncio.Event()
    started = asyncio.Event()

    async def handler(ws):
        await ws.recv()
        started.set()
        await ws.wait_closed()
        closed.set()

    async with serve(handler) as server:
        tts = ElevenLabsTTS("k", "v", base_url=base_url(server), recv_timeout=5.0)
        task = asyncio.create_task(collect(tts.synthesize(chunks("hi"), "en")))
        await asyncio.wait_for(started.wait(), 2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await asyncio.wait_for(closed.wait(), 2)
