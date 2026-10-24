import asyncio

import pytest

from server.hermes_client import HermesError
from server.router import Delta, Escalated, Router


class FakeHermes:
    def __init__(self, chunks, raises=None):
        self.chunks = chunks
        self.raises = raises
        self.received = None
        self.received_all = []
        self.calls = 0
        self.aclosed = False

    async def stream(self, messages):
        self.calls += 1
        self.received = messages
        self.received_all.append(messages)
        try:
            for c in self.chunks:
                yield c
            if self.raises:
                raise self.raises
        finally:
            self.aclosed = True


def text(events):
    return "".join(e.text for e in events if isinstance(e, Delta))


async def test_fast_answer_passes_through():
    fast, deep = FakeHermes(["Good ", "evening, sir."]), FakeHermes([])
    ev = [e async for e in Router(fast, deep).ask("win", "hello", "en")]
    assert "".join(e.text for e in ev if isinstance(e, Delta)) == "Good evening, sir."
    assert fast.received[-1]["content"] == "[lang:en] hello" and deep.calls == 0


async def test_camera_image_reaches_both_profiles_but_never_enters_history():
    import base64
    fast, deep = FakeHermes(["[[DEEP]]"]), FakeHermes(["A red mug."])
    router = Router(fast, deep)
    image = b"synthetic-camera-pixels"
    events = [e async for e in router.ask("android", "what is this", "en", image=image)]
    assert text(events) == "A red mug."
    expected = [
        {"type": "text", "text": "[lang:en] what is this"},
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(image).decode(), "detail": "auto"}},
    ]
    assert fast.received[-1]["content"] == expected
    assert deep.received[-1]["content"] == expected
    fast.chunks = ["Another reply."]
    [_ async for _ in router.ask("android", "and now", "en")]
    assert fast.received[0] == {"role": "user", "content": "[lang:en] what is this"}
    assert "data:image" not in str(fast.received)


async def test_split_marker_escalates_and_is_never_emitted():
    fast, deep = FakeHermes([" [[DE", "EP]]"]), FakeHermes(["Plano ", "pronto."])
    ev = [e async for e in Router(fast, deep).ask("android", "planeje", "pt")]
    assert isinstance(ev[0], Escalated)
    assert "".join(e.text for e in ev[1:]) == "Plano pronto."
    assert "[[DEEP]]" not in "".join(getattr(e, "text", "") for e in ev)
    assert fast.aclosed
    assert deep.received[-1]["content"] == "[lang:pt] planeje"


async def test_escalated_yielded_once_before_deep_text():
    fast, deep = FakeHermes(["[[DEEP]]"]), FakeHermes(["a", "b"])
    ev = [e async for e in Router(fast, deep).ask("d", "x", "en")]
    assert [type(e) for e in ev] == [Escalated, Delta, Delta]


async def test_short_reply_ending_before_8_chars_is_emitted():
    fast, deep = FakeHermes(["Yes."]), FakeHermes([])
    ev = [e async for e in Router(fast, deep).ask("d", "x", "en")]
    assert text(ev) == "Yes." and deep.calls == 0


async def test_history_is_shared_across_profiles_and_capped():
    fast, deep = FakeHermes(["ok"]), FakeHermes([])
    r = Router(fast, deep, history_turns=2)
    for i in range(3):
        [_ async for _ in r.ask("win", f"q{i}", "en")]
    sent = fast.received_all[-1]
    assert [m["content"] for m in sent if m["role"] == "user"] == ["[lang:en] q1", "[lang:en] q2"]


async def test_deep_answer_stored_without_marker_and_history_per_device():
    fast = FakeHermes(["[[DEEP]]"])
    deep = FakeHermes(["The ", "plan."])
    r = Router(fast, deep)
    [_ async for _ in r.ask("a", "q1", "en")]
    fast.chunks = ["fine, thanks"]
    [_ async for _ in r.ask("a", "q2", "en")]
    assert fast.received[:2] == [
        {"role": "user", "content": "[lang:en] q1"},
        {"role": "assistant", "content": "The plan."},
    ]
    [_ async for _ in r.ask("b", "q3", "en")]
    assert fast.received == [{"role": "user", "content": "[lang:en] q3"}]


