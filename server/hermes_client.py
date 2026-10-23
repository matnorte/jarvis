"""Streaming client for the Hermes Agent OpenAI-compatible API server.

API notes (docs: hermes-agent.nousresearch.com/docs/user-guide/features/api-server,
fetched 2026-10-23):
- POST /v1/chat/completions (default http://localhost:8642), bearer API_SERVER_KEY.
- With "stream": true the reply is SSE; answer text is in choices[0].delta.content.
  Reasoning arrives separately in delta.reasoning_content (ignored here).
- Lines starting with ":" are keepalive comments (every ~10 s idle); skipped.
  Named events (e.g. "event: hermes.tool.progress") are not "data:" content chunks
  carrying delta.content, so they yield nothing.
- finish_reason "error" signals failure ("cancelled" = interrupted turn).
- Optional session header X-Hermes-Session-Id exists (server-side history); NOT used:
  the router keeps its own history and sends the full messages array each time.
"""
from __future__ import annotations

import json
from collections.abc import AsyncIterator

import httpx

MODEL = "hermes-agent"
READ_TIMEOUT_S = 60.0


class HermesError(Exception):
    pass


class HermesClient:
    def __init__(self, base_url: str, api_key: str, http: httpx.AsyncClient | None = None):
        self._url = base_url.rstrip("/") + "/v1/chat/completions"
        self._api_key = api_key
        self._http = http or httpx.AsyncClient()

    async def stream(self, messages: list[dict]) -> AsyncIterator[str]:
        """Yield content deltas. Closing the generator early releases the response."""
        timeout = httpx.Timeout(10.0, read=READ_TIMEOUT_S)
        try:
            async with self._http.stream(
                "POST",
                self._url,
                json={"model": MODEL, "messages": messages, "stream": True},
                headers={"Authorization": f"Bearer {self._api_key}"},
                timeout=timeout,
            ) as resp:
                if resp.status_code != 200:
                    raise HermesError(f"Hermes returned HTTP {resp.status_code}")
                async for line in resp.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        return
                    try:
                        choice = json.loads(data)["choices"][0]
                    except (ValueError, KeyError, IndexError, TypeError):
                        continue
                    content = (choice.get("delta") or {}).get("content")
                    if content:
                        yield content
                    if choice.get("finish_reason") == "error":
                        raise HermesError("Hermes reported an error finish")
        except httpx.TimeoutException as e:
            raise HermesError("Hermes timed out") from e
        except httpx.HTTPError as e:
            raise HermesError(f"Hermes request failed: {e}") from e
