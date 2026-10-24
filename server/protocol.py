import json
from dataclasses import dataclass


class ProtocolError(ValueError):
    """Raised when a client message is unknown or invalid."""


@dataclass(frozen=True)
class Hello:
    token: str
    device: str
    client_vad: bool = False


@dataclass(frozen=True)
class EndUtterance:
    pass


@dataclass(frozen=True)
class Interrupt:
    pass


LANGS = ("pt", "en")


@dataclass(frozen=True)
class TextInput:
    text: str
    lang: str = "en"


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
        client_vad = data.get("client_vad", False)
        if not isinstance(client_vad, bool):
            raise ProtocolError("invalid field: client_vad (expected boolean)")
        return Hello(_str_field(data, "token"), _str_field(data, "device"), client_vad)
    if kind == "end_utterance":
        return EndUtterance()
    if kind == "interrupt":
        return Interrupt()
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
