import asyncio
import json

import httpx
import pytest

from server.hermes_client import HermesClient, HermesError


def make_client(handler) -> HermesClient:
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return HermesClient("http://hermes", "key", http)


async def test_stream_yields_deltas():
    body = ('data: {"choices":[{"delta":{"content":"Good "}}]}\n\n'
            'data: {"choices":[{"delta":{"content":"evening"}}]}\n\n'
            'data: [DONE]\n\n')
    client = make_client(lambda req: httpx.Response(200, text=body))
    assert [d async for d in client.stream([{"role": "user", "content": "hi"}])] == ["Good ", "evening"]


async def test_stream_sends_auth_and_model():
    seen = {}

    def handler(req):
        seen["auth"] = req.headers["authorization"]
        seen["body"] = json.loads(req.content)
        seen["url"] = str(req.url)
        return httpx.Response(200, text="data: [DONE]\n\n")

    [_ async for _ in make_client(handler).stream([])]
    assert seen["url"] == "http://hermes/v1/chat/completions"
    assert seen["auth"] == "Bearer key" and seen["body"]["model"] == "hermes-agent" and seen["body"]["stream"] is True


async def test_structured_camera_content_passes_through_unchanged():
    messages = [{"role": "user", "content": [
        {"type": "text", "text": "what is this"},
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,test-only", "detail": "auto"}},
    ]}]
    def handler(req):
        assert json.loads(req.content)["messages"] == messages
        return httpx.Response(200, text="data: [DONE]\n\n")
    [_ async for _ in make_client(handler).stream(messages)]


async def test_non_200_raises():
    with pytest.raises(HermesError):
        [_ async for _ in make_client(lambda r: httpx.Response(500)).stream([])]


async def test_timeout_raises():
    def handler(req):
        raise httpx.ReadTimeout("slow", request=req)

    with pytest.raises(HermesError):
        [_ async for _ in make_client(handler).stream([])]


async def test_skips_keepalive_empty_role_and_stops_at_done():
    body = (': keepalive\n\n'
            'data: {"choices":[{"delta":{"role":"assistant"}}]}\n\n'
            'data: {"choices":[{"delta":{"content":"Hi"}}]}\n\n'
            'data: [DONE]\n\n'
            'data: {"choices":[{"delta":{"content":"ignored"}}]}\n\n')
    client = make_client(lambda req: httpx.Response(200, text=body))
    assert [d async for d in client.stream([])] == ["Hi"]


async def test_early_aclose_is_clean():
    body = ('data: {"choices":[{"delta":{"content":"a"}}]}\n\n'
            'data: {"choices":[{"delta":{"content":"b"}}]}\n\n'
            'data: [DONE]\n\n')
    client = make_client(lambda req: httpx.Response(200, text=body))
    gen = client.stream([])
    assert await gen.__anext__() == "a"
    await gen.aclose()


def make_deadline_client(handler, deadline) -> HermesClient:
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return HermesClient("http://hermes", "key", http, deadline=deadline)


async def test_error_payload_raises():
    body = ('data: {"choices":[{"delta":{"content":"Hi"}}]}\n\n'
            'data: {"error":{"message":"upstream exploded","type":"server_error"}}\n\n'
            'data: [DONE]\n\n')
    client = make_client(lambda req: httpx.Response(200, text=body))
    got = []
    with pytest.raises(HermesError) as exc:
        async for d in client.stream([]):
            got.append(d)
    assert got == ["Hi"] and "upstream exploded" not in str(exc.value)


async def test_stream_ending_without_done_raises():
    body = 'data: {"choices":[{"delta":{"content":"Hi"}}]}\n\n'
    client = make_client(lambda req: httpx.Response(200, text=body))
    with pytest.raises(HermesError):
        [_ async for _ in client.stream([])]


async def test_overall_deadline_despite_keepalives():
    async def keepalives():
        yield b'data: {"choices":[{"delta":{"content":"Hm"}}]}\n\n'
        while True:  # keeps resetting httpx's read timeout
            await asyncio.sleep(0.02)
            yield b": keepalive\n\n"

    client = make_deadline_client(lambda req: httpx.Response(200, content=keepalives()), 0.3)
    loop = asyncio.get_running_loop()
    start = loop.time()
    got = []
    with pytest.raises(HermesError):
        async for d in client.stream([]):
            got.append(d)
    assert got == ["Hm"]
    assert loop.time() - start < 2.0


async def test_deadline_covers_waiting_for_headers():
    async def handler(req):
        await asyncio.sleep(5)
        return httpx.Response(200, text="data: [DONE]\n\n")

    client = make_deadline_client(handler, 0.2)
    loop = asyncio.get_running_loop()
    start = loop.time()
    with pytest.raises(HermesError):
        [_ async for _ in client.stream([])]
    assert loop.time() - start < 2.0


def test_default_deadline_is_60s():
    assert HermesClient("http://h", "k", httpx.AsyncClient())._deadline == 60.0
