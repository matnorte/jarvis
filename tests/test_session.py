import asyncio
import json
import math
import struct

from server.hermes_client import HermesError
from server.router import Delta, Escalated, Router
from server.session import Session
from server.stt import Transcript
from server.tts import TTSError

AUDIO = b"A" * 4
CANNED = b"C" * 4


class FakeSTT:
    def __init__(self, text="hello jarvis", lang="en", raises=None):
        self.result = Transcript(text, lang)
        self.raises = raises
        self.calls = []

    async def transcribe(self, pcm16):
        self.calls.append(pcm16)
        if self.raises:
            raise self.raises
        return self.result


class FakeRouter:
    """Script items: Escalated/Delta events, or a float to sleep first."""

    def __init__(self, script, raises=None, cleanup_sleep=0.0, cleanup_raises=None):
        self.script = script
        self.raises = raises
        self.cleanup_sleep = cleanup_sleep
        self.cleanup_raises = cleanup_raises
        self.calls = []
        self.closed = False

    async def ask(self, device, text, lang):
        self.calls.append((device, text, lang))
        try:
            for item in self.script:
                if isinstance(item, (int, float)):
                    await asyncio.sleep(item)
                else:
                    yield item
            if self.raises:
                raise self.raises
        finally:
            self.closed = True
            if self.cleanup_sleep:  # e.g. awaiting network closes
                await asyncio.sleep(self.cleanup_sleep)
            if self.cleanup_raises:
                raise self.cleanup_raises


class FakeTTS:
    def __init__(self, raises=None, audio=AUDIO):
        self.raises = raises
        self.audio = audio
        self.chunks = []
        self.langs = []
        self.started = 0
        self.closed = 0

    async def synthesize(self, chunks, lang):
        self.started += 1
        self.langs.append(lang)
        try:
            async for c in chunks:
                self.chunks.append(c)
                if self.raises:
                    raise self.raises
                yield self.audio
        finally:
            self.closed += 1


class FakeCanned:
    def __init__(self):
        self.requests = []

    def get(self, key, lang):
        self.requests.append((key, lang))
        return CANNED


class Harness:
    def __init__(self, stt=None, router=None, tts=None, **kw):
        self.sent = []
        self.stt = stt or FakeSTT()
        self.router = router or FakeRouter([Delta("Good evening, sir. "), Delta("All good.")])
        self.tts = tts or FakeTTS()
        self.canned = FakeCanned()
        self.session = Session(
            "dev", self.send_text, self.send_bytes,
            self.stt, self.router, self.tts, self.canned, **kw,
        )

    async def send_text(self, s):
        self.sent.append(json.loads(s))

    async def send_bytes(self, b):
        self.sent.append(bytes(b))

    def msgs(self, kind=None):
        return [m for m in self.sent if isinstance(m, dict) and (kind is None or m["type"] == kind)]

    def states(self):
        return [m["state"] for m in self.msgs("state")]

    def index(self, pred):
        return next(i for i, m in enumerate(self.sent) if pred(m))

    def no_levels(self):
        return [m for m in self.sent if not (isinstance(m, dict) and m["type"] == "level")]

    async def wait_for(self, pred, timeout=2.0):
        async with asyncio.timeout(timeout):
            while not any(pred(m) for m in self.sent):
                await asyncio.sleep(0.005)


def is_state(s):
    return lambda m: isinstance(m, dict) and m["type"] == "state" and m["state"] == s


def is_type(t):
    return lambda m: isinstance(m, dict) and m["type"] == t


def frame(amplitude, ms=20, rate=16000):
    n = rate * ms // 1000
    return struct.pack(
        f"<{n}h", *(int(amplitude * math.sin(2 * math.pi * 440 * i / rate)) for i in range(n))
    )


async def speak(h):
    for _ in range(10):
        await h.session.on_audio(frame(5000))
    for _ in range(40):  # 700 ms of silence ends the utterance after 35 frames
        await h.session.on_audio(frame(0))
    await h.session.join()


