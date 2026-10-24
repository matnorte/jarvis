"""Router: fast profile first, escalate to deep on the [[DEEP]] marker.

History is kept per device and shared by both profiles. A request carries at
most `history_turns` user messages including the current one (so
`history_turns - 1` prior user/assistant pairs). A turn is recorded only when
the answering stream completes normally; cancelled or failed turns leave no trace.
An answering stream (fast or deep) that completes with no non-whitespace text
raises HermesError, so the session speaks its error line instead of silence.
The deep profile has no routing rule, but as a backstop a leading [[DEEP]] in
its stream is stripped before anything is emitted or recorded.
"""
from __future__ import annotations

import base64
from collections.abc import AsyncIterator
from dataclasses import dataclass

from server.hermes_client import HermesError

MARKER = "[[DEEP]]"
MIN_CHARS = 8


@dataclass(frozen=True)
class Escalated:
    pass


@dataclass(frozen=True)
class Delta:
    text: str


RouterEvent = Escalated | Delta


class Router:
    def __init__(self, fast, deep, history_turns: int = 6) -> None:
        self._fast = fast
        self._deep = deep
        self._history_turns = history_turns
        self._history: dict[str, list[dict]] = {}

    def _prior(self, device: str) -> list[dict]:
        keep = max(self._history_turns - 1, 0) * 2
        hist = self._history.get(device, [])
        return hist[-keep:] if keep else []

    async def ask(self, device: str, text: str, lang: str, image: bytes | None = None) -> AsyncIterator[RouterEvent]:
        user = {"role": "user", "content": f"[lang:{lang}] {text}"}
        current = user if image is None else {"role": "user", "content": [
            {"type": "text", "text": user["content"]},
            {"type": "image_url", "image_url": {
                "url": "data:image/jpeg;base64," + base64.b64encode(image).decode("ascii"), "detail": "auto"}},
        ]}
        messages = [*self._prior(device), current]
        answer: list[str] = []

        fast_stream = self._fast.stream(messages)
        deep_stream = None
        deep_chunks = None
        try:
            buf = ""
            escalate = False
            emitted_buf = False
            async for chunk in fast_stream:
                if emitted_buf:
                    answer.append(chunk)
                    yield Delta(chunk)
                    continue
                buf += chunk
                if len("".join(buf.split())) >= MIN_CHARS:
                    if buf.strip().startswith(MARKER):
                        escalate = True
                        break
                    emitted_buf = True
                    answer.append(buf)
                    yield Delta(buf)
            else:
                # Stream ended: flush whatever was buffered.
                if not emitted_buf and buf:
                    if buf.strip().startswith(MARKER):
                        escalate = True
                    else:
                        answer.append(buf)
                        yield Delta(buf)

            if escalate:
                await fast_stream.aclose()
                yield Escalated()
                deep_stream = self._deep.stream(messages)
                deep_chunks = _strip_leading_marker(deep_stream)
                async for chunk in deep_chunks:
                    answer.append(chunk)
                    yield Delta(chunk)
        finally:
            await fast_stream.aclose()
            if deep_chunks is not None:
                await deep_chunks.aclose()
            if deep_stream is not None:
                await deep_stream.aclose()

        # Reached only on normal completion (not cancel, close, or error).
        if not "".join(answer).strip():
            raise HermesError("Hermes returned an empty reply")
        hist = self._history.setdefault(device, [])
        hist.append(user)
        hist.append({"role": "assistant", "content": "".join(answer)})
        del hist[: max(len(hist) - self._history_turns * 2, 0)]


async def _strip_leading_marker(stream: AsyncIterator[str]) -> AsyncIterator[str]:
    """Pass chunks through, dropping a [[DEEP]] that leads the stream (after
    whitespace) together with the whitespace after it, even when split across
    chunks. Closing `stream` is left to the caller."""
    buf = ""
    probing, trimming = True, False
    async for chunk in stream:
        if probing:
            buf += chunk
            head = buf.lstrip()
            if head.startswith(MARKER):
                probing, trimming = False, True
                chunk = head[len(MARKER):]
            elif MARKER.startswith(head):  # may still become the marker
                continue
            else:
                probing, chunk = False, buf
        if trimming:
            chunk = chunk.lstrip()
            if not chunk:
                continue
            trimming = False
        yield chunk
    if probing and buf:  # ended while still a possible marker prefix
        yield buf
