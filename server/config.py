import os
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path


def _parse_device_tokens(raw: str) -> dict[str, str]:
    tokens: dict[str, str] = {}
    for pair in raw.split(","):
        pair = pair.strip()
        if not pair:
            continue
        device, sep, token = pair.partition(":")
        if not sep or not device.strip() or not token.strip():
            raise ValueError(f"invalid DEVICE_TOKENS entry: {device!r}")
        tokens[token.strip()] = device.strip()
    return tokens


@dataclass(frozen=True)
class Settings:
    # Secrets stay out of repr() so a logged Settings never leaks them.
    elevenlabs_api_key: str = field(repr=False)
    elevenlabs_voice_id: str
    hermes_api_key: str = field(repr=False)
    hermes_fast_url: str = "http://127.0.0.1:8642/p/fast"
    hermes_deep_url: str = "http://127.0.0.1:8642/p/deep"
    stt_model: str = "base"
    tts_model: str = "eleven_turbo_v2_5"
    device_tokens: dict[str, str] = field(default_factory=dict, repr=False)
    canned_dir: Path = Path("cache/canned")

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ) -> "Settings":
        return cls(
            elevenlabs_api_key=env["ELEVENLABS_API_KEY"],
            elevenlabs_voice_id=env["ELEVENLABS_VOICE_ID"],
            hermes_api_key=env["API_SERVER_KEY"],
            hermes_fast_url=env.get("HERMES_FAST_URL", cls.hermes_fast_url),
            hermes_deep_url=env.get("HERMES_DEEP_URL", cls.hermes_deep_url),
            stt_model=env.get("STT_MODEL", cls.stt_model),
            tts_model=env.get("TTS_MODEL", cls.tts_model),
            device_tokens=_parse_device_tokens(env.get("DEVICE_TOKENS", "")),
            canned_dir=Path(env.get("CANNED_DIR", str(cls.canned_dir))),
        )
