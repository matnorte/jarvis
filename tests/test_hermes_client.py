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
