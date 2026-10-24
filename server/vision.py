"""Bounded, opt-in live camera context. No disk writes, image history, or payload logging."""
import base64
import binascii
from io import BytesIO
import time
import warnings
from collections.abc import Callable

from PIL import Image

MAX_JPEG_BYTES = 128 * 1024
MAX_BASE64_CHARS = ((MAX_JPEG_BYTES + 2) // 3) * 4
MAX_FRAME_EDGE = 1280
FRAME_TTL_S = 4.0
MIN_FRAME_INTERVAL_S = 0.4


class CameraFrameError(ValueError):
    def __init__(self):
        super().__init__("Invalid camera frame")


class CameraFrames:
    def __init__(self, now: Callable[[], float] = time.monotonic):
        self.enabled = False
        self._now = now
        self._frame: bytes | None = None
        self._received_at: float | None = None

    def set_enabled(self, enabled: bool) -> None:
        self.enabled = enabled
        self._frame = None
        self._received_at = None

    def offer(self, encoded: str) -> bool:
        if not self.enabled:
            return False
        now = self._now()
        if self._received_at is not None and now - self._received_at < MIN_FRAME_INTERVAL_S:
            return False
        try:
            if len(encoded) > MAX_BASE64_CHARS:
                raise CameraFrameError()
            jpeg = base64.b64decode(encoded, validate=True)
            if not jpeg or len(jpeg) > MAX_JPEG_BYTES:
                raise CameraFrameError()
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(BytesIO(jpeg), formats=("JPEG",)) as image:
                    if image.format != "JPEG" or not (0 < image.width <= MAX_FRAME_EDGE and 0 < image.height <= MAX_FRAME_EDGE):
                        raise CameraFrameError()
                    image.load()
        except (ValueError, OSError, binascii.Error, Image.DecompressionBombError, Image.DecompressionBombWarning):
            raise CameraFrameError() from None
        self._frame, self._received_at = jpeg, now
        return True

    def snapshot(self) -> bytes | None:
        if not self.enabled or self._received_at is None:
            return None
        if self._now() - self._received_at >= FRAME_TTL_S:
            self._frame = None
        return self._frame