def other_tasks():
    return [t for t in asyncio.all_tasks() if t is not asyncio.current_task()]


async def test_full_turn_message_order():
    h = Harness()
    await speak(h)
    assert h.states() == ["listening", "thinking", "speaking", "idle"]
    assert len(h.stt.calls) == 1 and len(h.stt.calls[0]) >= 10 * 640
    assert h.router.calls == [("dev", "hello jarvis", "en")]
    t = h.index(is_type("transcript"))
    assert h.sent[t] == {"type": "transcript", "text": "hello jarvis", "lang": "en", "final": True}
    replies = [i for i, m in enumerate(h.sent) if is_type("reply_text")(m)]
    assert replies and t < replies[0]
    assert "".join(h.sent[i]["delta"] for i in replies) == "Good evening, sir. All good."
    first_audio = h.index(lambda m: m == AUDIO)
    assert h.sent[first_audio - 1] == {"type": "state", "state": "speaking"}
    assert " ".join(h.tts.chunks) == "Good evening, sir. All good." and h.tts.langs == ["en"]
    assert h.sent[-2:] == [{"type": "speaking_done"}, {"type": "state", "state": "idle"}]
    assert h.router.closed and h.tts.closed == h.tts.started == 1
    assert other_tasks() == []


async def test_empty_transcript_skips_router():
    h = Harness(stt=FakeSTT(text="  . "))
    await speak(h)
    assert h.router.calls == []
    assert h.states() == ["listening", "thinking", "idle"]
    assert h.tts.started == 0 and not any(isinstance(m, bytes) for m in h.sent)

    h2 = Harness()
    await h2.session.on_text("   ")
    await h2.session.join()
    assert h2.router.calls == [] and h2.sent == [{"type": "state", "state": "idle"}]


async def test_escalation_sends_deep_state_and_ack_audio():
    h = Harness(router=FakeRouter([Escalated(), Delta("The deep answer, sir. ")]))
    await h.session.on_text("explain relativity", "pt")
    await h.session.join()
    deep = h.index(is_state("deep"))
    ack = h.index(lambda m: m == CANNED)
    audio = h.index(lambda m: m == AUDIO)
    assert deep < ack < audio
    assert h.sent[ack - 1] == {"type": "state", "state": "speaking"}
    assert h.states().count("speaking") == 1
    assert h.canned.requests == [("ack_deep", "pt")]
    assert h.tts.langs == ["pt"] and " ".join(h.tts.chunks) == "The deep answer, sir."
    assert h.router.calls == [("dev", "explain relativity", "pt")]
    assert h.sent[-2:] == [{"type": "speaking_done"}, {"type": "state", "state": "idle"}]


async def test_one_moment_plays_once_after_1_5s():
    h = Harness(router=FakeRouter([0.3, Delta("Right away, sir. "), 0.2, Delta("Done.")]),
                filler_after=0.05)
    await h.session.on_text("do the thing")
    await h.session.join()
    assert h.canned.requests == [("one_moment", "en")]
    assert [m for m in h.sent if m == CANNED] == [CANNED]
    assert h.index(lambda m: m == CANNED) < h.index(lambda m: m == AUDIO)
    assert h.states().count("speaking") == 1
    assert h.sent[-2:] == [{"type": "speaking_done"}, {"type": "state", "state": "idle"}]


async def test_no_one_moment_after_deep_ack():
    h = Harness(router=FakeRouter([Escalated(), 0.3, Delta("Deep reply here. ")]),
                filler_after=0.05)
    await h.session.on_text("hard question")
    await h.session.join()
    assert h.canned.requests == [("ack_deep", "en")]


