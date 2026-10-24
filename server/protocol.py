import json
from dataclasses import dataclass, field

from server.vision import MAX_BASE64_CHARS


class ProtocolError(ValueError):
    """Raised when a client message is unknown or invalid."""


@dataclass(frozen=True)
class Hello:
    token: str
    device: str
    client_vad: bool = False
    client_config: bool = False


@dataclass(frozen=True)
class ConfigAck:
    revision: int
    idle: bool


@dataclass(frozen=True)
class EndUtterance:
    pass


@dataclass(frozen=True)
class Interrupt:
    pass


@dataclass(frozen=True)
class Camera:
    enabled: bool


@dataclass(frozen=True)
class CameraFrame:
    jpeg: str = field(repr=False)


LANGS = ("pt", "en")


@dataclass(frozen=True)
class TextInput:
    text: str
    lang: str = "en"


ClientMsg = Hello | EndUtterance | Interrupt | TextInput | Camera | CameraFrame | ConfigAck


def _str_field(data: dict, key: str) -> str:
    value = data.get(key)
    if not isinstance(value, str):
        raise ProtocolError(f"missing or invalid field: {key}")
    return value


def parse_client(text: str) -> ClientMsg:
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, TypeError) as exc:
        raise ProtocolError("invalid JSON") from exc
    if not isinstance(data, dict):
        raise ProtocolError("message must be a JSON object")
    kind = data.get("type")
    if kind == "hello":
        client_vad = data.get("client_vad", False)
        if not isinstance(client_vad, bool):
            raise ProtocolError("invalid field: client_vad (expected boolean)")
        client_config = data.get('client_config', False)
        if not isinstance(client_config, bool):
            raise ProtocolError('invalid field: client_config (expected boolean)')
        return Hello(_str_field(data, "token"), _str_field(data, "device"), client_vad, client_config)
    if kind == 'config_ack':
        revision, idle = data.get('revision'), data.get('idle')
        if type(revision) is not int or revision < 0 or not isinstance(idle, bool):
            raise ProtocolError('invalid configuration acknowledgement')
        return ConfigAck(revision, idle)
    if kind == "end_utterance":
        return EndUtterance()
    if kind == "interrupt":
        return Interrupt()
    if kind == "camera":
        enabled = data.get("enabled")
        if not isinstance(enabled, bool):
            raise ProtocolError("invalid field: enabled (expected boolean)")
        return Camera(enabled)
    if kind == "camera_frame":
        jpeg = _str_field(data, "jpeg")
        if not jpeg or len(jpeg) > MAX_BASE64_CHARS:
            raise ProtocolError("invalid camera frame size")
        return CameraFrame(jpeg)
    if kind == "text":
        lang = data.get("lang", "en")
        if lang not in LANGS:
            raise ProtocolError("invalid field: lang (expected 'pt' or 'en')")
        return TextInput(_str_field(data, "text"), lang)
    raise ProtocolError(f"unknown message type: {kind!r}")


def _dump(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False)


def state(s: str) -> str:
    return _dump({"type": "state", "state": s})


def config(revision: int, settings: dict) -> str:
    return _dump({'type': 'config', 'revision': revision, 'settings': settings})


def transcript(text: str, lang: str, final: bool) -> str:
    return _dump({"type": "transcript", "text": text, "lang": lang, "final": final})


def reply_text(delta: str) -> str:
    return _dump({"type": "reply_text", "delta": delta})


def level(mic: float, out: float) -> str:
    return _dump({"type": "level", "mic": mic, "out": out})


def speaking_done() -> str:
    return _dump({"type": "speaking_done"})


def error(code: str, message: str) -> str:
    return _dump({"type": "error", "code": code, "message": message})
