import base64
from io import BytesIO

import pytest
from PIL import Image

from server.vision import CameraFrames, CameraFrameError, MAX_JPEG_BYTES


def jpeg(size=(16, 12), color="red"):
    out = BytesIO()
    Image.new("RGB", size, color).save(out, "JPEG")
    return out.getvalue()


def encoded(data):
    return base64.b64encode(data).decode("ascii")


def test_live_frames_are_opt_in_latest_only_expire_and_clear_on_stop():
    now = [0.0]
    camera = CameraFrames(now=lambda: now[0])
    red, blue = jpeg(), jpeg(color="blue")
    assert not camera.offer(encoded(red))
    assert camera.snapshot() is None
    camera.set_enabled(True)
    assert camera.offer(encoded(red))
    now[0] = 0.6
    assert camera.offer(encoded(blue))
    assert camera.snapshot() == blue
    now[0] = 5.0
    assert camera.snapshot() is None
    assert camera.offer(encoded(red))
    camera.set_enabled(False)
    assert camera.snapshot() is None
    camera.set_enabled(True)
    assert camera.snapshot() is None


def test_camera_rate_limit_keeps_last_accepted_frame():
    now = [0.0]
    camera = CameraFrames(now=lambda: now[0])
    camera.set_enabled(True)
    assert camera.offer(encoded(jpeg()))
    now[0] = 0.1
    assert not camera.offer(encoded(jpeg(color="blue")))
    assert camera.snapshot() == jpeg()


@pytest.mark.parametrize("bad", ["not-base64", encoded(b"not a jpeg"), encoded(b"\xff\xd8\xff\xd9"), encoded(b"x" * (128 * 1024 + 1))],
                         ids=["invalid-base64", "not-jpeg", "truncated", "oversized"])
def test_bad_camera_frames_are_rejected_without_echoing_data(bad):
    camera = CameraFrames()
    camera.set_enabled(True)
    with pytest.raises(CameraFrameError) as exc:
        camera.offer(bad)
    assert str(exc.value) == "Invalid camera frame"
    assert camera.snapshot() is None


def test_frame_dimensions_are_bounded_before_decoding_pixels():
    camera = CameraFrames()
    camera.set_enabled(True)
    large = jpeg((2000, 16))
    assert len(large) < MAX_JPEG_BYTES
    with pytest.raises(CameraFrameError):
        camera.offer(encoded(large))


def test_non_jpeg_is_rejected():
    data = BytesIO()
    Image.new("RGB", (16, 16)).save(data, "PNG")
    camera = CameraFrames(); camera.set_enabled(True)
    with pytest.raises(CameraFrameError):
        camera.offer(encoded(data.getvalue()))