async def test_aclose_mid_stream_closes_inner_and_records_nothing():
    fast, deep = FakeHermes(["Hello there, ", "sir. More."]), FakeHermes([])
    r = Router(fast, deep)
    gen = r.ask("d", "q1", "en")
    first = await gen.__anext__()
    assert isinstance(first, Delta)
    await gen.aclose()
    assert fast.aclosed
    [_ async for _ in r.ask("d", "q2", "en")]
    assert fast.received == [{"role": "user", "content": "[lang:en] q2"}]


async def test_cancel_during_deep_closes_deep_and_records_nothing():
    class Slow(FakeHermes):
        async def stream(self, messages):
            self.received = messages
            try:
                yield "partial"
                await asyncio.sleep(10)
            finally:
                self.aclosed = True

    fast, deep = FakeHermes(["[[DEEP]]"]), Slow([])
    r = Router(fast, deep)

    async def run():
        async for _ in r.ask("d", "q1", "en"):
            pass

    t = asyncio.create_task(run())
    await asyncio.sleep(0.05)
    t.cancel()
    with pytest.raises(asyncio.CancelledError):
        await t
    assert deep.aclosed and fast.aclosed
    [_ async for _ in r.ask("d", "q2", "en")]
    assert fast.received == [{"role": "user", "content": "[lang:en] q2"}]


async def test_stream_error_propagates_and_turn_not_recorded():
    fast = FakeHermes(["Hello there, sir"], raises=RuntimeError("boom"))
    r = Router(fast, FakeHermes([]))
    with pytest.raises(RuntimeError):
        [_ async for _ in r.ask("d", "q1", "en")]
    fast.raises = None
    [_ async for _ in r.ask("d", "q2", "en")]
    assert fast.received == [{"role": "user", "content": "[lang:en] q2"}]


async def test_empty_fast_reply_raises_and_records_nothing():
    for chunks in ([], ["  ", "\n"]):
        fast, deep = FakeHermes(chunks), FakeHermes([])
        r = Router(fast, deep)
        with pytest.raises(HermesError):
            [_ async for _ in r.ask("d", "q1", "en")]
        fast.chunks = ["fine"]
        [_ async for _ in r.ask("d", "q2", "en")]
        assert fast.received == [{"role": "user", "content": "[lang:en] q2"}]


async def test_empty_deep_reply_raises_and_records_nothing():
    fast, deep = FakeHermes(["[[DEEP]]"]), FakeHermes([" "])
    r = Router(fast, deep)
    with pytest.raises(HermesError):
        [_ async for _ in r.ask("d", "q1", "en")]
    fast.chunks = ["fine"]
    [_ async for _ in r.ask("d", "q2", "en")]
    assert fast.received == [{"role": "user", "content": "[lang:en] q2"}]


async def test_deep_stream_leading_marker_is_stripped():
    for chunks in (["[[DEEP]] The plan."], ["\n [[DE", "EP]]", " The ", "plan."]):
        fast, deep = FakeHermes(["[[DEEP]]"]), FakeHermes(chunks)
        r = Router(fast, deep)
        ev = [e async for e in r.ask("d", "q1", "en")]
        assert text(ev) == "The plan."
        fast.chunks = ["ok"]
        [_ async for _ in r.ask("d", "q2", "en")]
        assert fast.received[1] == {"role": "assistant", "content": "The plan."}


async def test_deep_marker_only_stripped_when_leading():
    fast = FakeHermes(["[[DEEP]]"])
    deep = FakeHermes(["[[D", "eep thoughts]] here."])
    ev = [e async for e in Router(fast, deep).ask("d", "q", "en")]
    assert text(ev) == "[[Deep thoughts]] here."
