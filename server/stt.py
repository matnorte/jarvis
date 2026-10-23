"""Speech-to-text (faster-whisper) and an energy-based fallback endpointer."""

import asyncio
import math
from dataclasses import dataclass
from typing import Any, Literal

import numpy as np

EndpointResult = Literal["end", "no_speech"]

_MIN_FLOOR = 100.0
_MIN_SPEECH_RMS = 300.0
_FLOOR_ALPHA = 0.05


@dataclass(frozen=True)
class Transcript:
    text: str
    lang: str


class Transcriber:
    """Wraps faster-whisper. The model is created lazily unless injected."""

    def __init__(self, model_size: str, model: Any = None) -> None:
        self._model_size = model_size
        self._model = model

    def _get_model(self) -> Any:
        if self._model is None:
            from faster_whisper import WhisperModel

            self._model = WhisperModel(
                self._model_size, device="cpu", compute_type="int8"
            )
        return self._model

    def _run(self, pcm16: bytes) -> Transcript:
        audio = np.frombuffer(pcm16, dtype="<i2").astype(np.float32) / 32768.0
        segments, info = self._get_model().transcribe(
            audio, beam_size=1, vad_filter=True
        )
        text = " ".join(s.text.strip() for s in segments).strip()
        lang = "pt" if getattr(info, "language", None) == "pt" else "en"
        return Transcript(text=text, lang=lang)

    async def transcribe(self, pcm16: bytes) -> Transcript:
        return await asyncio.to_thread(self._run, pcm16)


class Endpointer:
    """Fallback server-side endpointing on per-frame RMS energy.

    Feed each incoming PCM16 frame. A frame is speech when
    RMS > max(3 * floor, 300); the noise floor starts at and never drops
    below 100, and adapts only on non-speech frames. Returns "end" after
    `silence_ms` of silence following speech, "no_speech" if no speech
    occurs within `no_speech_ms`, else None. After returning a result the
    endpointer resets itself, so one instance can be reused per utterance
    (reset() is also available).
    """

    def __init__(
        self,
        sample_rate: int = 16000,
        silence_ms: int = 700,
        no_speech_ms: int = 6000,
    ) -> None:
        self._rate = sample_rate
        self._silence_ms = silence_ms
        self._no_speech_ms = no_speech_ms
        self.reset()

    def reset(self) -> None:
        self._floor = _MIN_FLOOR
        self._speech_seen = False
        self._silence = 0.0
        self._elapsed = 0.0

    def feed(self, pcm16: bytes) -> EndpointResult | None:
        samples = np.frombuffer(pcm16, dtype="<i2").astype(np.float64)
        if samples.size == 0:
            return None
        ms = samples.size * 1000.0 / self._rate
        rms = math.sqrt(float(np.mean(samples * samples)))
        self._elapsed += ms
        if rms > max(3 * self._floor, _MIN_SPEECH_RMS):
            self._speech_seen = True
            self._silence = 0.0
            return None
        self._floor = max(
            _MIN_FLOOR, (1 - _FLOOR_ALPHA) * self._floor + _FLOOR_ALPHA * rms
        )
        if self._speech_seen:
            self._silence += ms
            if self._silence >= self._silence_ms:
                self.reset()
                return "end"
        elif self._elapsed >= self._no_speech_ms:
            self.reset()
            return "no_speech"
        return None
