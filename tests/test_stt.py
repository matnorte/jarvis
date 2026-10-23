import math
from types import SimpleNamespace

import numpy as np

from server.stt import Endpointer, Transcriber, Transcript

RATE = 16000
FRAME = 320  # 20 ms


class FakeModel:
    def __init__(self, texts, language):
        self.texts = texts
        self.language = language
        self.calls = []

    def transcribe(self, audio, **kw):
        self.calls.append((audio, kw))
        segs = [SimpleNamespace(text=t) for t in self.texts]
        return iter(segs), SimpleNamespace(language=self.language)


def pcm(samples: np.ndarray) -> bytes:
    return samples.astype("<i2").tobytes()


async def test_transcribe_pt_joins_and_strips():
    model = FakeModel([" olá ", "mundo  "], "pt")
    t = Transcriber("base", model=model)
    audio = pcm(np.array([0, 16384, -16384, 32767], dtype=np.int32))
    out = await t.transcribe(audio)
    assert out == Transcript(text="olá mundo", lang="pt")
    arr, kw = model.calls[0]
    assert arr.dtype == np.float32
    assert abs(arr[1] - 0.5) < 1e-3 and arr.max() <= 1.0
    assert kw["beam_size"] == 1 and kw["vad_filter"] is True


async def test_transcribe_other_language_maps_to_en():
    t = Transcriber("base", model=FakeModel(["hola"], "es"))
    assert (await t.transcribe(b"\x00\x00" * 10)).lang == "en"


async def test_transcribe_en():
    t = Transcriber("base", model=FakeModel(["hello"], "en"))
    assert (await t.transcribe(b"\x00\x00" * 10)) == Transcript("hello", "en")


def tone(ms: int, amp: float = 8000) -> bytes:
    n = RATE * ms // 1000
    s = amp * np.sin(2 * math.pi * 440 * np.arange(n) / RATE)
    return pcm(s)


def zeros(ms: int) -> bytes:
    return b"\x00\x00" * (RATE * ms // 1000)


def noise(ms: int, amp: float = 50) -> bytes:
    n = RATE * ms // 1000
    rng = np.random.default_rng(0)
    return pcm(rng.uniform(-amp, amp, n))


def run(ep, data: bytes):
    results = []
    step = FRAME * 2
    for i in range(0, len(data), step):
        r = ep.feed(data[i : i + step])
        results.append(r)
        if r is not None:
            break
    return [r for r in results if r is not None]


def test_endpointer_end_after_silence():
    assert run(Endpointer(), tone(1000) + zeros(800)) == ["end"]


def test_endpointer_short_silence_is_none():
    assert run(Endpointer(), tone(1000) + zeros(300)) == []


def test_endpointer_no_speech():
    assert run(Endpointer(), noise(6100)) == ["no_speech"]


def test_endpointer_quiet_before_timeout_is_none():
    assert run(Endpointer(), noise(5000)) == []


def test_endpointer_resets_after_result():
    ep = Endpointer()
    assert run(ep, tone(500) + zeros(800)) == ["end"]
    # reused for next utterance: silence alone must not end it immediately
    assert run(ep, zeros(800)) == []
    assert run(ep, tone(500) + zeros(800)) == ["end"]