async def test_interrupt_cancels_router_and_tts():
    h = Harness(router=FakeRouter([Delta("First sentence here. "), 5.0, Delta("Never said.")]))
    await h.session.on_text("talk to me")
    await h.wait_for(lambda m: m == AUDIO)
    assert not h.router.closed
    mark = len(h.sent)
    await h.session.on_interrupt()
    assert h.sent[mark:] == [{"type": "state", "state": "listening"}]
    assert h.router.closed and h.tts.closed == h.tts.started == 1
    assert other_tasks() == []
    await asyncio.sleep(0.05)
    assert h.sent[mark:] == [{"type": "state", "state": "listening"}]
    assert "Never said." not in h.tts.chunks


async def test_interrupt_during_deep_ack_cancels_everything():
    h = Harness(router=FakeRouter([Escalated(), 5.0, Delta("Never said.")]))
    await h.session.on_text("hard question")
    await h.wait_for(lambda m: m == CANNED)
    mark = len(h.sent)
    await h.session.on_interrupt()
    assert h.sent[mark:] == [{"type": "state", "state": "listening"}]
    assert h.router.closed and other_tasks() == []


async def test_close_mid_turn_cancels_without_sending():
    h = Harness(router=FakeRouter([Delta("First sentence here. "), 5.0, Delta("Never said.")]))
    await h.session.on_text("talk to me")
    await h.wait_for(lambda m: m == AUDIO)
    mark = len(h.sent)
    await h.session.close()
    assert len(h.sent) == mark
    assert h.router.closed and h.tts.closed == h.tts.started == 1
    assert other_tasks() == []
    await h.session.on_audio(frame(5000))
    await h.session.on_text("hello again")
    await asyncio.sleep(0.05)
    assert len(h.sent) == mark and len(h.router.calls) == 1


async def test_router_error_speaks_error_line():
    secret = "sk-secret-123"
    h = Harness(router=FakeRouter([], raises=HermesError(f"bad key {secret}")))
    await h.session.on_text("hello there", "pt")
    await h.session.join()
    err = h.index(is_type("error"))
    assert h.sent[err]["code"] == "llm_failed"
    assert secret not in h.sent[err]["message"] and h.sent[err]["message"]
    tail = h.no_levels()[-4:]
    assert tail == [h.sent[err], {"type": "state", "state": "speaking"}, CANNED,
                    {"type": "state", "state": "idle"}]
    assert h.canned.requests == [("error", "pt")]
    assert h.router.closed and other_tasks() == []


async def test_tts_error_speaks_error_line():
    h = Harness(tts=FakeTTS(raises=TTSError("ElevenLabs error: quota")))
    await h.session.on_text("hello there")
    await h.session.join()
    err = h.msgs("error")
    assert [e["code"] for e in err] == ["tts_failed"]
    assert h.sent[-2:] == [CANNED, {"type": "state", "state": "idle"}]
    assert {"type": "speaking_done"} not in h.sent
    assert h.router.closed and other_tasks() == []


async def test_stt_error_speaks_error_line():
    h = Harness(stt=FakeSTT(raises=RuntimeError("model exploded")))
    await speak(h)
    assert [e["code"] for e in h.msgs("error")] == ["stt_failed"]
    assert h.router.calls == []
    assert h.sent[-2:] == [CANNED, {"type": "state", "state": "idle"}]


async def test_level_messages_throttled():
    h = Harness()
    for _ in range(100):  # 2 s of continuous speech: no endpoint fires
        await h.session.on_audio(frame(3000))
    levels = h.msgs("level")
    assert 1 <= len(levels) <= 31
    assert all(0.0 < m["mic"] <= 1.0 and m["out"] == 0.0 for m in levels)
    assert h.states() == ["listening"]


async def test_out_level_sent_for_tts_audio():
    loud = struct.pack("<h", 20000) * 2400  # 100 ms at 24 kHz, RMS*4 clips to 1
    h = Harness(tts=FakeTTS(audio=loud))
    await h.session.on_text("hello there")
    await h.session.join()
    outs = [m for m in h.msgs("level") if m["out"] > 0]
    assert outs and all(m["out"] == 1.0 for m in outs)


