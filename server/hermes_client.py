"""Streaming client for the Hermes Agent OpenAI-compatible API server.

API notes (docs: hermes-agent.nousresearch.com/docs/user-guide/features/api-server,
fetched 2026-10-23):
- POST /v1/chat/completions (base URL e.g. http://127.0.0.1:8642/p/fast), bearer API_SERVER_KEY.
- With "stream": true the reply is SSE; answer text is in choices[0].delta.content.
  Reasoning arrives separately in delta.reasoning_content (ignored here).
- Lines starting with ":" are keepalive comments (every ~10 s idle); skipped.
  Named events (e.g. "event: hermes.tool.progress") are not "data:" content chunks
  carrying delta.content, so they yield nothing.
- finish_reason "error" signals failure ("cancelled" = interrupted turn). A data
  payload carrying an "error" key (OpenAI-style error object) is also a failure,
  and a stream that ends without "data: [DONE]" is treated as truncated.
- httpx's read timeout restarts on every keepalive line, so it cannot bound a
  stalled agent; an overall deadline (default 60 s) covers the whole request.
- Optional session header X-Hermes-Session-Id exists (server-side history); NOT used:
  the router keeps its own history and sends the full messages array each time.
"""
from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator

import httpx

MODEL = "hermes-agent"
DEADLINE_S = 60.0


class HermesError(Exception):
    pass


class HermesClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        http: httpx.AsyncClient | None = None,
        deadline: float = DEADLINE_S,
    ):
        self._url = base_url.rstrip("/") + "/v1/chat/completions"
        self._api_key = api_key
        self._http = http or httpx.AsyncClient()
        self._deadline = deadline

    async def stream(self, messages: list[dict]) -> AsyncIterator[str]:
        """Yield content deltas. Closing the generator early releases the response.

        The whole request (headers and every line) must finish within the
        deadline; time spent by the consumer between deltas counts too.
        """
        loop = asyncio.get_running_loop()
        end = loop.time() + self._deadline

        def remaining() -> float:
            left = end - loop.time()
            if left <= 0:
                raise HermesError("Hermes deadline exceeded")
            return left

        timeout = httpx.Timeout(10.0, read=self._deadline)
        request = self._http.build_request(
            "POST",
            self._url,
            json={"model": MODEL, "messages": messages, "stream": True},
            headers={"Authorization": f"Bearer {self._api_key}"},
            timeout=timeout,
        )
        try:
            resp = await asyncio.wait_for(
                self._http.send(request, stream=True), remaining()
            )
            try:
                if resp.status_code != 200:
                    raise HermesError(f"Hermes returned HTTP {resp.status_code}")
                lines = resp.aiter_lines()
                try:
                    while True:
                        try:
                            line = await asyncio.wait_for(anext(lines), remaining())
                        except StopAsyncIteration:
                            raise HermesError("Hermes stream ended without [DONE]") from None
                        if not line.startswith("data:"):
                            continue
                        data = line[5:].strip()
                        if data == "[DONE]":
                            return
                        try:
                            payload = json.loads(data)
                        except ValueError:
                            continue
                        if isinstance(payload, dict) and payload.get("error") is not None:
                            # The error body may echo request details; keep it out.
                            raise HermesError("Hermes reported an error")
                        try:
                            choice = payload["choices"][0]
                            content = (choice.get("delta") or {}).get("content")
                        except (KeyError, IndexError, TypeError, AttributeError):
                            continue
                        if content:
                            yield content
                        if choice.get("finish_reason") == "error":
                            raise HermesError("Hermes reported an error finish")
                finally:
                    await lines.aclose()
            finally:
                await resp.aclose()
        except TimeoutError as e:
            raise HermesError("Hermes deadline exceeded") from e
        except httpx.TimeoutException as e:
            raise HermesError("Hermes timed out") from e
        except httpx.HTTPError as e:
            raise HermesError(f"Hermes request failed: {e}") from e
