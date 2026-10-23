import pytest

from server.config import Settings


def test_settings_parses_device_tokens():
    s = Settings.from_env({"ELEVENLABS_API_KEY": "k", "ELEVENLABS_VOICE_ID": "v",
                           "API_SERVER_KEY": "h", "DEVICE_TOKENS": "win:abc,android:def"})
    assert s.device_tokens == {"abc": "win", "def": "android"}
    assert s.hermes_fast_url == "http://127.0.0.1:8642"


def test_settings_missing_key_raises():
    with pytest.raises(KeyError):
        Settings.from_env({})
