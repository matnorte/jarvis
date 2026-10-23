"""Router: fast profile first, escalate to deep on the [[DEEP]] marker.

History is kept per device and shared by both profiles. A request carries at
most `history_turns` user messages including the current one (so
`history_turns - 1` prior user/assistant pairs). A turn is recorded only when
the answering stream completes normally; cancelled or failed turns leave no trace.
"""
from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass

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

    async def ask(self, device: str, text: str, lang: str) -> AsyncIterator[RouterEvent]:
        user = {"role": "user", "content": f"[lang:{lang}] {text}"}
        messages = [*self._prior(device), user]
        answer: list[str] = []

        fast_stream = self._fast.stream(messages)
        deep_stream = None
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
                async for chunk in deep_stream:
                    answer.append(chunk)
                    yield Delta(chunk)
        finally:
            await fast_stream.aclose()
            if deep_stream is not None:
                await deep_stream.aclose()

        # Reached only on normal completion (not cancel, close, or error).
        hist = self._history.setdefault(device, [])
        hist.append(user)
        hist.append({"role": "assistant", "content": "".join(answer)})
        del hist[: max(len(hist) - self._history_turns * 2, 0)]
