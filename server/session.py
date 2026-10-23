"""Per-connection turn state machine.

One Session per WebSocket. A turn (STT -> router -> chunker -> TTS) runs as a
single asyncio.Task; every helper task it starts (TTS consumers, the "one
moment" filler) is owned by it and reaped before it finishes, so cancelling
the turn task stops everything and nothing is sent afterwards.
"""
from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator, Awaitable, Callable

import numpy as np

from server import protocol
from server.router import Delta, Escalated
from server.stt import Endpointer
from server.text import ClauseChunker

log = logging.getLogger(__name__)

MIC_RATE = 16000
OUT_RATE = 24000
LEVEL_EVERY_MS = 66.0

_MESSAGES = {
    "stt_failed": "Speech recognition failed.",
    "llm_failed": "The assistant could not answer.",
    "tts_failed": "Speech synthesis failed.",
}


class _StageError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def _rms_level(pcm16: bytes) -> float:
    samples = np.frombuffer(pcm16[: len(pcm16) // 2 * 2], dtype="<i2").astype(np.float64)
    if samples.size == 0:
        return 0.0
    rms = float(np.sqrt(np.mean(samples * samples)))
    return min(1.0, rms / 32768.0 * 4)


async def _reap(*tasks: asyncio.Task | None) -> None:
    """Cancel tasks, wait for them, and retrieve their outcome."""
    live = [t for t in tasks if t is not None]
    for t in live:
        t.cancel()
    if live:
        await asyncio.wait(live)
    for t in live:
        if not t.cancelled():
            t.exception()


class _Speaker:
    """Feeds clause chunks to one lazily started TTS stream."""

    def __init__(self, session: Session, lang: str) -> None:
        self._session = session
        self._lang = lang
        self._chunker = ClauseChunker()
        self._queue: asyncio.Queue[str | None] = asyncio.Queue()
        self.task: asyncio.Task | None = None

    def _put(self, chunk: str) -> None:
        if self.task is None:
            self.task = asyncio.create_task(self._run())
        elif self.task.done():
            self.task.result()  # surface a TTS failure promptly
        self._queue.put_nowait(chunk)

    async def feed(self, delta: str) -> None:
        for chunk in self._chunker.feed(delta):
            self._put(chunk)

    async def finish(self) -> None:
        for chunk in self._chunker.flush():
            self._put(chunk)
        if self.task is not None:
            self._queue.put_nowait(None)
            await self.task

    async def _chunks(self) -> AsyncIterator[str]:
        while (chunk := await self._queue.get()) is not None:
            yield chunk

    async def _run(self) -> None:
        chunks = self._chunks()
        synth = self._session._tts.synthesize(chunks, self._lang)
        try:
            async for audio in synth:
                await self._session._play(audio)
        except Exception as exc:
            raise _StageError("tts_failed") from exc
        finally:
            await synth.aclose()
            await chunks.aclose()


class Session:
    def __init__(
        self,
        device: str,
        send_text: Callable[[str], Awaitable],
        send_bytes: Callable[[bytes], Awaitable],
        stt,
        router,
        tts,
        canned,
        filler_after: float = 1.5,
    ) -> None:
        self._device = device
        self._send_text = send_text
        self._send_bytes = send_bytes
        self._stt = stt
        self._router = router
        self._tts = tts
        self._canned = canned
        self._filler_after = filler_after
        self._endpointer = Endpointer(sample_rate=MIC_RATE)
        self._buf = bytearray()
        self._listening = False
        self._closed = False
        self._turn: asyncio.Task | None = None
        self._audio_lock = asyncio.Lock()
        self._speaking = False
        self._mic_ms = 0.0
        self._out_ms = 0.0

    # ----- public API -------------------------------------------------

    def _busy(self) -> bool:
        return self._turn is not None and not self._turn.done()

    async def on_audio(self, pcm16: bytes) -> None:
        if self._closed or self._busy():
            return  # barge-in is the client's `interrupt` message
        if not self._listening:
            self._listening = True
            await self._send_text(protocol.state("listening"))
        self._buf += pcm16
        self._mic_ms += len(pcm16) // 2 * 1000.0 / MIC_RATE
        if self._mic_ms >= LEVEL_EVERY_MS:
            self._mic_ms = 0.0
            await self._send_text(protocol.level(_rms_level(pcm16), 0.0))
        result = self._endpointer.feed(pcm16)
        if result == "end":
            await self.on_end_utterance()
        elif result == "no_speech":
            self._reset_input()
            await self._send_text(protocol.state("idle"))

    async def on_end_utterance(self) -> None:
        if self._closed or self._busy():
            return
        pcm = bytes(self._buf)
        self._reset_input()
        if not pcm:
            await self._send_text(protocol.state("idle"))
            return
        self._start(self._audio_turn, pcm)

    async def on_text(self, text: str, lang: str = "en") -> None:
        if self._closed:
            return
        await _reap(self._turn)
        self._reset_input()
        if len(text.strip()) < 2:
            await self._send_text(protocol.state("idle"))
            return
        self._start(self._text_turn, text, lang)

    async def on_interrupt(self) -> None:
        if self._closed:
            return
        await _reap(self._turn)
        self._reset_input()
        self._listening = True
        await self._send_text(protocol.state("listening"))

    async def close(self) -> None:
        self._closed = True
        await _reap(self._turn)

    async def join(self) -> None:
        """Wait for the current turn (if any) to finish."""
        if self._turn is not None:
            await asyncio.wait([self._turn])

    # ----- turn -------------------------------------------------------

    def _reset_input(self) -> None:
        self._buf.clear()
        self._endpointer.reset()
        self._listening = False
        self._mic_ms = 0.0

    def _start(self, turn, *args) -> None:
        # The coroutine is created inside the task, so cancelling the task
        # before its first step never leaves an un-awaited coroutine behind.
        self._speaking = False
        self._out_ms = 0.0
        self._turn = asyncio.create_task(self._guard(turn, *args))

    async def _guard(self, turn, *args) -> None:
        try:
            await turn(*args)
        except Exception as exc:  # e.g. the client went away mid-send
            log.warning("turn aborted: %s", type(exc).__name__)

    async def _audio_turn(self, pcm: bytes) -> None:
        await self._send_text(protocol.state("thinking"))
        try:
            tr = await self._stt.transcribe(pcm)
        except Exception as exc:
            log.warning("stt failed: %s", type(exc).__name__)
            await self._fail("stt_failed", "en")
            return
        await self._send_text(protocol.transcript(tr.text, tr.lang, True))
        await self._respond(tr.text, tr.lang)

    async def _text_turn(self, text: str, lang: str) -> None:
        await self._send_text(protocol.state("thinking"))
        await self._send_text(protocol.transcript(text, lang, True))
        await self._respond(text, lang)

    async def _respond(self, text: str, lang: str) -> None:
        text = text.strip()
        if len(text) < 2:
            await self._send_text(protocol.state("idle"))
            return
        try:
            await self._answer(text, lang)
        except _StageError as exc:
            log.warning("%s: %s", exc.code, type(exc.__cause__).__name__)
            await self._fail(exc.code, lang)
            return
        await self._send_text(protocol.speaking_done())
        await self._send_text(protocol.state("idle"))

    async def _answer(self, text: str, lang: str) -> None:
        events = self._router.ask(self._device, text, lang)
        speakers = [_Speaker(self, lang)]
        got_reply = asyncio.Event()
        filler = asyncio.create_task(self._filler(lang, got_reply))
        try:
            try:
                async for ev in events:
                    if isinstance(ev, Escalated):
                        got_reply.set()
                        await speakers[-1].finish()
                        await self._send_text(protocol.state("deep"))
                        await self._play(self._canned.get("ack_deep", lang))
                        speakers.append(_Speaker(self, lang))
                    elif isinstance(ev, Delta) and ev.text:
                        got_reply.set()
                        await self._send_text(protocol.reply_text(ev.text))
                        await speakers[-1].feed(ev.text)
            except _StageError:
                raise
            except Exception as exc:
                raise _StageError("llm_failed") from exc
            await speakers[-1].finish()
            got_reply.set()
            await filler  # returns at once unless the filler is mid-play
        finally:
            await events.aclose()
            await _reap(filler, *(s.task for s in speakers))

    async def _filler(self, lang: str, got_reply: asyncio.Event) -> None:
        try:
            await asyncio.wait_for(got_reply.wait(), self._filler_after)
            return
        except TimeoutError:
            pass
        await self._play(self._canned.get("one_moment", lang))

    async def _fail(self, code: str, lang: str) -> None:
        await self._send_text(protocol.error(code, _MESSAGES[code]))
        await self._play(self._canned.get("error", lang))
        await self._send_text(protocol.state("idle"))

    async def _play(self, audio: bytes) -> None:
        if not audio:
            return
        async with self._audio_lock:
            if not self._speaking:
                self._speaking = True
                await self._send_text(protocol.state("speaking"))
            await self._send_bytes(audio)
            self._out_ms += len(audio) // 2 * 1000.0 / OUT_RATE
            if self._out_ms >= LEVEL_EVERY_MS:
                self._out_ms = 0.0
                await self._send_text(protocol.level(0.0, _rms_level(audio)))
