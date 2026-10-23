import json
from dataclasses import dataclass


class ProtocolError(ValueError):
    """Raised when a client message is unknown or invalid."""


@dataclass(frozen=True)
class Hello:
    token: str
    device: str


@dataclass(frozen=True)
class EndUtterance:
    pass


@dataclass(frozen=True)
class Interrupt:
    pass


@dataclass(frozen=True)
class TextInput:
    text: str


ClientMsg = Hello | EndUtterance | Interrupt | TextInput


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
        return Hello(_str_field(data, "token"), _str_field(data, "device"))
    if kind == "end_utterance":
        return EndUtterance()
    if kind == "interrupt":
        return Interrupt()
    if kind == "text_input":
        return TextInput(_str_field(data, "text"))
    raise ProtocolError(f"unknown message type: {kind!r}")


def _dump(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False)


def state(s: str) -> str:
    return _dump({"type": "state", "state": s})


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
