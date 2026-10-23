import pytest

from server.config import Settings


def test_settings_parses_device_tokens():
    s = Settings.from_env({"ELEVENLABS_API_KEY": "k", "ELEVENLABS_VOICE_ID": "v",
                           "API_SERVER_KEY": "h", "DEVICE_TOKENS": "win:abc,android:def"})
    assert s.device_tokens == {"abc": "win", "def": "android"}
    assert s.hermes_fast_url == "http://127.0.0.1:8642/p/fast"
    assert s.hermes_deep_url == "http://127.0.0.1:8642/p/deep"


def test_settings_missing_key_raises():
    with pytest.raises(KeyError):
        Settings.from_env({})


def test_settings_repr_hides_secrets():
    s = Settings.from_env({"ELEVENLABS_API_KEY": "el-secret", "ELEVENLABS_VOICE_ID": "v",
                           "API_SERVER_KEY": "hermes-secret", "DEVICE_TOKENS": "win:tok-secret"})
    text = repr(s)
    for secret in ("el-secret", "hermes-secret", "tok-secret"):
        assert secret not in text
    assert "elevenlabs_voice_id='v'" in text