async def test_audio_ignored_while_turn_active():
    h = Harness(router=FakeRouter([5.0]))
    await h.session.on_text("hello there")
    await h.wait_for(is_state("thinking"))
    for _ in range(50):
        await h.session.on_audio(frame(5000))
    await h.session.on_end_utterance()
    assert h.states() == ["thinking"] and h.stt.calls == []
    await h.session.close()


async def test_interrupt_before_turn_starts():
    h = Harness()
    await h.session.on_text("hello there")
    await h.session.on_interrupt()  # turn task has not run a single step yet
    assert h.sent == [{"type": "state", "state": "listening"}]
    assert h.router.calls == [] and other_tasks() == []


async def test_close_while_interrupt_is_reaping():
    h = Harness(router=FakeRouter([Delta("First sentence here. "), 5.0, Delta("Never said.")],
                                  cleanup_sleep=0.5))
    await h.session.on_text("talk to me")
    await h.wait_for(lambda m: m == AUDIO)
    interrupting = asyncio.create_task(h.session.on_interrupt())
    await asyncio.sleep(0.05)  # the turn is now inside the router's cleanup
    assert h.router.closed and not interrupting.done()
    mark = len(h.sent)
    await h.session.close()  # second cancel lands on the router's cleanup
    assert len(h.sent) == mark
    await interrupting
    await asyncio.sleep(0.05)
    assert len(h.sent) == mark
    assert h.tts.closed == h.tts.started == 1
    assert other_tasks() == []


async def test_router_cleanup_error_on_interrupt_still_reaps():
    h = Harness(router=FakeRouter([Delta("First sentence here. "), 5.0, Delta("Never said.")],
                                  cleanup_raises=RuntimeError("cleanup boom")))
    await h.session.on_text("talk to me")
    await h.wait_for(lambda m: m == AUDIO)
    mark = len(h.sent)
    await h.session.on_interrupt()
    assert h.sent[mark:] == [{"type": "state", "state": "listening"}]
    assert h.tts.closed == h.tts.started == 1 and other_tasks() == []
    await asyncio.sleep(0.05)
    assert h.sent[mark:] == [{"type": "state", "state": "listening"}]


async def test_router_aclose_error_still_reaps_speaker():
    # TTS fails while the router is suspended at a yield; its aclose() then raises.
    h = Harness(router=FakeRouter([Delta("First sentence here. "), 0.1, Delta("Second one. "),
                                   Delta("Third one. ")],
                                  cleanup_raises=RuntimeError("cleanup boom")),
                tts=FakeTTS(raises=TTSError("quota")))
    await h.session.on_text("talk to me")
    await h.session.join()
    assert [e["code"] for e in h.msgs("error")] == ["tts_failed"]
    assert h.sent[-2:] == [CANNED, {"type": "state", "state": "idle"}]
    assert h.router.closed and h.tts.closed == h.tts.started == 1
    assert other_tasks() == []
    mark = len(h.sent)
    await asyncio.sleep(0.05)
    assert len(h.sent) == mark


async def test_utterance_capped_at_30_seconds():
    h = Harness()
    second = frame(5000, ms=1000)  # continuous speech: the endpointer never fires
    for _ in range(29):
        await h.session.on_audio(second)
    assert h.stt.calls == []
    await h.session.on_audio(frame(5000, ms=1500))  # crosses 30 s
    await h.session.join()
    assert [len(c) for c in h.stt.calls] == [960000]
    assert h.states() == ["listening", "thinking", "speaking", "idle"]


async def test_empty_hermes_reply_speaks_error_line():
    class EmptyHermes:
        async def stream(self, messages):
            for c in [" ", "\n"]:
                yield c

    h = Harness(router=Router(EmptyHermes(), EmptyHermes()))
    await h.session.on_text("hello there", "pt")
    await h.session.join()
    assert [e["code"] for e in h.msgs("error")] == ["llm_failed"]
    assert h.canned.requests == [("error", "pt")]
    assert CANNED in h.sent and {"type": "speaking_done"} not in h.sent
    assert h.sent[-1] == {"type": "state", "state": "idle"}
    assert other_tasks() == []
